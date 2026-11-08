package in.apnidukan.shops.service;

import in.apnidukan.common.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Asks Google's Geocoding API (the classic v3 JSON endpoint) where a typed address is ({@link #find}), or which
 * address a point is at ({@link #describe}: latlng=..., for filling the registration form from the phone's GPS):
 * GET https://maps.googleapis.com/maps/api/geocode/json?address=...&components=country:IN|postal_code:...&region=in&key=...
 *
 * Short timeouts (2 s to connect, 4 s for the whole answer, headers and body together) and no retries: the owner is
 * waiting on the registration screen and can always use the phone's GPS instead. Google's answers become:
 * <ul>
 *   <li>OK with a result inside India: the first result's location and how exact it is ({@link Precision}); a
 *       partial_match (Google matched only part of the address) is always AREA;</li>
 *   <li>ZERO_RESULTS, or a result outside India: empty (the caller answers 404 ADDRESS_NOT_FOUND);</li>
 *   <li>anything else (OVER_QUERY_LIMIT, OVER_DAILY_LIMIT, REQUEST_DENIED, INVALID_REQUEST, UNKNOWN_ERROR), a non-200
 *       HTTP status, an unreadable answer, a timeout or no network: 503 GEOCODING_UNAVAILABLE with a neutral
 *       message.</li>
 * </ul>
 * The API key travels only in the request URL, which is never logged or put in an error: logs carry Google's status
 * word or the exception's class name only, never the address, the URL or Google's error_message.
 */
public class GoogleGeocoder {

    private static final Logger log = LoggerFactory.getLogger(GoogleGeocoder.class);
    public static final URI GOOGLE = URI.create("https://maps.googleapis.com/maps/api/geocode/json");
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration ANSWER_TIMEOUT = Duration.ofSeconds(4);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** How exact Google's location is: on the building, on the street, or only somewhere in the area. */
    public enum Precision { EXACT, STREET, AREA }

    /**
     * Where the address is, as Google found it.
     * @param partial Google's partial_match: only part of the typed address matched (kept a shorter time)
     */
    public record Found(double latitude, double longitude, Precision precision, String formattedAddress,
                        boolean partial) {
    }

    /**
     * The address at a point, in the registration form's fields; any field Google didn't give is "".
     * @param addressLine the building (premise) or street number, and the street (route), e.g. "Ithum Tower, Sector 62 Road"
     * @param area        the locality inside the city (sublocality_level_1, sublocality or neighborhood)
     * @param city        the city (locality, else the district: administrative_area_level_2)
     * @param pincode     the postal code, only when it is a valid 6-digit Indian pincode
     */
    public record Place(String addressLine, String area, String city, String pincode, String formattedAddress) {
    }

    /** The result types worth filling the form from (not, say, the whole state). */
    static final String REVERSE_TYPES = "street_address|premise|route|sublocality|locality|postal_code";

    private final String apiKey;
    private final URI endpoint;
    private final Duration answerTimeout;
    private final HttpClient http;

    /**
     * @param apiKey   GOOGLE_GEOCODING_KEY (not blank: the caller only builds this when it is set)
     * @param endpoint {@link #GOOGLE}; a local fake in tests
     */
    public GoogleGeocoder(String apiKey, URI endpoint) {
        this(apiKey, endpoint, ANSWER_TIMEOUT);
    }

    /** @param answerTimeout {@link #ANSWER_TIMEOUT}; shorter in tests */
    GoogleGeocoder(String apiKey, URI endpoint, Duration answerTimeout) {
        this.apiKey = apiKey;
        this.endpoint = endpoint;
        this.answerTimeout = answerTimeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Where this address is; empty if Google doesn't know it (or only outside India). */
    public Optional<Found> find(String address, String pincode) {
        String query = "address=" + encode(address)
                + "&components=" + encode("country:IN|postal_code:" + pincode)
                + "&region=in";
        return firstResult(ask(query)).flatMap(GoogleGeocoder::found);
    }

    /**
     * The address at this point (latitude and longitude as given: the caller rounds them); empty if Google has none
     * (ZERO_RESULTS). Errors as for {@link #find}.
     */
    public Optional<Place> describe(String latitude, String longitude) {
        String query = "latlng=" + encode(latitude + "," + longitude)
                + "&result_type=" + encode(REVERSE_TYPES)
                + "&region=in&language=en";
        return firstResult(ask(query)).map(GoogleGeocoder::place);
    }

    /** Calls Google with this query (the key added last) and reads its JSON; 503 for anything but a 200. */
    private String ask(String query) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "?" + query + "&key=" + encode(apiKey)))
                .timeout(answerTimeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> answer = send(request);
        if (answer.statusCode() != 200) {
            log.error("Google geocoding answered HTTP {}", answer.statusCode());
            throw unavailable();
        }
        return answer.body();
    }

    /** Google's JSON answer → the first result, empty for none (ZERO_RESULTS), or 503 for an error status. */
    static Optional<JsonNode> firstResult(String body) {
        JsonNode root;
        try {
            root = JSON.readTree(body == null ? "" : body);
        } catch (JacksonException e) {
            log.error("Google geocoding sent an unreadable answer");
            throw unavailable();
        }
        String status = root.path("status").asString("");
        if ("ZERO_RESULTS".equals(status)) {
            return Optional.empty();
        }
        if (!"OK".equals(status)) {
            // Only Google's status word: its error_message can quote the request
            log.error("Google geocoding refused: {}", safeStatus(status));
            throw unavailable();
        }
        JsonNode first = root.path("results").path(0);
        return first.isObject() ? Optional.of(first) : Optional.empty();
    }

    /** A forward answer for the typed address (test entry point). */
    static Optional<Found> read(String body) {
        return firstResult(body).flatMap(GoogleGeocoder::found);
    }

    /** A reverse answer for a point (test entry point). */
    static Optional<Place> readPlace(String body) {
        return firstResult(body).map(GoogleGeocoder::place);
    }

    /** The first result's location, how exact it is; empty without a location or outside India. */
    private static Optional<Found> found(JsonNode first) {
        JsonNode location = first.path("geometry").path("location");
        if (!location.path("lat").isNumber() || !location.path("lng").isNumber()) {
            return Optional.empty();
        }
        double lat = location.path("lat").asDouble();
        double lng = location.path("lng").asDouble();
        if (!insideIndia(lat, lng)) {
            return Optional.empty();
        }
        String formatted = first.path("formatted_address").asString("");
        boolean partial = first.path("partial_match").asBoolean(false);
        Precision precision = partial ? Precision.AREA
                : precision(first.path("geometry").path("location_type").asString(""));
        return Optional.of(new Found(lat, lng, precision, cut(formatted, 300), partial));
    }

    /** The first result's address_components in the form's fields, each cut to the form's limit. */
    private static Place place(JsonNode first) {
        String premise = component(first, "premise");
        String number = component(first, "street_number");
        String route = component(first, "route");
        String building = premise.isEmpty() ? number : premise;
        String addressLine = building.isEmpty() ? route : route.isEmpty() ? building : building + ", " + route;
        String area = firstNonEmpty(component(first, "sublocality_level_1"), component(first, "sublocality"),
                component(first, "neighborhood"));
        String city = firstNonEmpty(component(first, "locality"), component(first, "administrative_area_level_2"));
        String postal = component(first, "postal_code").replace(" ", "");
        String pincode = postal.matches("[1-9][0-9]{5}") ? postal : "";
        return new Place(cut(addressLine, 200), cut(area, 100), cut(city, 60), pincode,
                cut(first.path("formatted_address").asString(""), 300));
    }

    /** The long_name of the first address component of this type, or "". */
    static String component(JsonNode result, String type) {
        JsonNode components = result.path("address_components");
        for (int i = 0; i < components.size(); i++) {
            JsonNode c = components.path(i);
            JsonNode types = c.path("types");
            for (int j = 0; j < types.size(); j++) {
                if (type.equals(types.path(j).asString(""))) {
                    return c.path("long_name").asString("").strip();
                }
            }
        }
        return "";
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (!v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max).strip() : s;
    }

    /** ROOFTOP is the building; RANGE_INTERPOLATED a point on the street; anything else only the area. */
    static Precision precision(String locationType) {
        return switch (locationType) {
            case "ROOFTOP" -> Precision.EXACT;
            case "RANGE_INTERPOLATED" -> Precision.STREET;
            default -> Precision.AREA;   // GEOMETRIC_CENTER, APPROXIMATE, or missing
        };
    }

    /** The same box a shop's location must be in (POST /shops). */
    static boolean insideIndia(double lat, double lng) {
        return lat >= 6.0 && lat <= 37.6 && lng >= 68.0 && lng <= 97.5;
    }

    /**
     * The whole exchange (connecting, headers and body) within {@link #ANSWER_TIMEOUT}: the request's own timeout
     * only covers the headers, so a body that trickles in is cut off here, and the exchange cancelled.
     */
    private HttpResponse<String> send(HttpRequest request) {
        CompletableFuture<HttpResponse<String>> call =
                http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            return call.get(answerTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            log.error("Google geocoding didn't answer within {} ms", answerTimeout.toMillis());
            throw unavailable();
        } catch (ExecutionException e) {   // no network, refused, the headers' timeout; the message isn't logged
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.error("Could not reach Google geocoding: {}", cause.getClass().getSimpleName());
            throw unavailable();
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw unavailable();
        }
    }

    /** Google's status if it is one of its plain upper-case words, else "OTHER" (nothing else reaches the log). */
    static String safeStatus(String status) {
        return status.matches("[A-Z_]{1,40}") ? status : "OTHER";
    }

    static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GEOCODING_UNAVAILABLE",
                "Address search isn't working right now. Use your location, or try again later.");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
