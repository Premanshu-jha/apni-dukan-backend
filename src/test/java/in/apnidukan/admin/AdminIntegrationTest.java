package in.apnidukan.admin;

import in.apnidukan.common.security.AuthPrincipal;
import in.apnidukan.common.security.JwtService;
import in.apnidukan.common.security.SessionRevocations;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static in.apnidukan.support.CapturingSenders.LAST_EMAIL;
import static in.apnidukan.support.CapturingSenders.LAST_SMS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The admin website's backend against real Postgres and Redis: roles (one super admin, admins added and removed),
 * OTP test mode (only test numbers, switches itself off, never an admin's number), and the field team's pages
 * (dashboard, lists, what changed since approval, decisions with checklist and notes, the timeline).
 */
@EnabledIf("in.apnidukan.support.TestContainers#dockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({CapturingSenders.class, AdminIntegrationTest.Fakes.class})
class AdminIntegrationTest {

    static final Instant NOW = Instant.parse("2026-11-10T04:30:00Z");
    static final TestClock CLOCK = new TestClock(NOW);

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        TestClock testClock() {
            return CLOCK;
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

    RestClient http;
    String base;
    UUID boss;
    UUID admin;
    UUID customer;

    @BeforeEach
    void setUp() {
        base = "http://localhost:" + environment.getProperty("local.server.port") + "/api/v1";
        http = RestClient.builder()
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory()).baseUrl(base)
                .build();
        TestData.clean(jdbc);
        redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        LAST_SMS.clear();
        LAST_EMAIL.clear();
        CLOCK.set(NOW);
        boss = person("9000000001");
        jdbc.update("UPDATE users SET admin_role = 'SUPER_ADMIN', name = 'Prem' WHERE id = ?", boss);
        admin = person("9000000002");
        jdbc.update("UPDATE users SET admin_role = 'ADMIN', name = 'Field Ravi' WHERE id = ?", admin);
        customer = person("9000000003");
    }

    // ---------------- roles ----------------

    @Test
    void thereIsAtMostOneSuperAdmin() {
        assertThatThrownBy(() -> jdbc.update("UPDATE users SET admin_role = 'SUPER_ADMIN' WHERE id = ?", admin))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theSuperAdminAddsAndRemovesAdminsAndOnlyHeCan() {
        assertThat(field(get("/admin/me", boss).body(), "adminRole")).isEqualTo("SUPER_ADMIN");
        assertThat(field(get("/admin/me", admin).body(), "adminRole")).isEqualTo("ADMIN");
        assertThat(get("/admin/me", customer).status()).isEqualTo(403);

        // An ordinary admin can't manage the team or test mode
        assertThat(field(get("/admin/team", admin).body(), "code")).isEqualTo("SUPER_ADMIN_ONLY");
        assertThat(post("/admin/team", Map.of("phone", "9000000009"), admin).status()).isEqualTo(403);
        assertThat(field(put("/admin/test-mode", Map.of("hours", 1), admin).body(), "code"))
                .isEqualTo("SUPER_ADMIN_ONLY");

        // A phone nobody has used yet: the account is made, and they sign in with the normal OTP
        Reply added = post("/admin/team", Map.of("phone", "+91 90000 00009"), boss);
        assertThat(added.status()).as(added.text()).isEqualTo(201);
        UUID newAdmin = UUID.fromString((String) field(added.body(), "userId"));
        assertThat(field(post("/admin/team", Map.of("phone", "9000000009"), boss).body(), "code"))
                .isEqualTo("ALREADY_ADMIN");
        List<?> team = (List<?>) get("/admin/team", boss).body();
        assertThat(team).hasSize(3);
        assertThat(field(team.get(0), "adminRole")).isEqualTo("SUPER_ADMIN");

        // Removing: signed out at once (the token they hold stops working)
        String held = token(newAdmin);
        assertThat(get("/admin/me", held).status()).isEqualTo(200);
        assertThat(delete("/admin/team/" + newAdmin, boss).status()).isEqualTo(204);
        assertThat(get("/admin/me", held).status()).isEqualTo(401);
        assertThat(get("/admin/shops?type=SALON&group=FIRST_VISIT", newAdmin).status()).isEqualTo(403);
        assertThat(field(delete("/admin/team/" + boss, boss).body(), "code")).isEqualTo("CANT_REMOVE_SUPER_ADMIN");
        assertThat(count("admin_events WHERE event IN ('ADMIN_ADDED', 'ADMIN_REMOVED')")).isEqualTo(2);
    }

    @Test
    void aTokenFromBeforeTheAdminWasRemovedInTheDatabaseIsRefused() {
        String held = token(admin);
        jdbc.update("UPDATE users SET admin_role = 'NONE' WHERE id = ?", admin);   // without the sign-out
        assertThat(get("/admin/shops?type=KIRANA&group=LIVE", held).status()).isEqualTo(404);
        assertThat(get("/admin/me", held).status()).isEqualTo(404);
    }

    // ---------------- OTP test mode ----------------

    @Test
    void testNumbersSignInWithAnyCodeOnlyWhileTestModeIsOn() {
        Reply added = post("/admin/test-mode/phones", Map.of("phone", "9111111111", "label", "Test barber"), boss);
        assertThat(added.status()).as(added.text()).isEqualTo(201);
        assertThat(field(added.body(), "on")).isEqualTo(false);

        // Off: a test number needs the real code, sent by SMS
        assertThat(post("/auth/otp/send", Map.of("phone", "9111111111"), (String) null).status()).isEqualTo(202);
        assertThat(LAST_SMS).containsKey("9111111111");
        LAST_SMS.clear();
        Reply guessed = post("/auth/otp/verify", Map.of("phone", "9111111111", "code", "123456"), (String) null);
        assertThat(guessed.status()).isIn(400, 429);

        Reply on = put("/admin/test-mode", Map.of("hours", 2), boss);
        assertThat(field(on.body(), "on")).as(on.text()).isEqualTo(true);
        assertThat(field(on.body(), "turnedOnByName")).isEqualTo("Prem");
        assertThat(field(field(get("/admin/me", admin).body(), "testMode"), "on")).isEqualTo(true);

        // On: no SMS, any 6 digits
        assertThat(post("/auth/otp/send", Map.of("phone", "9111111112"), (String) null).status()).isEqualTo(202);
        assertThat(LAST_SMS).containsKey("9111111112");   // not a test number: as usual
        assertThat(post("/auth/otp/send", Map.of("phone", "9111111111"), (String) null).status()).isEqualTo(202);
        assertThat(LAST_SMS).doesNotContainKey("9111111111");
        Reply in = post("/auth/otp/verify", Map.of("phone", "9111111111", "code", "424242"), (String) null);
        assertThat(in.status()).as(in.text()).isEqualTo(200);
        assertThat(post("/auth/otp/verify", Map.of("phone", "9111111111", "code", "12345"), (String) null).status())
                .isEqualTo(400);   // still 6 digits
        Reply other = post("/auth/otp/verify", Map.of("phone", "9111111112", "code", "424242"), (String) null);
        assertThat(other.status()).isEqualTo(400);   // the real code is needed for everyone else

        assertThat(jdbc.queryForObject("SELECT test_account FROM users WHERE phone = '9111111111'", Boolean.class))
                .isTrue();
        // The email code is always the real one (else a test account could claim someone's email)
        String access = (String) field(in.body(), "accessToken");
        assertThat(post("/auth/email/send-code", Map.of("email", "tester@example.com"), access).status())
                .isEqualTo(202);
        assertThat(LAST_EMAIL).containsKey("tester@example.com");
        assertThat(post("/auth/email/verify", Map.of("email", "tester@example.com", "code", "777777"), access)
                .status()).isEqualTo(400);

        // It switches itself off when the time is up, and the test account's session can't be renewed then
        String refresh = (String) field(in.body(), "refreshToken");
        CLOCK.advance(Duration.ofHours(2).plusMinutes(1));
        assertThat(field(get("/admin/test-mode", boss).body(), "on")).isEqualTo(false);
        Reply renewed = post("/auth/refresh", Map.of("refreshToken", refresh), (String) null);
        assertThat(field(renewed.body(), "code")).as(renewed.text()).isEqualTo("TEST_MODE_OFF");
        post("/auth/otp/send", Map.of("phone", "9111111111"), (String) null);
        assertThat(LAST_SMS).containsKey("9111111111");
        assertThat(count("admin_events WHERE event IN ('TEST_PHONE_ADDED', 'TEST_MODE_ON')")).isEqualTo(2);
    }

    @Test
    void aRealAccountsNumberCantBecomeATestNumberAndATestAccountCantBecomeAnAdmin() {
        assertThat(field(post("/admin/test-mode/phones", Map.of("phone", "9000000003", "label", "Oops"), boss)
                .body(), "code")).isEqualTo("REAL_ACCOUNT");   // the customer's number
        // Even if it got onto the list by hand, a real account never takes any code
        jdbc.update("INSERT INTO test_phones (phone, label, added_by, added_at) VALUES ('9000000003', 'x', ?, ?)",
                boss, Timestamp.from(NOW));
        put("/admin/test-mode", Map.of("hours", 1), boss);
        assertThat(post("/auth/otp/verify", Map.of("phone", "9000000003", "code", "123456"), (String) null)
                .status()).isEqualTo(400);

        // A number used for testing, then taken off the list, still can't be made an admin
        post("/admin/test-mode/phones", Map.of("phone", "9111111111", "label", "Tester"), boss);
        Reply in = post("/auth/otp/verify", Map.of("phone", "9111111111", "code", "424242"), (String) null);
        assertThat(in.status()).as(in.text()).isEqualTo(200);
        Reply removed = delete("/admin/test-mode/phones/9111111111", boss);
        assertThat(removed.status()).isEqualTo(200);
        assertThat(get("/auth/me", (String) field(in.body(), "accessToken")).status()).isEqualTo(401);   // out
        assertThat(field(post("/admin/team", Map.of("phone", "9111111111"), boss).body(), "code"))
                .isEqualTo("TEST_PHONE");

        // The number's real holder signs in later with the SMS code: the account becomes a normal one for good
        String tester = (String) field(post("/auth/otp/verify", Map.of("phone", "9111111111", "code", "424242"),
                (String) null).body(), "refreshToken");
        assertThat(tester).isNull();   // no longer a test number: any code is refused
        post("/auth/otp/send", Map.of("phone", "9111111111"), (String) null);
        Reply real = post("/auth/otp/verify", Map.of("phone", "9111111111", "code",
                CapturingSenders.codeSentTo(LAST_SMS, "9111111111")), (String) null);
        assertThat(real.status()).as(real.text()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT test_account FROM users WHERE phone = '9111111111'", Boolean.class))
                .isFalse();
        Reply renewed = post("/auth/refresh", Map.of("refreshToken", field(real.body(), "refreshToken")),
                (String) null);
        assertThat(renewed.status()).as(renewed.text()).isEqualTo(200);
    }

    @Test
    void theSuperAdminCantDeleteTheirAccountAndANewAdminSignsInAfresh() {
        Reply refused = post("/auth/me/delete-code", Map.of(), boss);
        assertThat(field(refused.body(), "code")).as(refused.text()).isEqualTo("SUPER_ADMIN");
        // Someone already signed in to a customer's account made an admin: that session ends
        String held = token(customer);
        assertThat(post("/admin/team", Map.of("phone", "9000000003"), boss).status()).isEqualTo(201);
        assertThat(get("/auth/me", held).status()).isEqualTo(401);
        List<?> team = (List<?>) get("/admin/team", boss).body();
        assertThat(field(team.get(team.size() - 1), "since")).isEqualTo(NOW.toString());
    }

    @Test
    void anAdminsNumberIsNeverATestNumber() {
        assertThat(field(post("/admin/test-mode/phones", Map.of("phone", "9000000002", "label", "Ravi"), boss)
                .body(), "code")).isEqualTo("ADMIN_PHONE");
        post("/admin/test-mode/phones", Map.of("phone", "9111111111", "label", "Tester"), boss);
        assertThat(field(post("/admin/test-mode/phones", Map.of("phone", "9111111111", "label", "Again"), boss)
                .body(), "code")).isEqualTo("ALREADY_TEST_PHONE");
        assertThat(field(post("/admin/team", Map.of("phone", "9111111111"), boss).body(), "code"))
                .isEqualTo("TEST_PHONE");
        // Even if both happened (set by hand), an admin's number still needs the real code
        jdbc.update("INSERT INTO test_phones (phone, label, added_by, added_at) VALUES ('9000000002', 'x', ?, ?)",
                boss, Timestamp.from(NOW));
        put("/admin/test-mode", Map.of("hours", 1), boss);
        post("/auth/otp/send", Map.of("phone", "9000000002"), (String) null);
        assertThat(LAST_SMS).containsKey("9000000002");
        assertThat(post("/auth/otp/verify", Map.of("phone", "9000000002", "code", "000001"), (String) null)
                .status()).isEqualTo(400);

        assertThat(put("/admin/test-mode", Map.of("hours", 9), boss).status()).isEqualTo(400);
        assertThat(field(delete("/admin/test-mode", boss).body(), "on")).isEqualTo(false);
        Reply removed = delete("/admin/test-mode/phones/9111111111", boss);
        assertThat((List<?>) field(removed.body(), "phones")).hasSize(1);
    }

    // ---------------- field visits ----------------

    @Test
    void theDashboardCountsEachGroupAndTheListsSearch() {
        UUID first = shop("SALON", "PENDING_VISIT", "Raju Hair", "9200000001");
        UUID recheck = shop("SALON", "PENDING_VISIT", "Sharma Salon", "9200000002");
        approve(recheck);
        moveThePin(recheck);
        shop("SALON", "APPROVED", "Live Cuts", "9200000003");
        UUID sentBack = shop("KIRANA", "PENDING_VISIT", "Gupta Store", "9200000004");
        assertThat(post("/admin/shops/" + sentBack + "/reject", Map.of("version", version(sentBack), "reason",
                "The shop photo doesn't show the name board."), admin).status()).isEqualTo(200);
        shop("KIRANA", "SUSPENDED", "Closed Mart", "9200000005");
        shop("KIRANA", "DRAFT", "Unfinished", "9200000006");   // not submitted: in no group

        Map<?, ?> counts = (Map<?, ?>) get("/admin/dashboard", admin).body();
        assertThat(counts.get("SALON")).isEqualTo(Map.of("FIRST_VISIT", 1, "RECHECK", 1, "SENT_BACK", 0, "LIVE", 1,
                "SUSPENDED", 0));
        assertThat(counts.get("KIRANA")).isEqualTo(Map.of("FIRST_VISIT", 0, "RECHECK", 0, "SENT_BACK", 1, "LIVE", 0,
                "SUSPENDED", 1));

        List<?> waiting = (List<?>) get("/admin/shops?type=SALON&group=FIRST_VISIT", admin).body();
        assertThat(waiting).hasSize(1);
        assertThat(field(waiting.get(0), "id")).isEqualTo(first.toString());
        assertThat(field(waiting.get(0), "group")).isEqualTo("FIRST_VISIT");
        assertThat(field(waiting.get(0), "ownerPhone")).isEqualTo("9200000001");
        assertThat(field(((List<?>) get("/admin/shops?type=SALON&group=RECHECK", admin).body()).get(0), "group"))
                .isEqualTo("RECHECK");
        // Search: name in part, owner phone in part, shop number exactly; LIKE wildcards are plain text
        assertThat((List<?>) get("/admin/shops?type=SALON&group=LIVE&q=cuts", admin).body()).hasSize(1);
        assertThat((List<?>) get("/admin/shops?type=SALON&group=LIVE&q=920000000", admin).body()).hasSize(1);
        long code = jdbc.queryForObject("SELECT shop_code FROM shops WHERE id = ?", Long.class, first);
        assertThat((List<?>) get("/admin/shops?type=SALON&group=FIRST_VISIT&q=" + code, admin).body()).hasSize(1);
        assertThat(get("/admin/dashboard", customer).status()).isEqualTo(403);
        // The older ?status= form is gone: type and group are required
        Reply old = get("/admin/shops?status=PENDING_VISIT", admin);
        assertThat(old.status()).isEqualTo(400);
        assertThat(field(old.body(), "code")).isEqualTo("VALIDATION_FAILED");
        assertThat(field(old.body(), "details")).isEqualTo(Map.of("type", "is required", "group", "is required"));
        assertThat(field(get("/admin/shops?type=SALON", admin).body(), "details"))
                .isEqualTo(Map.of("group", "is required"));
    }

    @Test
    void aReCheckShowsWhatChangedSinceApprovalAndTheTimeline() {
        UUID salon = shop("SALON", "PENDING_VISIT", "Sharma Salon", "9200000002");
        approve(salon);
        UUID owner = ownerOf(salon);
        Reply edited = patch("/shops/me", Map.of("version", version(salon), "name", "Sharma Hair Studio",
                "latitude", 17.466000, "gstin", "27AAPFU0939F1ZV"), owner);
        assertThat(field(edited.body(), "status")).as(edited.text()).isEqualTo("PENDING_VISIT");

        Reply page = get("/admin/shops/" + salon, admin);
        assertThat(page.status()).as(page.text()).isEqualTo(200);
        List<?> changes = (List<?>) field(page.body(), "changes");
        assertThat(changes).extracting(c -> field(c, "field")).containsExactly("name", "location", "gstin");
        assertThat(field(changes.get(0), "before")).isEqualTo("Sharma Salon");
        assertThat(field(changes.get(0), "after")).isEqualTo("Sharma Hair Studio");
        assertThat(((Number) field(page.body(), "pinMovedMetres")).longValue()).isBetween(105L, 120L);
        assertThat(field(page.body(), "salon")).isEqualTo(Map.of("barbers", 0, "services", 0));
        List<?> timeline = (List<?>) field(page.body(), "timeline");
        assertThat(timeline).extracting(e -> field(e, "event")).containsExactly("EDITED", "APPROVED");
        assertThat(field(timeline.get(1), "byName")).isEqualTo("Field Ravi");
        assertThat(field(timeline.get(1), "byAdmin")).isEqualTo(true);

        // Send back with a checklist, a reason for the owner and a note only admins see
        Reply back = post("/admin/shops/" + salon + "/reject", Map.of("version", version(salon), "reason",
                "The pin is 100 m away from the shop.", "note", "Owner was not there; called him.",
                "checklist", List.of("PIN_WRONG", "OTHER")), admin);
        assertThat(field(back.body(), "group")).as(back.text()).isEqualTo("SENT_BACK");
        Reply note = post("/admin/shops/" + salon + "/notes", Map.of("note", "Shop is behind the temple."), admin);
        assertThat(note.status()).isEqualTo(201);
        timeline = (List<?>) field(get("/admin/shops/" + salon, admin).body(), "timeline");
        assertThat(timeline).extracting(e -> field(e, "event")).containsExactly("NOTE", "SENT_BACK", "EDITED",
                "APPROVED");
        assertThat(field(timeline.get(1), "checklist")).isEqualTo(List.of("PIN_WRONG", "OTHER"));
        assertThat(field(timeline.get(1), "reason")).isEqualTo("The pin is 100 m away from the shop.");
        assertThat(field(timeline.get(1), "note")).isEqualTo("Owner was not there; called him.");
        // The owner sees the reason, never the note
        assertThat(get("/shops/me", owner).text()).contains("100 m away").doesNotContain("called him");

        // Sent again by the owner (both photos are needed to submit): on the timeline
        jdbc.update("UPDATE shops SET owner_photo_file_id = ?, shop_photo_file_id = ? WHERE id = ?",
                file(salon, owner, "OWNER_PHOTO"), file(salon, owner, "SHOP_PHOTO"), salon);
        Reply submitted = post("/shops/me/submit", Map.of("version", version(salon)), owner);
        assertThat(submitted.status()).as(submitted.text()).isEqualTo(200);
        assertThat(field(((List<?>) field(get("/admin/shops/" + salon, admin).body(), "timeline")).get(0), "event"))
                .isEqualTo("SUBMITTED");
    }

    @Test
    void whenAnotherAdminDecidedFirstTheSecondIsToldSoNotThatTheOwnerChangedIt() {
        UUID shop = shop("SALON", "PENDING_VISIT", "Raju Hair", "9200000001");
        long seen = version(shop);
        approve(shop);
        Reply late = post("/admin/shops/" + shop + "/reject", Map.of("version", seen, "reason", "Pin is wrong"),
                admin);
        assertThat(field(late.body(), "code")).as(late.text()).isEqualTo("WRONG_STATUS");
        assertThat(field(field(late.body(), "details"), "status")).isEqualTo("APPROVED");
    }

    @Test
    void editsMadeWhileLiveShowAtTheReCheckAndTheQueueKeepsItsOrder() {
        UUID older = shop("SALON", "PENDING_VISIT", "First In", "9200000007");
        UUID salon = shop("SALON", "PENDING_VISIT", "Sharma Salon", "9200000002");
        CLOCK.advance(Duration.ofMinutes(1));
        approve(salon);
        // Live: a new name needs no visit, and it shows as a change since approval
        CLOCK.advance(Duration.ofMinutes(1));
        UUID owner = ownerOf(salon);
        patch("/shops/me", Map.of("version", version(salon), "name", "Sharma Hair Studio"), owner);
        assertThat(((List<?>) field(get("/admin/shops/" + salon, admin).body(), "changes"))).hasSize(1);
        moveThePin(salon);
        List<?> changes = (List<?>) field(get("/admin/shops/" + salon, admin).body(), "changes");
        assertThat(changes).extracting(c -> field(c, "field")).containsExactly("name", "location");

        // The waiting shop sent first stays first, even after its owner fixes a typo (which saves the shop again)
        UUID newer = shop("SALON", "PENDING_VISIT", "Second In", "9200000008");
        submittedAt(older, NOW);
        submittedAt(newer, NOW.plusSeconds(30));
        patch("/shops/me", Map.of("version", version(older), "name", "First In Salon"), ownerOf(older));
        List<?> queue = search("FIRST_VISIT", null);
        assertThat(queue).extracting(x -> field(x, "id")).containsExactly(older.toString(), newer.toString());
        assertThat(field(queue.get(0), "statusSince")).isEqualTo(NOW.toString());

        // Search as the screen shows them: "#1042" and "+91 92000 00002"; LIKE's own characters are plain text
        long code = jdbc.queryForObject("SELECT shop_code FROM shops WHERE id = ?", Long.class, salon);
        assertThat(search("RECHECK", "#" + code)).hasSize(1);
        assertThat(search("RECHECK", "+91 92000 00002")).hasSize(1);
        assertThat(search("RECHECK", "+91 92000")).hasSize(1);
        assertThat(search("RECHECK", "09200000002")).hasSize(1);
        assertThat(search("RECHECK", "x_y")).isEmpty();
        assertThat(search("RECHECK", "%%%")).isEmpty();

        // Suspended and reinstated after being live: a re-check
        approve(salon);
        post("/admin/shops/" + salon + "/suspend", Map.of("version", version(salon), "reason", "Found shut"), admin);
        Reply back = post("/admin/shops/" + salon + "/reinstate", Map.of("version", version(salon)), admin);
        assertThat(field(back.body(), "group")).as(back.text()).isEqualTo("RECHECK");
    }

    @Test
    void decisionsCheckWhatTheyAreGiven() {
        UUID shop = shop("KIRANA", "PENDING_VISIT", "Gupta Store", "9200000004");
        Reply shortReason = post("/admin/shops/" + shop + "/reject", Map.of("version", version(shop), "reason", "x"),
                admin);
        assertThat(field(shortReason.body(), "code")).as(shortReason.text()).isEqualTo("VALIDATION_FAILED");
        Reply longNote = post("/admin/shops/" + shop + "/approve", Map.of("version", version(shop), "note",
                "n".repeat(501)), admin);
        assertThat(longNote.status()).isEqualTo(400);
        Reply badItem = post("/admin/shops/" + shop + "/suspend", Map.of("version", version(shop), "reason",
                "Fake shop", "checklist", List.of("NOT_A_THING")), admin);
        assertThat(badItem.status()).isEqualTo(400);
        assertThat(get("/admin/shops/" + UUID.randomUUID(), admin).status()).isEqualTo(404);
        assertThat(post("/admin/shops/" + shop + "/notes", Map.of("note", " "), admin).status()).isEqualTo(400);

        // Suspended, then reinstated: back for a visit as a re-check only if it was ever approved
        post("/admin/shops/" + shop + "/suspend", Map.of("version", version(shop), "reason", "Found shut twice",
                "checklist", List.of("SHOP_SHUT")), admin);
        Reply back = post("/admin/shops/" + shop + "/reinstate", Map.of("version", version(shop), "note",
                "Owner says open again"), admin);
        assertThat(field(back.body(), "group")).as(back.text()).isEqualTo("FIRST_VISIT");
        assertThat(count("shop_events WHERE event = 'REINSTATED' AND note = 'Owner says open again'")).isEqualTo(1);
    }

    @Test
    void aLongListComesFiftyAPageInOrderWithNothingTwiceOrLeftOut() {
        java.util.List<String> all = new java.util.ArrayList<>();
        for (int i = 0; i < 55; i++) {
            UUID s = shop("SALON", "PENDING_VISIT", "Queue " + i, "93000000" + String.format("%02d", i));
            submittedAt(s, NOW.plusSeconds(i / 2));   // pairs at the same moment: only the id orders them
            all.add(s.toString());
        }
        List<?> first = search("FIRST_VISIT", null);
        assertThat(first).hasSize(50);
        Object last = first.get(49);
        Reply next = get("/admin/shops?type=SALON&group=FIRST_VISIT&afterSince=" + field(last, "statusSince")
                + "&afterId=" + field(last, "id"), admin);
        assertThat(next.status()).as(next.text()).isEqualTo(200);
        List<Object> both = new java.util.ArrayList<>(first);
        both.addAll((List<?>) next.body());
        assertThat(both).extracting(x -> (String) field(x, "id")).containsExactlyInAnyOrderElementsOf(all);
        // Longest waiting first, across the page edge too
        List<String> since = both.stream().map(x -> (String) field(x, "statusSince")).toList();
        assertThat(since).isSortedAccordingTo(java.util.Comparator.comparing(Instant::parse));

        assertThat(get("/admin/shops?type=SALON&group=FIRST_VISIT&afterId=" + UUID.randomUUID(), admin).status())
                .isEqualTo(400);
        assertThat(get("/admin/shops?type=SALON&group=FIRST_VISIT&afterSince=yesterday&afterId="
                + UUID.randomUUID(), admin).status()).isEqualTo(400);
        // The older ?status= form is gone
        assertThat(get("/admin/shops?status=PENDING_VISIT", admin).status()).isEqualTo(400);
    }

    @Test
    void anyTimelineEntryKeepsTheListColumnsEvenFromCodeThatDoesntKnowThem() {
        // As a copy of the backend on the previous version would write it, straight into the timeline
        UUID shop = shop("KIRANA", "APPROVED", "Old Code Store", "9300000098");
        Instant later = NOW.plusSeconds(3600);
        jdbc.update("INSERT INTO shop_events (id, shop_id, at, by_user, by_admin, event) VALUES (?, ?, ?, ?, true, "
                + "'APPROVED')", UUID.randomUUID(), shop, Timestamp.from(later), admin);
        assertThat(jdbc.queryForObject("SELECT ever_approved FROM shops WHERE id = ?", Boolean.class, shop)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status_since FROM shops WHERE id = ?", Timestamp.class, shop)
                .toInstant()).isEqualTo(later);
        // A note moves nothing; an earlier entry never moves it back
        jdbc.update("INSERT INTO shop_events (id, shop_id, at, by_user, by_admin, event, note) VALUES (?, ?, ?, ?, "
                + "true, 'NOTE', 'x')", UUID.randomUUID(), shop, Timestamp.from(later.plusSeconds(60)), admin);
        jdbc.update("INSERT INTO shop_events (id, shop_id, at, by_user, by_admin, event) VALUES (?, ?, ?, ?, true, "
                + "'SUSPENDED')", UUID.randomUUID(), shop, Timestamp.from(NOW), admin);
        assertThat(jdbc.queryForObject("SELECT status_since FROM shops WHERE id = ?", Timestamp.class, shop)
                .toInstant()).isEqualTo(later);
    }

    @Test
    void anUnchangedAnswerIsA304WithNoBodyAndAChangeGivesANewOne() {
        UUID shop = shop("SALON", "PENDING_VISIT", "Etag Salon", "9300000099");
        String path = base + "/admin/dashboard";
        var first = http.get().uri(path).headers(h -> h.setBearerAuth(token(admin)))
                .exchange((rq, res) -> java.util.Map.entry(res.getStatusCode().value(),
                        String.valueOf(res.getHeaders().getETag())));
        assertThat(first.getKey()).isEqualTo(200);
        String etag = first.getValue();
        assertThat(etag).startsWith("\"");
        var same = http.get().uri(path).headers(h -> {
            h.setBearerAuth(token(admin));
            h.setIfNoneMatch(etag);
        }).exchange((rq, res) -> java.util.Map.entry(res.getStatusCode().value(), res.getBody().readAllBytes().length));
        assertThat(same.getKey()).isEqualTo(304);
        assertThat(same.getValue()).isZero();

        approve(shop);
        var changed = http.get().uri(path).headers(h -> {
            h.setBearerAuth(token(admin));
            h.setIfNoneMatch(etag);
        }).exchange((rq, res) -> java.util.Map.entry(res.getStatusCode().value(),
                String.valueOf(res.getHeaders().getETag())));
        assertThat(changed.getKey()).isEqualTo(200);
        assertThat(changed.getValue()).isNotEqualTo(etag);
    }

    @Test
    void aLongTimelineComesFiftyAPageNewestFirstWithTiesAcrossTheEdge() {
        UUID shop = shop("SALON", "PENDING_VISIT", "Busy Salon", "9300000201");
        for (int i = 0; i < 55; i++) {   // three entries at each moment: only seq orders them
            jdbc.update("INSERT INTO shop_events (id, shop_id, at, by_user, by_admin, event, note) VALUES (?, ?, ?, ?, "
                    + "true, 'NOTE', ?)", UUID.randomUUID(), shop, Timestamp.from(NOW.plusSeconds(i / 3)), admin,
                    "note " + i);
        }
        Reply page = get("/admin/shops/" + shop, admin);
        List<?> first = (List<?>) field(page.body(), "timeline");
        assertThat(first).hasSize(50);
        Object next = field(page.body(), "timelineNext");
        assertThat(next).isNotNull();
        assertThat(field(next, "afterAt")).isEqualTo(field(first.get(49), "at"));
        // The same first page from the timeline call
        Reply again = get("/admin/shops/" + shop + "/timeline", admin);
        assertThat((List<?>) field(again.body(), "items")).isEqualTo(first);
        assertThat(field(again.body(), "next")).isEqualTo(next);

        Reply rest = get("/admin/shops/" + shop + "/timeline?afterAt=" + field(next, "afterAt") + "&afterSeq="
                + field(next, "afterSeq"), admin);
        assertThat(rest.status()).as(rest.text()).isEqualTo(200);
        List<?> second = (List<?>) field(rest.body(), "items");
        assertThat(second).hasSize(5);
        assertThat(field(rest.body(), "next")).isNull();
        List<Object> all = new java.util.ArrayList<>(first);
        all.addAll(second);
        // Newest first, the later of entries made at the same moment first: note 54, 53, ... 0, each once
        assertThat(all).extracting(e -> field(e, "note"))
                .containsExactlyElementsOf(java.util.stream.IntStream.iterate(54, i -> i - 1).limit(55)
                        .mapToObj(i -> "note " + i).toList());

        assertThat(field(get("/admin/shops/" + shop + "/timeline?afterSeq=3", admin).body(), "code"))
                .isEqualTo("VALIDATION_FAILED");
        assertThat(field(get("/admin/shops/" + shop + "/timeline?afterAt=" + NOW + "&afterSeq=x", admin).body(),
                "code")).isEqualTo("BAD_REQUEST");
        assertThat(get("/admin/shops/" + UUID.randomUUID() + "/timeline", admin).status()).isEqualTo(404);
        assertThat(get("/admin/shops/" + shop + "/timeline", customer).status()).isEqualTo(403);
    }

    @Test
    void aSearchNeedsThreeLettersButAShopNumberOrPhoneDigitsWorkAtAnyLength() {
        UUID shop = shop("SALON", "PENDING_VISIT", "Raju Hair", "9200000001");
        long code = jdbc.queryForObject("SELECT shop_code FROM shops WHERE id = ?", Long.class, shop);
        for (String q : List.of("R", "Ra", " a ", "_")) {
            Reply r = get("/admin/shops?type=SALON&group=FIRST_VISIT&q=" + q.strip(), admin);
            assertThat(r.status()).as(q).isEqualTo(400);
            assertThat(field(r.body(), "details")).isEqualTo(Map.of("q", "type at least 3 letters"));
        }
        assertThat(search("FIRST_VISIT", "Raj")).hasSize(1);
        assertThat(search("FIRST_VISIT", "#" + code)).hasSize(1);
        assertThat(search("FIRST_VISIT", Long.toString(code))).hasSize(1);
        assertThat(search("FIRST_VISIT", "7")).isEmpty();   // a short shop number is allowed (none is 7)
        assertThat(search("FIRST_VISIT", "  ")).hasSize(1);   // blank: no search at all
    }

    @Test
    void theDashboardIsKept30SecondsInRedisAndADecisionClearsIt() {
        UUID first = shop("SALON", "PENDING_VISIT", "First Salon", "9300000301");
        Map<?, ?> counts = (Map<?, ?>) get("/admin/dashboard", admin).body();
        assertThat(field(counts.get("SALON"), "FIRST_VISIT")).isEqualTo(1);
        assertThat(redis.opsForValue().get("admin:dashboard:0")).contains("SALON.FIRST_VISIT=1");
        // A shop arriving by itself shows within 30 seconds (the kept counts are served meanwhile)
        shop("SALON", "PENDING_VISIT", "Second Salon", "9300000302");
        assertThat(field(((Map<?, ?>) get("/admin/dashboard", admin).body()).get("SALON"), "FIRST_VISIT"))
                .isEqualTo(1);
        // A decision clears them: the admin sees the new counts at once
        approve(first);
        Map<?, ?> after = (Map<?, ?>) get("/admin/dashboard", admin).body();
        assertThat(field(after.get("SALON"), "FIRST_VISIT")).isEqualTo(1);
        assertThat(field(after.get("SALON"), "LIVE")).isEqualTo(1);
        assertThat(redis.opsForValue().get("admin:dashboard:generation")).isEqualTo("1");
        // A reader that counted before the decision committed and writes afterwards writes under the old generation:
        // nobody reads that key any more
        redis.opsForValue().set("admin:dashboard:0", "KIRANA.FIRST_VISIT=0;KIRANA.RECHECK=0;KIRANA.SENT_BACK=0;"
                + "KIRANA.LIVE=0;KIRANA.SUSPENDED=0;SALON.FIRST_VISIT=2;SALON.RECHECK=0;SALON.SENT_BACK=0;"
                + "SALON.LIVE=0;SALON.SUSPENDED=0");
        assertThat(field(((Map<?, ?>) get("/admin/dashboard", admin).body()).get("SALON"), "LIVE")).isEqualTo(1);
        // Redis losing the text (or a broken value) just counts again
        redis.opsForValue().set("admin:dashboard:1", "garbage");
        assertThat(field(((Map<?, ?>) get("/admin/dashboard", admin).body()).get("SALON"), "LIVE")).isEqualTo(1);
    }

    // ---------------- helpers ----------------

    private UUID person(String phone) {
        UUID id = UUID.randomUUID();
        TestData.user(jdbc, id, phone);
        return id;
    }

    /** A shop with an owner, in a status, set straight in the database (its history starts with the next change). */
    private UUID shop(String type, String status, String name, String ownerPhone) {
        UUID owner = person(ownerPhone);
        UUID id = UUID.randomUUID();
        if ("SALON".equals(type)) {
            TestData.salon(jdbc, id, owner);
        } else {
            TestData.shop(jdbc, id, owner);
        }
        jdbc.update("UPDATE shops SET status = ?, name = ?, updated_at = ? WHERE id = ?", status, name,
                Timestamp.from(CLOCK.instant().plusSeconds(jdbc.queryForObject("SELECT count(*) FROM shops",
                        Long.class))), id);
        return id;
    }

    /** A photo row as an upload would leave it (the stored object itself isn't needed here). */
    private UUID file(UUID shop, UUID by, String purpose) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO files (id, shop_id, purpose, storage_key, content_type, size_bytes, sha256, "
                + "uploaded_by, created_at) VALUES (?, ?, ?, ?, 'image/jpeg', 10, ?, ?, ?)", id, shop, purpose,
                "test/" + id, id.toString().replace("-", "") + "00000000000000000000000000000000".substring(0, 0),
                by, Timestamp.from(NOW));
        return id;
    }

    private void approve(UUID shop) {
        Reply r = post("/admin/shops/" + shop + "/approve", Map.of("version", version(shop)), admin);
        assertThat(r.status()).as(r.text()).isEqualTo(200);
    }

    private void moveThePin(UUID shop) {
        Reply r = patch("/shops/me", Map.of("version", version(shop), "latitude", 17.470000), ownerOf(shop));
        assertThat(field(r.body(), "status")).as(r.text()).isEqualTo("PENDING_VISIT");
    }

    private void submittedAt(UUID shop, Instant at) {
        jdbc.update("INSERT INTO shop_events (id, shop_id, at, by_user, by_admin, event) VALUES (?, ?, ?, ?, false, "
                + "'SUBMITTED')", UUID.randomUUID(), shop, Timestamp.from(at), ownerOf(shop));
        // As ShopReviewStore.addEvent keeps it: when the shop entered its status is its latest such entry
        jdbc.update("UPDATE shops SET status_since = ? WHERE id = ?", Timestamp.from(at), shop);
    }

    /** A salon list, with the search text encoded exactly as a browser would send it. */
    private List<?> search(String group, String q) {
        // As a browser's URLSearchParams does: a space as "+", and "+" itself as %2B
        java.net.URI uri = java.net.URI.create(base + "/admin/shops?type=SALON&group=" + group + (q == null ? ""
                : "&q=" + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8)));
        Reply r = http.get().uri(uri).headers(h -> h.setBearerAuth(token(admin))).exchange((rq, res) -> read(res));
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        return (List<?>) r.body();
    }

    private long version(UUID shop) {
        return jdbc.queryForObject("SELECT version FROM shops WHERE id = ?", Long.class, shop);
    }

    private UUID ownerOf(UUID shop) {
        return jdbc.queryForObject("SELECT user_id FROM shop_members WHERE shop_id = ? AND role = 'OWNER'",
                UUID.class, shop);
    }

    private long count(String fromWhere) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + fromWhere, Long.class);
        return n == null ? 0 : n;
    }

    private String token(UUID who) {
        Boolean isAdmin = jdbc.queryForObject("SELECT admin_role <> 'NONE' FROM users WHERE id = ?", Boolean.class,
                who);
        return jwt.issue(new AuthPrincipal(who, null, null, Boolean.TRUE.equals(isAdmin)),
                sessions.currentGeneration(who)).token();
    }

    private Reply get(String path, UUID who) {
        return call(HttpMethod.GET, path, null, token(who));
    }

    private Reply get(String path, String token) {
        return call(HttpMethod.GET, path, null, token);
    }

    private Reply post(String path, Object body, UUID who) {
        return call(HttpMethod.POST, path, body, token(who));
    }

    private Reply post(String path, Object body, String token) {
        return call(HttpMethod.POST, path, body, token);
    }

    private Reply put(String path, Object body, UUID who) {
        return call(HttpMethod.PUT, path, body, token(who));
    }

    private Reply patch(String path, Object body, UUID who) {
        return call(HttpMethod.PATCH, path, body, token(who));
    }

    private Reply delete(String path, UUID who) {
        return call(HttpMethod.DELETE, path, null, token(who));
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
        return spec.exchange((rq, res) -> read(res));
    }

    private static Reply read(org.springframework.http.client.ClientHttpResponse res) throws java.io.IOException {
        int status = res.getStatusCode().value();
        String text = new String(res.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        Object parsed = text.isBlank() ? Map.of() : JSON.readValue(text, Object.class);
        return new Reply(status, parsed, text);
    }

    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

    private static Object field(Object json, String name) {
        return json instanceof Map<?, ?> m ? m.get(name) : null;
    }

    private record Reply(int status, Object body, String text) {
    }
}
