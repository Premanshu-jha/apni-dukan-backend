package in.apnidukan.shops;

import in.apnidukan.common.security.AuthPrincipal;
import in.apnidukan.common.security.JwtService;
import in.apnidukan.common.security.SessionRevocations;
import in.apnidukan.files.storage.FileStorage;
import in.apnidukan.shops.service.LicenceCheckJob;
import in.apnidukan.shops.service.LicenceExpiryJob;
import in.apnidukan.shops.service.LicenceExtract;
import in.apnidukan.shops.service.LicenceReader;
import in.apnidukan.support.CapturingSenders;
import in.apnidukan.support.TestClock;
import in.apnidukan.support.TestContainers;
import in.apnidukan.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The automatic trade licence check end to end against real Postgres, Redis and S3Mock: the owner's upload, the job
 * (with a fake AI that answers what each test says), the salon card's "Licensed" badge, the owner's message and the
 * field team's page. "Now" is Saturday 14 Nov 2026, 10:00 in India.
 */
@EnabledIf("in.apnidukan.support.TestContainers#dockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({CapturingSenders.class, LicenceCheckIntegrationTest.Fakes.class})
class LicenceCheckIntegrationTest {

    static final Instant NOW = Instant.parse("2026-11-14T04:30:00Z");
    static final TestClock CLOCK = new TestClock(NOW);
    /** What the fake AI does with each document it is given (the file's bytes as text). */
    static volatile Function<String, LicenceExtract> ai = text -> genuine("TR-1001", "Sharma Hair");
    static final AtomicInteger READS = new AtomicInteger();

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        TestClock testClock() {
            return CLOCK;
        }

        @Bean
        LicenceReader fakeAi() {
            return (bytes, contentType) -> {
                READS.incrementAndGet();
                return ai.apply(new String(bytes, StandardCharsets.UTF_8));
            };
        }
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        TestContainers.register(registry);
    }

    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired JwtService jwt;
    @Autowired SessionRevocations sessions;
    @Autowired FileStorage storage;
    @Autowired LicenceCheckJob job;
    @Autowired LicenceExpiryJob expiry;

    RestClient http;
    UUID admin;
    UUID owner;
    UUID salon;
    int phones;

    static LicenceExtract genuine(String number, String tradeName) {
        return new LicenceExtract(true, true, false, false, true, "Greater Hyderabad Municipal Corporation", number,
                tradeName, "Ravi Sharma", "Shop 4, Main Road, Kondapur", "500084", "Saloon",
                LocalDate.of(2027, 3, 31));
    }

    @BeforeEach
    void setUp() {
        http = RestClient.builder()
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + environment.getProperty("local.server.port") + "/api/v1")
                .build();
        TestData.clean(jdbc);
        redis.execute((RedisCallback<Object>) (RedisConnection c) -> {
            c.serverCommands().flushAll();
            return null;
        });
        CLOCK.set(NOW);
        READS.set(0);
        ai = text -> genuine("TR-1001", "Sharma Hair");
        phones = 0;
        admin = person();
        jdbc.update("UPDATE users SET admin_role = 'SUPER_ADMIN' WHERE id = ?", admin);
        owner = person();
        salon = liveSalon(owner, "Sharma Hair");
    }

    @Test
    void aGenuineMatchingLicencePassesTheCardSaysLicensedAndTheOwnerIsTold() {
        assertThat(card(salon).get("registered")).isEqualTo(false);
        Reply added = setLicence(salon, owner, upload(salon, owner, "licence-1"));
        assertThat(field(added.body(), "licenceCheck")).as(added.text()).isEqualTo("PENDING");
        assertThat(card(salon).get("registered")).isEqualTo(false);   // still being checked

        assertThat(job.runOnce()).isEqualTo(1);

        Reply mine = get("/shops/me", owner);
        assertThat(field(mine.body(), "licenceCheck")).as(mine.text()).isEqualTo("PASSED");
        assertThat(field(mine.body(), "licenceValidUntil")).isEqualTo("2027-03-31");
        assertThat(field(mine.body(), "registrationDocCheckedAt")).isNotNull();
        assertThat(card(salon).get("registered")).isEqualTo(true);
        assertThat(count("notification_jobs WHERE type = 'SHOP_LICENCE_PASSED' AND url = '/setup/document' "
                + "AND user_id = '" + owner + "'")).isEqualTo(1);
        Map<?, ?> doc = (Map<?, ?>) field(get("/admin/shops/" + salon, admin).body(), "registrationDoc");
        assertThat(doc.get("check")).isEqualTo("PASSED");
        assertThat(doc.get("licenceNumber")).isEqualTo("TR1001");   // letters and digits only
        assertThat(doc.get("since")).isNotNull();
        assertThat(field(mine.body(), "licenceCheckSince")).isNotNull();
        assertThat(field(field(mine.body(), "licenceRead"), "tradeName")).isEqualTo("Sharma Hair");   // the owner's too
        assertThat(field(doc.get("read"), "tradeName")).isEqualTo("Sharma Hair");
        assertThat(jdbc.queryForObject("SELECT note FROM shop_events WHERE shop_id = ? AND event = 'NOTE'",
                String.class, salon)).contains("TR1001").contains("31 Mar 2027");

        // Nothing more to read; the next round reads nothing
        assertThat(job.runOnce()).isZero();
        assertThat(READS.get()).isEqualTo(1);

        // Through its last day in India, then no badge from India's midnight (the owner sees it expired)
        CLOCK.set(Instant.parse("2027-03-31T18:29:00Z"));
        assertThat(card(salon).get("registered")).isEqualTo(true);
        assertThat(field(get("/salons/" + salon, person()).body(), "licensedUntil")).isEqualTo("2027-03-31");
        CLOCK.set(Instant.parse("2027-03-31T18:31:00Z"));
        assertThat(card(salon).get("registered")).isEqualTo(false);
        assertThat(field(get("/salons/" + salon, person()).body(), "licensedUntil")).isNull();
    }

    @Test
    void aMismatchFailsWithTheReasonAndFixingTheSalonsNameChecksItAgain() {
        ai = text -> genuine("TR-2002", "Royal Cuts");
        UUID file = upload(salon, owner, "licence-2");
        setLicence(salon, owner, file);
        ai = text -> new LicenceExtract(true, true, false, false, true, null, "TR-2002", "Royal Cuts", "Mohammed Ali",
                "Kondapur", "500084", "Saloon", LocalDate.of(2027, 3, 31));
        job.runOnce();
        Reply mine = get("/shops/me", owner);
        assertThat(field(mine.body(), "licenceCheck")).isEqualTo("FAILED");
        assertThat(field(mine.body(), "licenceCheckReason")).isEqualTo("NAME_MISMATCH");
        assertThat(card(salon).get("registered")).isEqualTo(false);
        assertThat(jdbc.queryForObject("SELECT body FROM notification_jobs WHERE type = 'SHOP_LICENCE_FAILED' "
                + "AND user_id = ?", String.class, owner)).contains("doesn't match your salon's name");

        // The owner renames the salon as on the licence: checked again by itself
        Reply renamed = patch("/shops/me", Map.of("version", version(salon), "name", "Royal Cuts"), owner);
        assertThat(field(renamed.body(), "licenceCheck")).as(renamed.text()).isEqualTo("PENDING");
        job.runOnce();
        assertThat(field(get("/shops/me", owner).body(), "licenceCheck")).isEqualTo("PASSED");

        // The same file sent again after a failure is checked again; sent again after it passed, nothing happens
        int before = READS.get();
        Reply again = setLicence(salon, owner, file);
        assertThat(field(again.body(), "licenceCheck")).isEqualTo("PASSED");
        assertThat(job.runOnce()).isZero();
        assertThat(READS.get()).isEqualTo(before);
    }

    @Test
    void oneLicencePerSalon() {
        UUID other = person();
        UUID second = liveSalon(other, "Sharma Hair Two");
        setLicence(salon, owner, upload(salon, owner, "a"));
        setLicence(second, other, upload(second, other, "b"));
        ai = text -> genuine(text.equals("a") ? "TR 7007" : "tr7007", "Sharma Hair");   // the same, printed two ways
        job.runOnce();
        assertThat(jdbc.queryForList("SELECT licence_check_status || ' ' || coalesce(licence_check_reason, '') FROM "
                + "shops WHERE id IN (?, ?) ORDER BY licence_check_status DESC", String.class, salon, second))
                .containsExactly("PASSED ", "FAILED DUPLICATE");
    }

    @Test
    void aLicenceReplacedWhileTheAiReadsItKeepsTheNewOnesCheck() {
        UUID first = upload(salon, owner, "first");
        setLicence(salon, owner, first);
        UUID replacement = upload(salon, owner, "second");
        ai = text -> {
            if (text.equals("first")) {   // the owner sends another one meanwhile
                assertThat(setLicence(salon, owner, replacement).status()).isEqualTo(200);
            }
            return genuine("TR-" + text, "Sharma Hair");
        };
        job.runOnce();   // the first answer is dropped: the replacement waits for its own check
        assertThat(jdbc.queryForObject("SELECT licence_check_status FROM shops WHERE id = ?", String.class, salon))
                .isEqualTo("PENDING");
        job.runOnce();
        Map<String, Object> row = jdbc.queryForMap("SELECT registration_doc_file_id, licence_check_status, "
                + "licence_number FROM shops WHERE id = ?", salon);
        assertThat(row.get("registration_doc_file_id")).isEqualTo(replacement);
        assertThat(row.get("licence_check_status")).isEqualTo("PASSED");
        assertThat(row.get("licence_number")).isEqualTo("TRSECOND");
        assertThat(count("notification_jobs WHERE type = 'SHOP_LICENCE_PASSED'")).isEqualTo(1);
    }

    @Test
    void whenTheAiIsDownItWaitsLongerEachTimeAndAnUnusableAnswerFailsAfterThreeTries() {
        setLicence(salon, owner, upload(salon, owner, "x"));
        ai = text -> {
            throw new LicenceReader.ReaderUnavailableException("down");
        };
        job.runOnce();
        assertThat(job.runOnce()).isZero();   // not due again yet
        assertThat(field(get("/shops/me", owner).body(), "licenceCheck")).isEqualTo("PENDING");
        CLOCK.advance(Duration.ofMinutes(1));
        assertThat(job.runOnce()).isEqualTo(1);   // the second try, a minute later
        CLOCK.advance(Duration.ofMinutes(1));
        assertThat(job.runOnce()).isZero();   // now it waits 2 minutes
        CLOCK.advance(Duration.ofMinutes(1));
        assertThat(job.runOnce()).isEqualTo(1);

        ai = text -> {
            throw new LicenceReader.UnusableAnswerException("no tool call");
        };
        // Two tries the AI didn't answer don't count: an answer it gave and we couldn't use is tried 3 times
        CLOCK.advance(Duration.ofHours(1));
        job.runOnce();
        assertThat(field(get("/shops/me", owner).body(), "licenceCheck")).isEqualTo("PENDING");
        CLOCK.advance(Duration.ofHours(1));
        job.runOnce();
        assertThat(field(get("/shops/me", owner).body(), "licenceCheck")).isEqualTo("PENDING");
        CLOCK.advance(Duration.ofHours(1));
        job.runOnce();   // the third unusable answer: that was the last
        Reply mine = get("/shops/me", owner);
        assertThat(field(mine.body(), "licenceCheck")).isEqualTo("FAILED");
        assertThat(field(mine.body(), "licenceCheckReason")).isEqualTo("NOT_READ");
    }

    @Test
    void removingTheLicenceWhileItWaitsLeavesNothingToCheck() {
        setLicence(salon, owner, upload(salon, owner, "x"));
        Reply gone = call(HttpMethod.DELETE, "/shops/me/registration-document?version=" + version(salon), null,
                owner);
        assertThat(gone.status()).as(gone.text()).isEqualTo(200);
        assertThat(job.runOnce()).isZero();
        assertThat(READS.get()).isZero();
    }

    @Test
    void anAnswerAboutTheOldNameIsDroppedWhenTheOwnerRenamesTheSalonMeanwhile() {
        setLicence(salon, owner, upload(salon, owner, "x"));
        ai = text -> {
            if (READS.get() == 1) {   // while the AI reads it, the owner renames the salon as on the licence
                assertThat(patch("/shops/me", Map.of("version", version(salon), "name", "Royal Cuts"), owner)
                        .status()).isEqualTo(200);
            }
            return genuine("TR-3003", "Royal Cuts");
        };
        job.runOnce();   // the answer about "Sharma Hair" (NAME_MISMATCH) is dropped; the new check waits
        assertThat(field(get("/shops/me", owner).body(), "licenceCheck")).isEqualTo("PENDING");
        assertThat(count("notification_jobs WHERE type = 'SHOP_LICENCE_FAILED'")).isZero();
        job.runOnce();
        assertThat(field(get("/shops/me", owner).body(), "licenceCheck")).isEqualTo("PASSED");
    }

    @Test
    void anAddressChangedByTheFieldTeamChecksTheLicenceAgain() {
        setLicence(salon, owner, upload(salon, owner, "x"));
        job.runOnce();
        assertThat(card(salon).get("registered")).isEqualTo(true);
        Reply moved = call(HttpMethod.PATCH, "/admin/shops/" + salon + "/address", Map.of("version", version(salon),
                "addressLine", "Plot 9, Hitech Road", "area", "Madhapur", "city", "Hyderabad", "pincode", "500081",
                "latitude", 17.448, "longitude", 78.391), admin);
        assertThat(moved.status()).as(moved.text()).isEqualTo(200);
        assertThat(field(get("/shops/me", owner).body(), "licenceCheck")).isEqualTo("PENDING");
        assertThat(field(get("/salons/" + salon, person()).body(), "licensedUntil")).isNull();
        job.runOnce();   // the licence says Kondapur 500084: not this address any more
        assertThat(field(get("/shops/me", owner).body(), "licenceCheckReason")).isEqualTo("ADDRESS_MISMATCH");
        // The field team sends it again (e.g. the owner showed a renewed one at the new address): checked once more
        assertThat(post("/admin/shops/" + salon + "/registration-document/recheck", Map.of("version", version(salon)),
                admin).status()).isEqualTo(200);
        assertThat(field(get("/shops/me", owner).body(), "licenceCheck")).isEqualTo("PENDING");
    }

    @Test
    void anOldUdyamCertificateIsNeverSentToTheAiAndAClosedSalonFreesItsLicence() {
        UUID udyam = upload(salon, owner, "udyam");
        jdbc.update("UPDATE shops SET registration_doc_type = 'UDYAM', registration_doc_file_id = ?, "
                + "licence_check_status = 'FAILED', licence_check_reason = 'NOT_TRADE_LICENCE' WHERE id = ?", udyam,
                salon);
        Reply renamed = patch("/shops/me", Map.of("version", version(salon), "name", "Sharma Hair Two"), owner);
        assertThat(field(renamed.body(), "licenceCheck")).as(renamed.text()).isEqualTo("FAILED");
        assertThat(field(post("/admin/shops/" + salon + "/registration-document/recheck", Map.of("version",
                version(salon)), admin).body(), "code")).isEqualTo("NOT_TRADE_LICENCE");
        assertThat(job.runOnce()).isZero();

        // A salon closed for good lets go of its licence: the same business registering again isn't DUPLICATE
        UUID other = person();
        UUID second = liveSalon(other, "Sharma Hair");
        setLicence(second, other, upload(second, other, "a"));
        job.runOnce();
        assertThat(jdbc.queryForObject("SELECT licence_number FROM shops WHERE id = ?", String.class, second))
                .isEqualTo("TR1001");
        assertThat(post("/shops/me/close", Map.of("version", version(second)), other).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT licence_check_status IS NULL AND registration_doc_file_id IS NULL "
                + "FROM shops WHERE id = ?", Boolean.class, second)).isTrue();
        setLicence(salon, owner, upload(salon, owner, "b"));
        job.runOnce();
        assertThat(field(get("/shops/me", owner).body(), "licenceCheck")).isEqualTo("PASSED");
    }

    @Test
    void aSalonStartsAtMostTenChecksADayAndADetailsChangePastThemIsReadTomorrow() {
        for (int i = 0; i < 10; i++) {
            assertThat(put("/shops/me/registration-document", Map.of("version", version(salon), "type",
                    "TRADE_LICENCE", "fileId", upload(salon, owner, "x" + i).toString()), owner).status())
                    .isEqualTo(200);
        }
        Reply eleventh = put("/shops/me/registration-document", Map.of("version", version(salon), "type",
                "TRADE_LICENCE", "fileId", upload(salon, owner, "y").toString()), owner);
        assertThat(eleventh.status()).isEqualTo(429);
        assertThat(field(eleventh.body(), "code")).isEqualTo("TOO_MANY_CHECKS");
        // A new name is still saved; its check waits for tomorrow
        Reply renamed = patch("/shops/me", Map.of("version", version(salon), "name", "Sharma Hair Studio"), owner);
        assertThat(renamed.status()).as(renamed.text()).isEqualTo(200);
        assertThat(job.runOnce()).isZero();
        CLOCK.advance(Duration.ofDays(1));
        assertThat(job.runOnce()).isEqualTo(1);
    }

    @Test
    void theSuperAdminsSeeLicencesToFollowUpAndOwnersAreToldBeforeAndWhenOneExpires() {
        setLicence(salon, owner, upload(salon, owner, "x"));
        assertThat((List<?>) get("/admin/shops/licences-to-follow-up", admin).body()).isEmpty();   // just sent
        CLOCK.advance(Duration.ofMinutes(31));   // waiting over half an hour (no AI key, the AI down)
        assertThat((List<?>) get("/admin/shops/licences-to-follow-up", admin).body()).extracting(x -> field(x, "id"))
                .containsExactly(salon.toString());
        job.runOnce();
        assertThat((List<?>) get("/admin/shops/licences-to-follow-up", admin).body()).isEmpty();   // passed

        // 1 Mar 2027, 22:30 India: a month before its last day (31 Mar 2027), but nothing at night
        CLOCK.set(Instant.parse("2027-03-01T17:00:00Z"));
        assertThat(expiry.runOnce()).isZero();
        // 2 Mar 2027, 10:00 India: the message goes
        CLOCK.set(Instant.parse("2027-03-02T04:30:00Z"));
        assertThat(expiry.runOnce()).isEqualTo(1);
        assertThat(expiry.runOnce()).isZero();   // once
        assertThat(jdbc.queryForObject("SELECT body FROM notification_jobs WHERE type = 'SHOP_LICENCE_EXPIRING'",
                String.class)).contains("31 Mar 2027");
        CLOCK.set(Instant.parse("2027-04-01T04:30:00Z"));
        assertThat(expiry.runOnce()).isEqualTo(1);
        assertThat(count("notification_jobs WHERE type = 'SHOP_LICENCE_EXPIRED' AND url = '/setup/document'"))
                .isEqualTo(1);
    }

    @Test
    void aNewSalonSendingAGstinOrUdyamNumberKeepsNone() {
        UUID someone = person();
        Reply created = post("/shops", Map.<String, Object>ofEntries(Map.entry("name", "New Cuts"),
                Map.entry("ownerName", "Ravi Sharma"), Map.entry("addressLine", "Shop 4, Main Road"),
                Map.entry("area", "Kondapur"), Map.entry("city", "Hyderabad"), Map.entry("pincode", "500084"),
                Map.entry("latitude", 17.465), Map.entry("longitude", 78.364), Map.entry("type", "SALON"),
                Map.entry("gstin", "36AAPFU0939F1ZV"), Map.entry("udyamNumber", "UDYAM-TS-02-0012345")), someone);
        assertThat(created.status()).as(created.text()).isEqualTo(201);
        assertThat(field(created.body(), "gstin")).isNull();
        assertThat(field(created.body(), "udyamNumber")).isNull();
        assertThat(jdbc.queryForObject("SELECT gstin IS NULL AND udyam_number IS NULL FROM shops WHERE id = ?::uuid",
                Boolean.class, field(created.body(), "id"))).isTrue();
    }

    // ---------- helpers ----------

    /** A live salon in Kondapur with a service, hours and a barber, so it shows in the salon list. */
    private UUID liveSalon(UUID who, String name) {
        UUID id = UUID.randomUUID();
        TestData.salon(jdbc, id, who);
        jdbc.update("UPDATE shops SET status = 'APPROVED', name = ?, owner_name = 'Ravi Sharma' WHERE id = ?", name,
                id);
        Reply service = post("/salon/services", Map.of("name", "Haircut", "durationMinutes", 30, "pricePaise", 15000),
                who);
        List<Map<String, Object>> days = new ArrayList<>();
        List<Map<String, Object>> hours = new ArrayList<>();
        for (int d = 1; d <= 7; d++) {
            days.add(Map.of("weekday", d, "open", "10:00", "close", "21:00"));
            hours.add(Map.of("weekday", d, "start", "10:00", "end", "21:00"));
        }
        assertThat(put("/salon/hours", Map.of("days", days), who).status()).isEqualTo(200);
        UUID barber = id(post("/salon/staff", Map.of("name", "Raju"), who));
        Reply s = put("/salon/staff/" + barber + "/services", Map.of("version", 0, "serviceIds", List.of(id(service))),
                who);
        assertThat(put("/salon/staff/" + barber + "/hours", Map.of("version", field(s.body(), "version"), "hours",
                hours), who).status()).isEqualTo(200);
        return id;
    }

    /** A REG_DOC upload whose stored bytes are this text (what the fake AI is given). */
    private UUID upload(UUID shop, UUID by, String text) {
        UUID id = UUID.randomUUID();
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        String key = "shops/" + shop + "/reg-doc/" + id + ".jpg";
        storage.put(key, new ByteArrayInputStream(bytes), bytes.length, "image/jpeg");
        jdbc.update("INSERT INTO files (id, shop_id, purpose, storage_key, content_type, size_bytes, sha256, "
                        + "uploaded_by, created_at) VALUES (?, ?, 'REG_DOC', ?, 'image/jpeg', ?, ?, ?, now())",
                id, shop, key, bytes.length, id.toString().replace("-", "") + "0".repeat(32), by);
        return id;
    }

    private Reply setLicence(UUID shop, UUID who, UUID file) {
        Reply r = put("/shops/me/registration-document", Map.of("version", version(shop), "type", "TRADE_LICENCE",
                "fileId", file.toString()), who);
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        return r;
    }

    private Map<?, ?> card(UUID shop) {
        Reply list = get("/salons?lat=17.465&lng=78.364", person());
        assertThat(list.status()).as(list.text()).isEqualTo(200);
        return ((List<?>) list.body()).stream().map(c -> (Map<?, ?>) c)
                .filter(c -> shop.toString().equals(c.get("id"))).findFirst().orElseThrow();
    }

    private long version(UUID shop) {
        return jdbc.queryForObject("SELECT version FROM shops WHERE id = ?", Long.class, shop);
    }

    private UUID person() {
        UUID id = UUID.randomUUID();
        TestData.user(jdbc, id, "93" + String.format("%08d", ++phones));
        return id;
    }

    private int count(String tableAndWhere) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + tableAndWhere, Integer.class);
        return n == null ? 0 : n;
    }

    private String token(UUID who) {
        boolean isAdmin = !"NONE".equals(jdbc.queryForObject("SELECT admin_role FROM users WHERE id = ?",
                String.class, who));
        return jwt.issue(new AuthPrincipal(who, null, null, isAdmin), sessions.currentGeneration(who)).token();
    }

    private Reply get(String path, UUID who) {
        return call(HttpMethod.GET, path, null, who);
    }

    private Reply post(String path, Object body, UUID who) {
        return call(HttpMethod.POST, path, body, who);
    }

    private Reply put(String path, Object body, UUID who) {
        return call(HttpMethod.PUT, path, body, who);
    }

    private Reply patch(String path, Object body, UUID who) {
        return call(HttpMethod.PATCH, path, body, who);
    }

    private Reply call(HttpMethod method, String path, Object body, UUID who) {
        if (path.startsWith("/shops/me") && method != HttpMethod.GET) {
            TestData.unlockEditing(jdbc, redis);   // the code before editing a verified shop: tested in AdminIT
        }
        RestClient.RequestBodySpec spec = http.method(method).uri(path).headers(h -> {
            if (who != null) {
                h.setBearerAuth(token(who));
            }
        });
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((rq, res) -> {
            int status = res.getStatusCode().value();
            String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
            Object parsed = text.isBlank() ? Map.of() : JSON.readValue(text, Object.class);
            return new Reply(status, parsed, text);
        });
    }

    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

    private static Object field(Object json, String name) {
        return ((Map<?, ?>) json).get(name);
    }

    private static UUID id(Reply r) {
        assertThat(r.status()).as(r.text()).isBetween(200, 201);
        return UUID.fromString((String) field(r.body(), "id"));
    }

    private record Reply(int status, Object body, String text) {
    }
}
