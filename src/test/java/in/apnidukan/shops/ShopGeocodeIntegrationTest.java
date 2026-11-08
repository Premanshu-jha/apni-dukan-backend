package in.apnidukan.shops;

import com.sun.net.httpserver.HttpServer;
import in.apnidukan.common.security.RateLimiter;
import in.apnidukan.support.CapturingSenders;
import in.apnidukan.support.TestContainers;
import in.apnidukan.support.TestData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static in.apnidukan.support.CapturingSenders.LAST_EMAIL;
import static in.apnidukan.support.CapturingSenders.LAST_SMS;
import static in.apnidukan.support.CapturingSenders.codeSentTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;

/**
 * POST /shops/geocode end to end with a Google key set: real Postgres and Redis in Docker, the real sign-in, and a
 * local fake of Google's Geocoding API (nothing is sent to Google).
 */
@EnabledIf("in.apnidukan.support.TestContainers#dockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CapturingSenders.class)
class ShopGeocodeIntegrationTest {

    private static final String OWNER_PHONE = "9848012381";
    private static final AtomicInteger GOOGLE_CALLS = new AtomicInteger();
    private static final AtomicReference<String> GOOGLE_ANSWER = new AtomicReference<>("");
    /** Google's answer to a reverse lookup (latlng=...). */
    private static final AtomicReference<String> GOOGLE_REVERSE = new AtomicReference<>("");
    private static final HttpServer GOOGLE = fakeGoogle();

    private static HttpServer fakeGoogle() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/geocode/json", exchange -> {
                GOOGLE_CALLS.incrementAndGet();
                String q = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
                byte[] out = (q.contains("key=test-geo-key")
                        ? (q.contains("latlng=") ? GOOGLE_REVERSE.get() : GOOGLE_ANSWER.get())
                        : "{\"status\":\"REQUEST_DENIED\"}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        TestContainers.register(registry);
        registry.add("app.geocoding.google-key", () -> "test-geo-key");
        registry.add("app.geocoding.google-url",
                () -> "http://127.0.0.1:" + GOOGLE.getAddress().getPort() + "/geocode/json");
    }

    @AfterAll
    static void stopGoogle() {
        GOOGLE.stop(0);
    }

    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @MockitoSpyBean RateLimiter limits;

    RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.builder()
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + environment.getProperty("local.server.port") + "/api/v1")
                .build();
        TestData.clean(jdbc);
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        LAST_SMS.clear();
        LAST_EMAIL.clear();
        GOOGLE_CALLS.set(0);
        GOOGLE_ANSWER.set("""
                {"status":"OK","results":[{"formatted_address":"Lake View Apartments, Kondapur, Hyderabad 500084",
                "geometry":{"location":{"lat":17.4651,"lng":78.3644},"location_type":"GEOMETRIC_CENTER"}}]}
                """);
        GOOGLE_REVERSE.set("""
                {"status":"OK","results":[{"formatted_address":"Lake View Apartments, Kondapur, Hyderabad 500084",
                "address_components":[{"long_name":"Lake View Apartments","types":["premise"]},
                {"long_name":"Kondapur","types":["sublocality_level_1","sublocality"]},
                {"long_name":"Hyderabad","types":["locality"]},{"long_name":"500084","types":["postal_code"]}]}]}
                """);
    }

    private static Map<String, Object> address() {
        Map<String, Object> a = new HashMap<>();
        a.put("addressLine", "Shop 4, Lake View Apartments");
        a.put("area", "Kondapur");
        a.put("city", "Hyderabad");
        a.put("pincode", "500084");
        return a;
    }

    @Test
    void findsTheAddressAndAnswersFromTheCacheTheSecondTime() {
        String access = token(OWNER_PHONE);
        Reply first = post("/shops/geocode", address(), access);
        assertThat(first.status()).as(first.body().toString()).isEqualTo(200);
        assertThat(((Number) first.body().get("latitude")).doubleValue()).isEqualTo(17.4651);
        assertThat(((Number) first.body().get("longitude")).doubleValue()).isEqualTo(78.3644);
        assertThat(first.body().get("precision")).isEqualTo("AREA");
        assertThat(first.body().get("formattedAddress")).asString().contains("Kondapur");

        Map<String, Object> sameButMessy = address();
        sameButMessy.put("addressLine", "  shop 4,   LAKE VIEW apartments ");
        Reply second = post("/shops/geocode", sameButMessy, access);
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.body().get("latitude")).isEqualTo(first.body().get("latitude"));
        assertThat(GOOGLE_CALLS.get()).isEqualTo(1);
    }

    @Test
    void anUnknownAddressIsNotFoundAndKeptAndGoogleFailingIsUnavailable() {
        String access = token(OWNER_PHONE);
        GOOGLE_ANSWER.set("{\"status\":\"ZERO_RESULTS\",\"results\":[]}");
        Reply none = post("/shops/geocode", address(), access);
        assertThat(none.status()).isEqualTo(404);
        assertThat(none.body().get("code")).isEqualTo("ADDRESS_NOT_FOUND");
        // Tapping again doesn't ask Google again (kept 6 hours)
        assertThat(post("/shops/geocode", address(), access).status()).isEqualTo(404);
        assertThat(GOOGLE_CALLS.get()).isEqualTo(1);

        GOOGLE_ANSWER.set("{\"status\":\"OVER_QUERY_LIMIT\",\"error_message\":\"key test-geo-key over\"}");
        Map<String, Object> other = address();
        other.put("addressLine", "Shop 5, Lake View Apartments");
        Reply down = post("/shops/geocode", other, access);
        assertThat(down.status()).isEqualTo(503);
        assertThat(down.body().get("code")).isEqualTo("GEOCODING_UNAVAILABLE");
        assertThat(down.body().toString()).doesNotContain("test-geo-key");
    }

    @Test
    void theAddressIsCheckedLikeARegistration() {
        String access = token(OWNER_PHONE);
        Map<String, Object> badPin = address();
        badPin.put("pincode", "50008");
        Reply r = post("/shops/geocode", badPin, access);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("code")).isEqualTo("VALIDATION_FAILED");
        assertThat(((Map<?, ?>) r.body().get("details")).containsKey("pincode")).isTrue();

        Map<String, Object> noCity = address();
        noCity.remove("city");
        assertThat(post("/shops/geocode", noCity, access).status()).isEqualTo(400);
        assertThat(GOOGLE_CALLS.get()).isZero();
    }

    @Test
    void onlySignedInPeopleMaySearch() {
        assertThat(post("/shops/geocode", address(), null).status()).isEqualTo(401);
        assertThat(GOOGLE_CALLS.get()).isZero();
    }

    @Test
    void past10GoogleLookupsADayAnd20SearchesAnHourThePersonIsToldToWait() {
        String access = token(OWNER_PHONE);
        for (int i = 0; i < 10; i++) {
            Map<String, Object> a = address();
            a.put("addressLine", "Shop " + i + ", Lake View Apartments");
            assertThat(post("/shops/geocode", a, access).status()).isEqualTo(200);
        }
        // An 11th new address would call Google: refused for the day
        Map<String, Object> eleventh = address();
        eleventh.put("addressLine", "Shop 99, Lake View Apartments");
        Reply day = post("/shops/geocode", eleventh, access);
        assertThat(day.status()).isEqualTo(429);
        assertThat(day.body().get("code")).isEqualTo("TRY_LATER");
        assertThat(GOOGLE_CALLS.get()).isEqualTo(10);

        // Addresses found before still come from the cache, until 20 searches in the hour
        Map<String, Object> again = address();
        again.put("addressLine", "Shop 0, Lake View Apartments");
        for (int i = 0; i < 9; i++) {
            assertThat(post("/shops/geocode", again, access).status()).isEqualTo(200);
        }
        Reply hour = post("/shops/geocode", again, access);
        assertThat(hour.status()).isEqualTo(429);
        assertThat(hour.body().get("code")).isEqualTo("TRY_LATER");
        assertThat(GOOGLE_CALLS.get()).isEqualTo(10);
    }

    @Test
    void redisDownAtTheDaysCounterAnswersServiceBusyWithoutCallingGoogle() {
        String access = token(OWNER_PHONE);
        doThrow(new RedisConnectionFailureException("Redis is down")).when(limits)
                .hit(startsWith("geocode-day:"), any());
        Reply r = post("/shops/geocode", address(), access);
        assertThat(r.status()).isEqualTo(503);
        assertThat(r.body().get("code")).isEqualTo("SERVICE_BUSY");
        assertThat(GOOGLE_CALLS.get()).isZero();
    }

    @Test
    void theAddressAtTheGpsPointFillsTheFormAndIsKept() {
        String access = token(OWNER_PHONE);
        Reply r = post("/shops/reverse-geocode", Map.of("latitude", 17.46512, "longitude", 78.36441), access);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(r.body().get("addressLine")).isEqualTo("Lake View Apartments");
        assertThat(r.body().get("area")).isEqualTo("Kondapur");
        assertThat(r.body().get("city")).isEqualTo("Hyderabad");
        assertThat(r.body().get("pincode")).isEqualTo("500084");
        // A few metres away: the same rounded point, from the cache
        assertThat(post("/shops/reverse-geocode", Map.of("latitude", 17.46508, "longitude", 78.36438), access)
                .status()).isEqualTo(200);
        assertThat(GOOGLE_CALLS.get()).isEqualTo(1);

        GOOGLE_REVERSE.set("{\"status\":\"ZERO_RESULTS\",\"results\":[]}");
        Reply none = post("/shops/reverse-geocode", Map.of("latitude", 20.0, "longitude", 80.0), access);
        assertThat(none.status()).isEqualTo(404);
        assertThat(none.body().get("code")).isEqualTo("ADDRESS_NOT_FOUND");
    }

    @Test
    void aPointOutsideIndiaIsRefusedBeforeGoogle() {
        String access = token(OWNER_PHONE);
        Reply r = post("/shops/reverse-geocode", Map.of("latitude", 51.5, "longitude", -0.12), access);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("code")).isEqualTo("VALIDATION_FAILED");
        assertThat(GOOGLE_CALLS.get()).isZero();
    }

    @Test
    void bothWaysShareOnePersonsDailyGoogleLimit() {
        String access = token(OWNER_PHONE);
        for (int i = 0; i < 5; i++) {
            Map<String, Object> a = address();
            a.put("addressLine", "Shop " + i + ", Lake View Apartments");
            assertThat(post("/shops/geocode", a, access).status()).isEqualTo(200);
            assertThat(post("/shops/reverse-geocode", Map.of("latitude", 17.0 + i / 100.0, "longitude", 78.0),
                    access).status()).isEqualTo(200);
        }
        Reply r = post("/shops/reverse-geocode", Map.of("latitude", 18.0, "longitude", 78.0), access);
        assertThat(r.status()).isEqualTo(429);
        assertThat(r.body().get("code")).isEqualTo("TRY_LATER");
        assertThat(GOOGLE_CALLS.get()).isEqualTo(10);
    }

    private String token(String phone) {
        post("/auth/otp/send", Map.of("phone", phone), null);
        Reply r = post("/auth/otp/verify", Map.of("phone", phone, "code", codeSentTo(LAST_SMS, phone)), null);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        TestData.completeProfile(jdbc, phone);
        return (String) r.body().get("accessToken");
    }

    private Reply post(String path, Object body, String token) {
        return http.post().uri(path).headers(h -> {
            if (token != null) {
                h.setBearerAuth(token);
            }
        }).contentType(MediaType.APPLICATION_JSON).body(body).exchange((rq, res) -> {
            int status = res.getStatusCode().value();
            Map<?, ?> b;
            try {
                b = status == 204 ? Map.of() : res.bodyTo(Map.class);
            } catch (org.springframework.web.client.RestClientException e) {   // e.g. an empty 401
                b = Map.of();
            }
            return new Reply(status, b == null ? Map.of() : b);
        });
    }

    private record Reply(int status, Map<?, ?> body) {
    }
}
