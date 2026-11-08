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
    /** After the pause, this much more is sent (to notice a client that hung up). */
    private volatile int extraBytes = 0;
    /** The client closed the connection while the fake was still sending. */
    private final java.util.concurrent.atomic.AtomicBoolean clientHungUp = new java.util.concurrent.atomic.AtomicBoolean();
    private HttpServer fake;

    private URI start() throws IOException {
        fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/maps/api/geocode/json", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (extraBytes > 0) {
                exchange.sendResponseHeaders(httpStatus, out.length + (long) extraBytes);
                try {
                    exchange.getResponseBody().write(out, 0, 1);
                    exchange.getResponseBody().flush();
                    Thread.sleep(bodyPause);
                    byte[] more = new byte[64 * 1024];
                    for (int sent = 0; sent < extraBytes; sent += more.length) {
                        exchange.getResponseBody().write(more);
                        exchange.getResponseBody().flush();
                    }
                } catch (IOException e) {
                    clientHungUp.set(true);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                exchange.close();
                return;
            }
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

    private static final String REVERSE = """
            {"status":"OK","results":[{"formatted_address":"Ithum Tower, Sector 62, Noida, Uttar Pradesh 201309, India",
            "address_components":[
              {"long_name":"Ithum Tower","types":["premise"]},
              {"long_name":"Sector 62 Road","types":["route"]},
              {"long_name":"Sector 62","types":["sublocality_level_1","sublocality","political"]},
              {"long_name":"Noida","types":["locality","political"]},
              {"long_name":"Gautam Buddh Nagar","types":["administrative_area_level_2","political"]},
              {"long_name":"201309","types":["postal_code"]}],
            "geometry":{"location":{"lat":28.6215,"lng":77.3652},"location_type":"ROOFTOP"}}]}
            """;

    @Test
    void asksForTheAddressAtAPointInEnglishWithTheKey() throws IOException {
        body = REVERSE;
        new GoogleGeocoder(KEY, start()).describe("28.6215", "77.3652");
        String q = URLDecoder.decode(query.get(), StandardCharsets.UTF_8);
        assertThat(q).contains("latlng=28.6215,77.3652")
                .contains("result_type=street_address|premise|route|sublocality|locality|postal_code")
                .contains("region=in").contains("language=en").contains("key=" + KEY)
                .doesNotContain("address=");
    }

    @Test
    void theFirstResultsComponentsFillTheFormsFields() {
        assertThat(GoogleGeocoder.readPlace(REVERSE)).contains(new GoogleGeocoder.Place("Ithum Tower, Sector 62 Road",
                "Sector 62", "Noida", "201309", "Ithum Tower, Sector 62, Noida, Uttar Pradesh 201309, India"));
    }

    @Test
    void missingPartsAreEmptyAndFallBack() {
        // A street number instead of a building, a neighborhood instead of a sublocality, the district without a
        // locality, and a postal code that isn't a 6-digit pincode
        String other = """
                {"status":"OK","results":[{"formatted_address":"x","address_components":[
                  {"long_name":"12","types":["street_number"]},
                  {"long_name":"Banjara Hills","types":["neighborhood"]},
                  {"long_name":"Hyderabad","types":["administrative_area_level_2"]},
                  {"long_name":"5000","types":["postal_code"]}]}]}
                """;
        assertThat(GoogleGeocoder.readPlace(other))
                .contains(new GoogleGeocoder.Place("12", "Banjara Hills", "Hyderabad", "", "x"));
        String bare = "{\"status\":\"OK\",\"results\":[{\"address_components\":["
                + "{\"long_name\":\"MG Road\",\"types\":[\"route\"]}]}]}";
        assertThat(GoogleGeocoder.readPlace(bare)).contains(new GoogleGeocoder.Place("MG Road", "", "", "", ""));
    }

    @Test
    void nothingAtThePointIsEmptyAndErrorsAreUnavailable() throws IOException {
        URI url = start();
        body = "{\"status\":\"ZERO_RESULTS\",\"results\":[]}";
        assertThat(new GoogleGeocoder(KEY, url).describe("28.6215", "77.3652")).isEmpty();
        body = "{\"status\":\"REQUEST_DENIED\",\"error_message\":\"key " + KEY + "\"}";
        assertUnavailable(() -> new GoogleGeocoder(KEY, url).describe("28.6215", "77.3652"));
    }

    @Test
    void aTimedOutCallIsCancelledSoTheConnectionCloses() throws Exception {
        body = ok(17.4374, 78.4482, "ROOFTOP");
        bodyPause = 1500;   // past the 300 ms timeout, then keep sending
        extraBytes = 64 * 1024 * 1024;
        URI url = start();
        assertUnavailable(() -> new GoogleGeocoder(KEY, url, java.time.Duration.ofMillis(300))
                .find("MG Road", "500016"));
        // The cancelled exchange closes its connection: the fake can't finish sending
        long until = System.currentTimeMillis() + 10_000;
        while (!clientHungUp.get() && System.currentTimeMillis() < until) {
            Thread.sleep(50);
        }
        assertThat(clientHungUp).isTrue();
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
