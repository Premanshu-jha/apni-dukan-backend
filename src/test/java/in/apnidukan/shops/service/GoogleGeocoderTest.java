package in.apnidukan.shops.service;

import com.sun.net.httpserver.HttpServer;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.shops.service.GoogleGeocoder.Found;
import in.apnidukan.shops.service.GoogleGeocoder.Precision;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The exact request Google receives and how each of its answers is read, against a local fake (nothing is sent). */
class GoogleGeocoderTest {

    private static final String KEY = "AIzaTestKey_do_not_leak";
    private final AtomicReference<String> query = new AtomicReference<>();
    private volatile int httpStatus = 200;
    private volatile String body = "";
    /** Milliseconds to wait between the first and the rest of the body (a body that trickles in). */
    private volatile long bodyPause = 0;
    private HttpServer fake;

    private URI start() throws IOException {
        fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/maps/api/geocode/json", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(httpStatus, out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                exchange.getResponseBody().write(out, 0, 1);
                exchange.getResponseBody().flush();
                if (bodyPause > 0) {
                    try {
                        Thread.sleep(bodyPause);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                exchange.getResponseBody().write(out, 1, out.length - 1);
            }
            exchange.close();
        });
        fake.start();
        return URI.create("http://127.0.0.1:" + fake.getAddress().getPort() + "/maps/api/geocode/json");
    }

    @AfterEach
    void stop() {
        if (fake != null) {
            fake.stop(0);
        }
    }

    private static String ok(double lat, double lng, String locationType) {
        return """
                {"status":"OK","results":[{"formatted_address":"12, MG Road, Ameerpet, Hyderabad, Telangana 500016, India",
                "geometry":{"location":{"lat":%s,"lng":%s},"location_type":"%s"}}]}
                """.formatted(lat, lng, locationType);
    }

    @Test
    void asksForTheAddressInIndiaWithThePincodeAndTheKey() throws IOException {
        body = ok(17.4374, 78.4482, "ROOFTOP");
        new GoogleGeocoder(KEY, start()).find("12, MG Road, Ameerpet, Hyderabad 500016", "500016");
        String q = URLDecoder.decode(query.get(), StandardCharsets.UTF_8);
        assertThat(q).contains("address=12, MG Road, Ameerpet, Hyderabad 500016")
                .contains("components=country:IN|postal_code:500016")
                .contains("region=in")
                .contains("key=" + KEY);
    }

    @Test
    void readsTheFirstResultAndHowExactItIs() throws IOException {
        body = ok(17.4374, 78.4482, "ROOFTOP");
        Optional<Found> found = new GoogleGeocoder(KEY, start()).find("12, MG Road", "500016");
        assertThat(found).hasValueSatisfying(f -> {
            assertThat(f.latitude()).isEqualTo(17.4374);
            assertThat(f.longitude()).isEqualTo(78.4482);
            assertThat(f.precision()).isEqualTo(Precision.EXACT);
            assertThat(f.formattedAddress()).startsWith("12, MG Road");
        });
    }

    @Test
    void aPartialMatchIsOnlyTheAreaWhateverTheLocationType() throws IOException {
        body = """
                {"status":"OK","results":[{"formatted_address":"Ameerpet, Hyderabad","partial_match":true,
                "geometry":{"location":{"lat":17.4374,"lng":78.4482},"location_type":"ROOFTOP"}}]}
                """;
        URI url = start();
        Optional<Found> found = new GoogleGeocoder(KEY, url).find("12, MG Road", "500016");
        assertThat(found).hasValueSatisfying(f -> {
            assertThat(f.precision()).isEqualTo(Precision.AREA);
            assertThat(f.partial()).isTrue();
        });
        body = ok(17.4374, 78.4482, "ROOFTOP");
        assertThat(new GoogleGeocoder(KEY, url).find("12, MG Road", "500016"))
                .hasValueSatisfying(f -> assertThat(f.partial()).isFalse());
    }

    @Test
    void aBodyThatTricklesInPastTheTimeoutIsCutOff() throws IOException {
        body = ok(17.4374, 78.4482, "ROOFTOP");
        bodyPause = 3000;   // the headers come at once, the body after 3 s
        URI url = start();
        long started = System.nanoTime();
        assertUnavailable(() -> new GoogleGeocoder(KEY, url, java.time.Duration.ofMillis(500))
                .find("MG Road", "500016"));
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(2500);
    }

    @Test
    void locationTypesBecomeExactStreetOrArea() {
        assertThat(GoogleGeocoder.precision("ROOFTOP")).isEqualTo(Precision.EXACT);
        assertThat(GoogleGeocoder.precision("RANGE_INTERPOLATED")).isEqualTo(Precision.STREET);
        assertThat(GoogleGeocoder.precision("GEOMETRIC_CENTER")).isEqualTo(Precision.AREA);
        assertThat(GoogleGeocoder.precision("APPROXIMATE")).isEqualTo(Precision.AREA);
        assertThat(GoogleGeocoder.precision("")).isEqualTo(Precision.AREA);
    }

    @Test
    void noResultsOrAResultOutsideIndiaIsNotFound() throws IOException {
        URI url = start();
        body = "{\"status\":\"ZERO_RESULTS\",\"results\":[]}";
        assertThat(new GoogleGeocoder(KEY, url).find("Nowhere", "500016")).isEmpty();
        body = ok(51.5072, -0.1276, "ROOFTOP");   // London
        assertThat(new GoogleGeocoder(KEY, url).find("MG Road", "500016")).isEmpty();
        body = "{\"status\":\"OK\",\"results\":[{\"geometry\":{}}]}";   // no location at all
        assertThat(new GoogleGeocoder(KEY, url).find("MG Road", "500016")).isEmpty();
    }

    @Test
    void googlesErrorsAreUnavailableAndNeverCarryTheKey() throws IOException {
        URI url = start();
        for (String status : new String[] {"OVER_QUERY_LIMIT", "OVER_DAILY_LIMIT", "REQUEST_DENIED", "INVALID_REQUEST",
                "UNKNOWN_ERROR"}) {
            body = "{\"status\":\"" + status + "\",\"error_message\":\"The provided API key " + KEY + " is invalid.\"}";
            assertUnavailable(() -> new GoogleGeocoder(KEY, url).find("MG Road", "500016"));
        }
    }

    @Test
    void anHttpErrorOrAnUnreadableAnswerIsUnavailable() throws IOException {
        URI url = start();
        httpStatus = 500;
        body = "oops";
        assertUnavailable(() -> new GoogleGeocoder(KEY, url).find("MG Road", "500016"));
        httpStatus = 200;
        body = "<html>not json</html>";
        assertUnavailable(() -> new GoogleGeocoder(KEY, url).find("MG Road", "500016"));
    }

    @Test
    void noNetworkIsUnavailableWithoutTheUrlOrKey() throws IOException {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        URI nowhere = URI.create("http://127.0.0.1:" + closedPort + "/maps/api/geocode/json");
        assertUnavailable(() -> new GoogleGeocoder(KEY, nowhere).find("MG Road", "500016"));
    }

    @Test
    void onlyPlainStatusWordsReachTheLog() {
        assertThat(GoogleGeocoder.safeStatus("REQUEST_DENIED")).isEqualTo("REQUEST_DENIED");
        assertThat(GoogleGeocoder.safeStatus("key=" + KEY)).isEqualTo("OTHER");
    }

    private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getCode()).isEqualTo("GEOCODING_UNAVAILABLE");
            assertThat(e.getStatus().value()).isEqualTo(503);
            assertThat(e.getMessage()).doesNotContain(KEY).doesNotContain("http");
            assertThat(e.getCause()).isNull();
        });
    }
}
