package in.apnidukan.leads.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * India's pincodes and cities (india_pincodes, india_cities: read-only reference data loaded from db/data/*.csv by
 * R__India_places), held in memory: about 19,300 pincodes and 70 cities, read once on first use. The Leads page's
 * "City or district" search and each place's areas never touch the database after that.
 *
 * <p>A place is a city (all areas of its districts: Hyderabad is India Post's Hyderabad, Medchal Malkajgiri, Ranga
 * Reddy and Sangareddy) or a district.
 */
@Repository
public class PincodeDirectory {

    private static final Pattern NOT_LETTER_OR_DIGIT = Pattern.compile("[^\\p{L}\\p{N}]+");

    /** Whether a place is a city or a district. */
    public enum Kind { CITY, DISTRICT }

    /**
     * One pincode.
     *
     * @param places    its post office names, e.g. "Kukatpally, Vivekanandanagar Colony"
     * @param latitude  the middle of its offices, or null (then only Mapbox can place it)
     * @param radiusM   how far its offices spread from that middle
     * @param doubtful  the point is far from the rest of its district (India Post's points are often wrong): used only
     *                  when Mapbox doesn't know the pincode, keeping only businesses that give this pincode
     */
    public record Pin(String pincode, String district, String state, String places, Double latitude,
                      Double longitude, int radiusM, boolean doubtful) {
    }

    /**
     * A city or a district: its pincodes in the order they are listed (a city's own district first, each district's in
     * pincode order) and, for a city, its districts ("Medchal Malkajgiri") and other names ("Secunderabad").
     */
    public record Place(Kind kind, String name, String state, List<String> districts, List<String> otherNames,
                        List<Pin> pins) {
        public Place {
            districts = List.copyOf(districts);
            otherNames = List.copyOf(otherNames);
            pins = List.copyOf(pins);
        }
    }

    /**
     * A place the search found.
     *
     * @param matched what matched when it wasn't the place's own name: another name ("Bangalore"), a pincode and its
     *                post offices ("500087 · JJ Nagar Colony"), or a post office ("Kukatpally (500072)"); else null
     * @param pincode the area that matched, to tick when the place is picked; else null
     */
    public record Hit(Place place, String matched, String pincode) {
    }

    /** {@code searchable}: each pincode's post office names, normalised once (searched on every keystroke). */
    private record Loaded(Map<String, Pin> byPincode, Map<String, String> searchable, Map<String, Place> districts,
                          Map<String, Place> cities, Map<String, Place> cityOf) {
    }

    private final JdbcTemplate jdbc;
    private volatile Loaded loaded;

    public PincodeDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Pin> pin(String pincode) {
        return Optional.ofNullable(data().byPincode().get(pincode));
    }

    public Optional<Place> place(Kind kind, String name, String state) {
        Loaded d = data();
        return Optional.ofNullable((kind == Kind.CITY ? d.cities() : d.districts()).get(key(name, state)));
    }

    /** The place to offer for a pincode: the smallest city it is part of, else its district. */
    public Optional<Place> placeOf(Pin pin) {
        return placeOf(pin, true);
    }

    private Optional<Place> placeOf(Pin pin, boolean cities) {
        Loaded d = data();
        Place city = cities ? d.cityOf().get(key(pin.district(), pin.state())) : null;
        return Optional.ofNullable(city != null ? city : d.districts().get(key(pin.district(), pin.state())));
    }

    /**
     * Places for what was typed (at least 2 characters), best first, at most {@code limit}:
     * <ol>
     *   <li>a city or district whose name (or a city's other name) starts with it;</li>
     *   <li>one with a later word starting with it;</li>
     *   <li>the districts of a state whose name starts with it;</li>
     *   <li>pincodes starting with it (a whole pincode: just that one), or post offices with a word starting with it,
     *       each offered as its place with that area to tick: areas in a city first, then elsewhere, in pincode
     *       order.</li>
     * </ol>
     */
    public List<Hit> search(String typed, int limit) {
        return search(typed, limit, true);
    }

