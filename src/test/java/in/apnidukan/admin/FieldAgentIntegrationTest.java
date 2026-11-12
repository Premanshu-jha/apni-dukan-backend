package in.apnidukan.admin;

import in.apnidukan.common.context.TeamLock;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static in.apnidukan.support.CapturingSenders.EMAILS_SENT;
import static in.apnidukan.support.CapturingSenders.LAST_EMAIL;
import static in.apnidukan.support.CapturingSenders.LAST_SMS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Field agents and the Team page against real Postgres and Redis: adding and editing members (phone, email, name,
 * role, designation, pincodes), which field agent visits each business (the rule, its log, its message), the super
 * admins' assign and move-all, and what a field agent may see and decide. Scenario numbers refer to the "Field
 * agents" section of the admin website's docs/scenarios.md.
 */
@EnabledIf("in.apnidukan.support.TestContainers#dockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({CapturingSenders.class, FieldAgentIntegrationTest.Fakes.class})
class FieldAgentIntegrationTest {

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
        registry.add("app.assignments.slice", () -> 2);   // every list goes through several slices
    }

    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired JwtService jwt;
    @Autowired SessionRevocations sessions;
    @Autowired PlatformTransactionManager transactions;

    RestClient http;
    UUID boss;
    UUID customer;
    int phones;

    @BeforeEach
    void setUp() {
        String base = "http://localhost:" + environment.getProperty("local.server.port") + "/api/v1";
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
        EMAILS_SENT.clear();
        CLOCK.set(NOW);
        phones = 0;
        boss = person("9000000001");
        jdbc.update("UPDATE users SET admin_role = 'SUPER_ADMIN', name = 'Prem' WHERE id = ?", boss);
        customer = person("9000000003");
    }

    // ---------------- adding (scenarios 1-10) ----------------

    @Test
    void aNewPhoneBecomesAFieldAgentWithTheTypedNameEmailAndPincodes() {
        Reply added = post("/admin/team", member("9800000001", "ravi@example.com", "Ravi Kumar", "ADMIN",
                List.of("500084", " 500081 ", "500084")), boss);
        assertThat(added.status()).as(added.text()).isEqualTo(201);
        UUID ravi = id(added);
        assertThat(field(added.body(), "name")).isEqualTo("Ravi Kumar");
        assertThat(field(added.body(), "email")).isEqualTo("ravi@example.com");
        assertThat(field(added.body(), "emailVerified")).isEqualTo(false);   // confirmed at their first sign-in
        assertThat(field(added.body(), "adminRole")).isEqualTo("ADMIN");
        assertThat(field(added.body(), "designation")).isEqualTo("FIELD_AGENT");
        assertThat(field(added.body(), "pincodes")).isEqualTo(List.of("500081", "500084"));   // trimmed, once each
        assertThat(field(added.body(), "pendingVisits")).isEqualTo(0);
        assertThat(LAST_SMS).isEmpty();   // nothing is sent when someone is added
        // They sign in with the phone's code; the website then asks for the email first (not verified yet)
        assertThat(field(get("/admin/me", ravi).body(), "designation")).isEqualTo("FIELD_AGENT");
        assertThat(field(get("/auth/me", ravi).body(), "emailVerified")).isEqualTo(false);
        assertThat(count("admin_events WHERE event = 'ADMIN_ADDED'")).isEqualTo(1);

        // Coverage tab: pincode → agents
        List<?> coverage = (List<?>) get("/admin/team/coverage", boss).body();
        assertThat(coverage).hasSize(2);
        assertThat(field(coverage.get(0), "pincode")).isEqualTo("500081");
        assertThat(field(((List<?>) field(coverage.get(0), "agents")).get(0), "name")).isEqualTo("Ravi Kumar");
    }

    @Test
    void anExistingCustomerAddedTakesTheTypedNameAndEmailAndTheOldAddressIsTold() {
        String held = token(customer);
        Reply added = post("/admin/team", member("9000000003", "work@example.com", "Sita Rao", "ADMIN",
                List.of("500084")), boss);
        assertThat(added.status()).as(added.text()).isEqualTo(201);
        assertThat(id(added)).isEqualTo(customer);   // the same account: their bookings and history stay
        assertThat(jdbc.queryForObject("SELECT email_verified_at IS NULL FROM users WHERE id = ?", Boolean.class,
                customer)).isTrue();
        assertThat(LAST_EMAIL.get("9000000003@test.in")).contains("w***@example.com");   // the old verified address
        assertThat(get("/auth/me", held).status()).isEqualTo(401);   // signed out: rights come with a new sign-in
    }

    @Test
    void theAddFormIsCheckedFieldByField() {
        Reply bad = post("/admin/team", member("12345", "not-an-email", "x", "ADMIN", List.of("012345", "5000")),
                boss);
        assertThat(bad.status()).isEqualTo(400);
        Map<?, ?> details = (Map<?, ?>) field(bad.body(), "details");
        assertThat(details.keySet().stream().map(String::valueOf).toList())
                .containsExactlyInAnyOrder("phone", "email", "name", "pincodes");
        assertThat((String) details.get("pincodes")).contains("012345").contains("5000");

        Reply none = post("/admin/team", member("9800000001", "a@example.com", "Ravi", "ADMIN", List.of()), boss);
        assertThat(field(field(none.body(), "details"), "pincodes")).isEqualTo("add at least one pincode");
        Map<String, Object> noDesignation = new HashMap<>(member("9800000001", "a@example.com", "Ravi", "ADMIN",
                List.of("500084")));
        noDesignation.remove("designation");
        assertThat(field(field(post("/admin/team", noDesignation, boss).body(), "details"), "designation"))
                .isEqualTo("choose a designation");
        Map<String, Object> noRole = new HashMap<>(noDesignation);
        noRole.put("role", "NONE");
        assertThat(field(field(post("/admin/team", noRole, boss).body(), "details"), "role")).isNotNull();

        // A super admin: designation and pincodes sent anyway are ignored
        Reply superAdmin = post("/admin/team", member("9800000002", "b@example.com", "Asha", "SUPER_ADMIN",
                List.of("500084")), boss);
        assertThat(superAdmin.status()).as(superAdmin.text()).isEqualTo(201);
        assertThat(field(superAdmin.body(), "designation")).isNull();
        assertThat(field(superAdmin.body(), "pincodes")).isEqualTo(List.of());
        assertThat(count("admin_pincodes")).isZero();
        assertThat(count("users WHERE admin_role = 'SUPER_ADMIN'")).isEqualTo(2);   // more than one is fine
    }

    @Test
    void addingRefusesAnAdminATestNumberATestEmailAndAnotherAccountsEmail() {
        UUID agent = agent("9800000001", "500084");
        assertThat(code(post("/admin/team", member(phoneOf(agent), "x@example.com", "Again", "ADMIN",
                List.of("500084")), boss))).isEqualTo("ALREADY_ADMIN");
        jdbc.update("INSERT INTO test_phones (phone, label, added_by, added_at) VALUES ('9111111111', 't', ?, ?)",
                boss, Timestamp.from(NOW));
        assertThat(code(post("/admin/team", member("9111111111", "t@example.com", "Tester", "ADMIN",
                List.of("500084")), boss))).isEqualTo("TEST_PHONE");
        jdbc.update("INSERT INTO test_emails (email, label, added_by, added_at) VALUES ('qa@example.com', 't', ?, ?)",
                boss, Timestamp.from(NOW));
        assertThat(code(post("/admin/team", member("9800000005", "QA@example.com", "Quality", "ADMIN",
                List.of("500084")), boss))).isEqualTo("TEST_EMAIL");
        // The customer's verified email belongs to them
        assertThat(code(post("/admin/team", member("9800000006", "9000000003@test.in", "Someone", "ADMIN",
                List.of("500084")), boss))).isEqualTo("EMAIL_IN_USE");
        assertThat(count("users WHERE phone IN ('9800000005', '9800000006')")).isZero();   // nothing half made
    }

    @Test
    void atMost300PincodesAndAtMost500Entries() {
        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < 301; i++) {
            many.add(String.valueOf(100000 + i));
        }
        Reply tooMany = post("/admin/team", member("9800000001", "a@example.com", "Ravi", "ADMIN", many), boss);
        assertThat(field(field(tooMany.body(), "details"), "pincodes")).isEqualTo("at most 300 pincodes");
        // 300 is fine
        Reply ok = post("/admin/team", member("9800000001", "a@example.com", "Ravi", "ADMIN", many.subList(0, 300)),
                boss);
        assertThat(ok.status()).as(ok.text()).isEqualTo(201);
        // More than 500 entries are refused before anything is read
        List<String> huge = new java.util.ArrayList<>(many);
        huge.addAll(many);
        assertThat(post("/admin/team", member("9800000002", "b@example.com", "Sita", "ADMIN", huge), boss).status())
                .isEqualTo(400);
    }

    @Test
    void anEditAddingAPincodePicksUpWhatWaitsThereUnassignedWithOneMessage() {
        UUID ravi = agent("9800000001", "500084");
        UUID a = waitingSalon("500099");
        UUID b = waitingSalon("500099");
        UUID theirs = waitingSalon("500084");
        assertThat(List.of(String.valueOf(assignee(a)), String.valueOf(assignee(b)))).containsOnly("null");
        long before = count("notification_jobs WHERE type = 'FIELD_VISIT_ASSIGNED' AND user_id = '" + ravi + "'");
        put("/admin/team/" + ravi, edit(ravi, Map.of("pincodes", List.of("500084", "500099"))), boss);
        assertThat(List.of(assignee(a), assignee(b), assignee(theirs))).containsOnly(ravi);
        assertThat(lastLog(a)).isEqualTo("COVERAGE_CHANGED");
        assertThat(lastLog(theirs)).isEqualTo("SUBMITTED");   // already theirs: not moved
        assertThat(count("notification_jobs WHERE type = 'FIELD_VISIT_ASSIGNED' AND user_id = '" + ravi + "'"))
                .isEqualTo(before + 1);   // one message for both
    }

    @Test
    void twoSuperAdminsAddingTheSamePhoneAtOnceMakeOneMember() throws Exception {
        UUID second = person("9000000004");
        jdbc.update("UPDATE users SET admin_role = 'SUPER_ADMIN' WHERE id = ?", second);
        Map<String, Object> body = member("9800000009", "same@example.com", "Same Person", "ADMIN",
                List.of("500084"));
        CompletableFuture<Reply> a = CompletableFuture.supplyAsync(() -> post("/admin/team", body, boss));
        CompletableFuture<Reply> b = CompletableFuture.supplyAsync(() -> post("/admin/team", body, second));
        Reply one = a.get(20, TimeUnit.SECONDS);
        Reply two = b.get(20, TimeUnit.SECONDS);
        assertThat(List.of(one.status(), two.status())).containsExactlyInAnyOrder(201, 409);
        assertThat(List.of(String.valueOf(code(one)), String.valueOf(code(two)))).contains("ALREADY_ADMIN");
        assertThat(count("users WHERE phone = '9800000009'")).isEqualTo(1);
    }

    @Test
    void onlySuperAdminsManageTheTeamAssignmentsAndSupport() {
        UUID agent = agent("9800000001", "500084");
        UUID shop = waitingSalon("500084");
        List<Reply> refused = List.of(
                get("/admin/team", agent),
                get("/admin/team/coverage", agent),
                post("/admin/team", member("9800000002", "c@example.com", "C", "ADMIN", List.of("500084")), agent),
                put("/admin/team/" + agent, edit(agent, Map.of()), agent),
                delete("/admin/team/" + boss, agent),
                post("/admin/shops/" + shop + "/assign", Map.of("adminId", agent), agent),
                post("/admin/assignments/move", Map.of("fromAdminId", agent, "toAdminId", agent), agent),
                post("/admin/salon/strikes/lookup", Map.of("phone", "9000000003"), agent),
                put("/admin/test-mode", Map.of("hours", 1), agent));
        for (Reply r : refused) {
            assertThat(r.status()).as(r.text()).isEqualTo(403);
            assertThat(code(r)).isEqualTo("SUPER_ADMIN_ONLY");
        }
        // Support still works for a super admin
        assertThat(post("/admin/salon/strikes/lookup", Map.of("phone", "9000000003"), boss).status()).isEqualTo(200);
    }

    // ---------------- editing (scenarios 11-21) ----------------

    @Test
    void aTypoInThePhoneOrEmailIsFixedAndTheyAreSignedOutEverywhere() {
        UUID agent = agent("9800000001", "500084");
        jdbc.update("UPDATE users SET email = 'old@example.com', email_verified_at = now() WHERE id = ?", agent);
        String held = token(agent);
        Reply fixed = put("/admin/team/" + agent, edit(agent, Map.of("phone", "9800000011", "email",
                "new@example.com")), boss);
        assertThat(fixed.status()).as(fixed.text()).isEqualTo(200);
        assertThat(field(fixed.body(), "phone")).isEqualTo("9800000011");
        assertThat(field(fixed.body(), "emailVerified")).isEqualTo(false);
        assertThat(get("/admin/me", held).status()).isEqualTo(401);
        // One email to the old address naming both changes
        assertThat(CapturingSenders.messageTo(LAST_EMAIL, "old@example.com"))
                .contains("the phone number ending 0011").contains("its email to n***@example.com");
        assertThat(EMAILS_SENT.get("old@example.com")).isEqualTo(1);
        // The old phone is told its account moved, as account recovery tells it
        assertThat(LAST_SMS.get("9800000001")).isEqualTo("account moved to a new number ending 0011");
        // They sign in with the new phone's code; the old phone has no account any more
        post("/auth/otp/send", Map.of("phone", "9800000011"), (String) null);
        Reply in = post("/auth/otp/verify", Map.of("phone", "9800000011", "code", smsCode("9800000011")),
                (String) null);
        assertThat(field(field(in.body(), "user"), "id")).isEqualTo(agent.toString());
        assertThat(count("users WHERE phone = '9800000001'")).isZero();
        assertThat(jdbc.queryForObject("SELECT detail FROM admin_events WHERE event = 'ADMIN_EDITED'", String.class))
                .contains("phone 9800000001 → 9800000011").contains("email");
    }

    @Test
    void aNewPhoneOrEmailOfAnotherAccountIsRefusedAndARenameKeepsThemSignedIn() {
        UUID agent = agent("9800000001", "500084");
        assertThat(code(put("/admin/team/" + agent, edit(agent, Map.of("phone", "9000000003")), boss)))
                .isEqualTo("PHONE_IN_USE");
        assertThat(code(put("/admin/team/" + agent, edit(agent, Map.of("email", "9000000003@test.in")), boss)))
                .isEqualTo("EMAIL_IN_USE");
        String held = token(agent);
        Reply renamed = put("/admin/team/" + agent, edit(agent, Map.of("name", "Ravi K")), boss);
        assertThat(field(renamed.body(), "name")).isEqualTo("Ravi K");
        assertThat(get("/admin/me", held).status()).isEqualTo(200);
    }

    @Test
    void anEditMadeOnAnOldVersionIsRefused() {
        UUID agent = agent("9800000001", "500084");
        Map<String, Object> stale = edit(agent, Map.of("name", "First"));
        assertThat(put("/admin/team/" + agent, stale, boss).status()).isEqualTo(200);
        Map<String, Object> second = new HashMap<>(stale);
        second.put("name", "Second");
        assertThat(code(put("/admin/team/" + agent, second, boss))).isEqualTo("MEMBER_CHANGED");
        assertThat(jdbc.queryForObject("SELECT name FROM users WHERE id = ?", String.class, agent)).isEqualTo("First");
        // Checked before anything else: a stale edit to a phone already in use says MEMBER_CHANGED, not PHONE_IN_USE
        second.put("phone", "9000000003");
        assertThat(code(put("/admin/team/" + agent, second, boss))).isEqualTo("MEMBER_CHANGED");
    }

    @Test
    void aStaleEditNeverWritesBackDetailsTheMemberChangedThemselves() {
        UUID agent = agent("9800000001", "500084");
        Map<String, Object> opened = edit(agent, Map.of());   // the form, as the super admin opened it
        // Meanwhile they rename themselves in their own app
        assertThat(patch("/auth/me", Map.of("name", "Ravi Changed"), agent).status()).isEqualTo(200);
        Map<String, Object> pincodesOnly = new HashMap<>(opened);
        pincodesOnly.put("pincodes", List.of("500084", "500081"));
        assertThat(code(put("/admin/team/" + agent, pincodesOnly, boss))).isEqualTo("MEMBER_CHANGED");
        assertThat(jdbc.queryForObject("SELECT name FROM users WHERE id = ?", String.class, agent))
                .isEqualTo("Ravi Changed");
    }

    @Test
    void removingAPincodeSendsItsWaitingBusinessesToAnotherAgentOrUnassigned() {
        UUID ravi = agent("9800000001", "500084", "500081");
        UUID waiting = waitingSalon("500084");
        UUID other = waitingSalon("500081");
        UUID visited = waitingSalon("500084");
        assertThat(assignee(waiting)).isEqualTo(ravi);
        approveAs(visited, ravi);

        // 500084 removed and nobody else covers it: Unassigned; 500081 stays Ravi's
        assertThat(put("/admin/team/" + ravi, edit(ravi, Map.of("pincodes", List.of("500081"))), boss).status())
                .isEqualTo(200);
        assertThat(assignee(waiting)).isNull();
        assertThat(assignee(other)).isEqualTo(ravi);
        assertThat(assignee(visited)).isEqualTo(ravi);   // already decided: keeps who visited it
        assertThat(lastLog(waiting)).isEqualTo("COVERAGE_CHANGED");

        // A new agent covering 500084 picks up what waits there unassigned, then gets new ones too
        UUID sita = agent("9800000002", "500084");
        assertThat(assignee(waiting)).isEqualTo(sita);
        assertThat(lastLog(waiting)).isEqualTo("COVERAGE_CHANGED");
        UUID next = waitingSalon("500084");
        // Sita's 500084 removed while Ravi covers it again: hers in 500084 go to him
        put("/admin/team/" + ravi, edit(ravi, Map.of("pincodes", List.of("500081", "500084"))), boss);
        put("/admin/team/" + sita, edit(sita, Map.of("pincodes", List.of("500001"))), boss);
        assertThat(assignee(next)).isEqualTo(ravi);
        assertThat(assignee(waiting)).isEqualTo(ravi);
    }

    @Test
    void removingAnUnrelatedPincodeLeavesBusinessesASuperAdminSentElsewhere() {
        UUID ravi = agent("9800000001", "500084");
        UUID sita = agent("9800000002", "500001", "500002");
        UUID shop = waitingSalon("500084");
        assertThat(post("/admin/shops/" + shop + "/assign", Map.of("adminId", sita), boss).status()).isEqualTo(204);
        put("/admin/team/" + sita, edit(sita, Map.of("pincodes", List.of("500001"))), boss);
        assertThat(assignee(shop)).isEqualTo(sita);   // 500084 was never hers to lose
        // Made a super admin: all of hers go, that one too
        put("/admin/team/" + sita, edit(sita, Map.of("role", "SUPER_ADMIN")), boss);
        assertThat(assignee(shop)).isEqualTo(ravi);
    }

    @Test
    void anAgentMadeASuperAdminOrRemovedHandsOnTheirWaitingBusinesses() {
        UUID ravi = agent("9800000001", "500084");
        UUID sita = agent("9800000002", "500084");
        UUID first = waitingSalon("500084");
        UUID second = waitingSalon("500084");
        assertThat(List.of(assignee(first), assignee(second))).containsExactlyInAnyOrder(ravi, sita);
        UUID ravis = ravi.equals(assignee(first)) ? first : second;

        Reply promoted = put("/admin/team/" + ravi, edit(ravi, Map.of("role", "SUPER_ADMIN")), boss);
        assertThat(field(promoted.body(), "designation")).isNull();
        assertThat(field(promoted.body(), "pincodes")).isEqualTo(List.of());
        assertThat(assignee(ravis)).isEqualTo(sita);

        // Back to Admin needs a designation and pincodes
        Map<String, Object> back = edit(ravi, Map.of("role", "ADMIN", "pincodes", List.of()));
        assertThat(field(field(put("/admin/team/" + ravi, back, boss).body(), "details"), "pincodes")).isNotNull();

        // Removed (by another super admin; never yourself): Sita's waiting ones go to nobody (no one covers 500084)
        assertThat(code(delete("/admin/team/" + boss, boss))).isEqualTo("CANT_REMOVE_YOURSELF");
        // A super admin who was demoted meanwhile can't act any more (checked again under the team lock)
        UUID other = person("9000000004");
        jdbc.update("UPDATE users SET admin_role = 'SUPER_ADMIN' WHERE id = ?", other);
        String othersToken = token(other);
        jdbc.update("UPDATE users SET admin_role = 'ADMIN', admin_designation = 'FIELD_AGENT' WHERE id = ?", other);
        assertThat(code(delete("/admin/team/" + sita, othersToken))).isEqualTo("SUPER_ADMIN_ONLY");
        assertThat(delete("/admin/team/" + sita, boss).status()).isEqualTo(204);
        assertThat(assignee(first)).isNull();
        assertThat(assignee(second)).isNull();
        assertThat(count("admin_pincodes")).isZero();
        assertThat(delete("/admin/team/" + ravi, boss).status()).isEqualTo(204);   // a super admin can be removed
    }

    @Test
    void aFieldAgentDeletingTheirAccountHandsOnTheirWaitingBusinesses() {
        UUID ravi = agent("9800000001", "500084");
        UUID shop = waitingSalon("500084");
        UUID sita = agent("9800000002", "500084");
        post("/auth/me/delete-code", Map.of(), ravi);
        Reply deleted = post("/auth/me/delete", Map.of("code", smsCode("9800000001")), ravi);
        assertThat(deleted.status()).as(deleted.text()).isEqualTo(204);
        assertThat(assignee(shop)).isEqualTo(sita);
        assertThat(lastLog(shop)).isEqualTo("ADMIN_REMOVED");
        assertThat(count("admin_pincodes WHERE user_id = '" + ravi + "'")).isZero();
    }

    // ---------------- the rule (scenarios 22-30) ----------------

    @Test
    void aSubmittedBusinessGoesToTheLeastLoadedAgentCoveringItsPincodeElseUnassigned() {
        UUID ravi = agent("9800000001", "500084");
        UUID sita = agent("9800000002", "500084");
        agent("9800000003", "500081");   // another pincode: never picked
        UUID first = waitingSalon("500084");
        assertThat(assignee(first)).isEqualTo(ravi);   // a tie: the longest on the team
        UUID second = waitingSalon("500084");
        assertThat(assignee(second)).isEqualTo(sita);   // fewer waiting
        UUID nobody = waitingSalon("500099");
        assertThat(assignee(nobody)).isNull();
        assertThat(lastLog(nobody)).isEqualTo("SUBMITTED");
        assertThat(jdbc.queryForObject("SELECT to_admin IS NULL FROM shop_assignments WHERE shop_id = ?",
                Boolean.class, nobody)).isTrue();
        // The new agent gets a message
        assertThat(count("notification_jobs WHERE type = 'FIELD_VISIT_ASSIGNED' AND user_id = '" + ravi + "'"))
                .isEqualTo(1);
        // The team page counts what is waiting
        List<?> team = (List<?>) get("/admin/team", boss).body();
        assertThat(team.stream().filter(m -> ravi.toString().equals(field(m, "userId"))).findFirst()
                .map(m -> field(m, "pendingVisits")).orElseThrow()).isEqualTo(1);
    }

    @Test
    void anAgentWhoWorksInTheBusinessIsNeverPicked() {
        UUID ravi = agent("9800000001", "500084");
        UUID shop = draftSalon("500084");
        TestData.member(jdbc, shop, ravi, "HELPER");
        submit(shop);
        assertThat(assignee(shop)).isNull();
        UUID sita = agent("9800000002", "500084");
        UUID next = draftSalon("500084");
        // He moves to work in the next one (someone works in one business at a time)
        jdbc.update("DELETE FROM shop_members WHERE user_id = ?", ravi);
        TestData.member(jdbc, next, ravi, "HELPER");
        submit(next);
        assertThat(assignee(next)).isEqualTo(sita);
    }

    @Test
    void aReCheckAPincodeChangeAndAReinstateAssignByTheRule() {
        UUID ravi = agent("9800000001", "500084");
        UUID sita = agent("9800000002", "500081");
        UUID shop = waitingSalon("500084");
        approveAs(shop, ravi);
        // An approved salon's owner gives a new GSTIN: it stays live and waits for a re-check, by the rule
        Reply edited = patch("/shops/me", Map.of("version", version(shop), "gstin", "27AAPFU0939F1ZV"), ownerOf(shop));
        assertThat(field(edited.body(), "status")).as(edited.text()).isEqualTo("APPROVED");
        assertThat(field(edited.body(), "recheckSince")).isNotNull();
        assertThat(assignee(shop)).isEqualTo(ravi);
        assertThat(lastLog(shop)).isEqualTo("RECHECK");
        // While it waits the field team moves it to a pincode Sita covers (the owner can't: verified), then back
        assertThat(field(patch("/shops/me", Map.of("version", version(shop), "pincode", "500081"), ownerOf(shop))
                .body(), "code")).isEqualTo("ADDRESS_LOCKED");
        moveTo(shop, "500081");
        assertThat(assignee(shop)).isEqualTo(sita);
        assertThat(lastLog(shop)).isEqualTo("PINCODE_CHANGED");
        moveTo(shop, "500084");
        assertThat(assignee(shop)).isEqualTo(ravi);
        // A pincode change to one the same agent covers keeps them
        jdbc.update("INSERT INTO admin_pincodes (user_id, pincode, added_at) VALUES (?, '500085', ?)", ravi,
                Timestamp.from(NOW));
        long logs = count("shop_assignments WHERE shop_id = '" + shop + "'");
        moveTo(shop, "500085");
        assertThat(assignee(shop)).isEqualTo(ravi);
        assertThat(count("shop_assignments WHERE shop_id = '" + shop + "'")).isEqualTo(logs);
        // A field agent may move only a business assigned to them
        assertThat(patch("/admin/shops/" + shop + "/address", Map.of("version", version(shop), "addressLine",
                "Shop 4, Main Road", "area", "Kondapur", "city", "Hyderabad", "pincode", "500085", "latitude", 17.47,
                "longitude", 78.364), sita).status()).isEqualTo(404);

        // Suspended, then reinstated by a super admin: assigned again
        assertThat(post("/admin/shops/" + shop + "/suspend", Map.of("version", version(shop), "reason",
                "Found shut at the visit"), boss).status()).isEqualTo(200);
        long messages = count("notification_jobs WHERE type = 'FIELD_VISIT_ASSIGNED' AND user_id = '" + ravi + "'");
        assertThat(post("/admin/shops/" + shop + "/reinstate", Map.of("version", version(shop)), boss).status())
                .isEqualTo(200);
        assertThat(assignee(shop)).isEqualTo(ravi);   // back with the same agent: logged and told again
        assertThat(lastLog(shop)).isEqualTo("REINSTATED");
        assertThat(count("notification_jobs WHERE type = 'FIELD_VISIT_ASSIGNED' AND user_id = '" + ravi + "'"))
                .isEqualTo(messages + 1);
    }

    @Test
    void sentBackAndSubmittedAgainGoesBackToTheSameAgentWhoIsToldAgain() {
        UUID ravi = agent("9800000001", "500084");
        UUID sita = agent("9800000002", "500084");
        UUID shop = waitingSalon("500084");
        assertThat(assignee(shop)).isEqualTo(ravi);
        assertThat(post("/admin/shops/" + shop + "/reject", Map.of("version", version(shop), "reason",
                "The name board isn't visible"), ravi).status()).isEqualTo(200);
        submit(shop);
        // Ravi isn't held back by this business itself (still counted as his while it was decided)
        assertThat(assignee(shop)).isEqualTo(ravi);
        assertThat(lastLog(shop)).isEqualTo("SUBMITTED");
        assertThat(count("notification_jobs WHERE type = 'FIELD_VISIT_ASSIGNED' AND user_id = '" + ravi + "'"))
                .isEqualTo(2);
        assertThat(count("notification_jobs WHERE type = 'FIELD_VISIT_ASSIGNED' AND user_id = '" + sita + "'"))
                .isZero();
    }

    @Test
    void anAgentWhoJoinsABusinessAssignedToThemHandsItOn() {
        UUID ravi = agent("9800000001", "500084");
        UUID sita = agent("9800000002", "500084");
        jdbc.update("UPDATE users SET email_verified_at = now() WHERE id IN (?, ?)", ravi, sita);
        UUID shop = waitingSalon("500084");
        assertThat(assignee(shop)).isEqualTo(ravi);
        Reply invited = post("/shops/me/helpers", Map.of("phone", "9800000001"), ownerOf(shop));
        assertThat(invited.status()).as(invited.text()).isEqualTo(202);
        String invite = (String) field(field(invited.body(), "invite"), "id");
        Reply joined = post("/me/invites/" + invite + "/accept", Map.of(), ravi);
        assertThat(joined.status()).as(joined.text()).isEqualTo(200);
        assertThat(assignee(shop)).isEqualTo(sita);
    }

    @Test
    void anAgentWhoTakesOverABusinessAssignedToThemHandsItOn() {
        UUID ravi = agent("9800000001", "500084");
        UUID sita = agent("9800000002", "500084");
        jdbc.update("UPDATE users SET email_verified_at = now() WHERE id IN (?, ?)", ravi, sita);
        UUID shop = waitingSalon("500084");
        assertThat(assignee(shop)).isEqualTo(ravi);
        Reply asked = post("/shops/me/handover", Map.of("phone", "9800000001", "version", version(shop)),
                ownerOf(shop));
        assertThat(asked.status()).as(asked.text()).isEqualTo(202);
        List<?> waiting = (List<?>) get("/me/handover", ravi).body();
        Reply taken = post("/me/handover/" + field(waiting.get(0), "id") + "/accept", null, ravi);
        assertThat(taken.status()).as(taken.text()).isEqualTo(200);
        assertThat(assignee(shop)).isEqualTo(sita);
    }

    @Test
    void longListsGoThroughEverySlice() {
        // app.assignments.slice is 2 here: five businesses take three slices each time
        UUID ravi = agent("9800000001", "500084");
        UUID sita = agent("9800000002", "500001");
        List<UUID> shops = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            shops.add(waitingSalon("500084"));
        }
        assertThat(shops).allSatisfy(s -> assertThat(assignee(s)).isEqualTo(ravi));
        jdbc.update("DELETE FROM notification_jobs");

        // Move all: every one moves, one message
        Reply moved = post("/admin/assignments/move", Map.of("fromAdminId", ravi, "toAdminId", sita), boss);
        assertThat(field(moved.body(), "moved")).isEqualTo(5);
        assertThat(shops).allSatisfy(s -> assertThat(assignee(s)).isEqualTo(sita));
        assertThat(count("notification_jobs WHERE type = 'FIELD_VISIT_ASSIGNED' AND user_id = '" + sita + "'"))
                .isEqualTo(1);

        // Sita doesn't cover 500084: removing her other pincode changes nothing for them...
        assertThat(put("/admin/team/" + sita, edit(sita, Map.of("pincodes", List.of("500002"))), boss).status())
                .isEqualTo(200);
        assertThat(shops).allSatisfy(s -> assertThat(assignee(s)).isEqualTo(sita));
        // ...making her a super admin hands every one on: Ravi covers 500084
        jdbc.update("DELETE FROM notification_jobs");
        assertThat(put("/admin/team/" + sita, edit(sita, Map.of("role", "SUPER_ADMIN")), boss).status())
                .isEqualTo(200);
        assertThat(shops).allSatisfy(s -> assertThat(assignee(s)).isEqualTo(ravi));
        assertThat(count("notification_jobs WHERE type = 'FIELD_VISIT_ASSIGNED' AND user_id = '" + ravi + "'"))
                .isEqualTo(1);

        // Ravi stops covering it: all five unassigned; then a new agent covering it picks up all five, one message
        assertThat(put("/admin/team/" + ravi, edit(ravi, Map.of("pincodes", List.of("500001"))), boss).status())
                .isEqualTo(200);
        assertThat(shops).allSatisfy(s -> assertThat(assignee(s)).isNull());
        jdbc.update("DELETE FROM notification_jobs");
        UUID gita = agent("9800000003", "500001");
        assertThat(put("/admin/team/" + gita, edit(gita, Map.of("pincodes", List.of("500001", "500084"))), boss)
                .status()).isEqualTo(200);
        assertThat(shops).allSatisfy(s -> assertThat(assignee(s)).isEqualTo(gita));
        assertThat(count("notification_jobs WHERE type = 'FIELD_VISIT_ASSIGNED' AND user_id = '" + gita + "'"))
                .isEqualTo(1);
    }

    @Test
    void aPincodeAddedThatStillCantPlaceABusinessLogsNothing() {
        UUID ravi = agent("9800000001", "500001");
        UUID shop = waitingSalon("500084");   // nobody covers it
        TestData.member(jdbc, shop, ravi, "HELPER");   // and Ravi works there
        long logs = count("shop_assignments WHERE shop_id = '" + shop + "'");
        assertThat(put("/admin/team/" + ravi, edit(ravi, Map.of("pincodes", List.of("500001", "500084"))), boss)
                .status()).isEqualTo(200);
        assertThat(assignee(shop)).isNull();
        assertThat(count("shop_assignments WHERE shop_id = '" + shop + "'")).isEqualTo(logs);
    }

    @Test
    void aSuperAdminDemotedWhileTheirChangeWaitsForTheTeamLockIsRefused() throws Exception {
        UUID ravi = agent("9800000001", "500084");
        UUID sita = agent("9800000002", "500084");
        UUID shop = waitingSalon("500084");
        UUID other = person("9000000004");
        jdbc.update("UPDATE users SET admin_role = 'SUPER_ADMIN' WHERE id = ?", other);
        String othersToken = token(other);

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> demotion = CompletableFuture.runAsync(() -> new TransactionTemplate(transactions)
                .executeWithoutResult(s -> {
                    TeamLock.exclusive(jdbc);   // as another super admin's Team-page edit takes it
                    jdbc.update("UPDATE users SET admin_role = 'ADMIN', admin_designation = 'FIELD_AGENT' "
                            + "WHERE id = ?", other);
                    locked.countDown();
                    try {
                        release.await(20, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
        assertThat(locked.await(20, TimeUnit.SECONDS)).isTrue();
        // Each passes the first check (the demotion isn't committed yet) and waits for the team lock
        List<CompletableFuture<Reply>> waiting = List.of(
                CompletableFuture.supplyAsync(() -> post("/admin/team",
                        member("9800000009", "n@example.com", "New Person", "ADMIN", List.of("500084")), othersToken)),
                CompletableFuture.supplyAsync(() -> call(HttpMethod.PUT, "/admin/team/" + ravi,
                        edit(ravi, Map.of("name", "Ravi K")), othersToken)),
                CompletableFuture.supplyAsync(() -> delete("/admin/team/" + sita, othersToken)),
                CompletableFuture.supplyAsync(() -> post("/admin/shops/" + shop + "/assign",
                        Map.of("adminId", sita), othersToken)),
                CompletableFuture.supplyAsync(() -> post("/admin/assignments/move",
                        Map.of("fromAdminId", ravi, "toAdminId", sita), othersToken)));
        Thread.sleep(500);
        assertThat(waiting).noneMatch(CompletableFuture::isDone);
        release.countDown();
        demotion.get(20, TimeUnit.SECONDS);
        for (CompletableFuture<Reply> f : waiting) {
            Reply r = f.get(20, TimeUnit.SECONDS);
            assertThat(r.status()).as(r.text()).isEqualTo(403);
            assertThat(code(r)).isEqualTo("SUPER_ADMIN_ONLY");
        }
        assertThat(count("users WHERE phone = '9800000009'")).isZero();
        assertThat(jdbc.queryForObject("SELECT name FROM users WHERE id = ?", String.class, ravi)).isNotEqualTo("Ravi K");
        assertThat(count("admin_pincodes WHERE user_id = '" + sita + "'")).isEqualTo(1);
        assertThat(assignee(shop)).isEqualTo(ravi);
    }

    @Test
    void aBusinessSubmittedWhileItsPincodeIsBeingTakenAwayNeverLandsWithThatAgent() throws Exception {
        UUID ravi = agent("9800000001", "500084");
        UUID sita = agent("9800000002", "500084");
        // Sita has more waiting, so Ravi would be picked
        jdbc.update("INSERT INTO admin_pincodes (user_id, pincode, added_at) VALUES (?, '500001', ?)", sita,
                Timestamp.from(NOW));
        waitingSalon("500001");
        UUID shop = draftSalon("500084");

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> teamChange = CompletableFuture.runAsync(() -> new TransactionTemplate(transactions)
                .executeWithoutResult(s -> {
                    TeamLock.exclusive(jdbc);   // as a Team-page edit takes it
                    jdbc.update("DELETE FROM admin_pincodes WHERE user_id = ? AND pincode = '500084'", ravi);
                    locked.countDown();
                    try {
                        release.await(20, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
        assertThat(locked.await(20, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Reply> submitted = CompletableFuture.supplyAsync(() -> post("/shops/me/submit",
                Map.of("version", version(shop)), ownerOf(shop)));
        Thread.sleep(500);
        assertThat(submitted).isNotDone();   // waits for the team change
        release.countDown();
        teamChange.get(20, TimeUnit.SECONDS);
        assertThat(submitted.get(20, TimeUnit.SECONDS).status()).isEqualTo(200);
        assertThat(assignee(shop)).isEqualTo(sita);
    }

    // ---------------- assign and move all (scenarios 31-35) ----------------

    @Test
    void aSuperAdminAssignsAnyWaitingBusinessToAnyFieldAgent() {
        UUID ravi = agent("9800000001", "500084");
        UUID sita = agent("9800000002", "500001");
        UUID shop = waitingSalon("500084");
        String ravisToken = token(ravi);
        assertThat(get("/admin/shops/" + shop, ravisToken).status()).isEqualTo(200);

        // Outside Sita's pincodes is fine for a super admin
        assertThat(post("/admin/shops/" + shop + "/assign", Map.of("adminId", sita), boss).status()).isEqualTo(204);
        assertThat(assignee(shop)).isEqualTo(sita);
        assertThat(lastLog(shop)).isEqualTo("MANUAL");
        assertThat(get("/admin/shops/" + shop, ravisToken).status()).isEqualTo(404);   // Ravi loses it at once
        assertThat(count("notification_jobs WHERE type = 'FIELD_VISIT_ASSIGNED' AND user_id = '" + sita + "'"))
                .isEqualTo(1);
        // Again to the same agent: nothing changes, no second message
        post("/admin/shops/" + shop + "/assign", Map.of("adminId", sita), boss);
        assertThat(count("shop_assignments WHERE shop_id = '" + shop + "' AND reason = 'MANUAL'")).isEqualTo(1);

        assertThat(code(post("/admin/shops/" + shop + "/assign", Map.of("adminId", boss), boss)))
                .isEqualTo("NOT_A_FIELD_AGENT");
        assertThat(code(post("/admin/shops/" + shop + "/assign", Map.of("adminId", customer), boss)))
                .isEqualTo("NOT_A_FIELD_AGENT");
        TestData.member(jdbc, shop, ravi, "HELPER");
        assertThat(code(post("/admin/shops/" + shop + "/assign", Map.of("adminId", ravi), boss)))
                .isEqualTo("OWN_SHOP");
        approveAs(shop, sita);
        assertThat(code(post("/admin/shops/" + shop + "/assign", Map.of("adminId", sita), boss)))
                .isEqualTo("WRONG_STATUS");
        assertThat(post("/admin/shops/" + UUID.randomUUID() + "/assign", Map.of("adminId", sita), boss).status())
                .isEqualTo(404);
    }

    @Test
    void moveAllHandsEveryWaitingBusinessToAnotherAgentExceptOnesTheyWorkIn() {
        UUID ravi = agent("9800000001", "500084");
        UUID sita = agent("9800000002", "500001");
        UUID a = waitingSalon("500084");
        UUID b = waitingSalon("500084");
        UUID theirs = waitingSalon("500084");
        TestData.member(jdbc, theirs, sita, "HELPER");
        UUID done = waitingSalon("500084");
        approveAs(done, ravi);
        Reply moved = post("/admin/assignments/move", Map.of("fromAdminId", ravi, "toAdminId", sita), boss);
        assertThat(moved.status()).as(moved.text()).isEqualTo(200);
        assertThat(field(moved.body(), "moved")).isEqualTo(2);
        assertThat(field(moved.body(), "stayed")).isEqualTo(1);
        assertThat(List.of(assignee(a), assignee(b), assignee(theirs), assignee(done)))
                .containsExactly(sita, sita, ravi, ravi);
        // One message for the move, not one per business
        assertThat(count("notification_jobs WHERE type = 'FIELD_VISIT_ASSIGNED' AND user_id = '" + sita + "'"))
                .isEqualTo(1);
        assertThat(code(post("/admin/assignments/move", Map.of("fromAdminId", ravi, "toAdminId", ravi), boss)))
                .isEqualTo("VALIDATION_FAILED");
        assertThat(code(post("/admin/assignments/move", Map.of("fromAdminId", ravi, "toAdminId", boss), boss)))
                .isEqualTo("NOT_A_FIELD_AGENT");
    }

    // ---------------- what a field agent sees (scenarios 36-43) ----------------

    @Test
    void aFieldAgentSeesAndDecidesOnlyTheirOwnBusinesses() {
        UUID ravi = agent("9800000001", "500084");
        UUID sita = agent("9800000002", "500081");
        UUID mine = waitingSalon("500084");
        UUID hers = waitingSalon("500081");
        UUID nobodys = waitingSalon("500099");

        List<?> list = (List<?>) get("/admin/shops?type=SALON&group=FIRST_VISIT", ravi).body();
        assertThat(list).hasSize(1);
        assertThat(field(list.get(0), "id")).isEqualTo(mine.toString());
        assertThat(field(field(list.get(0), "assignedTo"), "name")).isEqualTo("Agent 9800000001");
        // Asking for someone else's or the unassigned ones still gives only theirs
        assertThat((List<?>) get("/admin/shops?type=SALON&group=FIRST_VISIT&assignedTo=" + sita, ravi).body())
                .hasSize(1);
        assertThat((List<?>) get("/admin/shops?type=SALON&group=FIRST_VISIT&unassigned=true", ravi).body())
                .hasSize(1);
        assertThat(field(((Map<?, ?>) get("/admin/dashboard", ravi).body()).get("SALON"), "FIRST_VISIT"))
                .isEqualTo(1);

        for (UUID other : List.of(hers, nobodys)) {
            List<Reply> refused = List.of(get("/admin/shops/" + other, ravi),
                    get("/admin/shops/" + other + "/timeline", ravi),
                    post("/admin/shops/" + other + "/approve", Map.of("version", version(other)), ravi),
                    post("/admin/shops/" + other + "/reject", Map.of("version", version(other), "reason",
                            "Board not visible"), ravi),
                    post("/admin/shops/" + other + "/ask-to-fix", Map.of("version", version(other), "reason",
                            "Board not visible"), ravi),
                    post("/admin/shops/" + other + "/notes", Map.of("note", "Was here"), ravi));
            for (Reply r : refused) {
                assertThat(r.status()).as(r.text()).isEqualTo(404);
            }
        }
        assertThat(jdbc.queryForObject("SELECT status FROM shops WHERE id = ?", String.class, hers))
                .isEqualTo("PENDING_VISIT");
        approveAs(mine, ravi);
        // Decided: still theirs to look at
        assertThat(get("/admin/shops/" + mine, ravi).status()).isEqualTo(200);

        // A super admin sees everything, and can narrow the list
        assertThat((List<?>) get("/admin/shops?type=SALON&group=FIRST_VISIT", boss).body()).hasSize(2);
        List<?> unassigned = (List<?>) get("/admin/shops?type=SALON&group=FIRST_VISIT&unassigned=true", boss)
                .body();
        assertThat(unassigned).hasSize(1);
        assertThat(field(unassigned.get(0), "assignedTo")).isNull();
        assertThat((List<?>) get("/admin/shops?type=SALON&group=FIRST_VISIT&assignedTo=" + sita, boss).body())
                .hasSize(1);
        assertThat(code(get("/admin/shops?type=SALON&group=FIRST_VISIT&unassigned=true&assignedTo=" + sita, boss)))
                .isEqualTo("VALIDATION_FAILED");
        // Unassigned means waiting with no agent: not a filter for decided groups
        assertThat(field(field(get("/admin/shops?type=SALON&group=LIVE&unassigned=true", boss).body(), "details"),
                "unassigned")).isEqualTo("only for FIRST_VISIT and RECHECK");
    }

    @Test
    void aDecisionWaitingForAReassignmentIsRefusedAfterIt() throws Exception {
        UUID ravi = agent("9800000001", "500084");
        UUID sita = agent("9800000002", "500001");
        UUID shop = waitingSalon("500084");
        long v = version(shop);
        // A reassignment holds the business's row; Ravi's approve waits for it, then finds it isn't his
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> reassign = CompletableFuture.runAsync(() -> new TransactionTemplate(transactions)
                .executeWithoutResult(s -> {
                    jdbc.queryForList("SELECT id FROM shops WHERE id = ? FOR UPDATE", shop);
                    jdbc.update("UPDATE shops SET assigned_admin_id = ? WHERE id = ?", sita, shop);
                    locked.countDown();
                    try {
                        release.await(20, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
        assertThat(locked.await(20, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Reply> approve = CompletableFuture.supplyAsync(() -> post("/admin/shops/" + shop
                + "/approve", Map.of("version", v), ravi));
        Thread.sleep(500);
        release.countDown();
        reassign.get(20, TimeUnit.SECONDS);
        assertThat(approve.get(20, TimeUnit.SECONDS).status()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT status FROM shops WHERE id = ?", String.class, shop))
                .isEqualTo("PENDING_VISIT");
    }

    @Test
    void anAdminWithoutPincodesSeesNothingUntilSomeAreAdded() {
        // As an admin from before field agents: Admin with no pincodes
        UUID old = person("9800000001");
        jdbc.update("UPDATE users SET admin_role = 'ADMIN', admin_designation = 'FIELD_AGENT' WHERE id = ?", old);
        UUID shop = waitingSalon("500084");
        assertThat(assignee(shop)).isNull();
        assertThat((List<?>) get("/admin/shops?type=SALON&group=FIRST_VISIT", old).body()).isEmpty();
        assertThat(get("/admin/shops/" + shop, old).status()).isEqualTo(404);
        Reply team = get("/admin/team", boss);
        Object member = ((List<?>) team.body()).stream().filter(m -> old.toString().equals(field(m, "userId")))
                .findFirst().orElseThrow();
        assertThat(field(member, "pincodes")).isEqualTo(List.of());
        assertThat(field(member, "designation")).isEqualTo("FIELD_AGENT");
    }

    // ---------------- helpers ----------------

    private UUID person(String phone) {
        UUID id = UUID.randomUUID();
        TestData.user(jdbc, id, phone);
        return id;
    }

    /** A field agent added through the Team page, named "Agent <phone>", covering these pincodes. */
    private UUID agent(String phone, String... pincodes) {
        CLOCK.set(CLOCK.instant().plusSeconds(1));   // each joins later than the one before (tie order)
        Reply r = post("/admin/team", member(phone, "agent" + phone + "@example.com", "Agent " + phone, "ADMIN",
                List.of(pincodes)), boss);
        assertThat(r.status()).as(r.text()).isEqualTo(201);
        return id(r);
    }

    private static Map<String, Object> member(String phone, String email, String name, String role,
                                              List<String> pincodes) {
        Map<String, Object> m = new HashMap<>();
        m.put("phone", phone);
        m.put("email", email);
        m.put("name", name);
        m.put("role", role);
        m.put("designation", "FIELD_AGENT");
        m.put("pincodes", pincodes);
        return m;
    }

    /** The member as the Team page shows them, with these fields changed, and their version. */
    private Map<String, Object> edit(UUID who, Map<String, Object> changes) {
        Map<?, ?> shown = (Map<?, ?>) ((List<?>) get("/admin/team", boss).body()).stream()
                .filter(m -> who.toString().equals(field(m, "userId"))).findFirst().orElseThrow();
        Map<String, Object> body = new HashMap<>();
        body.put("version", shown.get("version"));
        body.put("phone", shown.get("phone"));
        body.put("email", shown.get("email") == null ? "x" + shown.get("phone") + "@example.com" : shown.get("email"));
        body.put("name", shown.get("name") == null ? "Someone" : shown.get("name"));
        body.put("role", shown.get("adminRole"));
        body.put("designation", shown.get("designation"));
        body.put("pincodes", shown.get("pincodes"));
        body.putAll(changes);
        return body;
    }

    private String phoneOf(UUID who) {
        return jdbc.queryForObject("SELECT phone FROM users WHERE id = ?", String.class, who);
    }

    /** A DRAFT salon in this pincode, with its photos and video, ready to submit. */
    private UUID draftSalon(String pincode) {
        phones++;
        UUID owner = person("92000" + String.format("%05d", phones));
        UUID id = UUID.randomUUID();
        TestData.salon(jdbc, id, owner);
        jdbc.update("UPDATE shops SET pincode = ?, name = ? WHERE id = ?", pincode, "Salon " + phones, id);
        UUID ownerPhoto = file(id, owner, "OWNER_PHOTO", "image/jpeg");
        UUID shopPhoto = file(id, owner, "SHOP_PHOTO", "image/jpeg");
        UUID video = file(id, owner, "SHOP_VIDEO", "video/mp4");
        jdbc.update("UPDATE shops SET owner_photo_file_id = ?, shop_photo_file_id = ?, shop_video_file_id = ? "
                + "WHERE id = ?", ownerPhoto, shopPhoto, video, id);
        TestData.setUpForVisit(jdbc, id, owner);
        return id;
    }

    /** A salon submitted by its owner (so the rule assigns it). */
    private UUID waitingSalon(String pincode) {
        UUID id = draftSalon(pincode);
        submit(id);
        return id;
    }

    private void submit(UUID shop) {
        Reply r = post("/shops/me/submit", Map.of("version", version(shop)), ownerOf(shop));
        assertThat(field(r.body(), "status")).as(r.text()).isEqualTo("PENDING_VISIT");
    }

    /** The field team (a super admin) moves the business to this pincode, the rest of its address as it is. */
    private void moveTo(UUID shop, String pincode) {
        Reply r = patch("/admin/shops/" + shop + "/address", Map.of("version", version(shop), "addressLine",
                "Shop 4, Main Road", "area", "Kondapur", "city", "Hyderabad", "pincode", pincode, "latitude",
                17.465, "longitude", 78.364), boss);
        assertThat(r.status()).as(r.text()).isEqualTo(200);
    }

    private void approveAs(UUID shop, UUID who) {
        Reply r = post("/admin/shops/" + shop + "/approve", Map.of("version", version(shop)), who);
        assertThat(r.status()).as(r.text()).isEqualTo(200);
    }

    private UUID file(UUID shop, UUID by, String purpose, String type) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO files (id, shop_id, purpose, storage_key, content_type, size_bytes, sha256, "
                + "uploaded_by, created_at) VALUES (?, ?, ?, ?, ?, 10, ?, ?, ?)", id, shop, purpose, "test/" + id,
                type, (id.toString() + id).replace("-", "").substring(0, 64), by, Timestamp.from(NOW));
        return id;
    }

    private UUID assignee(UUID shop) {
        return jdbc.queryForObject("SELECT assigned_admin_id FROM shops WHERE id = ?", UUID.class, shop);
    }

    private String lastLog(UUID shop) {
        // The clock stands still in these tests: the log is insert-only, so its physical order is the order written
        return jdbc.queryForObject("SELECT reason FROM shop_assignments WHERE shop_id = ? ORDER BY at DESC, ctid DESC "
                + "LIMIT 1", String.class, shop);
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

    private static String smsCode(String phone) {
        String text = LAST_SMS.get(phone);
        assertThat(text).as("an SMS to " + phone).isNotNull();
        return text.replaceAll("\\D", "");
    }

    private String token(UUID who) {
        Boolean isAdmin = jdbc.queryForObject("SELECT admin_role <> 'NONE' FROM users WHERE id = ?", Boolean.class,
                who);
        return jwt.issue(new AuthPrincipal(who, null, null, Boolean.TRUE.equals(isAdmin)),
                sessions.currentGeneration(who)).token();
    }

    private static UUID id(Reply r) {
        return UUID.fromString((String) field(r.body(), "userId"));
    }

    private static String code(Reply r) {
        return (String) field(r.body(), "code");
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

    private Reply delete(String path, String token) {
        return call(HttpMethod.DELETE, path, null, token);
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
