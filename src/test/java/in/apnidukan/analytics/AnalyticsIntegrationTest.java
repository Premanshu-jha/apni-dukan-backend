package in.apnidukan.analytics;

import in.apnidukan.analytics.service.ActivityMarks;
import in.apnidukan.analytics.service.AnalyticsJob;
import in.apnidukan.appupdates.service.AppUpdates;
import in.apnidukan.appupdates.service.AppVersionCounts;
import in.apnidukan.common.security.AuthPrincipal;
import in.apnidukan.common.security.JwtService;
import in.apnidukan.common.security.SessionRevocations;
import in.apnidukan.support.TestContainers;
import in.apnidukan.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin Analytics page end to end against real Postgres and Redis: people's activity marked from their requests,
 * app versions counted from update checks, the hourly job's daily totals and salons' use, and every endpoint (super
 * admins only). Money rows are written as the webhooks would (their own tests: SubscriptionIntegrationTest,
 * InAppSubscriptionIntegrationTest).
 */
@EnabledIf("in.apnidukan.support.TestContainers#dockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AnalyticsIntegrationTest {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final UUID BOSS = UUID.fromString("b0000000-0000-0000-0000-000000000001");
    private static final UUID AGENT = UUID.fromString("b0000000-0000-0000-0000-000000000002");
    private static final UUID CUSTOMER = UUID.fromString("b0000000-0000-0000-0000-000000000003");
    private static final UUID OWNER = UUID.fromString("b0000000-0000-0000-0000-000000000004");
    private static final UUID HELPER = UUID.fromString("b0000000-0000-0000-0000-000000000005");
    private static final UUID QUIET_OWNER = UUID.fromString("b0000000-0000-0000-0000-000000000006");
    private static final UUID SALON = UUID.fromString("b0000000-0000-0000-0000-0000000000a1");
    private static final UUID QUIET = UUID.fromString("b0000000-0000-0000-0000-0000000000a2");

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        TestContainers.register(registry);
    }

    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired SessionRevocations sessions;
    @Autowired ActivityMarks marks;
    @Autowired AppVersionCounts versions;
    @Autowired AppUpdates updates;
    @Autowired AnalyticsJob job;

    RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.builder()
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + environment.getProperty("local.server.port") + "/api/v1")
                .build();
        marks.flush();
        versions.flush();
        TestData.clean(jdbc);
        TestData.user(jdbc, BOSS, "9000000301");
        TestData.user(jdbc, AGENT, "9000000302");
        TestData.user(jdbc, CUSTOMER, "9000000303");
        TestData.user(jdbc, OWNER, "9000000304");
        TestData.user(jdbc, HELPER, "9000000305");
        TestData.user(jdbc, QUIET_OWNER, "9000000307");
        jdbc.update("UPDATE users SET admin_role = 'SUPER_ADMIN', name = 'Prem' WHERE id = ?", BOSS);
        jdbc.update("UPDATE users SET admin_role = 'ADMIN', admin_designation = 'FIELD_AGENT' WHERE id = ?", AGENT);
        TestData.salon(jdbc, SALON, OWNER);
        TestData.member(jdbc, SALON, HELPER, "HELPER");
        TestData.salon(jdbc, QUIET, QUIET_OWNER);
        jdbc.update("UPDATE shops SET status = 'APPROVED', ever_approved = true, pincode = '500084' WHERE id IN (?, ?)",
                SALON, QUIET);
        jdbc.update("UPDATE shops SET name = 'Quiet Cuts', pincode = '500032' WHERE id = ?", QUIET);
        updates.refresh();
    }

    // ---- Who may see it ----------------------------------------------------------------------------------------

    @Test
    void onlyASuperAdminSeesAnalytics() {
        for (String path : List.of("/admin/analytics/money", "/admin/analytics/subscriptions", "/admin/analytics/people",
                "/admin/analytics/bookings", "/admin/analytics/salons", "/admin/analytics/app-versions",
                "/admin/analytics/settings", "/admin/analytics/money/2026-11.csv")) {
            assertThat(get(path, BOSS).status()).as(path).isEqualTo(200);
            Reply agent = get(path, AGENT);
            assertThat(agent.status()).as(path).isEqualTo(403);
            assertThat(code(agent)).isEqualTo("SUPER_ADMIN_ONLY");
            assertThat(get(path, CUSTOMER).status()).as(path).isIn(403, 404);
            assertThat(get(path, null).status()).as(path).isEqualTo(401);
        }
        assertThat(saveRates(AGENT, 1500, 1100, 0).status()).isEqualTo(403);
    }

    // ---- Activity ------------------------------------------------------------------------------------------------

    @Test
    void eachPersonIsCountedOncePerDayPerSideAndChannel() throws Exception {
        // Three requests from the customer app, one from the website, two from the partner app (owner and helper)
        for (int i = 0; i < 3; i++) {
            call(HttpMethod.GET, "/auth/me", null, CUSTOMER, "customer-app");
        }
        call(HttpMethod.GET, "/auth/me", null, CUSTOMER, "customer-web");
        call(HttpMethod.GET, "/auth/me", null, OWNER, "partner-app");
        call(HttpMethod.GET, "/auth/me", null, HELPER, "partner-app");
        // Many at once from one person: still one mark
        together(20, () -> call(HttpMethod.GET, "/auth/me", null, OWNER, "partner-app").status());
        // A request without a sign-in, or with an unknown client and no Origin: not counted
        call(HttpMethod.GET, "/app-updates/partner?nativeLevel=1&versionCode=1&debug=true", null, null, "partner-app");
        call(HttpMethod.GET, "/auth/me", null, AGENT, "something-else");
        // A customer claiming to be the admin website, or the partner app: neither counted as that side
        call(HttpMethod.GET, "/auth/me", null, CUSTOMER, "admin-web");
        call(HttpMethod.GET, "/auth/me", null, CUSTOMER, "partner-app");
        marks.flush();
        assertThat(count("daily_activity WHERE audience = 'ADMIN'")).isZero();

        assertThat(jdbc.queryForList("SELECT audience || ':' || channel FROM daily_activity WHERE user_id = ? "
                + "ORDER BY 1", String.class, CUSTOMER)).containsExactly("CUSTOMER:APP", "CUSTOMER:WEB");
        assertThat(count("daily_activity WHERE audience = 'PARTNER'")).isEqualTo(2);
        assertThat(count("daily_activity WHERE user_id = '" + AGENT + "'")).isZero();

        job.runOnce();
        Map<String, Long> today = today("active_day");
        assertThat(today).containsEntry("CUSTOMER:APP", 1L).containsEntry("CUSTOMER:WEB", 1L)
                .containsEntry("CUSTOMER:ALL", 1L).containsEntry("PARTNER:APP", 2L).containsEntry("PARTNER:ALL", 2L);
        // Both partner-app users belong to SALON: its last active day is today
        assertThat(jdbc.queryForObject("SELECT last_active_day FROM salon_usage WHERE shop_id = ?", LocalDate.class,
                SALON)).isEqualTo(todayIndia());
    }

    @Test
    void oldActivityIsDeletedAndALastActiveDayNeverGoesBack() {
        LocalDate today = todayIndia();
        jdbc.update("INSERT INTO daily_activity (day, audience, channel, user_id) VALUES (?, 'PARTNER', 'APP', ?)",
                today.minusDays(40), OWNER);
        jdbc.update("INSERT INTO daily_activity (day, audience, channel, user_id) VALUES (?, 'PARTNER', 'APP', ?)",
                today.minusDays(3), OWNER);
        job.runOnce();
        assertThat(count("daily_activity")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_active_day FROM salon_usage WHERE shop_id = ?", LocalDate.class,
                SALON)).isEqualTo(today.minusDays(3));
        // Even once that day is gone too, the salon keeps it
        jdbc.update("DELETE FROM daily_activity");
        job.runOnce();
        assertThat(jdbc.queryForObject("SELECT last_active_day FROM salon_usage WHERE shop_id = ?", LocalDate.class,
                SALON)).isEqualTo(today.minusDays(3));
    }

    // ---- App versions --------------------------------------------------------------------------------------------

    @Test
    void appVersionsAreCountedFromTheFirstCheckOfTheDayOnly() throws Exception {
        together(10, () -> check("partner", 105, false, true));
        check("partner", 105, false, false);   // later checks that day: not counted
        check("partner", 104, true, true);      // a test APK
        check("customer", 30, false, true);
        versions.flush();
        versions.flush();                       // nothing left: adds nothing

        Reply r = get("/admin/analytics/app-versions", BOSS);
        List<Map<String, Object>> days = list(r.body(), "days");
        assertThat(days).anySatisfy(d -> assertThat(d).containsEntry("app", "partner").containsEntry("versionCode", 105)
                .containsEntry("store", true).containsEntry("phones", 10));
        assertThat(days).anySatisfy(d -> assertThat(d).containsEntry("versionCode", 104).containsEntry("store", false)
                .containsEntry("phones", 1));
        assertThat(days).anySatisfy(d -> assertThat(d).containsEntry("app", "customer").containsEntry("phones", 1));
    }

    // ---- The hourly job and the page -----------------------------------------------------------------------------

    @Test
    void theJobWorksOutBookingsSignupsAndSalonsAndRunningItAgainChangesNothing() {
        LocalDate today = todayIndia();
        LocalDate d1 = today.minusDays(1);
        LocalDate d2 = today.minusDays(2);
        UUID barber = UUID.randomUUID();
        UUID staff = UUID.randomUUID();
        TestData.user(jdbc, staff, "9000000306");
        TestData.member(jdbc, SALON, staff, "BARBER");
        jdbc.update("INSERT INTO salon_staff (id, shop_id, name, active, version, created_at, updated_at) "
                + "VALUES (?, ?, 'Raju', true, 0, now(), now())", barber, SALON);
        // Yesterday and the day before (both over, whatever the time now): bookings made and visits, by outcome
        visit(barber, "BOOKING", "COMPLETED", at(d1, 10), at(d2, 18));
        visit(barber, "BOOKING", "CANCELLED_BY_CUSTOMER", at(d1, 15), at(d1, 8));
        visit(barber, "BOOKING", "NO_SHOW", at(d2, 12), at(d2, 7));
        visit(barber, "WALK_IN", "COMPLETED", at(d1, 11), at(d1, 11));
        visit(barber, "BLOCK", "BLOCKED", at(d1, 13), at(d1, 9));
        // A test account's signup isn't counted
        UUID tester = UUID.randomUUID();
        TestData.user(jdbc, tester, "9000000399");
        jdbc.update("UPDATE users SET test_account = true WHERE id = ?", tester);

        assertThat(job.runOnce()).isGreaterThanOrEqualTo(3);
        List<Map<String, Object>> first = jdbc.queryForList("SELECT metric, day, dim, value FROM analytics_daily "
                + "ORDER BY metric, day, dim");
        job.runOnce();
        assertThat(jdbc.queryForList("SELECT metric, day, dim, value FROM analytics_daily ORDER BY metric, day, dim"))
                .isEqualTo(first);

        assertThat(value("bookings_created", d1, "")).isEqualTo(1);
        assertThat(value("bookings_created", d2, "")).isEqualTo(2);
        assertThat(value("bookings_by_pincode", d2, "500084")).isEqualTo(2);
        assertThat(value("visits", d1, "BOOKING:COMPLETED")).isEqualTo(1);
        assertThat(value("visits", d1, "WALK_IN:COMPLETED")).isEqualTo(1);
        assertThat(value("visits", d1, "BOOKING:CANCELLED_BY_CUSTOMER")).isEqualTo(1);
        assertThat(value("visits", d2, "BOOKING:NO_SHOW")).isEqualTo(1);
        assertThat(value("visits", d1, "BLOCK:BLOCKED")).isZero();
        assertThat(value("visit_hours", d1, "10")).isEqualTo(1);
        assertThat(value("completed_by_pincode", d1, "500084")).isEqualTo(2);
        // Everyone was made today: the customer, the two salons' owners, SALON's helper and barber, and two of the
        // team; the test account left out
        assertThat(today("signups")).containsEntry("CUSTOMER", 1L).containsEntry("OWNER", 2L)
                .containsEntry("STAFF", 2L).containsEntry("ADMIN", 2L);
        assertThat(today("users_now")).containsEntry("CUSTOMER", 1L).containsEntry("ADMIN", 2L);
        assertThat(today("salons_now")).containsEntry("APPROVED", 2L);

        Map<String, Object> usage = jdbc.queryForMap("SELECT * FROM salon_usage WHERE shop_id = ?", SALON);
        assertThat(usage).containsEntry("bookings_30d", 3).containsEntry("completed_30d", 2)
                .containsEntry("walk_ins_30d", 1).containsEntry("no_shows_30d", 1).containsEntry("staff", 1);

        // The page: bookings for all areas and for one
        Reply all = get("/admin/analytics/bookings", BOSS);
        assertThat(all.status()).isEqualTo(200);
        assertThat(list(all.body(), "topAreas")).first().satisfies(a -> assertThat(a).containsEntry("dim", "500084")
                .containsEntry("value", 3));
        Reply area = get("/admin/analytics/bookings?pincode=500032", BOSS);
        assertThat(list(area.body(), "created")).isEmpty();
        assertThat(code(get("/admin/analytics/bookings?pincode=5000", BOSS))).isEqualTo("VALIDATION_FAILED");
        assertThat(code(get("/admin/analytics/bookings?from=2026-13-01", BOSS))).isEqualTo("VALIDATION_FAILED");
        assertThat(code(get("/admin/analytics/bookings?from=2024-01-01&to=" + today, BOSS)))
                .isEqualTo("VALIDATION_FAILED");
        assertThat(code(get("/admin/analytics/bookings?from=" + today + "&to=" + today.minusDays(1), BOSS)))
                .isEqualTo("VALIDATION_FAILED");

        // Salons by use: the quiet one (nobody active, no bookings) first; by bookings, the busy one first
        Reply quiet = get("/admin/analytics/salons?sort=quiet", BOSS);
        assertThat(list(quiet.body(), "salons")).first().satisfies(s -> assertThat(s).containsEntry("name", "Quiet Cuts"));
        assertThat(((Number) ((Map<?, ?>) quiet.body()).get("quietSalons")).longValue()).isEqualTo(2);
        Reply busy = get("/admin/analytics/salons?sort=bookings", BOSS);
        assertThat(list(busy.body(), "salons")).first().satisfies(s -> assertThat(s).containsEntry("bookings30d", 3));
        assertThat(code(get("/admin/analytics/salons?sort=name", BOSS))).isEqualTo("VALIDATION_FAILED");

        Reply people = get("/admin/analytics/people", BOSS);
        assertThat(people.status()).isEqualTo(200);
        assertThat(list(people.body(), "accounts")).isNotEmpty();
    }

    @Test
    void moneyIsCountedPerMonthAndSourceWithGooglesFeeEstimatedAndTestPaymentsLeftOut() {
        Instant now = Instant.now();
        YearMonth month = YearMonth.from(now.atZone(INDIA));
        Instant inMonth = month.atDay(1).atTime(12, 0).atZone(INDIA).toInstant();
        Instant end = now.plus(Duration.ofDays(20));
        charge(SALON, "WEBSITE", "pay_W1", 50_000, 1_180L, inMonth, end, false);
        charge(QUIET, "GOOGLE_PLAY", "GPA.1", 50_000, null, inMonth, end, false);
        charge(QUIET, "APP_RAZORPAY", "pay_A1", 50_000, 1_180L, inMonth.minusSeconds(60), end, false);
        charge(SALON, "WEBSITE", "pay_TEST", 50_000, 1_180L, inMonth, end, true);
        refund("pay_W1", "rfnd_W1", 10_000, inMonth.plusSeconds(60));

        Reply r = get("/admin/analytics/money?months=1", BOSS);
        assertThat(r.status()).isEqualTo(200);
        Map<?, ?> m = (Map<?, ?>) list(r.body(), "months").get(0);
        assertThat(m.get("month")).isEqualTo(month.toString());
        Map<?, ?> total = (Map<?, ?>) m.get("total");
        assertThat(total.get("payments")).isEqualTo(3);
        assertThat(((Number) total.get("grossPaise")).longValue()).isEqualTo(150_000);
        assertThat(((Number) total.get("refundsPaise")).longValue()).isEqualTo(10_000);
        assertThat(((Number) total.get("razorpayFeePaise")).longValue()).isEqualTo(2_360);
        // Google: 15% of ₹500 on Play billing, 11% of ₹500 on Razorpay picked in the app
        assertThat(((Number) total.get("googleFeeEstimatePaise")).longValue()).isEqualTo(7_500 + 5_500);
        assertThat(((Number) total.get("netPaise")).longValue())
                .isEqualTo(150_000 - 10_000 - 2_360 - 13_000);

        // The monthly money now: each paid-up salon at what it last paid, less a part refunded (₹100 of SALON's ₹500)
        Map<?, ?> recurring = (Map<?, ?>) ((Map<?, ?>) r.body()).get("recurring");
        assertThat(((Number) recurring.get("salons")).longValue()).isEqualTo(2);
        assertThat(((Number) recurring.get("monthlyPaise")).longValue()).isEqualTo(40_000 + 50_000);
        // Refunded in full: that salon no longer counts
        refund("pay_W1", "rfnd_W2", 40_000, inMonth.plusSeconds(120));
        Map<?, ?> after = (Map<?, ?>) ((Map<?, ?>) get("/admin/analytics/money?months=1", BOSS).body()).get("recurring");
        assertThat(((Number) after.get("salons")).longValue()).isEqualTo(1);
        assertThat(((Number) after.get("monthlyPaise")).longValue()).isEqualTo(50_000);

        // Rates changed: the estimate follows; an old version is refused
        Map<?, ?> rates = (Map<?, ?>) get("/admin/analytics/settings", BOSS).body();
        int version = (Integer) rates.get("version");
        assertThat(saveRates(BOSS, 3000, 1100, version).status()).isEqualTo(200);
        assertThat(code(saveRates(BOSS, 1500, 1100, version))).isEqualTo("SETTINGS_CHANGED");
        assertThat(code(saveRates(BOSS, 3001, 1100, version + 1))).isEqualTo("VALIDATION_FAILED");
        assertThat(count("admin_events WHERE event = 'ANALYTICS_SETTINGS_CHANGED'")).isEqualTo(1);
        Map<?, ?> again = (Map<?, ?>) ((Map<?, ?>) list(get("/admin/analytics/money?months=1", BOSS).body(),
                "months").get(0)).get("total");
        assertThat(((Number) again.get("googleFeeEstimatePaise")).longValue()).isEqualTo(15_000 + 5_500);
        assertThat(code(get("/admin/analytics/money?months=0", BOSS))).isEqualTo("VALIDATION_FAILED");

        // The CSV: every payment and refund that isn't a test one; a name that a spreadsheet would run is made safe
        jdbc.update("UPDATE shops SET name = '=HYPERLINK(\"x\")' WHERE id = ?", QUIET);
        Reply csv = get("/admin/analytics/money/" + month + ".csv", BOSS);
        assertThat(csv.status()).isEqualTo(200);
        assertThat(csv.text()).contains("pay_W1").contains("rfnd_W1").contains("GPA.1").doesNotContain("pay_TEST")
                .contains("\"'=HYPERLINK(\"\"x\"\")\"").contains("-100.00").contains("Google Play");
        assertThat(code(get("/admin/analytics/money/2026-13.csv", BOSS))).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void subscriptionsShowWhereEachSalonStands() {
        Instant now = Instant.now();
        // SALON: trial ending in 3 days without AutoPay; QUIET: paid through Google Play and renewing
        subscription(SALON, now.plus(Duration.ofDays(3)), null);
        subscription(QUIET, now.minus(Duration.ofDays(20)), now.plus(Duration.ofDays(10)));
        jdbc.update("UPDATE salon_subscriptions SET play_purchase_token = 'tok', play_auto_renewing = true, "
                + "play_expires_at = ? WHERE shop_id = ?", Timestamp.from(now.plus(Duration.ofDays(10))), QUIET);
        charge(QUIET, "GOOGLE_PLAY", "GPA.2", 50_000, null, now.minus(Duration.ofDays(20)),
                now.plus(Duration.ofDays(10)), false);

        Map<?, ?> s = (Map<?, ?>) get("/admin/analytics/subscriptions", BOSS).body();
        assertThat(s.get("trial")).isEqualTo(1);
        assertThat(s.get("paying")).isEqualTo(1);
        assertThat(list(s, "payingBySource")).anySatisfy(x -> assertThat(x).containsEntry("source", "GOOGLE_PLAY")
                .containsEntry("salons", 1).containsEntry("renewing", 1));
        assertThat(list(s, "trialsEnding")).singleElement().satisfies(x -> assertThat(x)
                .containsEntry("shopId", SALON.toString()));
        assertThat(list(s, "problems")).isEmpty();

        // Google couldn't take the renewal: a payment problem
        jdbc.update("UPDATE salon_subscriptions SET play_state = 'SUBSCRIPTION_STATE_ON_HOLD' WHERE shop_id = ?",
                QUIET);
        Map<?, ?> after = (Map<?, ?>) get("/admin/analytics/subscriptions", BOSS).body();
        assertThat(after.get("paymentProblems")).isEqualTo(1);
        assertThat(list(after, "problems")).singleElement().satisfies(x -> assertThat(x)
                .containsEntry("shopId", QUIET.toString()));
    }

    @Test
    void everyStateASalonCanBeInIsCountedAsItsOwnScreenSaysAndTestSalonsAreLeftOut() {
        Instant now = Instant.now();
        UUID[] owners = new UUID[7];
        UUID[] salons = new UUID[7];
        for (int i = 0; i < 7; i++) {
            owners[i] = UUID.randomUUID();
            salons[i] = UUID.randomUUID();
            TestData.user(jdbc, owners[i], "900000040" + i);
            TestData.salon(jdbc, salons[i], owners[i]);
            jdbc.update("UPDATE shops SET status = 'APPROVED', ever_approved = true WHERE id = ?", salons[i]);
        }
        // 0: paid on the website, AutoPay on; 1: paid in the app with Razorpay; 2: time over yesterday (3 days to
        // pay); 3: hidden (over 5 days ago); 4: AutoPay set up but its charge 2 days late (a problem); 5: Razorpay
        // gave up (HALTED); 6: a test account's salon (never counted)
        subscription(salons[0], now.minus(Duration.ofDays(40)), now.plus(Duration.ofDays(20)));
        jdbc.update("UPDATE salon_subscriptions SET razorpay_subscription_id = 'sub_W', razorpay_status = 'ACTIVE' "
                + "WHERE shop_id = ?", salons[0]);
        subscription(salons[1], now.minus(Duration.ofDays(40)), now.plus(Duration.ofDays(20)));
        jdbc.update("UPDATE salon_subscriptions SET razorpay_subscription_id = 'sub_A', "
                + "app_billing_subscription_id = 'sub_A' WHERE shop_id = ?", salons[1]);
        subscription(salons[2], now.minus(Duration.ofDays(1)), null);
        subscription(salons[3], now.minus(Duration.ofDays(6)), null);
        subscription(salons[4], now.minus(Duration.ofDays(40)), now.minus(Duration.ofDays(2)));
        jdbc.update("UPDATE salon_subscriptions SET razorpay_subscription_id = 'sub_L', razorpay_status = 'ACTIVE' "
                + "WHERE shop_id = ?", salons[4]);
        subscription(salons[5], now.minus(Duration.ofDays(40)), now.plus(Duration.ofDays(5)));
        jdbc.update("UPDATE salon_subscriptions SET razorpay_status = 'HALTED' WHERE shop_id = ?", salons[5]);
        subscription(salons[6], now.plus(Duration.ofDays(2)), null);
        jdbc.update("UPDATE users SET test_account = true WHERE id = ?", owners[6]);
        // A closed salon isn't counted either
        jdbc.update("UPDATE shops SET status = 'CLOSED' WHERE id = ?", QUIET);
        subscription(QUIET, now.plus(Duration.ofDays(2)), null);

        Map<?, ?> s = (Map<?, ?>) get("/admin/analytics/subscriptions", BOSS).body();
        assertThat(s.get("trial")).isEqualTo(0);
        assertThat(s.get("paying")).isEqualTo(3);   // 0, 1 and 5 (HALTED, still paid up)
        assertThat(list(s, "payingBySource")).anySatisfy(x -> assertThat(x).containsEntry("source", "WEBSITE")
                .containsEntry("salons", 2).containsEntry("renewing", 1));
        assertThat(list(s, "payingBySource")).anySatisfy(x -> assertThat(x).containsEntry("source", "APP_RAZORPAY")
                .containsEntry("salons", 1));
        assertThat(s.get("grace")).isEqualTo(2);    // 2, and 4 (its paid time ran out 2 days ago)
        assertThat(s.get("lapsed")).isEqualTo(1);   // 3
        assertThat(s.get("paymentProblems")).isEqualTo(2);   // 4 (late) and 5 (HALTED)
        assertThat(list(s, "trialsEnding")).isEmpty();       // the test salon's trial isn't listed
    }

    @Test
    void madeUpVersionNumbersAreNeverCounted() {
        assertThat(check("partner", 999_999, false, true)).isEqualTo(200);
        check("partner", 105, false, true);
        versions.flush();
        assertThat(jdbc.queryForList("SELECT version_code FROM app_version_daily", Integer.class)).containsExactly(105);
    }

    // ---- Helpers -------------------------------------------------------------------------------------------------

    private static Instant at(LocalDate day, int hour) {
        return day.atTime(hour, 0).atZone(INDIA).toInstant();
    }

    /** A booking, walk-in or break of SALON's barber, written directly (the booking rules have their own tests). */
    private void visit(UUID barber, String kind, String status, Instant startsAt, Instant createdAt) {
        boolean booking = "BOOKING".equals(kind);
        jdbc.update("INSERT INTO appointments (id, shop_id, staff_id, kind, block_type, customer_id, booking_no, "
                + "start_code, starts_at, ends_at, status, created_by, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), SALON, barber, kind,
                "BLOCK".equals(kind) ? "BREAK" : null, booking ? CUSTOMER : null,
                booking ? "AN" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(java.util.Locale.ROOT) : null,
                booking ? "1234" : null, Timestamp.from(startsAt), Timestamp.from(startsAt.plus(Duration.ofMinutes(30))),
                status, OWNER, Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    private void subscription(UUID shop, Instant trialEnds, Instant paidUntil) {
        Instant access = paidUntil == null || paidUntil.isBefore(trialEnds) ? trialEnds : paidUntil;
        jdbc.update("INSERT INTO salon_subscriptions (shop_id, trial_ends_at, paid_until, bookable_until, created_at, "
                + "updated_at) VALUES (?, ?, ?, ?, now(), now())", shop, Timestamp.from(trialEnds),
                paidUntil == null ? null : Timestamp.from(paidUntil),
                Timestamp.from(access.plus(Duration.ofDays(4))));
    }

    private void charge(UUID shop, String source, String id, long paise, Long fee, Instant at, Instant end,
                        boolean test) {
        jdbc.update("INSERT INTO subscription_payments (id, shop_id, source, kind, external_id, amount_paise, "
                + "fee_paise, paid_at, period_end, test, created_at) VALUES (?, ?, ?, 'CHARGE', ?, ?, ?, ?, ?, ?, now())",
                UUID.randomUUID(), shop, source, id, paise, fee, Timestamp.from(at), Timestamp.from(end), test);
    }

    private void refund(String chargeId, String id, long paise, Instant at) {
        jdbc.update("INSERT INTO subscription_payments (id, shop_id, source, kind, external_id, charge_id, amount_paise, "
                + "paid_at, test, created_at) SELECT ?, shop_id, source, 'REFUND', ?, external_id, ?, ?, test, now() "
                + "FROM subscription_payments WHERE external_id = ?", UUID.randomUUID(), id, -paise,
                Timestamp.from(at), chargeId);
    }

    private int check(String app, int versionCode, boolean debug, boolean first) {
        return call(HttpMethod.GET, "/app-updates/" + app + "?nativeLevel=1&versionCode=" + versionCode + "&debug="
                + debug + "&first=" + first, null, null, null).status();
    }

    private Reply saveRates(UUID who, int play, int userChoice, int version) {
        return call(HttpMethod.PUT, "/admin/analytics/settings", Map.of("playFeeBps", play,
                "userChoiceFeeBps", userChoice, "version", version), who, null);
    }

    private long value(String metric, LocalDate day, String dim) {
        List<Long> v = jdbc.queryForList("SELECT value FROM analytics_daily WHERE metric = ? AND day = ? AND dim = ?",
                Long.class, metric, day, dim);
        return v.isEmpty() ? 0 : v.get(0);
    }

    private Map<String, Long> today(String metric) {
        Map<String, Long> out = new java.util.HashMap<>();
        jdbc.query("SELECT dim, value FROM analytics_daily WHERE metric = ? AND day = ?", rs -> {
            out.put(rs.getString(1), rs.getLong(2));
        }, metric, todayIndia());
        return out;
    }

    private static LocalDate todayIndia() {
        return LocalDate.now(INDIA);
    }

    private long count(String fromWhere) {
        return jdbc.queryForObject("SELECT count(*) FROM " + fromWhere, Long.class);
    }

    private Reply get(String path, UUID who) {
        return call(HttpMethod.GET, path, null, who, "admin-web");
    }

    private Reply call(HttpMethod method, String path, Object body, UUID who, String client) {
        RestClient.RequestBodySpec spec = http.method(method).uri(path).headers(h -> {
            if (who != null) {
                h.setBearerAuth(token(who));
            }
            if (client != null) {
                h.set("X-Clozest-Client", client);
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
        // As a sign-in does: a salon member's token names their salon and role
        java.util.List<Map<String, Object>> member = jdbc.queryForList("SELECT shop_id, role FROM shop_members "
                + "WHERE user_id = ?", who);
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

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object body, String name) {
        return (List<Map<String, Object>>) ((Map<?, ?>) body).get(name);
    }

    private static String code(Reply r) {
        return r.body() instanceof Map<?, ?> m ? (String) m.get("code") : null;
    }

    private static Reply read(org.springframework.http.client.ClientHttpResponse res) throws java.io.IOException {
        int status = res.getStatusCode().value();
        String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
        Object parsed;
        try {
            parsed = text.isBlank() ? Map.of() : JSON.readValue(text, Object.class);
        } catch (RuntimeException e) {
            parsed = Map.of();   // the CSV
        }
        return new Reply(status, parsed, text);
    }

    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

    private record Reply(int status, Object body, String text) {
    }
}
