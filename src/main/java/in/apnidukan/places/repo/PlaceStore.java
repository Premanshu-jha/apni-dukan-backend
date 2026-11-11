package in.apnidukan.places.repo;

import in.apnidukan.places.domain.Place;
import in.apnidukan.places.domain.Place.Kind;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The customer_places table (V40). Every read and write is scoped to its person: nobody reaches another's places. */
@Repository
public class PlaceStore {

    private static final String COLUMNS = "id, user_id, kind, name, address_line, area, city, pincode, latitude, "
            + "longitude, created_at, updated_at";

    private static final RowMapper<Place> ROW = (rs, i) -> new Place(rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class), Kind.valueOf(rs.getString("kind")), rs.getString("name"),
            rs.getString("address_line"), rs.getString("area"), rs.getString("city"), rs.getString("pincode"),
            rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    public PlaceStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One person's adds and changes one after the other (two phones adding at once can't pass the limit or take Home
     * twice), without locking the users row that sign-in writes.
     */
    public void lockPerson(UUID userId) {
        jdbc.query("SELECT pg_advisory_xact_lock(7104, hashtext(?))", rs -> null, userId.toString());
    }

    /** Home, then Work, then the others in the order they were saved. */
    public List<Place> list(UUID userId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM customer_places WHERE user_id = ? ORDER BY "
                + "CASE kind WHEN 'HOME' THEN 0 WHEN 'WORK' THEN 1 ELSE 2 END, created_at, id", ROW, userId);
    }

    public Optional<Place> find(UUID userId, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM customer_places WHERE id = ? AND user_id = ?", ROW, id, userId)
                .stream().findFirst();
    }

    public int count(UUID userId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM customer_places WHERE user_id = ?", Integer.class,
                userId);
        return n == null ? 0 : n;
    }

    /** The person's other place of this kind (Home or Work), if any. */
    public Optional<UUID> holderOf(UUID userId, Kind kind, UUID except) {
        return jdbc.queryForList("SELECT id FROM customer_places WHERE user_id = ? AND kind = ? AND id <> ?",
                UUID.class, userId, kind.name(), except).stream().findFirst();
    }

    public void insert(Place p) {
        jdbc.update("INSERT INTO customer_places (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                p.id(), p.userId(), p.kind().name(), p.name(), p.addressLine(), p.area(), p.city(), p.pincode(),
                p.latitude(), p.longitude(), Timestamp.from(p.createdAt()), Timestamp.from(p.updatedAt()));
    }

    /** False when it isn't this person's (or is gone). */
    public boolean update(Place p) {
        return jdbc.update("UPDATE customer_places SET kind = ?, name = ?, address_line = ?, area = ?, city = ?, "
                        + "pincode = ?, latitude = ?, longitude = ?, updated_at = ? WHERE id = ? AND user_id = ?",
                p.kind().name(), p.name(), p.addressLine(), p.area(), p.city(), p.pincode(), p.latitude(),
                p.longitude(), Timestamp.from(p.updatedAt()), p.id(), p.userId()) == 1;
    }

    /** False when it wasn't this person's (or was gone already). */
    public boolean delete(UUID userId, UUID id) {
        return jdbc.update("DELETE FROM customer_places WHERE id = ? AND user_id = ?", id, userId) == 1;
    }

    /** A deleted account's places. */
    public void deleteAll(UUID userId) {
        jdbc.update("DELETE FROM customer_places WHERE user_id = ?", userId);
    }
}
