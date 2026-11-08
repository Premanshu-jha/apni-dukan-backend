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
 * Asks Google's Geocoding API (the classic v3 JSON endpoint) where a typed address is:
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
                + "&region=in&key=" + encode(apiKey);
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "?" + query))
                .timeout(answerTimeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> answer = send(request);
        if (answer.statusCode() != 200) {
            log.error("Google geocoding answered HTTP {}", answer.statusCode());
            throw unavailable();
        }
        return read(answer.body());
    }

    /** Google's JSON answer → the first result, empty for none, or 503 for an error status. */
    static Optional<Found> read(String body) {
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
        return Optional.of(new Found(lat, lng, precision,
                formatted.length() > 300 ? formatted.substring(0, 300) : formatted, partial));
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
