package in.apnidukan.shops;

import in.apnidukan.common.security.AuthPrincipal;
import in.apnidukan.common.security.JwtService;
import in.apnidukan.support.CapturingSenders;
import in.apnidukan.support.TestContainers;
import in.apnidukan.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static in.apnidukan.support.CapturingSenders.LAST_EMAIL;
import static in.apnidukan.support.CapturingSenders.LAST_SMS;
import static in.apnidukan.support.CapturingSenders.codeSentTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shop registration and My shop end to end, against real Postgres and Redis in Docker, signing in through the real
 * OTP flow. Also checks Flyway's V3 and Envers' history tables.
 */
@EnabledIf("in.apnidukan.support.TestContainers#dockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CapturingSenders.class)
class ShopIntegrationTest {

    private static final String OWNER_PHONE = "9848012345";
    private static final String HELPER_PHONE = "9848012346";

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        TestContainers.register(registry);
    }

    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired JwtService jwt;

    RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.builder()
                // JDK client: the Apache client pulled in by Testcontainers silently retries 429 replies
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
    }

    @Test
    void registeringCreatesADraftShopWithYouAsOwnerAndTheNextTokenCarriesIt() {
        Map<?, ?> session = signIn(OWNER_PHONE);
        String access = (String) session.get("accessToken");

        Reply created = post("/shops", validShop(), access);
        assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
        assertThat(created.body().get("status")).isEqualTo("DRAFT");
        assertThat(created.body().get("myRole")).isEqualTo("OWNER");
        assertThat(((Number) created.body().get("shopCode")).longValue()).isGreaterThanOrEqualTo(1001);
        assertThat(created.body().get("name")).isEqualTo("Sharma Kirana Store");
        String shopId = (String) created.body().get("id");

        // The current token doesn't carry the shop yet; My shop still works (looked up by person)...
        assertThat(get("/shops/me", access).body().get("id")).isEqualTo(shopId);
        assertThat(jwt.parse(access).shopId()).isNull();

        // ...and after a refresh, the token carries the shop and the role
        Reply refreshed = post("/auth/refresh", Map.of("refreshToken", session.get("refreshToken")), null);
        AuthPrincipal who = jwt.parse((String) refreshed.body().get("accessToken"));
        assertThat(who.shopId()).hasToString(shopId);
        assertThat(who.shopRole()).isEqualTo("OWNER");

        // Signing in again on another phone also gets the shop
        AuthPrincipal again = jwt.parse((String) signIn(OWNER_PHONE).get("accessToken"));
        assertThat(again.shopId()).hasToString(shopId);
    }

    @Test
    void textIsTidiedAndGstinIsStoredInCapitals() {
        String access = token(OWNER_PHONE);
        Map<String, Object> shop = validShop();
        shop.put("name", "  Sharma    Kirana   ");
        shop.put("gstin", "27aapfu 0939f1zv");
        shop.put("udyamNumber", "udyam-ts-02-0012345");
        shop.put("latitude", new BigDecimal("17.46512349"));

        Reply r = post("/shops", shop, access);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        assertThat(r.body().get("name")).isEqualTo("Sharma Kirana");
        assertThat(r.body().get("gstin")).isEqualTo("27AAPFU0939F1ZV");
        assertThat(r.body().get("udyamNumber")).isEqualTo("UDYAM-TS-02-0012345");
        assertThat(new BigDecimal(r.body().get("latitude").toString())).isEqualByComparingTo("17.465123");
    }

    @Test
    void namesWithHiddenOrTrickCharactersAreRefusedAndSpacesOfAnyKindAreTidied() {
        String access = token(OWNER_PHONE);
        assertInvalid(access, "name", "\u00A0\u00A0\u00A0");                // no-break spaces only
        assertInvalid(access, "name", "Sharma\u200B Kirana");                // zero-width space
        assertInvalid(access, "ownerName", "Ramesh " + (char) 0x202E + "kcaj"); // right-to-left override
        assertInvalid(access, "city", "Hyder\u2028abad");                    // line separator

        Map<String, Object> shop = validShop();
        shop.put("name", "Sharma\u2003\u2003Kirana\u00A0Store");           // wide and no-break spaces
        shop.put("ownerName", "रमेश शर्मा");                                 // Hindi works
        Reply r = post("/shops", shop, access);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        assertThat(r.body().get("name")).isEqualTo("Sharma Kirana Store");
        assertThat(r.body().get("ownerName")).isEqualTo("रमेश शर्मा");
    }

    @Test
    void theShopAndRoleInATokenAreNeverTrustedForMyShop() {
        // Owner of shop A
        String ownerA = token(OWNER_PHONE);
        String shopA = (String) post("/shops", validShop(), ownerA).body().get("id");
        // Helper of shop A whose token claims OWNER of shop A (e.g. a stale or tampered claim)
        Map<?, ?> helper = signIn(HELPER_PHONE);
        UUID helperId = UUID.fromString((String) ((Map<?, ?>) helper.get("user")).get("id"));
        TestData.member(jdbc, UUID.fromString(shopA), helperId, "HELPER");
        String claimsOwner = jwt.issue(new AuthPrincipal(helperId, UUID.fromString(shopA), "OWNER", false), 0).token();

        Reply r = patch("/shops/me", Map.of("version", 0, "name", "Taken over"), claimsOwner);
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.body().get("code")).isEqualTo("OWNER_ONLY");
        assertThat(get("/shops/me", claimsOwner).body().get("myRole")).isEqualTo("HELPER");
    }

    @Test
    void twoEditsAtTheSameMomentNeverOverwriteEachOther() throws Exception {
        String access = token(OWNER_PHONE);
        post("/shops", validShop(), access);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Reply>> replies = new ArrayList<>();
            for (String name : List.of("First Phone Store", "Second Phone Store")) {
                replies.add(pool.submit(() -> {
                    start.await();
                    return patch("/shops/me", Map.of("version", 0, "name", name), access);
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Reply> f : replies) {
                statuses.add(f.get().status());
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT version FROM shops", Long.class)).isEqualTo(1);
    }

    @Test
    void onePersonCanHaveOnlyOneShop() {
        String access = token(OWNER_PHONE);
        assertThat(post("/shops", validShop(), access).status()).isEqualTo(201);
        Reply second = post("/shops", validShop(), access);
        assertThat(second.status()).isEqualTo(409);
        assertThat(second.body().get("code")).isEqualTo("ALREADY_HAS_SHOP");
    }

    @Test
    void registeringTwiceAtTheSameMomentCreatesOneShop() throws Exception {
        String access = token(OWNER_PHONE);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Reply>> replies = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                replies.add(pool.submit(() -> {
                    start.await();
                    return post("/shops", validShop(), access);
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Reply> f : replies) {
                statuses.add(f.get().status());
            }
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shops", Integer.class)).isEqualTo(1);
    }

    @Test
    void everyShopGetsItsOwnNumber() {
        long first = ((Number) post("/shops", validShop(), token(OWNER_PHONE)).body().get("shopCode")).longValue();
        long second = ((Number) post("/shops", validShop(), token(HELPER_PHONE)).body().get("shopCode")).longValue();
        assertThat(second).isGreaterThan(first);
    }

    @Test
    void theShopCodeComesFromTheSequenceEvenForARowInsertedByHand() {
        UUID ownerId = UUID.randomUUID();
        TestData.user(jdbc, ownerId, "9000000301");
        UUID shopId = UUID.randomUUID();
        TestData.shop(jdbc, shopId, ownerId);   // plain SQL, no shop_code given
        Long code = jdbc.queryForObject("SELECT shop_code FROM shops WHERE id = ?", Long.class, shopId);
        assertThat(code).isGreaterThanOrEqualTo(1001);
        // ...and the database records that the sequence belongs to the column
        assertThat(jdbc.queryForObject("SELECT pg_get_serial_sequence('shops', 'shop_code')", String.class))
                .endsWith("shop_code_seq");
    }

    @Test
    void wrongDetailsAreRefusedWithTheFieldNamed() {
        String access = token(OWNER_PHONE);
        assertInvalid(access, "pincode", "05008");
        assertInvalid(access, "pincode", "500 84");
        assertInvalid(access, "latitude", new BigDecimal("51.5072"));    // London
        assertInvalid(access, "longitude", new BigDecimal("-0.1276"));
        assertInvalid(access, "gstin", "27AAPFU0939F1Z");                 // 14 characters
        assertInvalid(access, "gstin", "27AAPFU0939F1ZW");                // typo: check character doesn't match
        assertInvalid(access, "gstin", "00AAPFU0939F1ZB");                // no state 00 (check character is right)
        assertInvalid(access, "udyamNumber", "UDYAM-12345");
        assertInvalid(access, "name", "   ");
        assertInvalid(access, "name", " a ");
        assertInvalid(access, "city", null);

        Map<String, Object> noPin = validShop();
        noPin.remove("latitude");
        noPin.remove("longitude");
        assertThat(post("/shops", noPin, access).status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shops", Integer.class)).isZero();
    }

    @Test
    void unknownFieldsAreRefused() {
        Map<String, Object> shop = validShop();
        shop.put("status", "APPROVED");   // nobody approves their own shop by sending a field
        Reply r = post("/shops", shop, token(OWNER_PHONE));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("code")).isEqualTo("UNKNOWN_FIELD");
        assertThat(((Map<?, ?>) r.body().get("details")).get("field")).isEqualTo("status");
    }

    @Test
    void withoutAShopMyShopSaysSo() {
        String access = token(OWNER_PHONE);
        Reply r = get("/shops/me", access);
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.body().get("code")).isEqualTo("NO_SHOP");
        assertThat(patch("/shops/me", Map.of("version", 0, "name", "New name"), access).status()).isEqualTo(404);
    }

    @Test
    void shopsNeedSignIn() {
        assertThat(post("/shops", validShop(), null).status()).isEqualTo(401);
        assertThat(get("/shops/me", null).status()).isEqualTo(401);
    }

    @Test
    void ownerEditsMyShopAndEveryVersionIsKeptWithWhoChangedIt() {
        Map<?, ?> session = signIn(OWNER_PHONE);
        String access = (String) session.get("accessToken");
        String userId = (String) ((Map<?, ?>) session.get("user")).get("id");
        Map<?, ?> shop = post("/shops", validShop(), access).body();
        String shopId = (String) shop.get("id");

        Reply edited = patch("/shops/me", Map.of("version", shop.get("version"), "name", "Sharma General Store",
                "gstin", "27AAPFU0939F1ZV"), access);
        assertThat(edited.status()).as(edited.body().toString()).isEqualTo(200);
        assertThat(edited.body().get("name")).isEqualTo("Sharma General Store");
        assertThat(edited.body().get("city")).isEqualTo("Hyderabad");          // untouched fields stay
        assertThat(((Number) edited.body().get("version")).longValue()).isEqualTo(1);

        // A second phone still holding version 0 can't overwrite that change
        Reply stale = patch("/shops/me", Map.of("version", 0, "name", "Old phone's name"), access);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.body().get("code")).isEqualTo("SHOP_CHANGED");

        // "" removes the GSTIN
        Reply removed = patch("/shops/me", Map.of("version", 1, "gstin", ""), access);
        assertThat(removed.body().get("gstin")).isNull();

        // History: created, renamed, GSTIN removed; each stamped with who did it
        List<Map<String, Object>> versions = jdbc.queryForList(
                "SELECT a.name, a.gstin, a.revtype, r.user_id FROM shops_aud a JOIN revinfo r ON r.rev = a.rev "
                        + "WHERE a.id = ?::uuid ORDER BY a.rev", shopId);
        assertThat(versions).hasSize(3);
        assertThat(versions.get(0).get("name")).isEqualTo("Sharma Kirana Store");
        assertThat(((Number) versions.get(0).get("revtype")).intValue()).isZero();
        assertThat(versions.get(1).get("name")).isEqualTo("Sharma General Store");
        assertThat(versions.get(1).get("gstin")).isEqualTo("27AAPFU0939F1ZV");
        assertThat(versions.get(2).get("gstin")).isNull();
        assertThat(versions).allSatisfy(v -> assertThat(v.get("user_id")).hasToString(userId));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shop_members_aud", Integer.class)).isEqualTo(1);
    }

    @Test
    void savingWithoutChangesDoesNotAddAVersion() {
        String access = token(OWNER_PHONE);
        Map<?, ?> shop = post("/shops", validShop(), access).body();
        Reply same = patch("/shops/me", Map.of("version", 0, "name", "Sharma Kirana Store"), access);
        assertThat(((Number) same.body().get("version")).longValue()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shops_aud WHERE id = ?::uuid", Integer.class,
                shop.get("id"))).isEqualTo(1);
    }

    @Test
    void aHelperSeesTheShopButCannotChangeIt() {
        String owner = token(OWNER_PHONE);
        String shopId = (String) post("/shops", validShop(), owner).body().get("id");
        Map<?, ?> helperSession = signIn(HELPER_PHONE);
        String helperId = (String) ((Map<?, ?>) helperSession.get("user")).get("id");
        TestData.member(jdbc, UUID.fromString(shopId), UUID.fromString(helperId), "HELPER");

        String helper = token(HELPER_PHONE);
        assertThat(jwt.parse(helper).shopRole()).isEqualTo("HELPER");
        Reply mine = get("/shops/me", helper);
        assertThat(mine.body().get("myRole")).isEqualTo("HELPER");

        Reply r = patch("/shops/me", Map.of("version", 0, "name", "Helper's name"), helper);
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.body().get("code")).isEqualTo("OWNER_ONLY");
        // ...and a helper can't register a second shop either
        assertThat(post("/shops", validShop(), helper).body().get("code")).isEqualTo("ALREADY_HAS_SHOP");
    }

    // ---------- helpers ----------

    @Test
    void registeringAShopNeedsAVerifiedEmailAndANameFirst() {
        post("/auth/otp/send", Map.of("phone", "9000000390"), null);
        Reply signedIn = post("/auth/otp/verify", Map.of("phone", "9000000390", "code",
                codeSentTo(LAST_SMS, "9000000390")), null);
        String access = (String) signedIn.body().get("accessToken");
        Reply r = post("/shops", validShop(), access);
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.body().get("code")).isEqualTo("PROFILE_INCOMPLETE");
        in.apnidukan.support.TestData.completeProfile(jdbc, "9000000390");
        assertThat(post("/shops", validShop(), access).status()).isEqualTo(201);
    }

    private static Map<String, Object> validShop() {
        Map<String, Object> shop = new HashMap<>();
        shop.put("name", "Sharma Kirana Store");
        shop.put("ownerName", "Ramesh Sharma");
        shop.put("addressLine", "Shop 4, Lake View Apartments");
        shop.put("area", "Kondapur");
        shop.put("city", "Hyderabad");
        shop.put("pincode", "500084");
        shop.put("latitude", new BigDecimal("17.465123"));
        shop.put("longitude", new BigDecimal("78.364456"));
        return shop;
    }

    private void assertInvalid(String access, String field, Object value) {
        Map<String, Object> shop = validShop();
        shop.put(field, value);
        Reply r = post("/shops", shop, access);
        assertThat(r.status()).as(field + "=" + value + " \u2192 " + r.body()).isEqualTo(400);
        assertThat(r.body().get("code")).isEqualTo("VALIDATION_FAILED");
        Map<?, ?> details = (Map<?, ?>) r.body().get("details");
        assertThat(details.containsKey(field)).as(r.body().toString()).isTrue();
    }

    private Map<?, ?> signIn(String phone) {
        post("/auth/otp/send", Map.of("phone", phone), null);
        Reply r = post("/auth/otp/verify", Map.of("phone", phone, "code", codeSentTo(LAST_SMS, phone)), null);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        in.apnidukan.support.TestData.completeProfile(jdbc, phone);   // the email and name screens
        return r.body();
    }

    private String token(String phone) {
        return (String) signIn(phone).get("accessToken");
    }

    private Reply post(String path, Object body, String token) {
        return http.post().uri(path).headers(h -> bearer(h, token)).contentType(MediaType.APPLICATION_JSON)
                .body(body).exchange((rq, res) -> reply(res));
    }

    private Reply patch(String path, Object body, String token) {
        return http.patch().uri(path).headers(h -> bearer(h, token)).contentType(MediaType.APPLICATION_JSON)
                .body(body).exchange((rq, res) -> reply(res));
    }

    private Reply get(String path, String token) {
        return http.get().uri(path).headers(h -> bearer(h, token)).exchange((rq, res) -> reply(res));
    }

    private static void bearer(org.springframework.http.HttpHeaders headers, String token) {
        if (token != null) {
            headers.setBearerAuth(token);
        }
    }

    private static Reply reply(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse res)
            throws java.io.IOException {
        int status = res.getStatusCode().value();
        Map<?, ?> body = status == 202 || status == 204 ? Map.of() : res.bodyTo(Map.class);
        return new Reply(status, body == null ? Map.of() : body);
    }

    private record Reply(int status, Map<?, ?> body) {
    }
}
