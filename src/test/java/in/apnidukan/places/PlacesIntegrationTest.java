package in.apnidukan.places;

import com.sun.net.httpserver.HttpServer;
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
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static in.apnidukan.support.CapturingSenders.LAST_EMAIL;
import static in.apnidukan.support.CapturingSenders.LAST_SMS;
import static in.apnidukan.support.CapturingSenders.codeSentTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A customer's saved places end to end (real Postgres and Redis in Docker, the real sign-in), and their address
 * search against a local fake of Google's Geocoding API (nothing is sent to Google).
 */
@EnabledIf("in.apnidukan.support.TestContainers#dockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CapturingSenders.class)
class PlacesIntegrationTest {

    private static final String RAVI = "9000000301";
    private static final String MEERA = "9000000302";
    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();
    private static final AtomicInteger GOOGLE_CALLS = new AtomicInteger();
    private static final HttpServer GOOGLE = fakeGoogle();

    private static HttpServer fakeGoogle() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/geocode/json", exchange -> {
                GOOGLE_CALLS.incrementAndGet();
                String q = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
                byte[] out = (q.contains("latlng=") ? """
                        {"status":"OK","results":[{"formatted_address":"Lake View Apartments, Kondapur, Hyderabad 500084",
                        "address_components":[{"long_name":"Lake View Apartments","types":["premise"]},
                        {"long_name":"Kondapur","types":["sublocality_level_1","sublocality"]},
                        {"long_name":"Hyderabad","types":["locality"]},{"long_name":"500084","types":["postal_code"]}]}]}
                        """ : """
                        {"status":"OK","results":[{"formatted_address":"Flat 4B, Lake View Apartments, Kondapur",
                        "geometry":{"location":{"lat":17.4651,"lng":78.3644},"location_type":"ROOFTOP"}}]}
                        """).getBytes(StandardCharsets.UTF_8);
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
    }

    private static Map<String, Object> place(String kind, String name, String addressLine) {
        Map<String, Object> p = new HashMap<>();
        p.put("kind", kind);
        if (name != null) {
            p.put("name", name);
        }
        p.put("addressLine", addressLine);
        p.put("area", "Kondapur");
        p.put("city", "Hyderabad");
        p.put("pincode", "500084");
        p.put("latitude", 17.4651234);
        p.put("longitude", 78.3644);
        return p;
    }

    @Test
    void aCustomerSavesHomeWorkAndOthersChangesAndDeletesThemAndNobodyElseSeesThem() {
        String ravi = token(RAVI);
        String meera = token(MEERA);
        Reply other = call(HttpMethod.POST, "/me/places", place("OTHER", "  Mom's   place ", "  12,   MG Road "), ravi);
        assertThat(other.status()).as(other.body().toString()).isEqualTo(201);
        assertThat(other.body().get("name")).isEqualTo("Mom's place");
        assertThat(other.body().get("addressLine")).isEqualTo("12, MG Road");
        assertThat(((Number) other.body().get("latitude")).doubleValue()).isEqualTo(17.465123);   // 6 decimals
        Reply work = call(HttpMethod.POST, "/me/places", place("WORK", "ignored", "Cyber Towers"), ravi);
        assertThat(work.status()).isEqualTo(201);
        assertThat(work.body().get("name")).isNull();
        Reply home = call(HttpMethod.POST, "/me/places", place("HOME", null, "Flat 4B"), ravi);
        assertThat(home.status()).isEqualTo(201);

        // Home, Work, then the others
        assertThat(kinds(ravi)).containsExactly("HOME", "WORK", "OTHER");
        assertThat(list(meera)).isEmpty();

        // Changing: the whole place; another person's is not found and stays as it was
        Map<String, Object> moved = place("HOME", null, "Flat 7C");
        moved.put("latitude", 17.47);
        String homeId = (String) home.body().get("id");
        Reply changed = call(HttpMethod.PUT, "/me/places/" + homeId, moved, ravi);
        assertThat(changed.status()).isEqualTo(200);
        assertThat(changed.body().get("addressLine")).isEqualTo("Flat 7C");
        assertThat(call(HttpMethod.PUT, "/me/places/" + homeId, place("HOME", null, "Not mine"), meera).status())
                .isEqualTo(404);
        assertThat(call(HttpMethod.DELETE, "/me/places/" + homeId, null, meera).status()).isEqualTo(204);
        assertThat(list(ravi)).hasSize(3);

        // Deleting, twice (a second tap): 204 both times
        assertThat(call(HttpMethod.DELETE, "/me/places/" + homeId, null, ravi).status()).isEqualTo(204);
        assertThat(call(HttpMethod.DELETE, "/me/places/" + homeId, null, ravi).status()).isEqualTo(204);
        assertThat(kinds(ravi)).containsExactly("WORK", "OTHER");
        assertThat(call(HttpMethod.PUT, "/me/places/" + homeId, moved, ravi).status()).isEqualTo(404);
        assertThat(call(HttpMethod.GET, "/me/places", null, null).status()).isEqualTo(401);
    }

    @Test
    void oneHomeOneWorkAnOtherNeedsANameThePinIsInIndiaAndTenAtMost() {
        String ravi = token(RAVI);
        String homeId = (String) call(HttpMethod.POST, "/me/places", place("HOME", null, "Flat 4B"), ravi).body()
                .get("id");
        Reply second = call(HttpMethod.POST, "/me/places", place("HOME", null, "Flat 9A"), ravi);
        assertThat(second.status()).isEqualTo(409);
        assertThat(second.body().get("code")).isEqualTo("PLACE_KIND_TAKEN");
        assertThat(((Map<?, ?>) second.body().get("details")).get("placeId")).isEqualTo(homeId);
        // A Work changed into a second Home: refused too; Home saved as Home again is fine
        String workId = (String) call(HttpMethod.POST, "/me/places", place("WORK", null, "Cyber Towers"), ravi)
                .body().get("id");
        assertThat(call(HttpMethod.PUT, "/me/places/" + workId, place("HOME", null, "Cyber Towers"), ravi).body()
                .get("code")).isEqualTo("PLACE_KIND_TAKEN");
        assertThat(call(HttpMethod.PUT, "/me/places/" + homeId, place("HOME", null, "Flat 4C"), ravi).status())
                .isEqualTo(200);

        Reply noName = call(HttpMethod.POST, "/me/places", place("OTHER", "  ", "Gym"), ravi);
        assertThat(noName.status()).isEqualTo(400);
        assertThat(((Map<?, ?>) noName.body().get("details")).containsKey("name")).isTrue();
        Map<String, Object> london = place("OTHER", "Abroad", "Baker Street");
        london.put("latitude", 51.5);
        london.put("longitude", -0.12);
        Reply abroad = call(HttpMethod.POST, "/me/places", london, ravi);
        assertThat(abroad.status()).isEqualTo(400);
        assertThat(abroad.body().get("code")).isEqualTo("VALIDATION_FAILED");
        // A Home's name is ignored, however long
        Reply named = call(HttpMethod.PUT, "/me/places/" + workId, place("WORK", "x".repeat(40), "Cyber Towers"), ravi);
        assertThat(named.status()).isEqualTo(200);
        assertThat(named.body().get("name")).isNull();
        Map<String, Object> badPin = place("OTHER", "Gym", "Road 2");
        badPin.put("pincode", "05008");
        assertThat(call(HttpMethod.POST, "/me/places", badPin, ravi).status()).isEqualTo(400);

        for (int i = 0; i < 8; i++) {
            assertThat(call(HttpMethod.POST, "/me/places", place("OTHER", "Place " + i, "Road " + i), ravi).status())
                    .isEqualTo(201);
        }
        Reply eleventh = call(HttpMethod.POST, "/me/places", place("OTHER", "One more", "Road 99"), ravi);
        assertThat(eleventh.status()).isEqualTo(409);
        assertThat(eleventh.body().get("code")).isEqualTo("TOO_MANY_PLACES");
        assertThat(list(ravi)).hasSize(10);
    }

    @Test
    void twoPhonesAddingAtOnceCantPassTheLimitOrSaveTwoHomes() throws Exception {
        String ravi = token(RAVI);
        for (int i = 0; i < 9; i++) {
            call(HttpMethod.POST, "/me/places", place("OTHER", "Place " + i, "Road " + i), ravi);
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Integer>> tries = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                tries.add(() -> call(HttpMethod.POST, "/me/places", place("HOME", null, "Flat 4B"), ravi).status());
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(tries)) {
                statuses.add(f.get());
            }
            assertThat(statuses).containsOnlyOnce(201).doesNotContain(500);
        } finally {
            pool.shutdownNow();
        }
        assertThat(list(ravi)).hasSize(10);
    }

    @Test
    void twoPhonesAddingOthersAtOnceAtNinePlacesSaveExactlyOne() throws Exception {
        String ravi = token(RAVI);
        for (int i = 0; i < 9; i++) {
            call(HttpMethod.POST, "/me/places", place("OTHER", "Place " + i, "Road " + i), ravi);
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Reply>> tries = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                int n = i;
                tries.add(() -> call(HttpMethod.POST, "/me/places", place("OTHER", "Gym " + n, "Road 9" + n), ravi));
            }
            List<Reply> replies = new ArrayList<>();
            for (Future<Reply> f : pool.invokeAll(tries)) {
                replies.add(f.get());
            }
            assertThat(replies).extracting(Reply::status).containsOnlyOnce(201).containsOnly(201, 409);
            assertThat(replies.stream().filter(r -> r.status() == 409).map(r -> r.body().get("code")))
                    .containsOnly("TOO_MANY_PLACES");
        } finally {
            pool.shutdownNow();
        }
        assertThat(list(ravi)).hasSize(10);
    }

    @Test
    void aTokenStillWorkingAfterTheAccountWasDeletedSavesNothing() {
        String ravi = token(RAVI);
        String id = (String) call(HttpMethod.POST, "/me/places", place("HOME", null, "Flat 4B"), ravi).body().get("id");
        // As if Redis missed the sign-out at deletion: the account is gone, the token not yet
        jdbc.update("UPDATE users SET deleted_at = now(), phone = NULL WHERE phone = ?", RAVI);
        Reply add = call(HttpMethod.POST, "/me/places", place("WORK", null, "Cyber Towers"), ravi);
        assertThat(add.status()).isEqualTo(401);
        assertThat(call(HttpMethod.PUT, "/me/places/" + id, place("HOME", null, "Flat 9"), ravi).status())
                .isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT address_line FROM customer_places", String.class)).isEqualTo("Flat 4B");
    }

    @Test
    void deletingTheAccountDeletesItsPlaces() {
        String ravi = token(RAVI);
        call(HttpMethod.POST, "/me/places", place("HOME", null, "Flat 4B"), ravi);
        call(HttpMethod.POST, "/auth/me/delete-code", Map.of(), ravi);
        String code = codeSentTo(LAST_SMS, RAVI);
        assertThat(call(HttpMethod.POST, "/auth/me/delete", Map.of("code", code), ravi).status()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_places", Integer.class)).isZero();
    }

    @Test
    void aCustomerFindsATypedAddressOrTheAddressAtTheGpsPointWithinCustomersShareOfTheDay() {
        String ravi = token(RAVI);
        Map<String, Object> typed = Map.of("addressLine", "Flat 4B", "area", "Kondapur", "city", "Hyderabad",
                "pincode", "500084");
        Reply found = call(HttpMethod.POST, "/me/places/geocode", typed, ravi);
        assertThat(found.status()).as(found.body().toString()).isEqualTo(200);
        assertThat(((Number) found.body().get("latitude")).doubleValue()).isEqualTo(17.4651);
        assertThat(found.body().get("precision")).isEqualTo("EXACT");
        Reply at = call(HttpMethod.POST, "/me/places/reverse-geocode", Map.of("latitude", 17.4651, "longitude", 78.3644),
                ravi);
        assertThat(at.status()).isEqualTo(200);
        assertThat(at.body().get("area")).isEqualTo("Kondapur");
        assertThat(at.body().get("pincode")).isEqualTo("500084");
        assertThat(GOOGLE_CALLS.get()).isEqualTo(2);
        assertThat(call(HttpMethod.POST, "/me/places/reverse-geocode", Map.of("latitude", 51.5, "longitude", -0.12),
                ravi).status()).isEqualTo(400);

        // Customers have used their share today: 503 for them, while an owner registering still searches
        String day = LocalDate.now(ZoneId.of("America/Los_Angeles")).toString();
        redis.opsForValue().set("rl:geocode-day-customers:" + day, "100");
        Map<String, Object> other = Map.of("addressLine", "Flat 9A", "area", "Kondapur", "city", "Hyderabad",
                "pincode", "500084");
        Reply used = call(HttpMethod.POST, "/me/places/geocode", other, ravi);
        assertThat(used.status()).isEqualTo(503);
        assertThat(used.body().get("code")).isEqualTo("GEOCODING_UNAVAILABLE");
        assertThat(call(HttpMethod.POST, "/shops/geocode", Map.of("addressLine", "Shop 9, Lake View", "area",
                "Kondapur", "city", "Hyderabad", "pincode", "500084"), token(MEERA)).status()).isEqualTo(200);
        assertThat(call(HttpMethod.POST, "/me/places/geocode", typed, null).status()).isEqualTo(401);
    }

    private List<Object> kinds(String token) {
        return list(token).stream().map(p -> (Object) p.get("kind")).toList();
    }

    private List<Map<?, ?>> list(String token) {
        Reply r = call(HttpMethod.GET, "/me/places", null, token);
        assertThat(r.status()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Map<?, ?>> items = (List<Map<?, ?>>) r.list();
        return items;
    }

    private String token(String phone) {
        call(HttpMethod.POST, "/auth/otp/send", Map.of("phone", phone), null);
        Reply r = call(HttpMethod.POST, "/auth/otp/verify", Map.of("phone", phone, "code", codeSentTo(LAST_SMS, phone)),
                null);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        TestData.completeProfile(jdbc, phone);
        return (String) r.body().get("accessToken");
    }

    private Reply call(HttpMethod method, String path, Object body, String token) {
        RestClient.RequestBodySpec spec = http.method(method).uri(path).headers(h -> {
            if (token != null) {
                h.setBearerAuth(token);
            }
        });
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((rq, res) -> {
            int status = res.getStatusCode().value();
            if (status == 204) {
                return new Reply(status, Map.of(), null);
            }
            byte[] raw = res.getBody().readAllBytes();
            if (raw.length == 0) {
                return new Reply(status, Map.of(), null);
            }
            Object parsed = JSON.readValue(raw, Object.class);
            return parsed instanceof List<?> l ? new Reply(status, Map.of(), l)
                    : new Reply(status, (Map<?, ?>) parsed, null);
        });
    }

    private record Reply(int status, Map<?, ?> body, List<?> list) {
    }
}
