package in.apnidukan.leads;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import in.apnidukan.common.security.AuthPrincipal;
import in.apnidukan.common.security.JwtService;
import in.apnidukan.common.security.SessionRevocations;
import in.apnidukan.leads.service.LeadMatchJob;
import in.apnidukan.support.TestContainers;
import in.apnidukan.support.TestData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin Leads page end to end against real Postgres and Redis, with a fake Mapbox: who may use it, the district
 * search and areas (India Post's real pincodes, loaded by V96), a scan (categories, full boxes split into quarters,
 * service words, other pincodes left out, Clozest salons matched by phone and by name), saving leads (versions,
 * assignment rules, contact details kept only once contacted, WhatsApp consent), follow-ups, the funnel, the monthly
 * cap, and the matching job.
 */
@EnabledIf("in.apnidukan.support.TestContainers#dockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LeadsIntegrationTest {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final UUID BOSS = UUID.fromString("c0000000-0000-0000-0000-000000000001");
    private static final UUID AGENT = UUID.fromString("c0000000-0000-0000-0000-000000000002");
    private static final UUID AGENT2 = UUID.fromString("c0000000-0000-0000-0000-000000000003");
    private static final UUID SUPPORT = UUID.fromString("c0000000-0000-0000-0000-000000000004");
    private static final UUID CUSTOMER = UUID.fromString("c0000000-0000-0000-0000-000000000005");
    private static final UUID OWNER = UUID.fromString("c0000000-0000-0000-0000-000000000006");
    private static final UUID OWNER2 = UUID.fromString("c0000000-0000-0000-0000-000000000007");
    private static final UUID SALON = UUID.fromString("c0000000-0000-0000-0000-0000000000a1");
    private static final UUID SALON2 = UUID.fromString("c0000000-0000-0000-0000-0000000000a2");
    private static final UUID LATER = UUID.fromString("c0000000-0000-0000-0000-0000000000a3");
    private static final String PIN = "500072";

    // ---- A fake Mapbox ---------------------------------------------------------------------------------------------

    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicReference<String> TOKEN_SEEN = new AtomicReference<>("");
    private static final HttpServer MAPBOX = fakeMapbox();

    private static HttpServer fakeMapbox() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/", LeadsIntegrationTest::answer);
            server.setExecutor(Executors.newFixedThreadPool(8));
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void answer(HttpExchange ex) throws IOException {
        CALLS.incrementAndGet();
        String path = ex.getRequestURI().getPath();
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        TOKEN_SEEN.set(q.getOrDefault("access_token", ""));
        String body;
        if (!"test-mapbox-token".equals(q.get("access_token"))) {
            send(ex, 401, "{\"message\":\"Not Authorized - Invalid Token\"}");
            return;
        }
        if (path.equals("/v1/forward") && "postcode".equals(q.get("types"))) {
            body = PIN.equals(q.get("q"))
                    ? "{\"features\":[{\"properties\":{\"name\":\"500072\",\"coordinates\":{\"latitude\":17.4939,"
                    + "\"longitude\":78.4015},\"bbox\":[78.38,17.47,78.42,17.51]}}]}"
                    : "{\"features\":[]}";
        } else if (path.equals("/v1/forward") && q.getOrDefault("types", "").contains("locality")) {
            // Place look-ups: Yapral is in 500087; a name with no pincode in India is skipped
            body = switch (q.getOrDefault("q", "")) {
                case "yapral" -> "{\"features\":[{\"properties\":{\"name\":\"Yapral\",\"feature_type\":\"neighborhood\","
                        + "\"context\":{\"postcode\":{\"name\":\"500087\"}}}},{\"properties\":{\"name\":\"Yapral Road\","
                        + "\"feature_type\":\"locality\",\"context\":{\"postcode\":{\"name\":\"500087\"}}}},"
                        + "{\"properties\":{\"name\":\"Nowhere\",\"feature_type\":\"place\",\"context\":{}}}]}";
                default -> "{\"features\":[]}";
            };
        } else if (path.equals("/v1/forward")) {
            String words = q.getOrDefault("q", "");
            body = switch (words) {
                case "mehendi artist" -> features(feature("mb-mehendi", "Arifa mehandi artist", 17.4995, 78.4096,
                        "500072", List.of("services"), null));
                case "makeup artist" -> features(feature("mb-artist", "saida chary artist", 17.4961, 78.4158, "500072",
                        List.of("art", "arts center"), null),
                        feature("mb-tia", "Tia's Beauty Parlour", 17.48467, 78.41323, "500072",
                                List.of("salon"), null));
                default -> features();
            };
        } else if (path.startsWith("/v1/category/")) {
            String category = path.substring("/v1/category/".length());
            double[] box = box(q.get("bbox"));
            boolean whole = box[2] - box[0] > 0.03;
            body = switch (category) {
                case "salon" -> whole ? features(
                        feature("mb-tia", "Tia's Beauty Parlour", 17.48467, 78.41323, "500072",
                                List.of("cosmetics shop", "salon"), "+919133576278"),
                        feature("mb-rich", "Rich Look Unisex", 17.4900, 78.4000, "500072", List.of("salon"), null),
                        feature("mb-other", "Next Door Salon", 17.5090, 78.4190, "500085", List.of("salon"), null),
                        feature("mb-nopin", "Lakme Salon KPHB", 17.4930, 78.3990, null, List.of("salon"), null))
                        : features();
                // A busy category: the whole box comes back full (25), each quarter with one more
                case "hairdresser" -> whole ? barbers(25) : features(feature("mb-q-" + q.get("bbox"),
                        "Quarter Cuts " + q.get("bbox"), (box[1] + box[3]) / 2, (box[0] + box[2]) / 2, "500072",
                        List.of("barber"), null));
                default -> features();
            };
        } else {
            send(ex, 404, "{}");
            return;
        }
        send(ex, 200, body);
    }

    private static String barbers(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(feature("mb-barber-" + i, "Barber " + i, 17.48 + i * 0.0005, 78.39 + i * 0.0005, "500072",
                    List.of("barber"), "+9190000001" + String.format("%02d", i)));
        }
        return features(out.toArray(String[]::new));
    }

    private static String feature(String id, String name, double lat, double lng, String postcode,
                                  List<String> categories, String phone) {
        return "{\"properties\":{\"mapbox_id\":\"" + id + "\",\"name\":\"" + name.replace("\"", "") + "\","
                + "\"full_address\":\"Road 1, Hyderabad\",\"coordinates\":{\"latitude\":" + lat + ",\"longitude\":"
                + lng + "},\"context\":{" + (postcode == null ? "" : "\"postcode\":{\"name\":\"" + postcode + "\"}")
                + "},\"poi_category\":[" + String.join(",", categories.stream().map(c -> "\"" + c + "\"").toList())
                + "],\"metadata\":{" + (phone == null ? "" : "\"phone\":\"" + phone + "\"") + "}}}";
    }

    private static String features(String... f) {
        return "{\"type\":\"FeatureCollection\",\"features\":[" + String.join(",", f) + "]}";
    }

    private static double[] box(String bbox) {
        String[] p = bbox == null ? new String[] {"0", "0", "0", "0"} : bbox.split(",");
        return new double[] {Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                Double.parseDouble(p[3])};
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw != null) {
            for (String part : raw.split("&")) {
                int eq = part.indexOf('=');
                if (eq > 0) {
                    out.put(part.substring(0, eq), URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
        }
        return out;
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        TestContainers.register(registry);
        registry.add("app.leads.mapbox-token", () -> "test-mapbox-token");
        registry.add("app.leads.mapbox-url", () -> "http://127.0.0.1:" + MAPBOX.getAddress().getPort() + "/v1");
        registry.add("app.leads.mapbox-monthly-cap", () -> "100000");
    }

    @AfterAll
    static void stopMapbox() {
        MAPBOX.stop(0);
    }

    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired JwtService jwt;
    @Autowired SessionRevocations sessions;
    @Autowired LeadMatchJob matchJob;
    @Autowired in.apnidukan.leads.repo.PincodeDirectory directory;

    RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.builder()
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + environment.getProperty("local.server.port") + "/api/v1")
                .build();
        TestData.clean(jdbc);
        redis.delete(redis.keys("rl:lead-scans:*"));
        redis.delete(redis.keys("rl:lead-lookups:*"));
        redis.delete(redis.keys("rl:lead-checks:*"));
        redis.delete(redis.keys("rl:lead-refreshes:*"));
        TestData.user(jdbc, BOSS, "9000000401");
        TestData.user(jdbc, AGENT, "9000000402");
        TestData.user(jdbc, AGENT2, "9000000403");
        TestData.user(jdbc, SUPPORT, "9000000404");
        TestData.user(jdbc, CUSTOMER, "9000000405");
        TestData.user(jdbc, OWNER, "9133576278");
        TestData.user(jdbc, OWNER2, "9000000407");
        jdbc.update("UPDATE users SET admin_role = 'SUPER_ADMIN', name = 'Prem' WHERE id = ?", BOSS);
        jdbc.update("UPDATE users SET admin_role = 'ADMIN', admin_designation = 'FIELD_AGENT', name = 'Ravi' "
                + "WHERE id = ?", AGENT);
        jdbc.update("UPDATE users SET admin_role = 'ADMIN', admin_designation = 'FIELD_AGENT', name = 'Sita' "
                + "WHERE id = ?", AGENT2);
        jdbc.update("UPDATE users SET admin_role = 'ADMIN', admin_designation = 'SUPPORT' WHERE id = ?", SUPPORT);
        jdbc.update("INSERT INTO admin_pincodes (user_id, pincode, added_at) VALUES (?, ?, now())", AGENT, PIN);
        // A salon on Clozest whose owner's phone is Tia's, and one with Rich Look's name at Rich Look's place
        TestData.salon(jdbc, SALON, OWNER);
        jdbc.update("UPDATE shops SET name = 'Tias Parlour', status = 'APPROVED', pincode = ?, latitude = 17.40, "
                + "longitude = 78.30 WHERE id = ?", PIN, SALON);
        TestData.salon(jdbc, SALON2, OWNER2);
        jdbc.update("UPDATE shops SET name = 'Rich Look', status = 'PENDING_VISIT', pincode = ?, latitude = 17.4900, "
                + "longitude = 78.4000 WHERE id = ?", PIN, SALON2);
        CALLS.set(0);
    }

    // ---- Who may use it ------------------------------------------------------------------------------------------

    @Test
    void onlyTheFieldTeamUsesLeads() {
        for (String path : List.of("/admin/leads/districts?q=hyd", "/admin/leads/usage", "/admin/leads/due",
                "/admin/leads/areas?district=Hyderabad&state=Telangana")) {
            assertThat(get(path, BOSS).status()).as(path).isEqualTo(200);
            assertThat(get(path, AGENT).status()).as(path).isEqualTo(200);
            assertThat(get(path, SUPPORT).status()).as(path).isEqualTo(404);
            assertThat(get(path, CUSTOMER).status()).as(path).isIn(403, 404);
            assertThat(get(path, null).status()).as(path).isEqualTo(401);
        }
        assertThat(call(HttpMethod.POST, "/admin/leads/scan/" + PIN, null, SUPPORT).status()).isEqualTo(404);
        // The funnel and everyone's follow-ups are for super admins
        assertThat(code(get("/admin/leads/funnel?district=Hyderabad&state=Telangana", AGENT)))
                .isEqualTo("SUPER_ADMIN_ONLY");
        assertThat(code(get("/admin/leads/due?everyone=true", AGENT))).isEqualTo("SUPER_ADMIN_ONLY");
        assertThat(get("/admin/leads/due?everyone=true", BOSS).status()).isEqualTo(200);
    }

    // ---- Districts and areas -------------------------------------------------------------------------------------

    @Test
    void citiesAndDistrictsAreFoundByNameAnotherNameAPostOfficeOrAPincode() {
        // "Hyderabad" is the city first (all three of India Post's districts), then the district alone
        List<Map<String, Object>> byName = items(get("/admin/leads/districts?cities=true&q=hyder", AGENT));
        assertThat(byName.getFirst()).containsEntry("kind", "CITY").containsEntry("district", "Hyderabad")
                .containsEntry("state", "Telangana").containsEntry("matched", null).containsEntry("pincode", null)
                .containsEntry("districts", List.of("Hyderabad", "Medchal Malkajgiri", "Ranga Reddy", "Sangareddy"))
                .containsEntry("pincodes", 169);
        assertThat(byName.get(1)).containsEntry("kind", "DISTRICT").containsEntry("district", "Hyderabad");
        // Another name
        assertThat(items(get("/admin/leads/districts?cities=true&q=bangal", AGENT)).getFirst()).containsEntry("kind", "CITY")
                .containsEntry("district", "Bengaluru").containsEntry("matched", "Bangalore");
        // A post office in a city's district: offered as the city, with that area to tick
        assertThat(items(get("/admin/leads/districts?cities=true&q=kukatpally", AGENT))).anySatisfy(d -> {
            assertThat(d).containsEntry("kind", "CITY").containsEntry("district", "Hyderabad")
                    .containsEntry("pincode", PIN);
            assertThat((String) d.get("matched")).contains("Kukatpally").contains(PIN);
        });
        // A whole pincode: just that area (Yapral's, filed by India Post under Medchal Malkajgiri)
        List<Map<String, Object>> byPin = items(get("/admin/leads/districts?cities=true&q=500087", AGENT));
        assertThat(byPin).singleElement().satisfies(d -> {
            assertThat(d).containsEntry("kind", "CITY").containsEntry("district", "Hyderabad")
                    .containsEntry("pincode", "500087");
            assertThat((String) d.get("matched")).startsWith("500087 · ");
        });
        // A town outside any city: its district
        assertThat(items(get("/admin/leads/districts?cities=true&q=saharsa", AGENT)).getFirst()).containsEntry("kind", "DISTRICT")
                .containsEntry("district", "Saharsa").containsEntry("state", "Bihar");
        assertThat(items(get("/admin/leads/districts?cities=true&q=852201", AGENT))).singleElement()
                .satisfies(d -> assertThat(d).containsEntry("district", "Saharsa").containsEntry("pincode", "852201"));
        assertThat(items(get("/admin/leads/districts?cities=true&q=50007", AGENT))).isNotEmpty();
        assertThat(items(get("/admin/leads/districts?cities=true&q=h", AGENT))).isEmpty();
        assertThat(code(get("/admin/leads/districts?cities=true&q=" + "x".repeat(61), AGENT))).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void theAdminWebsiteFromBeforeCitiesStillGetsDistrictsOnly() {
        // Deployed before the new admin website: without cities=true, no city hits, an area is offered as its district
        assertThat(items(get("/admin/leads/districts?q=hyder", AGENT))).allSatisfy(d -> assertThat(d)
                .containsEntry("kind", "DISTRICT"));
        assertThat(items(get("/admin/leads/districts?q=500087", AGENT))).singleElement().satisfies(d -> assertThat(d)
                .containsEntry("district", "Medchal Malkajgiri").containsEntry("pincode", "500087"));
        // Area hits in pincode order, the same after every restart
        List<Object> pins = items(get("/admin/leads/districts?cities=true&q=5000", AGENT)).stream()
                .map(d -> d.get("pincode")).toList();
        assertThat(pins).hasSize(15).isSortedAccordingTo(java.util.Comparator.comparing(Object::toString));
        assertThat(pins.getFirst()).isEqualTo("500001");
    }

    @Test
    void everyPincodeInIndiaIsFoundAndBelongsToAPlace() {
        // The CSV loader filled the table (R__India_places), and each of its pincodes is offered as a city or district
        List<String> all = jdbc.queryForList("SELECT pincode FROM india_pincodes", String.class);
        assertThat(all).hasSizeGreaterThan(19000).contains("500087", "852201", "110001", "744101");
        assertThat(all).allSatisfy(p -> assertThat(directory.pin(p).flatMap(directory::placeOf)).isPresent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM india_cities", Integer.class)).isGreaterThan(60);
        // India Post's far-off points are marked doubtful (Yapral's is 54 km off); a real far village keeps its point
        assertThat(jdbc.queryForObject("SELECT location_doubtful FROM india_pincodes WHERE pincode = '500087'",
                Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT latitude IS NOT NULL FROM india_pincodes WHERE pincode = '743370'",
                Boolean.class)).isTrue();
    }

    @Test
    void aWordMatchesTheStartOfAPostOfficesWordAndAStateGivesItsDistricts() {
        // "alwal" is Alwal in Hyderabad, not Palwal in Haryana; areas in a city come before villages elsewhere
        List<Map<String, Object>> alwal = items(get("/admin/leads/districts?cities=true&q=alwal", AGENT));
        assertThat(alwal).isNotEmpty().allSatisfy(d -> assertThat((String) d.get("matched")).doesNotContain("Palwal"));
        assertThat(alwal.getFirst()).containsEntry("district", "Hyderabad");
        // A state: its districts first
        assertThat(items(get("/admin/leads/districts?cities=true&q=telangana", AGENT)).getFirst())
                .containsEntry("kind", "DISTRICT").containsEntry("state", "Telangana").containsEntry("pincode", null);
    }

    @Test
    void aLookUpTheMapCouldNotMakeDoesNotUseTheHourlyAllowance() {
        String month = LocalDate.now(INDIA).format(DateTimeFormatter.ofPattern("yyyy-MM"));
        jdbc.update("INSERT INTO mapbox_usage (month, requests) VALUES (?, 100000)", month);
        assertThat(code(get("/admin/leads/places?q=yapral", AGENT))).isEqualTo("MAPBOX_CAP_REACHED");
        String count = redis.opsForValue().get("rl:lead-lookups:" + AGENT);
        assertThat(count == null ? 0 : Integer.parseInt(count)).isZero();
        // The hourly limit: 30 look-ups per person
        jdbc.update("DELETE FROM mapbox_usage");
        redis.opsForValue().set("rl:lead-lookups:" + AGENT, "30", java.time.Duration.ofMinutes(30));
        Reply limited = get("/admin/leads/places?q=yapral", AGENT);
        assertThat(code(limited)).isEqualTo("TOO_MANY_REQUESTS");
    }

    @Test
    void aPlaceNameOurListLacksIsLookedUpOnTheMap() {
        List<Map<String, Object>> found = items(get("/admin/leads/places?q=yapral", AGENT));
        // Two Mapbox answers in the same pincode: offered once; the one without a pincode is skipped
        assertThat(found).singleElement().satisfies(d -> assertThat(d).containsEntry("kind", "CITY")
                .containsEntry("district", "Hyderabad").containsEntry("pincode", "500087")
                .containsEntry("matched", "Yapral (500087)"));
        assertThat(CALLS.get()).isEqualTo(1);
        String month = LocalDate.now(INDIA).format(DateTimeFormatter.ofPattern("yyyy-MM"));
        assertThat(jdbc.queryForObject("SELECT requests FROM mapbox_usage WHERE month = ?", Long.class, month))
                .isEqualTo(1);
        assertThat(items(get("/admin/leads/places?q=zzzq", AGENT))).isEmpty();
        assertThat(code(get("/admin/leads/places?q=ya", AGENT))).isEqualTo("VALIDATION_FAILED");
        assertThat(code(get("/admin/leads/places?q=500087", AGENT))).isEqualTo("VALIDATION_FAILED");
        assertThat(get("/admin/leads/places?q=yapral", SUPPORT).status()).isEqualTo(404);
        // Capped: the month's cap counts look-ups too
        jdbc.update("UPDATE mapbox_usage SET requests = 100000 WHERE month = ?", month);
        assertThat(code(get("/admin/leads/places?q=yapral", AGENT))).isEqualTo("MAPBOX_CAP_REACHED");
    }

    @Test
    void aCityListsTheAreasOfAllItsDistricts() {
        Reply r = get("/admin/leads/areas?kind=CITY&district=Hyderabad&state=Telangana", AGENT);
        assertThat(r.status()).isEqualTo(200);
        assertThat(map(r.body())).containsEntry("kind", "CITY")
                .containsEntry("districts", List.of("Hyderabad", "Medchal Malkajgiri", "Ranga Reddy", "Sangareddy"));
        List<Map<String, Object>> areas = list(r.body(), "areas");
        assertThat(areas).hasSize(169);
        assertThat(areas.getFirst()).containsEntry("district", "Hyderabad");
        assertThat(areas).anySatisfy(a -> assertThat(a).containsEntry("pincode", "500087")
                .containsEntry("district", "Medchal Malkajgiri"));
        // Patancheru, Ameenpur, Tellapur: filed under Sangareddy, part of the city
        assertThat(areas).anySatisfy(a -> assertThat(a).containsEntry("pincode", "502032")
                .containsEntry("district", "Sangareddy"));
        assertThat(areas).anySatisfy(a -> assertThat(a).containsEntry("pincode", PIN).containsEntry("mine", true));
        assertThat(code(get("/admin/leads/areas?kind=TOWN&district=Hyderabad&state=Telangana", AGENT)))
                .isEqualTo("VALIDATION_FAILED");
        assertThat(code(get("/admin/leads/areas?kind=CITY&district=Saharsa&state=Bihar", AGENT)))
                .isEqualTo("DISTRICT_NOT_FOUND");
        assertThat(map(get("/admin/leads/funnel?kind=CITY&district=Hyderabad&state=Telangana", BOSS).body()))
                .containsEntry("kind", "CITY").containsEntry("district", "Hyderabad");
    }

    @Test
    void areasShowWhichAreMineAndWhatIsDoneThere() {
        Reply r = get("/admin/leads/areas?district=Medchal Malkajgiri&state=Telangana", AGENT);
        assertThat(r.status()).isEqualTo(200);
        List<Map<String, Object>> areas = list(r.body(), "areas");
        Map<String, Object> kukatpally = areas.stream().filter(a -> PIN.equals(a.get("pincode"))).findFirst().orElseThrow();
        assertThat((String) kukatpally.get("places")).contains("Kukatpally");
        assertThat(kukatpally).containsEntry("mine", true).containsEntry("onClozest", 2).containsEntry("contacted", 0);
        assertThat(areas).filteredOn(a -> !PIN.equals(a.get("pincode"))).allSatisfy(a -> assertThat(a).containsEntry("mine", false));
        assertThat(code(get("/admin/leads/areas?district=Nowhere&state=Telangana", AGENT))).isEqualTo("DISTRICT_NOT_FOUND");
    }

    // ---- Scanning ------------------------------------------------------------------------------------------------

    @Test
    void aScanFindsEveryBeautyBusinessInTheAreaAndMatchesClozestSalons() {
        Reply r = scan(AGENT);
        assertThat(r.status()).isEqualTo(200);
        Map<String, Map<String, Object>> shops = byId(list(r.body(), "shops"));
        // The quarters of the full barber box were searched too; other pincodes and non-beauty businesses left out
        assertThat(shops).containsKeys("mb-tia", "mb-rich", "mb-nopin", "mb-mehendi", "mb-barber-0", "mb-barber-24");
        assertThat(shops.keySet()).filteredOn(k -> k.startsWith("mb-q-")).hasSize(4);
        assertThat(shops).doesNotContainKeys("mb-other", "mb-artist");
        // Tia's: on Clozest by the owner's phone; agents see the salon's name and status, not its id
        assertThat(shops.get("mb-tia")).containsEntry("phone", "9133576278").containsEntry("kind", "LADIES_PARLOUR")
                .containsEntry("chain", false).containsEntry("onMapbox", true);
        assertThat(map(shops.get("mb-tia"), "clozest")).containsEntry("name", "Tias Parlour")
                .containsEntry("status", "APPROVED").containsEntry("shopId", null);
        // Rich Look: on Clozest by name and place
        assertThat(map(shops.get("mb-rich"), "clozest")).containsEntry("status", "PENDING_VISIT");
        assertThat(shops.get("mb-nopin")).containsEntry("chain", true).containsEntry("clozest", null)
                .containsEntry("pincode", PIN);
        assertThat(shops.get("mb-mehendi")).containsEntry("kind", "MEHENDI");
        // Both salons matched: nothing left over
        assertThat(list(r.body(), "clozestOnly")).isEmpty();
        // Every request was counted for the month; the token went only to Mapbox
        int requests = (Integer) ((Map<?, ?>) r.body()).get("requests");
        assertThat(requests).isEqualTo(CALLS.get());
        assertThat(jdbc.queryForObject("SELECT requests FROM mapbox_usage", Long.class)).isEqualTo((long) requests);
        assertThat(r.text()).doesNotContain("test-mapbox-token");
        assertThat(((Map<?, ?>) r.body()).get("partial")).isEqualTo(false);

        // A super admin also gets the salon's id (to open its page)
        Map<String, Map<String, Object>> asBoss = byId(list(scan(BOSS).body(), "shops"));
        assertThat(map(asBoss.get("mb-tia"), "clozest")).containsEntry("shopId", SALON.toString());
    }

    @Test
    void aSalonNoBusinessMatchedIsStillListedAsOnClozest() {
        jdbc.update("UPDATE shops SET name = 'Hidden Gem', latitude = 17.47, longitude = 78.38 WHERE id = ?", SALON2);
        List<Map<String, Object>> only = list(scan(BOSS).body(), "clozestOnly");
        assertThat(only).singleElement().satisfies(s -> assertThat(s).containsEntry("name", "Hidden Gem")
                .containsEntry("status", "PENDING_VISIT").containsEntry("shopId", SALON2.toString()));
    }

    @Test
    void aPincodeNobodyCanPlaceAndBadPincodesAreRefused() {
        assertThat(code(call(HttpMethod.POST, "/admin/leads/scan/111111", null, AGENT))).isEqualTo("AREA_NOT_FOUND");
        assertThat(code(call(HttpMethod.POST, "/admin/leads/scan/abc", null, AGENT))).isEqualTo("AREA_NOT_FOUND");
    }

    @Test
    void theMonthlyCapStopsScansAndNothingPassesItWhenManyScanAtOnce() throws Exception {
        String month = LocalDate.now(INDIA).format(DateTimeFormatter.ofPattern("yyyy-MM"));
        jdbc.update("INSERT INTO mapbox_usage (month, requests) VALUES (?, 100000)", month);
        Reply r = scan(AGENT);
        assertThat(r.status()).isEqualTo(429);
        assertThat(code(r)).isEqualTo("MAPBOX_CAP_REACHED");
        assertThat(CALLS.get()).isZero();

        // 40 requests left; four scans at once (each wants far more): the month never passes the cap
        jdbc.update("UPDATE mapbox_usage SET requests = 99960 WHERE month = ?", month);
        List<Reply> replies = together(4, () -> scan(AGENT));
        long used = jdbc.queryForObject("SELECT requests FROM mapbox_usage WHERE month = ?", Long.class, month);
        assertThat(used).isLessThanOrEqualTo(100000L);
        // Counted = sent: a request counted but not sent (its scan stopped meanwhile) is given back
        assertThat(used - 99960).isEqualTo(CALLS.get());
        assertThat(replies).allSatisfy(x -> assertThat(x.status()).isIn(200, 429, 503));
        // Not all four could finish: at least one was refused or cut short by the cap
        assertThat(replies).anySatisfy(x -> assertThat(x.status() == 429
                || Boolean.TRUE.equals(((Map<?, ?>) x.body()).get("capReached"))).isTrue());
    }

    // ---- Saving leads --------------------------------------------------------------------------------------------

    private Map<String, Object> body(String status, Integer version) {
        Map<String, Object> b = new HashMap<>();
        b.put("status", status);
        b.put("note", "Owner Lakshmi, call after 6 pm");
        b.put("pincode", PIN);
        b.put("name", "Sri Swathi Beauty Parlour");
        b.put("phone", "+91 98480 12345");
        b.put("latitude", 17.4850);
        b.put("longitude", 78.4110);
        b.put("version", version);
        return b;
    }

    @Test
    void aLeadIsSavedWithItsContactDetailsOnlyOnceContacted() {
        Reply first = put("mb-swathi", body("NEW", null), AGENT);
        assertThat(first.status()).isEqualTo(200);
        assertThat(map(first.body())).containsEntry("status", "NEW").containsEntry("version", 0);
        assertThat(jdbc.queryForMap("SELECT name, phone, latitude FROM leads WHERE mapbox_id = 'mb-swathi'"))
                .containsEntry("name", null).containsEntry("phone", null).containsEntry("latitude", null);

        Reply called = put("mb-swathi", body("CALLED", 0), AGENT);
        assertThat(map(called.body())).containsEntry("status", "CALLED").containsEntry("version", 1);
        assertThat(map(map(called.body()), "updatedBy")).containsEntry("name", "Ravi");
        assertThat(jdbc.queryForMap("SELECT name, phone FROM leads WHERE mapbox_id = 'mb-swathi'"))
                .containsEntry("name", "Sri Swathi Beauty Parlour").containsEntry("phone", "9848012345");

        // The same again changes nothing (no new version, no history)
        assertThat(map(put("mb-swathi", body("CALLED", 1), AGENT).body())).containsEntry("version", 1);
        List<Map<String, Object>> history = items(get("/admin/leads/mb-swathi/history", AGENT));
        assertThat(history).extracting(h -> h.get("field")).containsExactly("STATUS", "NOTE", "STATUS");
        assertThat(map(history.getFirst(), "by")).containsEntry("name", "Ravi");

        // Once tried, it can't go back to "not contacted" (its saved record would be lost)
        assertThat(field(put("mb-swathi", body("NEW", 1), AGENT))).isEqualTo("status");
        // No answer: kept, but not counted as contacted, and no WhatsApp consent from it
        assertThat(put("mb-swathi", body("NO_ANSWER", 1), AGENT).status()).isEqualTo(200);
        Map<String, Object> noAnswer = body("NO_ANSWER", 2);
        noAnswer.put("whatsappOk", true);
        assertThat(field(put("mb-swathi", noAnswer, AGENT))).isEqualTo("whatsappOk");
        List<Map<String, Object>> areas = list(get("/admin/leads/areas?district=Medchal Malkajgiri&state=Telangana",
                AGENT).body(), "areas");
        assertThat(areas).filteredOn(a -> PIN.equals(a.get("pincode"))).singleElement()
                .satisfies(a -> assertThat(a).containsEntry("contacted", 0));
    }

    @Test
    void anOverdueFollowUpCanStillBeUpdatedWithoutMovingItsDate() {
        LocalDate past = LocalDate.now(INDIA).minusDays(2);
        put("mb-late", body("CALLED", null), AGENT);
        jdbc.update("UPDATE leads SET follow_up_on = ? WHERE mapbox_id = 'mb-late'", java.sql.Date.valueOf(past));
        Map<String, Object> b = body("INTERESTED", 0);
        b.put("followUpOn", past.toString());
        assertThat(put("mb-late", b, AGENT).status()).isEqualTo(200);
        // A new past date is still refused
        b = body("INTERESTED", 1);
        b.put("followUpOn", past.minusDays(1).toString());
        assertThat(field(put("mb-late", b, AGENT))).isEqualTo("followUpOn");
    }

    @Test
    void aSalonAlreadyOnClozestIsNeverASignupFromALead() {
        // Tia's owner registered (setUp) before anyone called: the scan shows it on Clozest, but calling it credits nobody
        Map<String, Object> b = body("CALLED", null);
        b.put("name", "Tia's Beauty Parlour");
        b.put("phone", "9133576278");
        b.put("assignedTo", AGENT.toString());
        assertThat(put("mb-tia", b, AGENT).status()).isEqualTo(200);
        Map<String, Map<String, Object>> shops = byId(list(scan(AGENT).body(), "shops"));
        assertThat(map(shops.get("mb-tia"), "clozest")).containsEntry("name", "Tias Parlour");
        assertThat(map(shops.get("mb-tia"), "lead")).containsEntry("signedUpAt", null);
        assertThat(matchJob.runOnce()).isZero();
        assertThat(jdbc.queryForObject("SELECT shop_id FROM leads WHERE mapbox_id = 'mb-tia'", UUID.class)).isNull();
    }

    @Test
    void aSignedUpLeadKeepsItsStatusAndALeadOfSomeoneWhoLeftIsFree() {
        Map<String, Object> b = body("INTERESTED", null);
        b.put("assignedTo", AGENT2.toString());
        put("mb-s", b, AGENT2);
        // Sita leaves the field team: anyone can still work on her lead, and take it
        jdbc.update("UPDATE users SET admin_role = 'NONE', admin_designation = NULL WHERE id = ?", AGENT2);
        Map<String, Object> keep = body("VISITED", 0);
        keep.put("assignedTo", AGENT2.toString());
        assertThat(put("mb-s", keep, AGENT).status()).isEqualTo(200);
        Map<String, Object> take = body("VISITED", 1);
        take.put("assignedTo", AGENT.toString());
        assertThat(put("mb-s", take, AGENT).status()).isEqualTo(200);
        // It signs up: its status can't change any more; its note still can
        UUID owner = UUID.fromString("c0000000-0000-0000-0000-000000000009");
        TestData.user(jdbc, owner, "9848012345");
        TestData.salon(jdbc, LATER, owner);
        jdbc.update("UPDATE shops SET pincode = ? WHERE id = ?", PIN, LATER);
        assertThat(matchJob.runOnce()).isEqualTo(1);
        Map<String, Object> back = body("NOT_INTERESTED", 3);
        back.put("assignedTo", AGENT.toString());
        assertThat(code(put("mb-s", back, AGENT))).isEqualTo("LEAD_SIGNED_UP");
        Map<String, Object> note = body("VISITED", 3);
        note.put("assignedTo", AGENT.toString());
        note.put("note", "Signed up after the demo");
        assertThat(put("mb-s", note, AGENT).status()).isEqualTo(200);
    }

    @Test
    void myLeadsListTheLeadsIWorkOnWithoutSearchingTheMap() {
        Map<String, Object> mine = body("INTERESTED", null);
        mine.put("assignedTo", AGENT.toString());
        put("mb-mine", mine, AGENT);
        put("mb-free", body("CALLED", null), AGENT);
        assertThat(items(get("/admin/leads/mine", AGENT))).extracting(d -> d.get("mapboxId")).containsExactly("mb-mine");
        assertThat(items(get("/admin/leads/mine", AGENT)).getFirst()).containsEntry("phone", "9848012345")
                .containsEntry("version", 0);
        assertThat(code(get("/admin/leads/mine?everyone=true", AGENT))).isEqualTo("SUPER_ADMIN_ONLY");
        assertThat(items(get("/admin/leads/mine?everyone=true", BOSS))).hasSize(2);
        assertThat(CALLS.get()).isZero();
    }

    @Test
    void aStaleSaveIsAConflictEvenWhenSomeoneTriedTheLeadMeanwhile() {
        put("mb-race", body("CALLED", null), AGENT2);
        // Ravi's sheet opened before Sita saved: he only typed a note, the status still "not contacted"
        Reply r = put("mb-race", body("NEW", null), AGENT);
        assertThat(r.status()).isEqualTo(409);
        assertThat(code(r)).isEqualTo("LEAD_CHANGED");
    }

    @Test
    void aNewPhoneNeedsWhatsappConsentRecordedAgain() {
        Map<String, Object> b = body("CALLED", null);
        b.put("whatsappOk", true);
        put("mb-ph", b, AGENT);
        Map<String, Object> other = body("CALLED", 0);
        other.put("whatsappOk", true);
        other.put("phone", "98765 43210");
        // The old consent carried over in the sheet doesn't cover the new number: it ends, and the history says so
        // (consent given, then ended: two WhatsApp lines)
        assertThat(put("mb-ph", other, AGENT2).status()).isEqualTo(200);
        assertThat(jdbc.queryForMap("SELECT whatsapp_ok, whatsapp_ok_by FROM leads WHERE mapbox_id = 'mb-ph'"))
                .containsEntry("whatsapp_ok", false).containsEntry("whatsapp_ok_by", null);
        assertThat(jdbc.queryForList("SELECT field FROM lead_events WHERE mapbox_id = 'mb-ph' AND field IN "
                + "('PHONE', 'WHATSAPP')", String.class)).containsExactlyInAnyOrder("PHONE", "WHATSAPP", "WHATSAPP");
        // Asked again: ticked on the next save, recorded as Sita's
        other.put("version", 1);
        assertThat(put("mb-ph", other, AGENT2).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT whatsapp_ok_by FROM leads WHERE mapbox_id = 'mb-ph'", UUID.class))
                .isEqualTo(AGENT2);
        // The phone gone from the listing: logged too
        Map<String, Object> none = body("CALLED", 2);
        none.remove("phone");
        assertThat(put("mb-ph", none, AGENT2).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lead_events WHERE mapbox_id = 'mb-ph' AND field = 'PHONE'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void aScanRefusedBeforeSearchingDoesNotUseTheHourlyAllowance() {
        String month = LocalDate.now(INDIA).format(DateTimeFormatter.ofPattern("yyyy-MM"));
        jdbc.update("INSERT INTO mapbox_usage (month, requests) VALUES (?, 100000)", month);
        assertThat(code(scan(AGENT))).isEqualTo("MAPBOX_CAP_REACHED");
        String count = redis.opsForValue().get("rl:lead-scans:" + AGENT);
        assertThat(count == null ? 0 : Integer.parseInt(count)).isZero();
    }

    @Test
    void anOldVersionIsRefusedAndOnlyOneOfManySavesAtOnceWins() throws Exception {
        put("mb-swathi", body("CALLED", null), AGENT);
        Reply stale = put("mb-swathi", body("VISITED", 7), AGENT2);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(code(stale)).isEqualTo("LEAD_CHANGED");
        assertThat(map(map(map(stale.body()), "details"), "lead")).containsEntry("status", "CALLED");
        // A new lead saved twice at once: one creates it, the other is told it changed
        List<Reply> both = together(6, () -> put("mb-new", body("CALLED", null), AGENT));
        assertThat(both).filteredOn(x -> x.status() == 200).hasSize(1);
        assertThat(both).filteredOn(x -> x.status() == 409).hasSize(5);
        // Six saves of version 0 at once: one wins
        List<Reply> six = together(6, () -> put("mb-new", body("VISITED", 0), AGENT2));
        assertThat(six).filteredOn(x -> x.status() == 200).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT version FROM leads WHERE mapbox_id = 'mb-new'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void agentsTakeFreeLeadsButOnlyASuperAdminReassigns() {
        Map<String, Object> mine = body("CALLED", null);
        mine.put("assignedTo", AGENT.toString());
        assertThat(put("mb-a", mine, AGENT).status()).isEqualTo(200);
        // Another agent can't take it from Ravi, nor give it to someone else
        Map<String, Object> steal = body("CALLED", 0);
        steal.put("assignedTo", AGENT2.toString());
        assertThat(code(put("mb-a", steal, AGENT2))).isEqualTo("ASSIGNED_TO_SOMEONE_ELSE");
        // But can still update its status and note (keeping Ravi assigned)
        Map<String, Object> update = body("INTERESTED", 0);
        update.put("assignedTo", AGENT.toString());
        assertThat(put("mb-a", update, AGENT2).status()).isEqualTo(200);
        // The super admin moves it to Sita; Ravi can let go of his own
        Map<String, Object> move = body("INTERESTED", 1);
        move.put("assignedTo", AGENT2.toString());
        assertThat(put("mb-a", move, BOSS).status()).isEqualTo(200);
        Map<String, Object> letGo = body("INTERESTED", 2);
        letGo.put("assignedTo", null);
        assertThat(code(put("mb-a", letGo, AGENT))).isEqualTo("ASSIGNED_TO_SOMEONE_ELSE");
        assertThat(put("mb-a", letGo, AGENT2).status()).isEqualTo(200);
        // Someone not on the field team can't be assigned
        Map<String, Object> support = body("INTERESTED", 3);
        support.put("assignedTo", SUPPORT.toString());
        assertThat(code(put("mb-a", support, BOSS))).isEqualTo("VALIDATION_FAILED");
        // The history names people, not ids
        assertThat(items(get("/admin/leads/mb-a/history", BOSS)))
                .filteredOn(h -> "ASSIGNED".equals(h.get("field")))
                .extracting(h -> h.get("newValue")).contains("Sita", "Ravi");
    }

    @Test
    void badFieldsAreNamed() {
        Map<String, Object> b = body("MAYBE", null);
        assertThat(field(put("mb-x", b, AGENT))).isEqualTo("status");
        b = body("CALLED", null);
        b.put("name", " ");
        assertThat(field(put("mb-x", b, AGENT))).isEqualTo("name");
        b = body("CALLED", null);
        b.put("phone", "12345");
        assertThat(field(put("mb-x", b, AGENT))).isEqualTo("phone");
        b = body("CALLED", null);
        b.put("note", "x".repeat(501));
        assertThat(field(put("mb-x", b, AGENT))).isEqualTo("note");
        b = body("CALLED", null);
        b.put("followUpOn", LocalDate.now(INDIA).minusDays(1).toString());
        assertThat(field(put("mb-x", b, AGENT))).isEqualTo("followUpOn");
        b = body("CALLED", null);
        b.put("pincode", "111111");
        assertThat(field(put("mb-x", b, AGENT))).isEqualTo("pincode");
        // WhatsApp consent only from someone contacted, with a phone
        b = body("NEW", null);
        b.put("whatsappOk", true);
        assertThat(field(put("mb-x", b, AGENT))).isEqualTo("whatsappOk");
        b = body("CALLED", null);
        b.put("phone", null);
        b.put("whatsappOk", true);
        assertThat(field(put("mb-x", b, AGENT))).isEqualTo("whatsappOk");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM leads", Integer.class)).isZero();
    }

    @Test
    void whatsappConsentRecordsWhenAndWhoAndKeepsTheFirstTime() {
        Map<String, Object> b = body("INTERESTED", null);
        b.put("whatsappOk", true);
        Map<String, Object> saved = map(put("mb-w", b, AGENT).body());
        assertThat(saved).containsEntry("whatsappOk", true);
        assertThat(map(saved, "whatsappOkBy")).containsEntry("name", "Ravi");
        Object at = saved.get("whatsappOkAt");
        b.put("version", 0);
        b.put("note", "changed");
        Map<String, Object> again = map(put("mb-w", b, AGENT2).body());
        assertThat(again.get("whatsappOkAt")).isEqualTo(at);
        assertThat(map(again, "whatsappOkBy")).containsEntry("name", "Ravi");
    }

    // ---- Signups, follow-ups, the funnel -------------------------------------------------------------------------

    @Test
    void aContactedLeadIsTiedToTheSalonItBecomesAndTheAgentGetsTheCredit() {
        Map<String, Object> b = body("INTERESTED", null);
        b.put("assignedTo", AGENT.toString());
        put("mb-swathi", b, AGENT);
        assertThat(matchJob.runOnce()).isZero();

        // The salon registers later with the phone the agent saved
        UUID owner = UUID.fromString("c0000000-0000-0000-0000-000000000008");
        TestData.user(jdbc, owner, "9848012345");
        TestData.salon(jdbc, LATER, owner);
        jdbc.update("UPDATE shops SET pincode = ? WHERE id = ?", PIN, LATER);
        assertThat(matchJob.runOnce()).isEqualTo(1);
        assertThat(matchJob.runOnce()).isZero();
        Map<String, Object> lead = jdbc.queryForMap("SELECT shop_id, credited_to FROM leads WHERE mapbox_id = 'mb-swathi'");
        assertThat(lead).containsEntry("shop_id", LATER).containsEntry("credited_to", AGENT);
        assertThat(items(get("/admin/leads/mb-swathi/history", BOSS)).getFirst()).containsEntry("field", "SIGNED_UP");

        Reply funnel = get("/admin/leads/funnel?district=Medchal Malkajgiri&state=Telangana", BOSS);
        assertThat(list(funnel.body(), "areas")).singleElement().satisfies(a -> assertThat(a)
                .containsEntry("pincode", PIN).containsEntry("interested", 1).containsEntry("signedUp", 1));
        assertThat(list(funnel.body(), "people")).singleElement().satisfies(p -> {
            assertThat(map(p, "person")).containsEntry("name", "Ravi");
            assertThat(p).containsEntry("signedUp", 1);
        });
    }

    @Test
    void followUpsDueAreListedForTheirPersonAndEveryoneForASuperAdmin() {
        LocalDate today = LocalDate.now(INDIA);
        Map<String, Object> mine = body("CALLED", null);
        mine.put("assignedTo", AGENT.toString());
        mine.put("followUpOn", today.toString());
        put("mb-today", mine, AGENT);
        Map<String, Object> later = body("CALLED", null);
        later.put("assignedTo", AGENT.toString());
        later.put("followUpOn", today.plusDays(3).toString());
        put("mb-later", later, AGENT);
        Map<String, Object> hers = body("CALLED", null);
        hers.put("assignedTo", AGENT2.toString());
        hers.put("followUpOn", today.toString());
        put("mb-hers", hers, AGENT2);
        // A follow-up that is past by now (written as if saved days ago)
        jdbc.update("UPDATE leads SET follow_up_on = ? WHERE mapbox_id = 'mb-hers'", java.sql.Date.valueOf(today.minusDays(2)));

        assertThat(items(get("/admin/leads/due", AGENT))).extracting(d -> d.get("mapboxId")).containsExactly("mb-today");
        assertThat(items(get("/admin/leads/due", AGENT)).getFirst()).containsEntry("name", "Sri Swathi Beauty Parlour")
                .containsEntry("phone", "9848012345");
        assertThat(items(get("/admin/leads/due?everyone=true", BOSS))).extracting(d -> d.get("mapboxId"))
                .containsExactly("mb-hers", "mb-today");
    }

    @Test
    void aContactedLeadMapboxNoLongerListsStillShowsInItsArea() {
        put("mb-gone", body("VISITED", null), AGENT);
        Map<String, Map<String, Object>> shops = byId(list(scan(AGENT).body(), "shops"));
        assertThat(shops.get("mb-gone")).containsEntry("onMapbox", false)
                .containsEntry("name", "Sri Swathi Beauty Parlour").containsEntry("phone", "9848012345");
        assertThat(map(shops.get("mb-gone"), "lead")).containsEntry("status", "VISITED");
        // And the area's counts show it
        List<Map<String, Object>> areas = list(get("/admin/leads/areas?district=Medchal Malkajgiri&state=Telangana",
                AGENT).body(), "areas");
        assertThat(areas).filteredOn(a -> PIN.equals(a.get("pincode"))).singleElement()
                .satisfies(a -> assertThat(a).containsEntry("contacted", 1));
    }

    @Test
    void tooManyScansByOnePersonAreSlowedDown() {
        redis.opsForValue().set("rl:lead-scans:" + AGENT, "60", java.time.Duration.ofMinutes(30));
        Reply r = scan(AGENT);
        assertThat(r.status()).isEqualTo(429);
        assertThat(code(r)).isEqualTo("TOO_MANY_REQUESTS");
        assertThat(CALLS.get()).isZero();
        assertThat(scan(AGENT2).status()).isEqualTo(200);
    }


    // ---- Keeping a searched area up to date (no map requests) ----------------------------------------------------

    @Test
    void aSearchedAreaHearsOfANewSignupWithoutSearchingTheMapAgain() {
        jdbc.update("UPDATE shops SET updated_at = now() - interval '1 hour'");
        Reply scanned = scan(AGENT);
        String asOf = (String) map(scanned.body()).get("asOf");
        assertThat(asOf).isNotNull();
        CALLS.set(0);
        // Nothing changed since the scan
        Reply quiet = changes(AGENT, Map.of(PIN, asOf));
        assertThat(quiet.status()).as(quiet.text()).isEqualTo(200);
        assertThat(list(quiet.body(), "areas")).isEmpty();
        assertThat(map(quiet.body()).get("asOf")).isNotNull();

        // Lakme registers on Clozest where the map shows it, and another salon nobody on the map matches
        UUID owner = UUID.fromString("c0000000-0000-0000-0000-000000000008");
        TestData.user(jdbc, owner, "9848012345");
        TestData.salon(jdbc, LATER, owner);
        jdbc.update("UPDATE shops SET name = 'Lakme Salon', status = 'PENDING_VISIT', pincode = ?, latitude = 17.4930, "
                + "longitude = 78.3990 WHERE id = ?", PIN, LATER);
        List<Map<String, Object>> changed = list(changes(AGENT, Map.of(PIN, asOf)).body(), "areas");
        assertThat(changed).singleElement().satisfies(c -> assertThat(c).containsEntry("pincode", PIN)
                .containsKey("changedAt"));

        Map<String, Map<String, Object>> fromScan = byId(list(scanned.body(), "shops"));
        assertThat(fromScan.get("mb-tia")).containsKey("matchKey");
        assertThat(fromScan.get("mb-tia").get("matchKey")).isNotNull();
        assertThat(fromScan.get("mb-rich").get("matchKey")).isNull();
        Reply r = refresh(AGENT, List.of(seen("mb-nopin", "Lakme Salon KPHB", 17.4930, 78.3990, null),
                seenFrom(fromScan.get("mb-tia"))));
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        Map<String, Map<String, Object>> shops = byId(list(r.body(), "shops"));
        assertThat(map(shops.get("mb-nopin"), "clozest")).containsEntry("name", "Lakme Salon")
                .containsEntry("status", "PENDING_VISIT").containsEntry("shopId", null);
        assertThat(map(shops.get("mb-tia"), "clozest")).containsEntry("name", "Tias Parlour");
        // Rich Look wasn't sent this time: it shows as a Clozest salon no business matched
        assertThat(list(r.body(), "clozestOnly")).extracting(s -> s.get("name")).containsExactly("Rich Look");
        String refreshedAsOf = (String) map(r.body()).get("asOf");
        assertThat(refreshedAsOf).isNotNull();

        // Rich Look is approved later: its area is listed again
        jdbc.update("UPDATE shops SET updated_at = now() - interval '1 hour'");
        assertThat(list(changes(AGENT, Map.of(PIN, Instant.now().minusSeconds(5).toString())).body(), "areas"))
                .isEmpty();
        jdbc.update("UPDATE shops SET status = 'APPROVED', updated_at = now() WHERE id = ?", SALON2);
        assertThat(list(changes(AGENT, Map.of(PIN, Instant.now().minusSeconds(5).toString())).body(), "areas"))
                .extracting(c -> c.get("pincode")).containsExactly(PIN);
        // No map requests at all
        assertThat(CALLS.get()).isZero();
    }

    @Test
    void aLeadSomeoneElseWorksShowsUpAndNothingIsTiedFromThePagesDetails() {
        jdbc.update("UPDATE shops SET updated_at = now() - interval '1 hour'");
        String asOf = (String) map(scan(AGENT).body()).get("asOf");
        Map<String, Object> rich = body("CALLED", null);
        rich.put("name", "Rich Look Unisex");
        rich.put("phone", null);
        rich.put("latitude", 17.4900);
        rich.put("longitude", 78.4000);
        assertThat(put("mb-rich", rich, AGENT2).status()).isEqualTo(200);
        assertThat(put("mb-swathi", body("INTERESTED", null), AGENT2).status()).isEqualTo(200);
        assertThat(list(changes(AGENT, Map.of(PIN, asOf)).body(), "areas")).extracting(c -> c.get("pincode"))
                .containsExactly(PIN);

        // A salon registers later with Swathi's saved phone
        UUID owner = UUID.fromString("c0000000-0000-0000-0000-000000000008");
        TestData.user(jdbc, owner, "9848012345");
        TestData.salon(jdbc, LATER, owner);
        jdbc.update("UPDATE shops SET pincode = ? WHERE id = ?", PIN, LATER);

        Reply r = refresh(AGENT, List.of(seen("mb-rich", "Rich Look Unisex", 17.4900, 78.4000, null)));
        Map<String, Object> richNow = byId(list(r.body(), "shops")).get("mb-rich");
        assertThat(map(richNow, "lead")).containsEntry("status", "CALLED");
        assertThat(map(richNow, "clozest")).containsEntry("name", "Rich Look");
        // Swathi's lead isn't on the page yet: it comes as a saved lead, matched to the new salon by phone
        assertThat(list(r.body(), "saved")).singleElement().satisfies(s -> {
            assertThat(s).containsEntry("mapboxId", "mb-swathi").containsEntry("onMapbox", false);
            assertThat(map(s, "lead")).containsEntry("status", "INTERESTED").containsEntry("signedUpAt", null);
            assertThat(map(s, "clozest")).containsEntry("name", "Test Shop");
        });
        // Only a scan or the matching job ties a lead to a salon
        assertThat(jdbc.queryForObject("SELECT shop_id FROM leads WHERE mapbox_id = 'mb-swathi'", UUID.class)).isNull();
        jdbc.update("UPDATE leads SET updated_at = now() - interval '1 hour'");
        jdbc.update("UPDATE shops SET updated_at = now() - interval '1 hour'");
        String beforeJob = Instant.now().minusSeconds(5).toString();
        assertThat(list(changes(AGENT, Map.of(PIN, beforeJob)).body(), "areas")).isEmpty();
        assertThat(matchJob.runOnce()).isEqualTo(1);
        // The job's tie is a change the next check finds
        assertThat(list(changes(AGENT, Map.of(PIN, beforeJob)).body(), "areas")).extracting(c -> c.get("pincode"))
                .containsExactly(PIN);
        Reply after = refresh(AGENT, List.of(seen("mb-rich", "Rich Look Unisex", 17.4900, 78.4000, null)));
        assertThat(map(byId(list(after.body(), "saved")).get("mb-swathi"), "lead").get("signedUpAt")).isNotNull();
    }

    @Test
    void aSuperAdminGetsTheSalonsIdOnRefreshAndOthersCannotRefresh() {
        Map<String, Object> tia = byId(list(scan(BOSS).body(), "shops")).get("mb-tia");
        Reply r = refresh(BOSS, List.of(seenFrom(tia)));
        assertThat(map(byId(list(r.body(), "shops")).get("mb-tia"), "clozest")).containsEntry("shopId",
                SALON.toString());
        assertThat(changes(SUPPORT, Map.of(PIN, "2026-01-01T00:00:00Z")).status()).isEqualTo(404);
        assertThat(refresh(SUPPORT, List.of()).status()).isEqualTo(404);
        // Someone not on the admin team is stopped before the page (403), as for the other Leads calls
        assertThat(changes(CUSTOMER, Map.of(PIN, "2026-01-01T00:00:00Z")).status()).isIn(403, 404);
        assertThat(refresh(CUSTOMER, List.of()).status()).isIn(403, 404);
        assertThat(changes(null, Map.of(PIN, "2026-01-01T00:00:00Z")).status()).isEqualTo(401);
    }

    @Test
    void badChecksAndRefreshesAreRefused() {
        assertThat(field(call(HttpMethod.POST, "/admin/leads/changes", null, AGENT))).isEqualTo("areas");
        // A pincode no longer in the list is skipped, not a reason to refuse the others; a time ahead counts as now
        Map<String, String> withOld = new HashMap<>();
        withOld.put("111111", "2026-01-01T00:00:00Z");
        withOld.put(PIN, "2099-01-01T00:00:00Z");
        Reply old = changes(AGENT, withOld);
        assertThat(old.status()).as(old.text()).isEqualTo(200);
        assertThat(list(old.body(), "areas")).isEmpty();
        List<Object> withNull = new ArrayList<>();
        withNull.add(null);
        assertThat(field(call(HttpMethod.POST, "/admin/leads/changes", Map.of("areas", withNull), AGENT)))
                .isEqualTo("areas");
        assertThat(field(call(HttpMethod.POST, "/admin/leads/changes",
                Map.of("areas", List.of(Map.of("pincode", PIN))), AGENT))).isEqualTo("areas");
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 301; i++) {
            many.add(Map.of("pincode", PIN, "since", "2026-01-01T00:00:00Z"));
        }
        assertThat(field(call(HttpMethod.POST, "/admin/leads/changes", Map.of("areas", many), AGENT)))
                .isEqualTo("areas");

        assertThat(code(call(HttpMethod.POST, "/admin/leads/refresh/111111", Map.of("shops", List.of()), AGENT)))
                .isEqualTo("AREA_NOT_FOUND");
        assertThat(field(call(HttpMethod.POST, "/admin/leads/refresh/" + PIN, null, AGENT))).isEqualTo("shops");
        assertThat(field(refresh(AGENT, List.of(seen("mb-x", "X", 51.5, -0.1, null))))).isEqualTo("shops");
        assertThat(field(refresh(AGENT, List.of(seen(" ", "X", null, null, null))))).isEqualTo("shops");
        assertThat(field(refresh(AGENT, List.of(seen("mb-x", "X", 17.49, null, null))))).isEqualTo("shops");
        assertThat(field(refresh(AGENT, List.of(seen("mb-x", "X".repeat(301), null, null, null))))).isEqualTo("shops");
        assertThat(field(refresh(AGENT, List.of(seen("m".repeat(201), "X", null, null, null))))).isEqualTo("shops");
        List<Object> nullShop = new ArrayList<>();
        nullShop.add(null);
        assertThat(field(call(HttpMethod.POST, "/admin/leads/refresh/" + PIN, Map.of("shops", nullShop), AGENT)))
                .isEqualTo("shops");
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 3001; i++) {
            tooMany.add(seen("mb-" + i, "S", null, null, null));
        }
        assertThat(field(refresh(AGENT, tooMany))).isEqualTo("shops");
    }

    @Test
    void madeUpPhonesNeverMatchAndABusyAreaFitsInOneRefresh() {
        // The owner's phone sent without the scan's key (or with another business's key): no match, so nobody can
        // find out whose phone a salon's owner has
        Map<String, Object> tia = byId(list(scan(AGENT).body(), "shops")).get("mb-tia");
        Map<String, Object> forged = seen("mb-x", "Someone", null, null, "09133576278");
        assertThat(map(byId(list(refresh(AGENT, List.of(forged)).body(), "shops")).get("mb-x"), "clozest")).isNull();
        forged.put("matchKey", tia.get("matchKey"));
        assertThat(map(byId(list(refresh(AGENT, List.of(forged)).body(), "shops")).get("mb-x"), "clozest")).isNull();
        // The map's own phone with its key matches even without a point
        Map<String, Object> real = seenFrom(tia);
        real.put("latitude", null);
        real.put("longitude", null);
        assertThat(map(byId(list(refresh(AGENT, List.of(real)).body(), "shops")).get("mb-tia"), "clozest"))
                .containsEntry("name", "Tias Parlour");

        // 2,500 businesses (well over 64 KB) in one refresh
        List<Map<String, Object>> busy = new ArrayList<>();
        for (int i = 0; i < 2500; i++) {
            busy.add(seen("dXJuOm1ieHBvaTpiYzQ5ZjA3Ni1hNmUzLTQ2MTgtOTNlYS1lY2M0ZGQ2ZDk3NDAt" + i,
                    "Some Beauty Parlour and Spa " + i, 17.4901234567, 78.4001234567, "+91 98480 12345"));
        }
        Reply r = refresh(AGENT, busy);
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        assertThat(list(r.body(), "shops")).hasSize(2500);
    }

    @Test
    void checksAndRefreshesAreLimitedPerPersonEvenWhenTheyArriveTogether() throws Exception {
        List<Integer> statuses = together(12, () -> changes(AGENT, Map.of(PIN, "2026-01-01T00:00:00Z")).status());
        assertThat(statuses).filteredOn(s -> s == 200).hasSize(6);
        assertThat(statuses).filteredOn(s -> s == 429).hasSize(6);
        Reply slowed = changes(AGENT, Map.of(PIN, "2026-01-01T00:00:00Z"));
        assertThat(code(slowed)).isEqualTo("TOO_MANY_REQUESTS");
        // Someone else isn't slowed down
        assertThat(changes(AGENT2, Map.of(PIN, "2026-01-01T00:00:00Z")).status()).isEqualTo(200);

        redis.opsForValue().set("rl:lead-refreshes:" + AGENT, "60", java.time.Duration.ofSeconds(50));
        assertThat(code(refresh(AGENT, List.of()))).isEqualTo("TOO_MANY_REQUESTS");
        assertThat(refresh(AGENT2, List.of()).status()).isEqualTo(200);
        assertThat(CALLS.get()).isZero();
    }

    // ---- Helpers -------------------------------------------------------------------------------------------------

    private Reply changes(UUID who, Map<String, String> since) {
        List<Map<String, Object>> areas = new ArrayList<>();
        since.forEach((pin, at) -> areas.add(Map.of("pincode", pin, "since", at)));
        return call(HttpMethod.POST, "/admin/leads/changes", Map.of("areas", areas), who);
    }

    private Reply refresh(UUID who, List<Map<String, Object>> shops) {
        return call(HttpMethod.POST, "/admin/leads/refresh/" + PIN, Map.of("shops", shops), who);
    }

    /** A business as the page sends it back from its scan (with the phone's key). */
    private static Map<String, Object> seenFrom(Map<String, Object> shop) {
        Map<String, Object> m = seen((String) shop.get("mapboxId"), (String) shop.get("name"),
                ((Number) shop.get("latitude")).doubleValue(), ((Number) shop.get("longitude")).doubleValue(),
                (String) shop.get("phone"));
        m.put("matchKey", shop.get("matchKey"));
        return m;
    }

    private static Map<String, Object> seen(String id, String name, Double lat, Double lng, String phone) {
        Map<String, Object> m = new HashMap<>();
        m.put("mapboxId", id);
        m.put("name", name);
        m.put("latitude", lat);
        m.put("longitude", lng);
        m.put("phone", phone);
        return m;
    }

    private Reply scan(UUID who) {
        return call(HttpMethod.POST, "/admin/leads/scan/" + PIN, null, who);
    }

    private Reply put(String id, Map<String, Object> body, UUID who) {
        return call(HttpMethod.PUT, "/admin/leads/" + id, body, who);
    }

    private Reply get(String path, UUID who) {
        return call(HttpMethod.GET, path, null, who);
    }

    private Reply call(HttpMethod method, String path, Object body, UUID who) {
        RestClient.RequestBodySpec spec = http.method(method).uri(path).headers(h -> {
            if (who != null) {
                h.setBearerAuth(token(who));
            }
        });
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((rq, res) -> read(res));
    }

    private String token(UUID who) {
        Boolean isAdmin = jdbc.queryForObject("SELECT admin_role <> 'NONE' FROM users WHERE id = ?", Boolean.class,
                who);
        List<Map<String, Object>> member = jdbc.queryForList("SELECT shop_id, role FROM shop_members WHERE user_id = ?",
                who);
        UUID shop = member.isEmpty() ? null : (UUID) member.get(0).get("shop_id");
        String role = member.isEmpty() ? null : (String) member.get(0).get("role");
        return jwt.issue(new AuthPrincipal(who, shop, role, Boolean.TRUE.equals(isAdmin)),
                sessions.currentGeneration(who)).token();
    }

    private static <T> List<T> together(int n, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(task));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static Map<String, Map<String, Object>> byId(List<Map<String, Object>> shops) {
        Map<String, Map<String, Object>> out = new HashMap<>();
        shops.forEach(s -> out.put((String) s.get("mapboxId"), s));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object body, String name) {
        return (List<Map<String, Object>>) ((Map<?, ?>) body).get(name);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Reply r) {
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        return (List<Map<String, Object>>) r.body();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object body) {
        return (Map<String, Object>) body;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object body, String name) {
        return (Map<String, Object>) ((Map<?, ?>) body).get(name);
    }

    private static String code(Reply r) {
        return r.body() instanceof Map<?, ?> m ? (String) m.get("code") : null;
    }

    private static String field(Reply r) {
        assertThat(r.status()).as(r.text()).isEqualTo(400);
        Map<?, ?> details = (Map<?, ?>) ((Map<?, ?>) r.body()).get("details");
        return details == null || details.isEmpty() ? null : (String) details.keySet().iterator().next();
    }

    private static Reply read(org.springframework.http.client.ClientHttpResponse res) throws IOException {
        int status = res.getStatusCode().value();
        String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
        Object parsed = text.isBlank() ? Map.of() : JSON.readValue(text, Object.class);
        return new Reply(status, parsed, text);
    }

    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

    private record Reply(int status, Object body, String text) {
    }
}
