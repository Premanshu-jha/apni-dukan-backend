package in.apnidukan.leads.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * India's pincodes (india_pincodes, read-only reference data from India Post's directory), held in memory: about
 * 19,300 rows, read once on first use. The Leads page's district search and each district's areas never touch the
 * database after that.
 */
@Repository
public class PincodeDirectory {

    private static final Pattern NOT_LETTER_OR_DIGIT = Pattern.compile("[^\\p{L}\\p{N}]+");

    /**
     * One pincode.
     *
     * @param places    its post office names, e.g. "Kukatpally, Vivekanandanagar Colony"
     * @param latitude  the middle of its offices, or null (then only Mapbox can place it)
     * @param radiusM   how far its offices spread from that middle
     */
    public record Pin(String pincode, String district, String state, String places, Double latitude,
                      Double longitude, int radiusM) {
    }

    /** A district: its name, its state and its pincodes (in pincode order). */
    public record District(String name, String state, List<Pin> pins) {
        public District {
            pins = List.copyOf(pins);
        }
    }

    /**
     * A district the search found.
     *
     * @param matched what matched when it wasn't the district's own name, e.g. "Kukatpally (500072)"; else null
     */
    public record Hit(String district, String state, int pincodes, String matched) {
    }

    private record Loaded(Map<String, Pin> byPincode, Map<String, District> byKey) {
    }

    private final JdbcTemplate jdbc;
    private volatile Loaded loaded;

    public PincodeDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Pin> pin(String pincode) {
        return Optional.ofNullable(data().byPincode().get(pincode));
    }

    public Optional<District> district(String name, String state) {
        return Optional.ofNullable(data().byKey().get(key(name, state)));
    }

    /**
     * Districts for what was typed (at least 2 characters), best first, at most {@code limit}: a district or state
     * whose name starts with it, then one whose name contains it, then a district with a matching pincode or post
     * office (named in {@link Hit#matched}).
     */
    public List<Hit> search(String typed, int limit) {
        String q = normal(typed);
        if (q.length() < 2) {
            return List.of();
        }
        record Ranked(int rank, Hit hit) {
        }
        List<Ranked> ranked = new ArrayList<>();
        boolean digits = q.chars().allMatch(Character::isDigit);
        for (District d : data().byKey().values()) {
            String name = normal(d.name());
            String state = normal(d.state());
            int rank = name.startsWith(q) ? 0 : name.contains(q) ? 1 : state.startsWith(q) ? 3 : -1;
            String matched = null;
            if (rank < 0) {
                for (Pin p : d.pins()) {
                    if (digits ? p.pincode().startsWith(q) : normal(p.places()).contains(q)) {
                        rank = 2;
                        matched = (digits ? p.pincode() : firstPlace(p.places(), q)) + (digits ? "" : " (" + p.pincode() + ")");
                        break;
                    }
                }
            }
            if (rank >= 0) {
                ranked.add(new Ranked(rank, new Hit(d.name(), d.state(), d.pins().size(), matched)));
            }
        }
        return ranked.stream()
                .sorted(Comparator.comparingInt(Ranked::rank)
                        .thenComparing(r -> -r.hit().pincodes())
                        .thenComparing(r -> r.hit().district()))
                .limit(limit)
                .map(Ranked::hit)
                .toList();
    }

    private Loaded data() {
        Loaded l = loaded;
        if (l == null) {
            synchronized (this) {
                l = loaded;
                if (l == null) {
                    l = load();
                    loaded = l;
                }
            }
        }
        return l;
    }

    private Loaded load() {
        Map<String, Pin> byPincode = new LinkedHashMap<>();
        Map<String, List<Pin>> pinsByKey = new LinkedHashMap<>();
        jdbc.query("SELECT pincode, district, state, places, latitude, longitude, radius_m FROM india_pincodes "
                + "ORDER BY pincode", rs -> {
            BigDecimal lat = rs.getBigDecimal("latitude");
            BigDecimal lng = rs.getBigDecimal("longitude");
            Pin p = new Pin(rs.getString("pincode"), rs.getString("district"), rs.getString("state"),
                    rs.getString("places"), lat == null ? null : lat.doubleValue(),
                    lng == null ? null : lng.doubleValue(), rs.getInt("radius_m"));
            byPincode.put(p.pincode(), p);
            pinsByKey.computeIfAbsent(key(p.district(), p.state()), k -> new ArrayList<>()).add(p);
        });
        Map<String, District> byKey = new LinkedHashMap<>();
        pinsByKey.forEach((k, pins) -> byKey.put(k, new District(pins.getFirst().district(), pins.getFirst().state(),
                List.copyOf(pins))));
        return new Loaded(Map.copyOf(byPincode), byKey);
    }

    static String key(String district, String state) {
        return normal(district) + "|" + normal(state);
    }

    /** Lower case, letters and digits only, words joined by single spaces ("K.V. Rangareddy" → "k v rangareddy"). */
    static String normal(String s) {
        return s == null ? "" : NOT_LETTER_OR_DIGIT.matcher(s.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    private static String firstPlace(String places, String q) {
        for (String place : places.split(", ")) {
            if (normal(place).contains(q)) {
                return place;
            }
        }
        return places;
    }
}
