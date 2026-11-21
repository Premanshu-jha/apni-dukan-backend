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
        TestContainers.register(registry, true); // has kirana cases: kirana on
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
    void textIsTidiedGstinIsStoredInCapitalsAndAUdyamNumberIsIgnored() {
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
        // Udyam is no longer asked for (2026-11-17): one sent by an older app is ignored, not stored
        assertThat(r.body().get("udyamNumber")).isNull();
        assertThat(jdbc.queryForObject("SELECT udyam_number FROM shops", String.class)).isNull();
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

    @Test
    void addressSearchIsOffWithoutAGoogleKeySoTheAppHidesIt() {
        Reply r = post("/shops/geocode", Map.of("addressLine", "Shop 4, Lake View Apartments", "area", "Kondapur",
                "city", "Hyderabad", "pincode", "500084"), token(OWNER_PHONE));
        assertThat(r.status()).isEqualTo(503);
        assertThat(r.body().get("code")).isEqualTo("GEOCODING_OFF");
    }

    @Test
    void theOwnerChangesKiranaToSalonAndBackWhileRegisteringKeepingTheDetailsAndPhotos() {
        String access = token(OWNER_PHONE);
        Map<String, Object> kirana = validShop();
        kirana.put("gstin", "27AAPFU0939F1ZV");
        Reply made = post("/shops", kirana, access);
        assertThat(made.body().get("type")).isEqualTo("KIRANA");

        Reply salon = put("/shops/me/type", Map.of("version", 0, "type", "SALON"), access);
        assertThat(salon.status()).as(salon.body().toString()).isEqualTo(200);
        assertThat(salon.body().get("type")).isEqualTo("SALON");
        assertThat(salon.body().get("name")).isEqualTo("Sharma Kirana Store");
        assertThat(salon.body().get("status")).isEqualTo("DRAFT");
        assertThat(salon.body().get("gstin")).as("a salon shows no GSTIN").isNull();
        assertThat(jdbc.queryForObject("SELECT gstin FROM shops", String.class)).as("kept for changing back")
                .isEqualTo("27AAPFU0939F1ZV");
        assertThat(((Number) salon.body().get("version")).longValue()).isEqualTo(1);
        // The field team's timeline says so; nothing about the shop's status moved
        assertThat(jdbc.queryForObject("SELECT note FROM shop_events WHERE event = 'NOTE'", String.class))
                .isEqualTo("Owner changed it from kirana to salon while registering");

        // The same kind again changes nothing (no new version)
        Reply same = put("/shops/me/type", Map.of("version", 1, "type", "SALON"), access);
        assertThat(((Number) same.body().get("version")).longValue()).isEqualTo(1);
        // A stale version is refused
        Reply stale = put("/shops/me/type", Map.of("version", 0, "type", "KIRANA"), access);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.body().get("code")).isEqualTo("SHOP_CHANGED");
        // Salon access follows the type at once (read from the database, not the token)
        assertThat(statusOf("/salon/services", access)).isEqualTo(200);

        Reply back = put("/shops/me/type", Map.of("version", 1, "type", "KIRANA"), access);
        assertThat(back.body().get("type")).isEqualTo("KIRANA");
        assertThat(back.body().get("gstin")).as("the kirana's GSTIN is back").isEqualTo("27AAPFU0939F1ZV");
        assertThat(get("/salon/services", access).body().get("code")).isEqualTo("NOT_A_SALON");
    }

    @Test
    void theVideoAndATradeLicenceStayAndAreCheckedAgainWhileAShopsAndEstablishmentsCertificateLeavesASalon() {
        Map<?, ?> session = signIn(OWNER_PHONE);
        String access = (String) session.get("accessToken");
        UUID owner = UUID.fromString((String) ((Map<?, ?>) session.get("user")).get("id"));
        Map<String, Object> salon = validShop();
        salon.put("type", "SALON");
        UUID shopId = UUID.fromString((String) post("/shops", salon, access).body().get("id"));
        UUID video = file(shopId, owner, "SHOP_VIDEO", "video/mp4");
        UUID licence = file(shopId, owner, "REG_DOC", "image/jpeg");
        jdbc.update("UPDATE shops SET shop_video_file_id = ?, registration_doc_type = 'TRADE_LICENCE', "
                + "registration_doc_file_id = ?, licence_check_status = 'FAILED', licence_check_reason = "
                + "'NOT_A_SALON_TRADE' WHERE id = ?", video, licence, shopId);

        // A kirana now: the video stays, and the trade licence is checked again for a kirana's trade
        Reply r = put("/shops/me/type", Map.of("version", 0, "type", "KIRANA"), access);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(r.body().get("shopVideoFileId")).isEqualTo(video.toString());
        assertThat(r.body().get("registrationDocFileId")).isEqualTo(licence.toString());
        assertThat(r.body().get("licenceCheck")).isEqualTo("PENDING");
        assertThat(r.body().get("licenceCheckReason")).isNull();
        assertThat(jdbc.queryForObject("SELECT licence_check_due_at IS NOT NULL FROM shops", Boolean.class)).isTrue();

        // A kirana's Shops and Establishments certificate, then back to a salon: it goes (a salon's can't be one)
        UUID certificate = file(shopId, owner, "REG_DOC", "image/jpeg");
        Reply se = put("/shops/me/registration-document", Map.of("version", 1, "type", "SHOPS_ESTABLISHMENT",
                "fileId", certificate.toString()), access);
        assertThat(se.status()).as(se.body().toString()).isEqualTo(200);
        Reply back = put("/shops/me/type", Map.of("version", 2, "type", "SALON"), access);
        assertThat(back.status()).as(back.body().toString()).isEqualTo(200);
        assertThat(back.body().get("registrationDocFileId")).isNull();
        assertThat(back.body().get("licenceCheck")).isNull();
        assertThat(back.body().get("shopVideoFileId")).isEqualTo(video.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM files WHERE id = ?", Long.class, certificate)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM files WHERE id = ?", Long.class, video)).isOne();
    }

    @Test
    void oneUploadUsedAsBothTheCertificateAndTheFssaiLicenceIsKeptWhileEitherStillPointsAtIt() {
        Map<?, ?> session = signIn(OWNER_PHONE);
        String access = (String) session.get("accessToken");
        UUID owner = UUID.fromString((String) ((Map<?, ?>) session.get("user")).get("id"));
        Map<String, Object> kirana = validShop();
        kirana.put("type", "KIRANA");
        UUID shopId = UUID.fromString((String) post("/shops", kirana, access).body().get("id"));
        // One PDF holding both papers (the same bytes share one file)
        UUID both = file(shopId, owner, "REG_DOC", "application/pdf");
        assertThat(put("/shops/me/registration-document", Map.of("version", 0, "type", "SHOPS_ESTABLISHMENT",
                "fileId", both.toString()), access).status()).isEqualTo(200);
        Reply fssai = put("/kirana/online/fssai", Map.of("number", "10012345678901", "fileId", both.toString()),
                access);
        assertThat(fssai.status()).as(fssai.body().toString()).isEqualTo(200);

        // A salon now: the certificate comes off the shop, but the FSSAI licence still points at the file
        Reply salon = put("/shops/me/type", Map.of("version", 1, "type", "SALON"), access);
        assertThat(salon.status()).as(salon.body().toString()).isEqualTo(200);
        assertThat(salon.body().get("registrationDocFileId")).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM files WHERE id = ?", Long.class, both)).isOne();

        // And the other way: back to a kirana with it as the trade licence, then a new FSSAI certificate
        assertThat(put("/shops/me/type", Map.of("version", 2, "type", "KIRANA"), access).status()).isEqualTo(200);
        assertThat(put("/shops/me/registration-document", Map.of("version", 3, "type", "TRADE_LICENCE",
                "fileId", both.toString()), access).status()).isEqualTo(200);
        UUID fresh = file(shopId, owner, "REG_DOC", "image/jpeg");
        Reply replaced = put("/kirana/online/fssai", Map.of("number", "10012345678902", "fileId", fresh.toString()),
                access);
        assertThat(replaced.status()).as(replaced.body().toString()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM files WHERE id = ?", Long.class, both)).isOne();
        assertThat(jdbc.queryForObject("SELECT registration_doc_file_id FROM shops WHERE id = ?", UUID.class, shopId))
                .isEqualTo(both);
    }

    @Test
    void aKiranaThatAlreadyWorksAtItsCounterStaysAKirana() {
        Map<?, ?> session = signIn(OWNER_PHONE);
        String access = (String) session.get("accessToken");
        Map<String, Object> kirana = validShop();
        kirana.put("type", "KIRANA");
        UUID shopId = UUID.fromString((String) post("/shops", kirana, access).body().get("id"));

        // A helper invited, not yet accepted: the same
        Reply invited = post("/shops/me/helpers", Map.of("phone", "9848099999"), access);
        assertThat(invited.status()).as(invited.body().toString()).isEqualTo(202);
        Reply withInvite = put("/shops/me/type", Map.of("version", 0, "type", "SALON"), access);
        assertThat(withInvite.body().get("code")).isEqualTo("HAS_COUNTER_DATA");
        jdbc.update("DELETE FROM shop_invites WHERE shop_id = ?", shopId);

        // A helper with his own sign-in: a salon would hide him
        Map<?, ?> helper = signIn(HELPER_PHONE);
        UUID helperId = UUID.fromString((String) ((Map<?, ?>) helper.get("user")).get("id"));
        TestData.member(jdbc, shopId, helperId, "HELPER");
        Reply withHelper = put("/shops/me/type", Map.of("version", 0, "type", "SALON"), access);
        assertThat(withHelper.status()).isEqualTo(409);
        assertThat(withHelper.body().get("code")).isEqualTo("HAS_COUNTER_DATA");
        jdbc.update("DELETE FROM shop_members WHERE user_id = ?", helperId);

        // A product in its list (it billed while waiting for the visit, then was sent back)
        Reply product = post("/kirana/products", Map.of("name", "Tata Salt 1 kg", "unit", "PIECE", "pricePaise", 2800),
                access);
        assertThat(product.status()).as(product.body().toString()).isEqualTo(201);
        Reply withProduct = put("/shops/me/type", Map.of("version", 0, "type", "SALON"), access);
        assertThat(withProduct.body().get("code")).isEqualTo("HAS_COUNTER_DATA");
        assertThat(jdbc.queryForObject("SELECT type FROM shops WHERE id = ?", String.class, shopId)).isEqualTo("KIRANA");
    }

    @Test
    void theTypeIsLockedOnceSubmittedOrEverApprovedAndAKiranaHasNoBarbers() {
        Map<?, ?> session = signIn(OWNER_PHONE);
        String access = (String) session.get("accessToken");
        Map<String, Object> salon = validShop();
        salon.put("type", "SALON");
        UUID shopId = UUID.fromString((String) post("/shops", salon, access).body().get("id"));

        // A barber with his own sign-in: a kirana can't have him
        Map<?, ?> barber = signIn(HELPER_PHONE);
        UUID barberId = UUID.fromString((String) ((Map<?, ?>) barber.get("user")).get("id"));
        TestData.member(jdbc, shopId, barberId, "BARBER");
        Reply refused = put("/shops/me/type", Map.of("version", 0, "type", "KIRANA"), access);
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.body().get("code")).isEqualTo("HAS_BARBERS");
        // Only the owner
        Reply helper = put("/shops/me/type", Map.of("version", 0, "type", "KIRANA"), (String) barber.get("accessToken"));
        assertThat(helper.status()).isEqualTo(403);
        jdbc.update("DELETE FROM shop_members WHERE user_id = ?", barberId);
        // A barber invited, not yet joined: the same
        UUID ownerId = UUID.fromString((String) ((Map<?, ?>) session.get("user")).get("id"));
        UUID invite = UUID.randomUUID();
        jdbc.update("INSERT INTO shop_invites (id, shop_id, role, staff_id, to_phone, invited_by, status, created_at, "
                + "expires_at) VALUES (?, ?, 'BARBER', ?, '9848099999', ?, 'PENDING', now(), now() + interval '7 days')",
                invite, shopId, UUID.randomUUID(), ownerId);
        Reply invited = put("/shops/me/type", Map.of("version", 0, "type", "KIRANA"), access);
        assertThat(invited.body().get("code")).isEqualTo("HAS_BARBERS");
        jdbc.update("UPDATE shop_invites SET status = 'CANCELLED' WHERE id = ?", invite);

        // Submitted: locked
        jdbc.update("UPDATE shops SET status = 'PENDING_VISIT' WHERE id = ?", shopId);
        Reply submitted = put("/shops/me/type", Map.of("version", 0, "type", "KIRANA"), access);
        assertThat(submitted.status()).isEqualTo(409);
        assertThat(submitted.body().get("code")).isEqualTo("WRONG_STATUS");
        // Sent back by the field team (a draft again, never approved, with their reason): still registering, so it
        // may change; their reason stays for the owner to fix, and the next Submit clears it
        jdbc.update("UPDATE shops SET status = 'DRAFT', status_reason = 'Shop board not visible' WHERE id = ?", shopId);
        Reply sentBack = put("/shops/me/type", Map.of("version", 0, "type", "KIRANA"), access);
        assertThat(sentBack.status()).as(sentBack.body().toString()).isEqualTo(200);
        assertThat(sentBack.body().get("statusReason")).isEqualTo("Shop board not visible");
        // A draft that was approved once (suspended, reinstated, sent back): its kind was verified, locked for good
        jdbc.update("UPDATE shops SET ever_approved = true WHERE id = ?", shopId);
        Reply verified = put("/shops/me/type", Map.of("version", 1, "type", "SALON"), access);
        assertThat(verified.status()).isEqualTo(409);
        assertThat(verified.body().get("code")).isEqualTo("WRONG_STATUS");
        // A missing type names the field
        Reply none = put("/shops/me/type", Map.of("version", 1), access);
        assertThat(none.status()).isEqualTo(400);
        assertThat(((Map<?, ?>) none.body().get("details")).containsKey("type")).isTrue();
    }

    @Test
    void theKindChangesAtMostTenTimesADay() {
        String access = token(OWNER_PHONE);
        post("/shops", validShop(), access);
        for (int v = 0; v < 10; v++) {
            Reply r = put("/shops/me/type", Map.of("version", v, "type", v % 2 == 0 ? "SALON" : "KIRANA"), access);
            assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        }
        Reply eleventh = put("/shops/me/type", Map.of("version", 10, "type", "SALON"), access);
        assertThat(eleventh.status()).isEqualTo(429);
        assertThat(eleventh.body().get("code")).isEqualTo("TOO_MANY_REQUESTS");
        // The same kind again is no change and never counts
        assertThat(put("/shops/me/type", Map.of("version", 10, "type", "KIRANA"), access).status()).isEqualTo(200);
    }

    private UUID file(UUID shopId, UUID by, String purpose, String contentType) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO files (id, shop_id, purpose, storage_key, content_type, size_bytes, sha256, "
                + "uploaded_by, created_at) VALUES (?, ?, ?, ?, ?, 10, ?, ?, now())", id, shopId, purpose,
                "test/" + id, contentType, id.toString().replace("-", "") + "0".repeat(32), by);
        return id;
    }

    /** The status alone (a list answer can't be read as the Map the other helpers read). */
    private int statusOf(String path, String token) {
        return http.get().uri(path).headers(h -> bearer(h, token)).exchange((rq, res) -> res.getStatusCode().value());
    }

    private Reply put(String path, Object body, String token) {
        return http.put().uri(path).headers(h -> bearer(h, token)).contentType(MediaType.APPLICATION_JSON)
                .body(body).exchange((rq, res) -> reply(res));
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
