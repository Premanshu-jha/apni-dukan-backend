package in.apnidukan.leads.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Mapbox's Search Box API (https://api.mapbox.com/search/searchbox/v1), for the Leads page:
 * <ul>
 *   <li>{@link #postcode}: where a pincode is and how far it reaches (GET /forward?q=500072&types=postcode);</li>
 *   <li>{@link #category}: up to 25 businesses of one category inside a box (GET /category/{category}?bbox=...);</li>
 *   <li>{@link #words}: up to 10 businesses matching words inside a box (GET /forward?q=...&types=poi&bbox=...).</li>
 * </ul>
 * Short timeouts (3 s to connect, 8 s for the answer), no retries: one failed request only leaves a gap the scan
 * reports. A non-200 answer, an unreadable one or no network throws {@link Unavailable}; a 401/403 (a wrong or revoked
 * token) throws {@link Refused}. The access token travels only in the URL, which is never logged: logs carry the
 * status code or the exception's class name only.
 */
public class MapboxSearch {

    private static final Logger log = LoggerFactory.getLogger(MapboxSearch.class);
    public static final URI MAPBOX = URI.create("https://api.mapbox.com/search/searchbox/v1");
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration ANSWER_TIMEOUT = Duration.ofSeconds(8);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A box: west, south, east, north. */
    public record Box(double west, double south, double east, double north) {
        double centreLng() {
            return (west + east) / 2;
        }

        double centreLat() {
            return (south + north) / 2;
        }

        /** The four quarters (for a box with more businesses than one answer holds). */
        List<Box> quarters() {
            double x = centreLng();
            double y = centreLat();
            return List.of(new Box(west, south, x, y), new Box(x, south, east, y), new Box(west, y, x, north),
                    new Box(x, y, east, north));
        }

        String param() {
            return fmt(west) + "," + fmt(south) + "," + fmt(east) + "," + fmt(north);
        }

        /** A box of this many metres either side of a point. */
        static Box around(double lat, double lng, int metres) {
            double dLat = metres / 111_320.0;
            double dLng = metres / (111_320.0 * Math.cos(Math.toRadians(lat)));
            return new Box(lng - dLng, lat - dLat, lng + dLng, lat + dLat);
        }
    }

    /** A pincode's place on Mapbox: its middle and, when Mapbox gives one, the box it covers. */
    public record Postcode(double latitude, double longitude, Box box) {
    }

    /** Mapbox couldn't answer (timeout, 5xx, 429, unreadable): this one request is lost. */
    public static class Unavailable extends RuntimeException {
        Unavailable(String message) {
            super(message);
        }
    }

    /** Mapbox refused the token (401/403): no request will work until it is fixed. */
    public static class Refused extends RuntimeException {
        Refused() {
            super("Mapbox refused the access token");
        }
    }

    private final String token;
    private final URI endpoint;
    private final HttpClient http;
    private final Duration answerTimeout;

    /**
     * @param token    MAPBOX_TOKEN (not blank: the caller only builds this when it is set)
     * @param endpoint {@link #MAPBOX}; a local fake in tests
     */
    public MapboxSearch(String token, URI endpoint) {
        this(token, endpoint, ANSWER_TIMEOUT);
    }

    MapboxSearch(String token, URI endpoint, Duration answerTimeout) {
        this.token = token;
        this.endpoint = endpoint;
        this.answerTimeout = answerTimeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public Optional<Postcode> postcode(String pincode) {
        JsonNode answer = ask("/forward", "q=" + encode(pincode) + "&types=postcode&country=IN&limit=1&language=en");
        for (JsonNode f : answer.path("features")) {
            JsonNode p = f.path("properties");
            if (!pincode.equals(p.path("name").asString(""))) {
                continue;
            }
            JsonNode c = p.path("coordinates");
            if (!c.path("latitude").isNumber() || !c.path("longitude").isNumber()) {
                continue;
            }
            JsonNode b = p.path("bbox").isArray() ? p.path("bbox") : f.path("bbox");
            Box box = b.isArray() && b.size() == 4 && b.get(0).isNumber()
                    ? new Box(b.get(0).asDouble(), b.get(1).asDouble(), b.get(2).asDouble(), b.get(3).asDouble())
                    : null;
            return Optional.of(new Postcode(c.path("latitude").asDouble(), c.path("longitude").asDouble(), box));
        }
        return Optional.empty();
    }

    /** At most {@code limit} (1 to 25) businesses of this category inside the box, nearest its middle first. */
    public List<Poi> category(String category, Box box, int limit) {
        return pois(ask("/category/" + encode(category), "bbox=" + box.param()
                + "&proximity=" + fmt(box.centreLng()) + "," + fmt(box.centreLat())
                + "&limit=" + limit + "&country=IN&language=en"));
    }

    /** At most 10 businesses matching these words inside the box. */
    public List<Poi> words(String words, Box box) {
        return pois(ask("/forward", "q=" + encode(words) + "&types=poi&bbox=" + box.param()
                + "&proximity=" + fmt(box.centreLng()) + "," + fmt(box.centreLat())
                + "&limit=10&country=IN&language=en"));
    }

    private JsonNode ask(String path, String query) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + path + "?" + query
                        + "&access_token=" + encode(token)))
                .timeout(answerTimeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("Mapbox search failed: {}", e.getClass().getSimpleName());
            throw new Unavailable("Mapbox could not be reached");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("Interrupted");
        }
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            log.warn("Mapbox refused the token ({})", status);
            throw new Refused();
        }
        if (status != 200) {
            log.warn("Mapbox search answered {}", status);
            throw new Unavailable("Mapbox answered " + status);
        }
        try {
            return JSON.readTree(response.body());
        } catch (JacksonException e) {
            log.warn("Mapbox search answer unreadable: {}", e.getClass().getSimpleName());
            throw new Unavailable("Unreadable answer");
        }
    }

    /** The answer's businesses (features with a mapbox_id and a point); anything else in it is skipped. */
    static List<Poi> pois(JsonNode answer) {
        List<Poi> out = new ArrayList<>();
        for (JsonNode f : answer.path("features")) {
            JsonNode p = f.path("properties");
            String id = p.path("mapbox_id").asString("");
            JsonNode c = p.path("coordinates");
            if (id.isBlank() || id.length() > 200 || !c.path("latitude").isNumber() || !c.path("longitude").isNumber()) {
                continue;
            }
            List<String> categories = new ArrayList<>();
            p.path("poi_category").forEach(n -> categories.add(n.asString("")));
            p.path("poi_category_ids").forEach(n -> categories.add(n.asString("")));
            JsonNode meta = p.path("metadata");
            String website = text(meta.path("website"));
            String address = text(p.path("full_address"));
            out.add(new Poi(id, clip(text(p.path("name")), 150), address == null ? text(p.path("place_formatted")) : address,
                    c.path("latitude").asDouble(), c.path("longitude").asDouble(),
                    pincode(text(p.path("context").path("postcode").path("name"))), List.copyOf(categories),
                    text(meta.path("phone")),
                    website != null && website.toLowerCase(Locale.ROOT).startsWith("https://") ? website : null,
                    hours(meta.path("open_hours"))));
        }
        return out;
    }

    /** Mapbox's opening periods ({open: {day, time: "1000"}, close: {...}}), or null if it has none. */
    static Poi.Hours hours(JsonNode openHours) {
        JsonNode periods = openHours.path("periods");
        if (!periods.isArray() || periods.isEmpty()) {
            return null;
        }
        Set<Integer> days = new HashSet<>();
        int minutes = 0;
        String opens = null;
        String closes = null;
        boolean late = false;
        for (JsonNode period : periods) {
            String o = period.path("open").path("time").asString("");
            String c = period.path("close").path("time").asString("");
            if (!o.matches("\\d{4}") || !c.matches("\\d{4}")) {
                continue;
            }
            days.add(period.path("open").path("day").asInt(-1));
            int from = Integer.parseInt(o.substring(0, 2)) * 60 + Integer.parseInt(o.substring(2));
            int to = Integer.parseInt(c.substring(0, 2)) * 60 + Integer.parseInt(c.substring(2));
            minutes += to >= from ? to - from : to + 1440 - from;
            if (opens == null || o.compareTo(opens) < 0) {
                opens = o;
            }
            if (closes == null || c.compareTo(closes) > 0) {
                closes = c;
            }
            if (c.compareTo("2100") >= 0 || c.compareTo("0400") < 0) {
                late = true;
            }
        }
        days.remove(-1);
        if (days.isEmpty()) {
            return null;
        }
        return new Poi.Hours(days.size(), opens, closes, minutes / 60.0 / days.size(), late);
    }

    private static String text(JsonNode n) {
        String s = n.isString() ? n.asString().strip() : "";
        return s.isEmpty() ? null : s;
    }

    private static String pincode(String s) {
        return s != null && s.matches("[1-9]\\d{5}") ? s : null;
    }

    private static String clip(String s, int max) {
        return s == null ? "" : s.length() <= max ? s : s.substring(0, max);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static String fmt(double d) {
        return String.format(Locale.ROOT, "%.5f", d);
    }
}
