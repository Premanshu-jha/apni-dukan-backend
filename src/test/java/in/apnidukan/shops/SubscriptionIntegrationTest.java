package in.apnidukan.shops;

import com.sun.net.httpserver.HttpServer;
import in.apnidukan.common.security.AuthPrincipal;
import in.apnidukan.common.security.JwtService;
import in.apnidukan.common.security.SessionRevocations;
import in.apnidukan.shops.service.SubscriptionNoticeJob;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import in.apnidukan.shops.repo.SubscriptionStore;
import in.apnidukan.shops.service.SubscriptionService;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The salons' subscription end to end: real Postgres and Redis in Docker, the real sign-in tokens, a clock the test
 * moves by hand, and a local fake of Razorpay's API (nothing is sent to Razorpay). SUBSCRIPTIONS_ENFORCED is on here:
 * a lapsed salon is hidden and takes no new bookings.
 *
 * "Now" starts at Tuesday 10 Nov 2026, 10:00 in India.
 */
@EnabledIf("in.apnidukan.support.TestContainers#dockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({CapturingSenders.class, SubscriptionIntegrationTest.Fakes.class})
class SubscriptionIntegrationTest {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    static final Instant TUESDAY_10_00 = Instant.parse("2026-11-10T04:30:00Z");
    static final TestClock CLOCK = new TestClock(TUESDAY_10_00);
    static final String KEY_SECRET = "test-key-secret";
    static final String WEBHOOK_SECRET = "test-webhook-secret";

    /** The fake Razorpay: each POST /v1/subscriptions it got (its body), and how it answers. */
    static final List<String> RAZORPAY_BODIES = new CopyOnWriteArrayList<>();
    static final List<String> RAZORPAY_AUTH = new CopyOnWriteArrayList<>();
    /** The subscriptions the fake Razorpay was asked to cancel, and how it answers that. */
    static final List<String> CANCELLED = new CopyOnWriteArrayList<>();
    static volatile int cancelStatus = 200;
    static final AtomicInteger NEXT_SUB = new AtomicInteger();
    static volatile int razorpayStatus = 200;
    static volatile long razorpayDelayMs = 0;
    private static final HttpServer RAZORPAY = fakeRazorpay();

    private static HttpServer fakeRazorpay() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newFixedThreadPool(4));
            server.createContext("/v1/subscriptions", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if (path.endsWith("/cancel")) {
                    exchange.getRequestBody().readAllBytes();
                    if (cancelStatus == 200) {
                        CANCELLED.add(path.split("/")[3]);
                    }
                    exchange.sendResponseHeaders(cancelStatus, -1);
                    exchange.close();
                    return;
                }
                RAZORPAY_BODIES.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                RAZORPAY_AUTH.add(exchange.getRequestHeaders().getFirst("Authorization"));
                try {
                    Thread.sleep(razorpayDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                byte[] out = (razorpayStatus == 200
                        ? "{\"id\":\"sub_TEST" + NEXT_SUB.incrementAndGet() + "\",\"entity\":\"subscription\","
                                + "\"status\":\"created\",\"short_url\":\"https://rzp.io/x\"}"
                        : "{\"error\":{\"code\":\"SERVER_ERROR\"}}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(razorpayStatus, out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        TestClock testClock() {
            return CLOCK;
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestContainers.register(registry);
        registry.add("app.razorpay.key-id", () -> "rzp_test_abc");
        registry.add("app.razorpay.key-secret", () -> KEY_SECRET);
        registry.add("app.razorpay.plan-id", () -> "plan_500");
        registry.add("app.razorpay.webhook-secret", () -> WEBHOOK_SECRET);
        registry.add("app.razorpay.endpoint", () -> "http://127.0.0.1:" + RAZORPAY.getAddress().getPort());
        registry.add("app.subscriptions.enforce", () -> "true");
    }

    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired JwtService jwt;
    @Autowired SessionRevocations sessions;
    @Autowired SubscriptionNoticeJob notices;
    @Autowired SubscriptionService subscriptions;
    @Autowired PlatformTransactionManager transactionManager;

    RestClient http;
    UUID boss;
    UUID owner;
    UUID helper;
    UUID customer;
    UUID salon;

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
        CLOCK.set(TUESDAY_10_00);
        RAZORPAY_BODIES.clear();
        RAZORPAY_AUTH.clear();
        CANCELLED.clear();
        cancelStatus = 200;
        razorpayStatus = 200;
        razorpayDelayMs = 0;
        boss = person("9000000009");
        jdbc.update("UPDATE users SET admin_role = 'SUPER_ADMIN', name = 'Prem' WHERE id = ?", boss);
        owner = person("9000000001");
        helper = person("9000000002");
        customer = person("9000000101");
        salon = UUID.randomUUID();
        TestData.salon(jdbc, salon, owner);
        TestData.member(jdbc, salon, helper, "HELPER");
    }

    // ---------- the trial ----------

    @Test
    void theFirstApprovalStartsA30DayTrialAndApprovingAgainLaterKeepsIt() {
        assertThat(get("/shops/me/subscription", owner).body()).containsEntry("state", "NONE");
        assertThat(post("/shops/me/subscription/checkout", null, owner).code()).isEqualTo("SUBSCRIPTION_NOT_STARTED");

        approve();
        Reply mine = get("/shops/me/subscription", owner);
        assertThat(mine.body()).containsEntry("state", "TRIAL").containsEntry("pricePerMonth", 500)
                .containsEntry("canPay", true).containsEntry("enforced", true).containsEntry("testMode", true);
        assertThat(Instant.parse((String) mine.body().get("trialEndsAt"))).isEqualTo(TUESDAY_10_00.plus(Duration.ofDays(30)));
        // Trial ends Thu 10 Dec 10:00; hidden from midnight ending Sun 13 Dec, India time: "pay by 13 Dec" holds all day
        assertThat(Instant.parse((String) mine.body().get("hiddenFrom"))).isEqualTo(HIDDEN);
        assertThat(HIDDEN).isEqualTo(LocalDate.of(2026, 12, 14).atStartOfDay(INDIA).toInstant());
        assertThat(count("notification_jobs WHERE type = 'SHOP_APPROVED' AND body LIKE "
                + "'%free trial runs until 10 Dec 2026; then ₹500 a month. Nothing to pay now.%'")).isEqualTo(1);

        // Suspended and approved again ten days on: the trial isn't given again
        CLOCK.advance(Duration.ofDays(10));
        jdbc.update("UPDATE shops SET status = 'PENDING_VISIT' WHERE id = ?", salon);
        assertThat(post("/admin/shops/" + salon + "/approve", Map.of("version", version()), boss).status())
                .isEqualTo(200);
        assertThat(Instant.parse((String) get("/shops/me/subscription", owner).body().get("trialEndsAt")))
                .isEqualTo(TUESDAY_10_00.plus(Duration.ofDays(30)));
        // ...and its message doesn't say a trial started
        assertThat(count("notification_jobs WHERE type = 'SHOP_APPROVED' AND body LIKE '%free trial%'")).isEqualTo(1);
    }

    @Test
    void everyoneInTheSalonSeesItOnlyTheOwnerPays() {
        approve();
        assertThat(get("/shops/me/subscription", helper).body()).containsEntry("state", "TRIAL")
                .containsEntry("canPay", false);
        assertThat(post("/shops/me/subscription/checkout", null, helper).code()).isEqualTo("OWNER_ONLY");
        assertThat(get("/shops/me/subscription", customer).code()).isEqualTo("NO_SHOP");
        assertThat(RAZORPAY_BODIES).isEmpty();
    }

    // ---------- paying ----------

    @Test
    void checkoutMakesOneSubscriptionChargedFromTheTrialsEndAndATapAgainGetsTheSameOne() {
        approve();
        Reply first = post("/shops/me/subscription/checkout", null, owner);
        assertThat(first.status()).as(first.text()).isEqualTo(200);
        assertThat(first.body()).containsEntry("keyId", "rzp_test_abc").containsEntry("subscriptionId", "sub_TEST"
                + NEXT_SUB.get()).containsEntry("name", "Clozest").containsEntry("themeColor", "#0B0F26")
                .containsEntry("testMode", true);
        assertThat((String) first.body().get("description")).contains("₹500 a month").contains("10 Dec 2026");
        assertThat(((Map<?, ?>) first.body().get("prefill")).get("contact")).isEqualTo("9000000001");
        assertThat(RAZORPAY_BODIES).hasSize(1);
        assertThat(RAZORPAY_BODIES.get(0)).contains("\"plan_id\":\"plan_500\"")
                .contains("\"start_at\":" + TUESDAY_10_00.plus(Duration.ofDays(30)).getEpochSecond())
                .contains("\"shop_id\":\"" + salon + "\"").contains("\"customer_notify\":0");
        assertThat(RAZORPAY_AUTH.get(0)).isEqualTo("Basic " + java.util.Base64.getEncoder()
                .encodeToString(("rzp_test_abc:" + KEY_SECRET).getBytes(StandardCharsets.UTF_8)));

        // Closed the checkout and tapped Pay again a few minutes later: the same subscription, Razorpay not asked
        CLOCK.advance(Duration.ofMinutes(5));
        assertThat(post("/shops/me/subscription/checkout", null, owner).body().get("subscriptionId"))
                .isEqualTo(first.body().get("subscriptionId"));
        assertThat(RAZORPAY_BODIES).hasSize(1);

        // Over an hour later: a new one, the unfinished one cancelled at Razorpay first (it can't be approved later)
        CLOCK.advance(Duration.ofHours(2));
        assertThat(post("/shops/me/subscription/checkout", null, owner).body().get("subscriptionId"))
                .isNotEqualTo(first.body().get("subscriptionId"));
        assertThat(RAZORPAY_BODIES).hasSize(2);
        assertThat(CANCELLED).containsExactly((String) first.body().get("subscriptionId"));
        assertThat(count("razorpay_cancellations")).isZero();
    }

    @Test
    void afterTheTrialTheFirstMonthIsChargedAtOnce() {
        approve();
        CLOCK.advance(Duration.ofDays(31));
        assertThat(get("/shops/me/subscription", owner).body()).containsEntry("state", "GRACE");
        Reply r = post("/shops/me/subscription/checkout", null, owner);
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        assertThat(r.body().get("firstChargeAt")).isNull();
        assertThat((String) r.body().get("description")).contains("first payment today");
        assertThat(RAZORPAY_BODIES.get(0)).doesNotContain("start_at");
        // Approved now: AutoPay on (not "overdue" for the trial that ended days ago), and no reminder goes out
        String sub = (String) r.body().get("subscriptionId");
        Reply ok = post("/shops/me/subscription/confirm", Map.of("razorpayPaymentId", "pay_1",
                "razorpaySubscriptionId", sub, "razorpaySignature", hmac(KEY_SECRET, "pay_1|" + sub)), owner);
        assertThat(ok.body()).as(ok.text()).containsEntry("state", "AUTOPAY").containsEntry("autoPayFailed", false);
        assertThat(notices.runOnce()).isZero();
    }

    @Test
    void twoTapsAtOnceAskRazorpayOnce() throws Exception {
        approve();
        razorpayDelayMs = 700;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Reply>> replies = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            replies.add(pool.submit(() -> {
                go.await();
                return post("/shops/me/subscription/checkout", null, owner);
            }));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Reply> f : replies) {
            Reply r = f.get(10, TimeUnit.SECONDS);
            statuses.add(r.status());
            if (r.status() == 409) {
                assertThat(r.code()).isEqualTo("PAYMENT_STARTING");
            }
        }
        pool.shutdown();
        assertThat(statuses).contains(200);
        assertThat(RAZORPAY_BODIES).hasSize(1);
        assertThat(count("salon_subscriptions WHERE razorpay_subscription_id IS NOT NULL")).isEqualTo(1);
    }

    @Test
    void razorpayDownLetsTheNextTapTryAgainAtOnce() {
        approve();
        razorpayStatus = 500;
        Reply down = post("/shops/me/subscription/checkout", null, owner);
        assertThat(down.status()).isEqualTo(503);
        assertThat(down.code()).isEqualTo("PAYMENTS_UNAVAILABLE");
        assertThat(down.retryAfter()).isEqualTo("30");
        razorpayStatus = 200;
        assertThat(post("/shops/me/subscription/checkout", null, owner).status()).isEqualTo(200);
    }

    @Test
    void theCheckoutsSignedAnswerTurnsAutoPayOnAndAForgedOneIsRefused() {
        approve();
        String sub = (String) post("/shops/me/subscription/checkout", null, owner).body().get("subscriptionId");
        Reply forged = post("/shops/me/subscription/confirm", Map.of("razorpayPaymentId", "pay_1",
                "razorpaySubscriptionId", sub, "razorpaySignature", "00ff"), owner);
        assertThat(forged.code()).isEqualTo("PAYMENT_NOT_CONFIRMED");
        Reply other = post("/shops/me/subscription/confirm", Map.of("razorpayPaymentId", "pay_1",
                "razorpaySubscriptionId", "sub_SOMEONE_ELSE",
                "razorpaySignature", hmac(KEY_SECRET, "pay_1|sub_SOMEONE_ELSE")), owner);
        assertThat(other.code()).isEqualTo("PAYMENT_NOT_CONFIRMED");

        Reply ok = post("/shops/me/subscription/confirm", Map.of("razorpayPaymentId", "pay_1",
                "razorpaySubscriptionId", sub, "razorpaySignature", hmac(KEY_SECRET, "pay_1|" + sub)), owner);
        assertThat(ok.status()).as(ok.text()).isEqualTo(200);
        assertThat(ok.body()).containsEntry("state", "AUTOPAY").containsEntry("canPay", false);
        assertThat(post("/shops/me/subscription/checkout", null, owner).code()).isEqualTo("ALREADY_SUBSCRIBED");
    }

    // ---------- webhooks ----------

    @Test
    void webhooksAreCheckedHandledOnceAndNeverGoBack() {
        approve();
        String sub = (String) post("/shops/me/subscription/checkout", null, owner).body().get("subscriptionId");
        long t = TUESDAY_10_00.getEpochSecond();

        String authenticated = event("subscription.authenticated", sub, "authenticated", 0, t + 10);
        assertThat(webhook(authenticated, "wrong", "evt_1").status()).isEqualTo(403);
        assertThat(webhook(authenticated, null, "evt_1").status()).isEqualTo(403);
        assertThat(webhook(authenticated, sign(authenticated), "evt_1").body()).containsEntry("result", "UPDATED");
        assertThat(webhook(authenticated, sign(authenticated), "evt_1").body()).containsEntry("result", "DUPLICATE");
        assertThat(get("/shops/me/subscription", owner).body()).containsEntry("state", "AUTOPAY");
        assertThat(count("notification_jobs WHERE type = 'SUBSCRIPTION_AUTOPAY' AND user_id = '" + owner + "'"))
                .isPositive();

        // The first month charged at the trial's end, paid for a month after it
        long paidTo = TUESDAY_10_00.plus(Duration.ofDays(61)).getEpochSecond();
        String charged = event("subscription.charged", sub, "active", paidTo, t + 100);
        assertThat(webhook(charged, sign(charged), "evt_2").body()).containsEntry("result", "UPDATED");
        Map<String, Object> mine = get("/shops/me/subscription", owner).body();
        assertThat(Instant.parse((String) mine.get("paidUntil"))).isEqualTo(Instant.ofEpochSecond(paidTo));
        assertThat(Instant.parse((String) mine.get("hiddenFrom")))
                .isEqualTo(SubscriptionStore.bookableAfter(Instant.ofEpochSecond(paidTo)));
        assertThat(count("notification_jobs WHERE type = 'SUBSCRIPTION_PAID'")).isEqualTo(1);
        // The same charge sent again under another event id: nothing moves, nobody is told twice
        assertThat(webhook(charged, sign(charged), "evt_2b").body()).containsEntry("result", "UPDATED");
        assertThat(count("notification_jobs WHERE type = 'SUBSCRIPTION_PAID'")).isEqualTo(1);

        // An older event arriving late (authenticated, before the charge) doesn't undo ACTIVE
        String late = event("subscription.authenticated", sub, "authenticated", 0, t + 50);
        assertThat(webhook(late, sign(late), "evt_3").body()).containsEntry("result", "OLD");
        assertThat(get("/shops/me/subscription", owner).body()).containsEntry("razorpayStatus", "ACTIVE");
        // An older charge's period doesn't move paid_until back
        String oldCharge = event("subscription.charged", sub, "active", paidTo - 86_400 * 30, t + 20);
        webhook(oldCharge, sign(oldCharge), "evt_4");
        assertThat(Instant.parse((String) get("/shops/me/subscription", owner).body().get("paidUntil")))
                .isEqualTo(Instant.ofEpochSecond(paidTo));

        // AutoPay stopped: the owner is told, and can pay again
        String halted = event("subscription.halted", sub, "halted", paidTo, t + 200);
        assertThat(webhook(halted, sign(halted), "evt_5").body()).containsEntry("result", "UPDATED");
        assertThat(get("/shops/me/subscription", owner).body()).containsEntry("state", "PAID")
                .containsEntry("canPay", true);
        assertThat(count("notification_jobs WHERE type = 'SUBSCRIPTION_STOPPED'")).isPositive();

        // A subscription no salon has: answered, changes nothing
        String stranger = event("subscription.charged", "sub_NOBODY", "active", paidTo, t + 300);
        assertThat(webhook(stranger, sign(stranger), "evt_6").body()).containsEntry("result", "UNKNOWN");
        // A charge on a subscription the salon replaced meanwhile still counts for it (its notes name the salon)
        long later = paidTo + 86_400L * 30;
        String replaced = event("subscription.charged", "sub_REPLACED", "active", later, t + 400, salon);
        assertThat(webhook(replaced, sign(replaced), "evt_8").body()).containsEntry("result", "CREDITED");
        assertThat(Instant.parse((String) get("/shops/me/subscription", owner).body().get("paidUntil")))
                .isEqualTo(Instant.ofEpochSecond(later));
        assertThat(count("notification_jobs WHERE type = 'SUBSCRIPTION_PAID'")).isEqualTo(2);
        // ...and, still live at Razorpay while no salon has it, it is cancelled so it can't keep charging
        assertThat(CANCELLED).contains("sub_REPLACED");
        // Not about a subscription: ignored
        String payment = "{\"event\":\"payment.captured\",\"payload\":{},\"created_at\":" + t + "}";
        assertThat(webhook(payment, sign(payment), "evt_7").body()).containsEntry("result", "IGNORED");
    }

    // ---------- lapsing ----------

    @Test
    void aLapsedSalonIsHiddenAndTakesNoNewBookingsButKeepsItsBookingsUntilItPays() {
        approve();
        UUID haircut = id(post("/salon/services", Map.of("name", "Haircut", "durationMinutes", 30, "pricePaise", 15000),
                owner));
        UUID raju = barber("Raju", List.of(haircut));
        assertThat(raju).isNotNull();
        // Day 32: the trial ended two days ago, still in its grace: bookable. A booking for the day after the grace
        CLOCK.set(TUESDAY_10_00.plus(Duration.ofDays(32)));
        assertThat(get("/shops/me/subscription", owner).body()).containsEntry("state", "GRACE");
        UUID booking = id(book(at(LocalDate.of(2026, 12, 14), "12:00"), haircut));

        // Sun 13 Dec 23:59, the grace's last day: still visible
        CLOCK.set(HIDDEN.minusSeconds(60));
        assertThat(get("/shops/me/subscription", owner).body()).containsEntry("state", "GRACE");
        assertThat((List<?>) get("/salons?lat=17.466&lng=78.365", customer).rawBody()).hasSize(1);
        // Midnight: hidden
        CLOCK.set(HIDDEN.plusSeconds(1));
        assertThat(get("/shops/me/subscription", owner).body()).containsEntry("state", "HIDDEN");
        List<?> near = (List<?>) get("/salons?lat=17.466&lng=78.365", customer).rawBody();
        assertThat(near).isEmpty();
        assertThat(get("/salons/" + salon, customer).body()).containsEntry("notTakingBookings", true);
        Reply refused = book(at(LocalDate.of(2026, 12, 15), "12:00"), haircut);
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("SALON_NOT_BOOKABLE");
        // The booking made before stays, and its customer may still move it (only new bookings stop)
        assertThat(jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, booking))
                .isEqualTo("CONFIRMED");
        Reply times = get("/salons/" + salon + "/availability?services=" + haircut + "&date=2026-12-15&moving="
                + booking, customer);
        assertThat(times.status()).as(times.text()).isEqualTo(200);
        Reply moved = post("/bookings/" + booking + "/reschedule",
                Map.of("startAt", at(LocalDate.of(2026, 12, 15), "13:00").toString()), customer);
        assertThat(moved.status()).as(moved.text()).isEqualTo(200);

        // It pays: findable again at once
        String sub = (String) post("/shops/me/subscription/checkout", null, owner).body().get("subscriptionId");
        long paidTo = CLOCK.instant().plus(Duration.ofDays(30)).getEpochSecond();
        String charged = event("subscription.charged", sub, "active", paidTo, CLOCK.instant().getEpochSecond());
        webhook(charged, sign(charged), "evt_pay");
        assertThat((List<?>) get("/salons?lat=17.466&lng=78.365", customer).rawBody()).hasSize(1);
        assertThat(get("/salons/" + salon, customer).body()).containsEntry("notTakingBookings", false);
        assertThat(book(at(LocalDate.of(2026, 12, 15), "12:00"), haircut).status()).isEqualTo(201);
    }

    @Test
    void ownersAreToldAWeekBeforeAtTheEndAndWhenHiddenEachOnce() {
        approve();
        Instant end = TUESDAY_10_00.plus(Duration.ofDays(30));
        CLOCK.set(end.minus(Duration.ofDays(8)));
        assertThat(notices.runOnce()).isZero();
        CLOCK.set(end.minus(Duration.ofDays(7)));
        assertThat(notices.runOnce()).isEqualTo(1);
        assertThat(notices.runOnce()).isZero();
        assertThat(count("notification_jobs WHERE type = 'SUBSCRIPTION_ENDING' AND user_id = '" + owner + "'"))
                .isEqualTo(1);
        CLOCK.set(end.plusSeconds(60));
        assertThat(notices.runOnce()).isEqualTo(1);
        assertThat(count("notification_jobs WHERE type = 'SUBSCRIPTION_ENDED'")).isEqualTo(1);
        // "Pay by" names the grace's last day: hidden from midnight starting the day after
        assertThat(count("notification_jobs WHERE type = 'SUBSCRIPTION_ENDED' AND body LIKE '%by 13 Dec 2026, or "
                + "customers won''t find your salon from the next day.'")).isEqualTo(1);
        // The grace's last day, evening: still not hidden, nothing new
        CLOCK.set(HIDDEN.minus(Duration.ofHours(4)));
        assertThat(notices.runOnce()).isZero();
        // Hidden from midnight; told the next morning (no messages at night)
        CLOCK.set(HIDDEN.plus(Duration.ofHours(10)));
        assertThat(notices.runOnce()).isEqualTo(1);
        assertThat(notices.runOnce()).isZero();
        assertThat(count("notification_jobs WHERE type = 'SUBSCRIPTION_HIDDEN'")).isEqualTo(1);
        // The helper is never told: only the owner pays
        assertThat(count("notification_jobs WHERE type LIKE 'SUBSCRIPTION_%' AND user_id = '" + helper + "'"))
                .isZero();
    }

    @Test
    void noMessagesAtNightNorWithAutoPayOnAndASalonThatSkippedStepsGetsOnlyTheLatest() {
        approve();
        Instant end = TUESDAY_10_00.plus(Duration.ofDays(30));
        // 22:30 in India: nothing
        CLOCK.set(LocalDate.ofInstant(end, INDIA).atTime(22, 30).atZone(INDIA).toInstant().plus(Duration.ofDays(5)));
        assertThat(notices.runOnce()).isZero();
        // Next morning, already hidden: only "hidden"
        CLOCK.set(LocalDate.ofInstant(end, INDIA).atTime(10, 0).atZone(INDIA).toInstant().plus(Duration.ofDays(6)));
        assertThat(notices.runOnce()).isEqualTo(1);
        assertThat(count("notification_jobs WHERE type = 'SUBSCRIPTION_HIDDEN'")).isEqualTo(1);
        assertThat(count("notification_jobs WHERE type IN ('SUBSCRIPTION_ENDING', 'SUBSCRIPTION_ENDED')")).isZero();

        // Another salon with AutoPay on: never told
        UUID other = UUID.randomUUID();
        UUID otherOwner = person("9000000003");
        TestData.salon(jdbc, other, otherOwner);
        jdbc.update("UPDATE shops SET status = 'APPROVED' WHERE id = ?", other);
        jdbc.update("INSERT INTO salon_subscriptions (shop_id, trial_ends_at, bookable_until, razorpay_status, "
                + "created_at, updated_at) VALUES (?, ?, ?, 'ACTIVE', now(), now())", other,
                Timestamp.from(CLOCK.instant().minus(Duration.ofHours(12))), Timestamp.from(CLOCK.instant().plus(
                        Duration.ofDays(2))));
        assertThat(notices.runOnce()).isZero();
        assertThat(count("notification_jobs WHERE user_id = '" + otherOwner + "'")).isZero();
    }

    // ---------- admin ----------

    @Test
    void theTeamSeesItAndASuperAdminCanEndOnlyATestSalonsTrial() {
        approve();
        assertThat(get("/admin/shops/" + salon + "/subscription", boss).body()).containsEntry("state", "TRIAL")
                .containsEntry("canPay", false).containsEntry("testSalon", false);
        assertThat(post("/admin/shops/" + salon + "/subscription/end-trial", null, boss).code())
                .isEqualTo("NOT_A_TEST_SALON");

        UUID agent = person("9000000008");
        jdbc.update("UPDATE users SET admin_role = 'ADMIN', admin_designation = 'FIELD_AGENT' WHERE id = ?", agent);
        jdbc.update("UPDATE users SET test_account = true WHERE id = ?", owner);
        assertThat(get("/admin/shops/" + salon + "/subscription", boss).body()).containsEntry("testSalon", true);
        // The reviewer opened the payment screen during the trial (first charge: the trial's end)
        String early = (String) post("/shops/me/subscription/checkout", null, owner).body().get("subscriptionId");
        assertThat(post("/admin/shops/" + salon + "/subscription/end-trial", null, agent).code())
                .isEqualTo("SUPER_ADMIN_ONLY");
        Reply ended = post("/admin/shops/" + salon + "/subscription/end-trial", null, boss);
        assertThat(ended.status()).as(ended.text()).isEqualTo(200);
        assertThat(ended.body()).containsEntry("state", "GRACE");
        assertThat(get("/shops/me/subscription", owner).body()).containsEntry("state", "GRACE")
                .containsEntry("canPay", true);
        assertThat(count("shop_events WHERE shop_id = '" + salon + "' AND note LIKE 'Test salon%'")).isEqualTo(1);
        // That checkout is dropped and cancelled at Razorpay (the reviewer can't approve the old one later); paying now
        // makes a new subscription charged today, not on the old trial's end
        assertThat(CANCELLED).containsExactly(early);
        Reply now = post("/shops/me/subscription/checkout", null, owner);
        assertThat(now.body().get("subscriptionId")).isNotEqualTo(early);
        assertThat((String) now.body().get("description")).contains("first payment today");
        assertThat(RAZORPAY_BODIES.get(RAZORPAY_BODIES.size() - 1)).doesNotContain("start_at");
    }

    // ---------- never two subscriptions, never hidden while "AutoPay on" ----------

    @Test
    void aCheckoutOvertakenWhileRazorpayAnswersIsNeverOfferedAndIsCancelled() throws Exception {
        approve();
        razorpayDelayMs = 1500;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<Reply> tap = pool.submit(() -> post("/shops/me/subscription/checkout", null, owner));
        // Meanwhile the salon is handed over (or closed, or a later tap marks it): this checkout's mark is gone
        Thread.sleep(500);
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> subscriptions.stopAutoPay(salon));
        jdbc.update("UPDATE salon_subscriptions SET creating_since = NULL");
        Reply r = tap.get(10, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(r.status()).as(r.text()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("SUBSCRIPTION_CHANGED");
        assertThat(CANCELLED).containsExactly("sub_TEST" + NEXT_SUB.get());
        assertThat(count("salon_subscriptions WHERE razorpay_subscription_id IS NOT NULL")).isZero();
        assertThat(count("razorpay_cancellations")).isZero();
    }

    @Test
    void afterAFailedAutoPayPayingAgainCancelsTheOldSubscriptionFirstAndNeverMakesTwo() {
        approve();
        String first = autoPayOn();
        long t = CLOCK.instant().getEpochSecond();
        // The first charge failed: Razorpay retries it; the owner is told and may pay another way
        String pending = event("subscription.pending", first, "pending", 0, t + 5);
        assertThat(webhook(pending, sign(pending), "evt_p").body()).containsEntry("result", "UPDATED");
        assertThat(get("/shops/me/subscription", owner).body()).containsEntry("state", "TRIAL")
                .containsEntry("autoPayFailed", true).containsEntry("canPay", true);
        assertThat(count("notification_jobs WHERE type = 'SUBSCRIPTION_FAILED'")).isEqualTo(1);

        // Razorpay can't cancel the old one right now: no new one is made
        cancelStatus = 500;
        assertThat(post("/shops/me/subscription/checkout", null, owner).code()).isEqualTo("PAYMENTS_UNAVAILABLE");
        assertThat(RAZORPAY_BODIES).hasSize(1);
        // Once it can: the old one is cancelled, then the new one made
        cancelStatus = 200;
        Reply again = post("/shops/me/subscription/checkout", null, owner);
        assertThat(again.status()).as(again.text()).isEqualTo(200);
        assertThat(CANCELLED).containsExactly(first);
        assertThat(RAZORPAY_BODIES).hasSize(2);
        assertThat(count("razorpay_cancellations")).isZero();
    }

    @Test
    void theCheckoutsAnswerNeverHidesALaterEventAndAnOverdueAutoPayIsTreatedAsUnpaid() {
        approve();
        String sub = autoPayOn();
        // Razorpay's "authenticated", stamped a second before the app's answer, still counts: the owner is told
        long t = CLOCK.instant().getEpochSecond();
        String authenticated = event("subscription.authenticated", sub, "authenticated", 0, t - 1);
        assertThat(webhook(authenticated, sign(authenticated), "evt_a").body()).containsEntry("result", "UPDATED");
        assertThat(count("notification_jobs WHERE type = 'SUBSCRIPTION_AUTOPAY'")).isEqualTo(1);

        // The trial ended and no charge came (a lost webhook): AutoPay on until a day late, then treated as unpaid
        Instant end = TUESDAY_10_00.plus(Duration.ofDays(30));
        CLOCK.set(end.plus(Duration.ofHours(20)));
        assertThat(get("/shops/me/subscription", owner).body()).containsEntry("state", "AUTOPAY");
        CLOCK.set(end.plus(Duration.ofDays(1)).plusSeconds(60));
        assertThat(get("/shops/me/subscription", owner).body()).containsEntry("state", "GRACE")
                .containsEntry("autoPayFailed", true).containsEntry("canPay", true);
        assertThat(notices.runOnce()).isEqualTo(1);
        assertThat(count("notification_jobs WHERE type = 'SUBSCRIPTION_ENDED' AND title = "
                + "'Your AutoPay payment didn''t go through'")).isEqualTo(1);
        // Paying again cancels the silent one first
        assertThat(post("/shops/me/subscription/checkout", null, owner).status()).isEqualTo(200);
        assertThat(CANCELLED).containsExactly(sub);
    }

    @Test
    void stoppingAutoPayForAClosingOrNewOwnerCancelsItAtRazorpayAndKeepsWhatWasPaid() {
        approve();
        String sub = autoPayOn();
        long paidTo = TUESDAY_10_00.plus(Duration.ofDays(61)).getEpochSecond();
        String charged = event("subscription.charged", sub, "active", paidTo, CLOCK.instant().getEpochSecond());
        webhook(charged, sign(charged), "evt_c");

        // Razorpay down at that moment: the cancellation waits, and the job makes it later
        cancelStatus = 500;
        Boolean stopped = new TransactionTemplate(transactionManager).execute(s -> subscriptions.stopAutoPay(salon));
        assertThat(stopped).isTrue();
        assertThat(CANCELLED).isEmpty();
        assertThat(count("razorpay_cancellations WHERE subscription_id = '" + sub + "'")).isEqualTo(1);
        Map<String, Object> after = get("/shops/me/subscription", owner).body();
        assertThat(after).containsEntry("state", "PAID");
        assertThat(after.get("razorpayStatus")).isNull();
        assertThat(Instant.parse((String) after.get("paidUntil"))).isEqualTo(Instant.ofEpochSecond(paidTo));
        cancelStatus = 200;
        notices.runOnce();
        assertThat(CANCELLED).containsExactly(sub);
        assertThat(count("razorpay_cancellations")).isZero();
        // Its later events find no salon; nothing changes
        String cancelled = event("subscription.cancelled", sub, "cancelled", 0, CLOCK.instant().getEpochSecond() + 9);
        assertThat(webhook(cancelled, sign(cancelled), "evt_x").body()).containsEntry("result", "UNKNOWN");
    }

    @Test
    void switchingHidingOnCountsEveryTrialFromThatDaySoNobodyIsHiddenWithoutWarning() {
        approve();
        // Approved long ago, while hiding was off: its trial ran out long before
        jdbc.update("UPDATE salon_subscriptions SET trial_ends_at = ?, bookable_until = ?, notice_level = 2",
                Timestamp.from(TUESDAY_10_00.minus(Duration.ofDays(60))),
                Timestamp.from(TUESDAY_10_00.minus(Duration.ofDays(57))));
        // A live salon approved by an older version: no row at all
        UUID older = UUID.randomUUID();
        TestData.salon(jdbc, older, person("9000000004"));
        jdbc.update("UPDATE shops SET status = 'APPROVED', ever_approved = true WHERE id = ?", older);
        jdbc.update("DELETE FROM subscription_enforcement");

        subscriptions.beginEnforcement();
        Map<String, Object> mine = get("/shops/me/subscription", owner).body();
        assertThat(mine).containsEntry("state", "TRIAL");
        assertThat(Instant.parse((String) mine.get("trialEndsAt"))).isEqualTo(TUESDAY_10_00.plus(Duration.ofDays(30)));
        assertThat(Instant.parse((String) mine.get("hiddenFrom"))).isEqualTo(HIDDEN);
        assertThat(count("salon_subscriptions WHERE notice_level = 0")).isEqualTo(2);
        assertThat(count("salon_subscriptions WHERE shop_id = '" + older + "'")).isEqualTo(1);

        // Once only: a server starting later changes nothing
        CLOCK.advance(Duration.ofDays(5));
        subscriptions.beginEnforcement();
        assertThat(Instant.parse((String) get("/shops/me/subscription", owner).body().get("trialEndsAt")))
                .isEqualTo(TUESDAY_10_00.plus(Duration.ofDays(30)));
    }

    // ---------- helpers ----------

    /** Hidden from (trial end Thu 10 Dec 10:00 + 3 days, to the next midnight in India). */
    static final Instant HIDDEN = SubscriptionStore.bookableAfter(TUESDAY_10_00.plus(Duration.ofDays(30)));

    /** The owner opens the checkout and approves AutoPay (its signed answer); returns the subscription's id. */
    private String autoPayOn() {
        String sub = (String) post("/shops/me/subscription/checkout", null, owner).body().get("subscriptionId");
        Reply ok = post("/shops/me/subscription/confirm", Map.of("razorpayPaymentId", "pay_1",
                "razorpaySubscriptionId", sub, "razorpaySignature", hmac(KEY_SECRET, "pay_1|" + sub)), owner);
        assertThat(ok.body()).as(ok.text()).containsEntry("state", "AUTOPAY");
        return sub;
    }

    private void approve() {
        jdbc.update("UPDATE shops SET status = 'PENDING_VISIT' WHERE id = ?", salon);
        Reply r = post("/admin/shops/" + salon + "/approve", Map.of("version", version()), boss);
        assertThat(r.status()).as(r.text()).isEqualTo(200);
    }

    private long version() {
        return jdbc.queryForObject("SELECT version FROM shops WHERE id = ?", Long.class, salon);
    }

    private UUID person(String phone) {
        UUID id = UUID.randomUUID();
        TestData.user(jdbc, id, phone);
        return id;
    }

    private UUID barber(String name, List<UUID> services) {
        UUID id = id(post("/salon/staff", Map.of("name", name), owner));
        Reply s = put("/salon/staff/" + id + "/services", Map.of("version", 0, "serviceIds", services), owner);
        List<Map<String, Object>> week = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            week.add(Map.of("weekday", day, "start", "10:00", "end", "21:00"));
        }
        put("/salon/staff/" + id + "/hours", Map.of("version", s.body().get("version"), "hours", week), owner);
        return id;
    }

    private Reply book(Instant start, UUID service) {
        return call(HttpMethod.POST, "/bookings", Map.of("salonId", salon.toString(), "serviceIds", List.of(service),
                "startAt", start.toString()), customer, UUID.randomUUID());
    }

    private static Instant at(LocalDate day, String hhmm) {
        return day.atTime(LocalTime.parse(hhmm)).atZone(INDIA).toInstant();
    }

    private static String event(String event, String sub, String status, long currentEnd, long createdAt) {
        return event(event, sub, status, currentEnd, createdAt, null);
    }

    private static String event(String event, String sub, String status, long currentEnd, long createdAt,
                                UUID shop) {
        return "{\"entity\":\"event\",\"account_id\":\"acc_1\",\"event\":\"" + event + "\",\"contains\":"
                + "[\"subscription\"],\"payload\":{\"subscription\":{\"entity\":{\"id\":\"" + sub + "\",\"entity\":"
                + "\"subscription\",\"plan_id\":\"plan_500\",\"status\":\"" + status + "\",\"current_end\":"
                + (currentEnd == 0 ? "null" : Long.toString(currentEnd)) + ",\"notes\":{"
                + (shop == null ? "" : "\"shop_id\":\"" + shop + "\"") + "}}}},\"created_at\":"
                + createdAt + "}";
    }

    private static String sign(String body) {
        return hmac(WEBHOOK_SECRET, body);
    }

    private static String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private Reply webhook(String body, String signature, String eventId) {
        return http.post().uri("/payments/razorpay/webhook").contentType(MediaType.APPLICATION_JSON).headers(h -> {
            if (signature != null) {
                h.set("X-Razorpay-Signature", signature);
            }
            h.set("X-Razorpay-Event-Id", eventId);
        }).body(body.getBytes(StandardCharsets.UTF_8)).exchange((rq, res) -> read(res));
    }

    private long count(String fromWhere) {
        return jdbc.queryForObject("SELECT count(*) FROM " + fromWhere, Long.class);
    }

    private String token(UUID who) {
        Boolean admin = jdbc.queryForObject("SELECT admin_role <> 'NONE' FROM users WHERE id = ?", Boolean.class,
                who);
        return jwt.issue(new AuthPrincipal(who, null, null, Boolean.TRUE.equals(admin)),
                sessions.currentGeneration(who)).token();
    }

    private Reply get(String path, UUID who) {
        return call(HttpMethod.GET, path, null, who, null);
    }

    private Reply post(String path, Object body, UUID who) {
        return call(HttpMethod.POST, path, body, who, null);
    }

    private Reply put(String path, Object body, UUID who) {
        return call(HttpMethod.PUT, path, body, who, null);
    }

    private Reply call(HttpMethod method, String path, Object body, UUID who, UUID key) {
        RestClient.RequestBodySpec spec = http.method(method).uri(path).headers(h -> {
            if (who != null) {
                h.setBearerAuth(token(who));
            }
            if (key != null) {
                h.set("Idempotency-Key", key.toString());
            }
        });
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((rq, res) -> read(res));
    }

    private static Reply read(org.springframework.http.client.ClientHttpResponse res) throws IOException {
        int status = res.getStatusCode().value();
        String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
        Object parsed = text.isBlank() ? Map.of() : JSON.readValue(text, Object.class);
        return new Reply(status, parsed, text, res.getHeaders().getFirst("Retry-After"));
    }

    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

    private static UUID id(Reply r) {
        assertThat(r.status()).as(r.text()).isBetween(200, 201);
        return UUID.fromString((String) r.body().get("id"));
    }

    private record Reply(int status, Object rawBody, String text, String retryAfter) {

        @SuppressWarnings("unchecked")
        Map<String, Object> body() {
            return rawBody instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        }

        String code() {
            return (String) body().get("code");
        }
    }
}