    /**
     * As {@link #search(String, int)}; without {@code cities}, districts only (for an admin website from before
     * cities, which would ask for a city's areas as a district's).
     */
    public List<Hit> search(String typed, int limit, boolean cities) {
        String q = normal(typed);
        if (q.length() < 2) {
            return List.of();
        }
        Loaded d = data();
        record Ranked(int rank, int size, Hit hit) {
        }
        List<Ranked> ranked = new ArrayList<>();
        Set<String> placesAdded = new LinkedHashSet<>();
        boolean digits = q.chars().allMatch(Character::isDigit);
        if (!digits && cities) {
            for (Place c : d.cities().values()) {
                int rank = nameRank(normal(c.name()), q);
                String matched = null;
                if (rank < 0) {
                    for (String other : c.otherNames()) {
                        int r = nameRank(normal(other), q);
                        if (r >= 0) {
                            rank = r;
                            matched = other;
                            break;
                        }
                    }
                }
                if (rank >= 0 && placesAdded.add(id(c))) {
                    ranked.add(new Ranked(rank, -c.pins().size(), new Hit(c, matched, null)));
                }
            }
        }
        if (!digits) {
            for (Place p : d.districts().values()) {
                int rank = nameRank(normal(p.name()), q);
                if (rank < 0 && normal(p.state()).startsWith(q)) {
                    rank = 2; // "telangana": its districts before post offices that happen to contain the word
                }
                if (rank >= 0 && placesAdded.add(id(p))) {
                    ranked.add(new Ranked(rank, -p.pins().size(), new Hit(p, null, null)));
                }
            }
        }
        // Areas: a pincode, or a post office name
        int areas = 0;
        for (Pin pin : d.byPincode().values()) {
            if (areas >= 200) {
                break; // enough to fill the list with the best (city areas first); a common word matches thousands
            }
            // A post office whose name has a word starting with it ("alwal" isn't Palwal)
            String names = d.searchable().get(pin.pincode());
            boolean hit = digits ? pin.pincode().startsWith(q) : names.startsWith(q) || names.contains(" " + q);
            if (!hit) {
                continue;
            }
            Place place = placeOf(pin, cities).orElse(null);
            if (place == null) {
                continue;
            }
            String matched = digits ? pin.pincode() + " · " + firstPlaces(pin.places())
                    : firstPlace(pin.places(), q) + " (" + pin.pincode() + ")";
            // Areas in a city first (where most salons are), then elsewhere
            ranked.add(new Ranked(place.kind() == Kind.CITY ? 3 : 4, 0, new Hit(place, matched, pin.pincode())));
            areas++;
        }
        return ranked.stream()
                .sorted(Comparator.comparingInt(Ranked::rank)
                        .thenComparingInt(Ranked::size)
                        .thenComparing(r -> r.hit().place().name())
                        .thenComparing(r -> r.hit().pincode() == null ? "" : r.hit().pincode()))
                .limit(limit)
                .map(Ranked::hit)
                .toList();
    }

    /** 0: the name starts with it; 1: a later word starts with it ("reddy" → Ranga Reddy, not "alwal" → Palwal). */
    private static int nameRank(String name, String q) {
        return name.startsWith(q) ? 0 : name.contains(" " + q) ? 1 : -1;
    }

    private static String id(Place p) {
        return p.kind() + "|" + key(p.name(), p.state());
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
        jdbc.query("SELECT pincode, district, state, places, latitude, longitude, radius_m, location_doubtful "
                + "FROM india_pincodes ORDER BY pincode", rs -> {
            BigDecimal lat = rs.getBigDecimal("latitude");
            BigDecimal lng = rs.getBigDecimal("longitude");
            Pin p = new Pin(rs.getString("pincode"), rs.getString("district"), rs.getString("state"),
                    rs.getString("places"), lat == null ? null : lat.doubleValue(),
                    lng == null ? null : lng.doubleValue(), rs.getInt("radius_m"), rs.getBoolean("location_doubtful"));
            byPincode.put(p.pincode(), p);
            pinsByKey.computeIfAbsent(key(p.district(), p.state()), k -> new ArrayList<>()).add(p);
        });
        Map<String, Place> districts = new LinkedHashMap<>();
        pinsByKey.forEach((k, pins) -> districts.put(k, new Place(Kind.DISTRICT, pins.getFirst().district(),
                pins.getFirst().state(), List.of(pins.getFirst().district()), List.of(), pins)));

        record Part(String city, String cityState, String district, String districtState) {
        }
        Map<String, List<Part>> parts = new LinkedHashMap<>();
        jdbc.query("SELECT city, city_state, district, district_state FROM india_city_districts "
                + "ORDER BY city, city_state, position", rs -> {
            Part p = new Part(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4));
            parts.computeIfAbsent(key(p.city(), p.cityState()), k -> new ArrayList<>()).add(p);
        });
        Map<String, Place> cities = new LinkedHashMap<>();
        Map<String, Place> cityOf = new LinkedHashMap<>();
        jdbc.query("SELECT name, state, other_names FROM india_cities ORDER BY name", rs -> {
            String name = rs.getString("name");
            String state = rs.getString("state");
            List<String> names = new ArrayList<>();
            List<Pin> pins = new ArrayList<>();
            for (Part p : parts.getOrDefault(key(name, state), List.of())) {
                Place district = districts.get(key(p.district(), p.districtState()));
                if (district != null) {
                    names.add(district.name());
                    pins.addAll(district.pins());
                }
            }
            List<String> other = Arrays.stream(rs.getString("other_names").split(";"))
                    .map(String::strip).filter(s -> !s.isEmpty()).toList();
            if (!pins.isEmpty()) {
                cities.put(key(name, state), new Place(Kind.CITY, name, state, names, other, pins));
            }
        });
        // Each district's smallest city: a Gurugram pincode is offered as Gurugram, not Delhi NCR
        for (Place c : cities.values()) {
            for (Pin p : c.pins()) {
                String k = key(p.district(), p.state());
                Place had = cityOf.get(k);
                if (had == null || had.pins().size() > c.pins().size()) {
                    cityOf.put(k, c);
                }
            }
        }
        // Kept in pincode order (Map.copyOf would iterate in a random order: area hits would differ per restart)
        Map<String, String> searchable = new java.util.HashMap<>();
        byPincode.values().forEach(p -> searchable.put(p.pincode(), normal(p.places())));
        return new Loaded(Collections.unmodifiableMap(byPincode), Map.copyOf(searchable), districts, cities, cityOf);
    }

    static String key(String name, String state) {
        return normal(name) + "|" + normal(state);
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

    private static String firstPlaces(String places) {
        String[] all = places.split(", ");
        return all.length <= 2 ? places : all[0] + ", " + all[1];
    }
}
