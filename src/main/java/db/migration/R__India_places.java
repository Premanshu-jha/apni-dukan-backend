package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * Loads India's pincodes and cities for the Leads page from the CSV files in db/data, the only source of that data:
 * india-pincodes.csv (built by docs/tools/pincodes.py from India Post's directory) into india_pincodes, and
 * india-cities.csv (kept by hand) into india_cities and india_city_districts.
 *
 * <p>A repeatable migration: Flyway runs it after the versioned ones, and again whenever either CSV changes (its
 * checksum is the files'), so updating the data is editing a CSV. Pincodes are updated in place (inserted, changed,
 * removed); cities are replaced. All in Flyway's transaction: a broken file changes nothing.
 */
public class R__India_places extends BaseJavaMigration {

    public static final String PINCODES = "db/data/india-pincodes.csv";
    public static final String CITIES = "db/data/india-cities.csv";
    private static final int BATCH = 1000;

    @Override
    public Integer getChecksum() {
        CRC32 crc = new CRC32();
        crc.update(read(PINCODES));
        crc.update(read(CITIES));
        return (int) crc.getValue();
    }

    @Override
    public void migrate(Context context) throws SQLException {
        Connection db = context.getConnection();
        // Flyway runs repeatable migrations after the versioned ones it was asked for: on a database migrated only part
        // of the way (MigrationUpgradeIntegrationTest stops at old versions) the tables don't exist yet, so there is
        // nothing to load. Flyway still records it as run, so such a database gets the CSVs only once they change
        // next. Our databases are always migrated to the latest version (V96 and V99 first), so this never happens
        // to them.
        if (!exists(db, "india_pincodes") || !exists(db, "india_cities")) {
            LoggerFactory.getLogger(R__India_places.class).warn("india_pincodes/india_cities missing: not loaded");
            return;
        }
        try (Statement st = db.createStatement()) {
            st.execute("SET LOCAL lock_timeout = '3s'");
        }
        loadPincodes(db, rows(PINCODES, 8));
        loadCities(db, rows(CITIES, 4));
    }

    private static void loadPincodes(Connection db, List<String[]> rows) throws SQLException {
        Set<String> seen = new HashSet<>();
        try (PreparedStatement up = db.prepareStatement(
                "INSERT INTO india_pincodes (pincode, district, state, places, latitude, longitude, radius_m, "
                        + "location_doubtful) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (pincode) DO UPDATE SET "
                        + "district = EXCLUDED.district, state = EXCLUDED.state, places = EXCLUDED.places, "
                        + "latitude = EXCLUDED.latitude, longitude = EXCLUDED.longitude, radius_m = EXCLUDED.radius_m, "
                        + "location_doubtful = EXCLUDED.location_doubtful "
                        + "WHERE (india_pincodes.district, india_pincodes.state, india_pincodes.places, "
                        + "india_pincodes.latitude, india_pincodes.longitude, india_pincodes.radius_m, "
                        + "india_pincodes.location_doubtful) IS DISTINCT FROM (EXCLUDED.district, EXCLUDED.state, "
                        + "EXCLUDED.places, EXCLUDED.latitude, EXCLUDED.longitude, EXCLUDED.radius_m, "
                        + "EXCLUDED.location_doubtful)")) {
            int n = 0;
            for (String[] r : rows) {
                if (!seen.add(r[0])) {
                    throw new IllegalStateException(PINCODES + ": pincode " + r[0] + " appears twice");
                }
                up.setString(1, r[0]);
                up.setString(2, r[1]);
                up.setString(3, r[2]);
                up.setString(4, r[3]);
                decimal(up, 5, r[4]);
                decimal(up, 6, r[5]);
                up.setInt(7, Integer.parseInt(r[6]));
                up.setBoolean(8, "true".equalsIgnoreCase(r[7].strip()));
                up.addBatch();
                if (++n % BATCH == 0) {
                    up.executeBatch();
                }
            }
            up.executeBatch();
        }
        // A pincode India Post no longer lists goes too (nothing refers to this table)
        try (PreparedStatement gone = db.prepareStatement("DELETE FROM india_pincodes WHERE NOT (pincode = ANY (?))")) {
            gone.setArray(1, db.createArrayOf("varchar", seen.toArray()));
            gone.executeUpdate();
        }
    }

    private static void loadCities(Connection db, List<String[]> rows) throws SQLException {
        try (Statement st = db.createStatement()) {
            st.execute("DELETE FROM india_cities"); // their districts go with them (ON DELETE CASCADE)
        }
        try (PreparedStatement city = db.prepareStatement(
                "INSERT INTO india_cities (name, state, other_names) VALUES (?, ?, ?)");
             PreparedStatement part = db.prepareStatement("INSERT INTO india_city_districts "
                     + "(city, city_state, position, district, district_state) VALUES (?, ?, ?, ?, ?)")) {
            for (String[] r : rows) {
                city.setString(1, r[0]);
                city.setString(2, r[1]);
                city.setString(3, String.join("; ", split(r[2], ';')));
                city.addBatch();
                int position = 0;
                for (String ds : split(r[3], ';')) {
                    int slash = ds.lastIndexOf('/');
                    if (slash <= 0) {
                        throw new IllegalStateException(CITIES + ": " + r[0] + ": district \"" + ds + "\" needs "
                                + "District/State");
                    }
                    part.setString(1, r[0]);
                    part.setString(2, r[1]);
                    part.setInt(3, position++);
                    part.setString(4, ds.substring(0, slash).strip());
                    part.setString(5, ds.substring(slash + 1).strip());
                    part.addBatch();
                }
                if (position == 0) {
                    throw new IllegalStateException(CITIES + ": " + r[0] + " has no districts");
                }
            }
            city.executeBatch();
            part.executeBatch();
        }
        // Every city's district must be one the pincodes have, or the city would list no areas there
        try (Statement st = db.createStatement();
             var rs = st.executeQuery("SELECT c.city, c.district, c.district_state FROM india_city_districts c "
                     + "WHERE NOT EXISTS (SELECT 1 FROM india_pincodes p WHERE p.district = c.district "
                     + "AND p.state = c.district_state) LIMIT 1")) {
            if (rs.next()) {
                throw new IllegalStateException(CITIES + ": " + rs.getString(1) + ": no pincodes in "
                        + rs.getString(2) + ", " + rs.getString(3));
            }
        }
    }

    private static boolean exists(Connection db, String table) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            ps.setString(1, "public." + table);
            try (var rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private static void decimal(PreparedStatement ps, int i, String v) throws SQLException {
        if (v.isBlank()) {
            ps.setNull(i, Types.NUMERIC);
        } else {
            ps.setBigDecimal(i, new BigDecimal(v.strip()));
        }
    }

    private static List<String> split(String s, char sep) {
        List<String> out = new ArrayList<>();
        for (String part : s.split(java.util.regex.Pattern.quote(String.valueOf(sep)))) {
            if (!part.isBlank()) {
                out.add(part.strip());
            }
        }
        return out;
    }

    /** The CSV's rows after its header, each with exactly {@code columns} fields. */
    public static List<String[]> rows(String file, int columns) {
        List<String[]> rows = parse(new String(read(file), StandardCharsets.UTF_8));
        if (rows.isEmpty()) {
            throw new IllegalStateException(file + " is empty");
        }
        List<String[]> out = new ArrayList<>(rows.size());
        for (int i = 1; i < rows.size(); i++) {
            String[] r = rows.get(i);
            if (r.length == 1 && r[0].isEmpty()) {
                continue; // a blank line
            }
            if (r.length != columns) {
                throw new IllegalStateException(file + " line " + (i + 1) + ": " + r.length + " fields, expected "
                        + columns);
            }
            out.add(r);
        }
        return out;
    }

    /** RFC 4180 CSV: commas, fields in double quotes may hold commas, quotes ("") and line breaks. */
    public static List<String[]> parse(String text) {
        List<String[]> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                rows.add(row.toArray(String[]::new));
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row.toArray(String[]::new));
        }
        return rows;
    }

    private static byte[] read(String file) {
        try (InputStream in = R__India_places.class.getClassLoader().getResourceAsStream(file)) {
            if (in == null) {
                throw new IllegalStateException(file + " is missing from the classpath");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
