package in.apnidukan.shops;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import in.apnidukan.common.security.AuthPrincipal;
import in.apnidukan.common.security.JwtService;
import in.apnidukan.common.security.SessionRevocations;
import in.apnidukan.shops.service.PlayReports;
import in.apnidukan.shops.service.PlaySubscriptions;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Paying the subscription in the Play Store app (user choice billing): Razorpay picked on Google's choice screen (its
 * payments reported to Google), or Google Play picked (its purchase checked, acknowledged, followed). Real Postgres
 * and Redis; one local fake standing in for Razorpay, Google's sign-in and the Google Play Developer API.
 */
@EnabledIf("in.apnidukan.support.TestContainers#dockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({CapturingSenders.class, InAppSubscriptionIntegrationTest.Fakes.class})
class InAppSubscriptionIntegrationTest {

    static final Instant TUESDAY_10_00 = Instant.parse("2026-11-10T04:30:00Z");
    static final TestClock CLOCK = new TestClock(TUESDAY_10_00);
    static final String KEY_SECRET = "test-key-secret";
    static final String WEBHOOK_SECRET = "test-webhook-secret";
    static final String PUSH_SECRET = "push-secret-123";
    static final String PRODUCT = "clozest_salon_monthly";
    static final String APP_TOKEN = "ext-token-ABCDEFGHIJ";
    static final JsonMapper JSON = JsonMapper.builder().build();

    static final AtomicInteger NEXT_SUB = new AtomicInteger();
    static final List<String> CANCELLED = new CopyOnWriteArrayList<>();
    /** What the fake Google Play got: "METHOD path?query body". */
    static final List<String> GOOGLE = new CopyOnWriteArrayList<>();
    /** How the fake answers external transaction reports (by default 200). */
    static volatile int reportStatus = 200;
    /** How the fake answers acknowledge and cancel calls (by default 200). */
    static volatile int ackStatus = 200;
    static volatile int cancelStatus = 200;
    /** The Google Play purchases the fake knows: token → its subscriptionsv2 JSON. */
    static final Map<String, String> PURCHASES = new ConcurrentHashMap<>();
    private static final HttpServer FAKE = fake();
    private static final String KEY_FILE = keyFile();

    private static HttpServer fake() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setExecutor(Executors.newFixedThreadPool(4));
            server.createContext("/v1/subscriptions", exchange -> {
                String path = exchange.getRequestURI().getPath();
                exchange.getRequestBody().readAllBytes();
                if (path.endsWith("/cancel")) {
                    CANCELLED.add(path.split("/")[3]);
                    answer(exchange, 200, "{}");
                    return;
                }
                answer(exchange, 200, "{\"id\":\"sub_APP" + NEXT_SUB.incrementAndGet()
                        + "\",\"entity\":\"subscription\",\"status\":\"created\"}");
            });
            server.createContext("/token", exchange -> {
                exchange.getRequestBody().readAllBytes();
                answer(exchange, 200, "{\"access_token\":\"play-access\",\"expires_in\":3599}");
            });
            server.createContext("/androidpublisher", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String query = exchange.getRequestURI().getQuery();
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                GOOGLE.add(exchange.getRequestMethod() + " " + path + (query == null ? "" : "?" + query) + " " + body);
                if (path.contains("/purchases/subscriptionsv2/tokens/")) {
                    String token = path.substring(path.lastIndexOf('/') + 1);
                    String found = PURCHASES.get(token);
                    answer(exchange, found == null ? 404 : 200, found == null ? "{}" : found);
                } else if (path.endsWith("/externalTransactions")) {
                    answer(exchange, reportStatus, "{\"error\":{\"message\":\"nope\"}}");
                } else if (path.endsWith(":acknowledge")) {
                    answer(exchange, ackStatus, "{}");
                } else {
                    answer(exchange, cancelStatus, "{}");   // cancel
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void answer(HttpExchange exchange, int status, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    private static String keyFile() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            String marker = "PRIVATE " + "KEY-----";   // split, so the secret scanner doesn't take a test key for a leak
            String pem = "-----BEGIN " + marker + "\n" + Base64.getMimeEncoder().encodeToString(
                    g.generateKeyPair().getPrivate().getEncoded()) + "\n-----END " + marker + "\n";
            return JSON.writeValueAsString(Map.of("type", "service_account", "project_id", "clozest",
                    "private_key_id", "k1", "private_key", pem, "client_email", "play@clozest.iam.gserviceaccount.com",
                    "token_uri", "http://localhost:" + FAKE.getAddress().getPort() + "/token"));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
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
        String base = "http://localhost:" + FAKE.getAddress().getPort();
        registry.add("app.razorpay.key-id", () -> "rzp_test_abc");
        registry.add("app.razorpay.key-secret", () -> KEY_SECRET);
        registry.add("app.razorpay.plan-id", () -> "plan_500");
        registry.add("app.razorpay.webhook-secret", () -> WEBHOOK_SECRET);
        registry.add("app.razorpay.endpoint", () -> base);
        registry.add("app.google-play.service-account", () -> KEY_FILE);
        registry.add("app.google-play.product-id", () -> PRODUCT);
        registry.add("app.google-play.endpoint", () -> base);
        registry.add("app.google-play.notification-secret", () -> PUSH_SECRET);
    }

    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired JwtService jwt;
    @Autowired SessionRevocations sessions;
    @Autowired PlayReports reports;
    @Autowired PlaySubscriptions play;

    RestClient http;
    UUID boss;
    UUID owner;
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
        CANCELLED.clear();
        GOOGLE.clear();
        PURCHASES.clear();
        reportStatus = 200;
        ackStatus = 200;
        cancelStatus = 200;
        boss = person("9000000009");
        jdbc.update("UPDATE users SET admin_role = 'SUPER_ADMIN', name = 'Prem' WHERE id = ?", boss);
        owner = person("9000000001");
        salon = UUID.randomUUID();
        TestData.salon(jdbc, salon, owner);
        jdbc.update("UPDATE shops SET status = 'PENDING_VISIT' WHERE id = ?", salon);
        Reply r = post("/admin/shops/" + salon + "/approve", Map.of("version", jdbc.queryForObject(
                "SELECT version FROM shops WHERE id = ?", Long.class, salon)), boss);
        assertThat(r.status()).as(r.text()).isEqualTo(200);
    }

    // ---------- Razorpay picked on Google's choice screen ----------

    @Test
    void theAppSaysPayingInTheAppIsPossibleFromWhenAndWhichProductToBuy() {
        Map<String, Object> view = get("/shops/me/subscription", owner).body();
        assertThat(view).containsEntry("inAppProductId", PRODUCT).containsEntry("googlePlay", false);
        // From the trial's end (15 minutes before it: the first payment is then at once)
        assertThat(Instant.parse((String) view.get("inAppPayFrom")))
                .isEqualTo(Instant.parse((String) view.get("accessUntil")).minus(Duration.ofMinutes(15)));
    }

    @Test
    void duringTheTrialTheAppCantOpenAPaymentButTheWebsiteStillCan() {
        Reply r = post("/shops/me/subscription/checkout", Map.of("externalTransactionToken", APP_TOKEN), owner);
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("PAY_IN_APP_LATER");
        assertThat(checkout(null)).isNotNull();
        assertThat(count("salon_subscriptions WHERE app_billing_token IS NOT NULL")).isZero();
    }

    @Test
    void razorpayFromTheAppIsReportedToGoogleTheFirstPaymentWithTheTokenThenEachCharge() throws Exception {
        trialOver();
        String sub = checkout(APP_TOKEN);
        long t = CLOCK.instant().getEpochSecond();
        // Approving AutoPay is no payment: nothing to report
        webhook(event("subscription.authenticated", sub, "authenticated", 0, t, null), "e0");
        assertThat(count("play_external_transactions")).isZero();
        assertThat(webhook(event("subscription.charged", sub, "active", t + 30 * 86400, t, "pay_A"), "e1").status())
                .isEqualTo(200);
        // A replayed webhook (another event id, the same payment) adds nothing
        webhook(event("subscription.charged", sub, "active", t + 30 * 86400, t, "pay_A"), "e1b");
        CLOCK.advance(Duration.ofDays(30));
        webhook(event("subscription.charged", sub, "active", t + 60 * 86400, t + 30 * 86400, "pay_B"), "e2");
        assertThat(jdbc.queryForList("SELECT id || ' ' || payment_id || ' ' || amount_paise || ' ' || tax_state FROM "
                + "play_external_transactions ORDER BY created_at, id", String.class))
                .containsExactly("s-" + sub + " pay_A 50000 TELANGANA", "p-pay_B pay_B 50000 TELANGANA");

        assertThat(reports.runOnce()).isEqualTo(2);
        List<String> sent = GOOGLE.stream().filter(g -> g.contains("/externalTransactions")).toList();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0)).contains("externalTransactionId=s-" + sub);
        JsonNode first = JSON.readTree(sent.get(0).substring(sent.get(0).indexOf(' ', 5) + 1));
        assertThat(first.path("recurringTransaction").path("externalTransactionToken").asString()).isEqualTo(APP_TOKEN);
        assertThat(first.path("recurringTransaction").path("externalSubscription").path("subscriptionType")
                .asString()).isEqualTo("RECURRING");
        assertThat(first.path("originalPreTaxAmount").path("priceMicros").asString()).isEqualTo("500000000");
        assertThat(first.path("userTaxAddress").path("administrativeArea").asString()).isEqualTo("TELANGANA");
        JsonNode charge = JSON.readTree(sent.get(1).substring(sent.get(1).indexOf(' ', 5) + 1));
        assertThat(charge.path("recurringTransaction").path("initialExternalTransactionId").asString())
                .isEqualTo("s-" + sub);
        assertThat(charge.path("originalPreTaxAmount").path("priceMicros").asString()).isEqualTo("500000000");
        assertThat(jdbc.queryForList("SELECT DISTINCT status FROM play_external_transactions", String.class))
                .containsExactly("DONE");
    }

    @Test
    void theWebsiteNeverReusesTheAppsCheckoutSoItsPaymentsAreNeverReported() {
        trialOver();
        String app = checkout(APP_TOKEN);
        // The owner then pays on the website within the hour: a new subscription of its own; the app's is cancelled
        String web = checkout(null);
        assertThat(web).isNotEqualTo(app);
        assertThat(CANCELLED).contains(app);
        webhook(event("subscription.charged", web, "active", CLOCK.instant().getEpochSecond() + 30 * 86400,
                CLOCK.instant().getEpochSecond(), "pay_W"), "w1");
        assertThat(count("play_external_transactions")).isZero();
        // And the app, back again, gets its own (with the new token), never the website's
        CLOCK.advance(Duration.ofMinutes(1));
        jdbc.update("UPDATE salon_subscriptions SET paid_until = NULL, razorpay_status = 'CREATED'");
        assertThat(checkout(APP_TOKEN)).isNotEqualTo(web);
    }

    @Test
    void googleNotAnsweringIsTriedAgainLaterARefusalIsKeptWithItsChargesAndA409CountsAsDone() {
        trialOver();
        String sub = checkout(APP_TOKEN);
        long t = CLOCK.instant().getEpochSecond();
        webhook(event("subscription.charged", sub, "active", t + 30 * 86400, t, "pay_1"), "x1");
        reportStatus = 503;
        assertThat(reports.runOnce()).isZero();
        assertThat(jdbc.queryForObject("SELECT status || ' ' || attempts FROM play_external_transactions",
                String.class)).isEqualTo("PENDING 1");
        // Not due yet: nothing sent
        GOOGLE.clear();
        reports.runOnce();
        assertThat(GOOGLE).isEmpty();
        CLOCK.advance(Duration.ofMinutes(5));
        reportStatus = 409;
        assertThat(reports.runOnce()).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM play_external_transactions", String.class))
                .isEqualTo("DONE");

        String second = checkoutAfterReset();
        long t2 = CLOCK.instant().getEpochSecond();
        webhook(event("subscription.charged", second, "active", t2 + 30 * 86400, t2, "pay_2"), "x2");
        reportStatus = 400;
        reports.runOnce();
        assertThat(jdbc.queryForObject("SELECT status FROM play_external_transactions WHERE id = ?", String.class,
                "s-" + second)).isEqualTo("REFUSED");
        // Its next charge can't go either: kept as refused at once, Google not asked, never retried every minute
        webhook(event("subscription.charged", second, "active", t2 + 60 * 86400, t2 + 30 * 86400, "pay_3"), "x3");
        GOOGLE.clear();
        reportStatus = 200;
        reports.runOnce();
        assertThat(GOOGLE).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM play_external_transactions WHERE id = 'p-pay_3'",
                String.class)).isEqualTo("REFUSED");
    }

    @Test
    void twoServersRunningTheReportsAtOnceNeverSendOneTwice() throws Exception {
        trialOver();
        String sub = checkout(APP_TOKEN);
        long t = CLOCK.instant().getEpochSecond();
        webhook(event("subscription.charged", sub, "active", t + 30 * 86400, t, "pay_1"), "y1");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> reports.runOnce());
            var b = pool.submit(() -> reports.runOnce());
            assertThat(a.get() + b.get()).isOne();
        } finally {
            pool.shutdown();
        }
        assertThat(GOOGLE.stream().filter(g -> g.contains("/externalTransactions")).count()).isOne();
    }

    @Test
    void notGooglesTokenIsRefused() {
        trialOver();
        Reply r = post("/shops/me/subscription/checkout", Map.of("externalTransactionToken", "bad token!"), owner);
        assertThat(r.status()).isEqualTo(400);
        assertThat(count("salon_subscriptions WHERE app_billing_token IS NOT NULL")).isZero();
    }

    // ---------- Google Play picked ----------

    @Test
    void aGooglePlayPurchaseIsCheckedAcknowledgedAndPaysTheSalon() {
        Instant expires = TUESDAY_10_00.plus(Duration.ofDays(60));
        PURCHASES.put("gp-token-1", purchase(salon, expires, true, false));
        Reply r = post("/shops/me/subscription/google-play", Map.of("purchaseToken", "gp-token-1"), owner);
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        assertThat(r.body()).containsEntry("state", "AUTOPAY").containsEntry("googlePlay", true);
        assertThat(paidUntil()).isEqualTo(expires);
        assertThat(GOOGLE).anyMatch(g -> g.startsWith("POST") && g.contains("gp-token-1:acknowledge"));
        assertThat(count("salon_subscriptions WHERE play_acknowledged")).isOne();
        // The website's checkout now says there's nothing to pay (it would charge twice)
        assertThat(post("/shops/me/subscription/checkout", null, owner).code()).isEqualTo("ALREADY_SUBSCRIBED");
        // Sent again: fine
        assertThat(post("/shops/me/subscription/google-play", Map.of("purchaseToken", "gp-token-1"), owner).status())
                .isEqualTo(200);
    }

    @Test
    void googleNotTakingTheAcknowledgementIsNoErrorAndTheRecheckDoesItMinutesLater() {
        PURCHASES.put("gp-ack-00001", purchase(salon, TUESDAY_10_00.plus(Duration.ofDays(60)), true, false));
        ackStatus = 503;
        Reply r = post("/shops/me/subscription/google-play", Map.of("purchaseToken", "gp-ack-00001"), owner);
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        assertThat(r.body()).containsEntry("state", "AUTOPAY");
        assertThat(count("salon_subscriptions WHERE play_acknowledged")).isZero();
        // Long before its end: the recheck still asks again within minutes, and acknowledges it
        ackStatus = 200;
        CLOCK.advance(Duration.ofMinutes(6));
        assertThat(play.recheck()).isOne();
        assertThat(count("salon_subscriptions WHERE play_acknowledged")).isOne();
        // Acknowledged and far from its end: not asked again
        CLOCK.advance(Duration.ofMinutes(6));
        assertThat(play.recheck()).isZero();
    }

    @Test
    void aPurchaseForAnotherSalonOrAnotherProductOrUnknownOrATestOneIsRefused() {
        PURCHASES.put("gp-other-00001", purchase(UUID.randomUUID(), TUESDAY_10_00.plus(Duration.ofDays(30)), true, true));
        assertThat(post("/shops/me/subscription/google-play", Map.of("purchaseToken", "gp-other-00001"), owner).code())
                .isEqualTo("PLAY_PURCHASE_INVALID");
        assertThat(post("/shops/me/subscription/google-play", Map.of("purchaseToken", "gp-unknown"), owner).code())
                .isEqualTo("PLAY_PURCHASE_INVALID");
        PURCHASES.put("gp-wrong-00001", purchase(salon, TUESDAY_10_00.plus(Duration.ofDays(30)), true, true)
                .replace(PRODUCT, "something_else"));
        assertThat(post("/shops/me/subscription/google-play", Map.of("purchaseToken", "gp-wrong-00001"), owner).code())
                .isEqualTo("PLAY_PURCHASE_INVALID");
        // A licence tester's free purchase never pays a real salon (GOOGLE_PLAY_ALLOW_TEST_PURCHASES is off)
        PURCHASES.put("gp-test-00001", purchase(salon, TUESDAY_10_00.plus(Duration.ofDays(30)), true, true)
                .replace("{\"subscriptionState\"", "{\"testPurchase\":{},\"subscriptionState\""));
        assertThat(post("/shops/me/subscription/google-play", Map.of("purchaseToken", "gp-test-00001"), owner).code())
                .isEqualTo("PLAY_PURCHASE_INVALID");
        assertThat(count("salon_subscriptions WHERE play_purchase_token IS NOT NULL")).isZero();
    }

    @Test
    void aFreeTrialOrGooglesGracePeriodIsNeverCountedAsPaid() {
        PURCHASES.put("gp-free-00001", purchase(salon, TUESDAY_10_00.plus(Duration.ofDays(90)), true, false)
                .replace("\"autoRenewingPlan\"", "\"offerPhase\":{\"freeTrial\":{}},\"autoRenewingPlan\""));
        post("/shops/me/subscription/google-play", Map.of("purchaseToken", "gp-free-00001"), owner);
        assertThat(jdbc.queryForObject("SELECT paid_until FROM salon_subscriptions", java.sql.Timestamp.class))
                .isNull();
        PURCHASES.put("gp-free-00001", purchase(salon, TUESDAY_10_00.plus(Duration.ofDays(90)), true, true)
                .replace("SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD"));
        push(PUSH_SECRET, "gp-free-00001");
        assertThat(jdbc.queryForObject("SELECT paid_until FROM salon_subscriptions", java.sql.Timestamp.class))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_jobs WHERE type = 'SUBSCRIPTION_PAID'",
                Integer.class)).isZero();
    }

    @Test
    void buyingWithGooglePlayStopsAnyLiveRazorpaySubscriptionEvenOneNotApprovedYet() {
        String approved = checkout(null);
        assertThat(post("/shops/me/subscription/confirm", Map.of("razorpayPaymentId", "pay_1",
                "razorpaySubscriptionId", approved, "razorpaySignature", hmac(KEY_SECRET, "pay_1|" + approved)), owner)
                .status()).isEqualTo(200);
        PURCHASES.put("gp-token-2", purchase(salon, TUESDAY_10_00.plus(Duration.ofDays(60)), true, true));
        post("/shops/me/subscription/google-play", Map.of("purchaseToken", "gp-token-2"), owner);
        assertThat(CANCELLED).contains(approved);

        // A checkout opened on the website and not approved yet: cancelled too, and if Razorpay's approval still comes
        // through (it was approved just before), that subscription is cancelled again and nothing is said about it
        jdbc.update("UPDATE salon_subscriptions SET razorpay_subscription_id = 'sub_LATE', razorpay_status = 'CREATED'");
        CANCELLED.clear();
        webhook(event("subscription.authenticated", "sub_LATE", "authenticated", 0, CLOCK.instant().getEpochSecond(),
                null), "late1");
        assertThat(CANCELLED).contains("sub_LATE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_jobs WHERE type = 'SUBSCRIPTION_AUTOPAY'",
                Integer.class)).isZero();
    }

    @Test
    void googlesNotificationsNeedTheSecretAndMoveThePaidEndARenewalAtATime() throws Exception {
        Instant first = TUESDAY_10_00.plus(Duration.ofDays(60));
        PURCHASES.put("gp-token-3", purchase(salon, first, true, true));
        post("/shops/me/subscription/google-play", Map.of("purchaseToken", "gp-token-3"), owner);
        Instant renewed = first.plus(Duration.ofDays(30));
        PURCHASES.put("gp-token-3", purchase(salon, renewed, true, true));
        assertThat(push("wrong", "gp-token-3").status()).isEqualTo(403);
        assertThat(push(PUSH_SECRET, "gp-token-3").body()).containsEntry("result", "UPDATED");
        assertThat(paidUntil()).isEqualTo(renewed);
        // Cancelled by the owner in the Play Store: paid time stays, no longer AutoPay
        PURCHASES.put("gp-token-3", purchase(salon, renewed, false, true).replace("SUBSCRIPTION_STATE_ACTIVE",
                "SUBSCRIPTION_STATE_CANCELED"));
        push(PUSH_SECRET, "gp-token-3");
        assertThat(get("/shops/me/subscription", owner).body()).containsEntry("googlePlay", false)
                .containsEntry("state", "PAID");
    }

    @Test
    void anOlderAnswerFromGoogleNeverOverwritesANewerOne() {
        PURCHASES.put("gp-old-000001", purchase(salon, TUESDAY_10_00.plus(Duration.ofDays(60)), true, true));
        post("/shops/me/subscription/google-play", Map.of("purchaseToken", "gp-old-000001"), owner);
        // A newer answer is in already (the owner cancelled; written by a notification asked later than this one)
        jdbc.update("UPDATE salon_subscriptions SET play_auto_renewing = false, play_checked_at = ?",
                java.sql.Timestamp.from(TUESDAY_10_00.plus(Duration.ofMinutes(10))));
        push(PUSH_SECRET, "gp-old-000001");   // asked at 10:00, Google still said renewing
        assertThat(count("salon_subscriptions WHERE play_auto_renewing")).isZero();
    }

    @Test
    void aRefundTakesBackTheTimeGooglePlayGave() throws Exception {
        Instant expires = TUESDAY_10_00.plus(Duration.ofDays(60));
        PURCHASES.put("gp-refund-00001", purchase(salon, expires, true, true));
        post("/shops/me/subscription/google-play", Map.of("purchaseToken", "gp-refund-00001"), owner);
        assertThat(paidUntil()).isEqualTo(expires);
        // Refunded and revoked: Google says it expired now
        CLOCK.advance(Duration.ofHours(1));
        PURCHASES.put("gp-refund-00001", purchase(salon, CLOCK.instant(), false, true)
                .replace("SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_EXPIRED"));
        assertThat(voided("gp-refund-00001").body()).containsEntry("result", "UPDATED");
        assertThat(paidUntil()).isEqualTo(CLOCK.instant());
        assertThat(get("/shops/me/subscription", owner).body()).containsEntry("googlePlay", false)
                .containsEntry("state", "TRIAL");
    }

    @Test
    void aSecondPurchaseReplacesTheFirstWhichStopsRenewingAndIsNeverAttachedAgain() {
        PURCHASES.put("gp-a-0000001", purchase(salon, TUESDAY_10_00.plus(Duration.ofDays(60)), true, true));
        post("/shops/me/subscription/google-play", Map.of("purchaseToken", "gp-a-0000001"), owner);
        // Another Google account on the counter phone buys too
        PURCHASES.put("gp-b-0000001", purchase(salon, TUESDAY_10_00.plus(Duration.ofDays(61)), true, true));
        assertThat(post("/shops/me/subscription/google-play", Map.of("purchaseToken", "gp-b-0000001"), owner).status())
                .isEqualTo(200);
        assertThat(count("salon_subscriptions WHERE play_purchase_token = 'gp-b-0000001'")).isOne();
        assertThat(count("play_dropped_purchases WHERE purchase_token = 'gp-a-0000001'")).isOne();
        play.recheck();
        assertThat(GOOGLE).anyMatch(g -> g.contains("gp-a-0000001:cancel"));
        // The first one's later notice changes nothing
        assertThat(push(PUSH_SECRET, "gp-a-0000001").body()).containsEntry("result", "UNKNOWN");
        assertThat(count("salon_subscriptions WHERE play_purchase_token = 'gp-b-0000001'")).isOne();
        assertThat(post("/shops/me/subscription/google-play", Map.of("purchaseToken", "gp-a-0000001"), owner).code())
                .isEqualTo("PLAY_PURCHASE_TAKEN");
    }

    @Test
    void anOldCancelledPurchaseFoundLaterNeverReplacesTheRenewingOneAndIsNotAcknowledged() {
        PURCHASES.put("gp-live-00001", purchase(salon, TUESDAY_10_00.plus(Duration.ofDays(60)), true, true));
        post("/shops/me/subscription/google-play", Map.of("purchaseToken", "gp-live-00001"), owner);
        // Bought earlier on another phone and cancelled there; that phone's app sends it when the page opens
        PURCHASES.put("gp-gone-00001", purchase(salon, TUESDAY_10_00.plus(Duration.ofDays(20)), false, false)
                .replace("SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_CANCELED"));
        GOOGLE.clear();
        assertThat(post("/shops/me/subscription/google-play", Map.of("purchaseToken", "gp-gone-00001"), owner).status())
                .isEqualTo(200);
        assertThat(count("salon_subscriptions WHERE play_purchase_token = 'gp-live-00001'")).isOne();
        assertThat(count("play_dropped_purchases")).isZero();
        assertThat(GOOGLE).noneMatch(g -> g.contains(":acknowledge") || g.contains(":cancel"));
    }

    @Test
    void aNewPurchaseAfterTheOldOneEndedIsFoundThroughGooglesNotification() {
        PURCHASES.put("gp-old2-00001", purchase(salon, TUESDAY_10_00.plus(Duration.ofDays(60)), false, true)
                .replace("SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_CANCELED"));
        post("/shops/me/subscription/google-play", Map.of("purchaseToken", "gp-old2-00001"), owner);
        CLOCK.advance(Duration.ofDays(61));
        // Bought again; the app closed before telling us
        PURCHASES.put("gp-new2-00001", purchase(salon, CLOCK.instant().plus(Duration.ofDays(30)), true, false));
        assertThat(push(PUSH_SECRET, "gp-new2-00001").body()).containsEntry("result", "UPDATED");
        assertThat(count("salon_subscriptions WHERE play_purchase_token = 'gp-new2-00001' AND play_acknowledged")).isOne();
        assertThat(paidUntil()).isEqualTo(CLOCK.instant().plus(Duration.ofDays(30)));
    }

    @Test
    void aPurchaseTheAppNeverConfirmedIsFoundThroughGooglesNotificationAndAcknowledged() throws Exception {
        PURCHASES.put("gp-token-4", purchase(salon, TUESDAY_10_00.plus(Duration.ofDays(60)), true, false));
        assertThat(push(PUSH_SECRET, "gp-token-4").body()).containsEntry("result", "UPDATED");
        assertThat(count("salon_subscriptions WHERE play_purchase_token = 'gp-token-4'")).isOne();
        assertThat(GOOGLE).anyMatch(g -> g.contains("gp-token-4:acknowledge"));
    }

    @Test
    void googlesNoticeAndTheAppsConfirmArrivingTogetherBothEndWithThePurchaseOnce() throws Exception {
        PURCHASES.put("gp-race-00001", purchase(salon, TUESDAY_10_00.plus(Duration.ofDays(60)), true, false));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> post("/shops/me/subscription/google-play", Map.of("purchaseToken", "gp-race-00001"),
                    owner).status());
            var b = pool.submit(() -> push(PUSH_SECRET, "gp-race-00001").status());
            assertThat(a.get()).isEqualTo(200);
            assertThat(b.get()).isEqualTo(200);
        } finally {
            pool.shutdown();
        }
        assertThat(count("salon_subscriptions WHERE play_purchase_token = 'gp-race-00001'")).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_jobs WHERE type = 'SUBSCRIPTION_PAID'",
                Integer.class)).isPositive();
    }

    @Test
    void closingTheSalonStopsItsGooglePlaySubscriptionRenewingUntilGoogleTakesItAndNeverAttachesItAgain() {
        PURCHASES.put("gp-token-5", purchase(salon, TUESDAY_10_00.plus(Duration.ofDays(60)), true, true));
        post("/shops/me/subscription/google-play", Map.of("purchaseToken", "gp-token-5"), owner);
        cancelStatus = 503;   // Google not answering at the moment of closing
        TestData.unlockEditing(jdbc, redis);
        Reply closed = post("/shops/me/close", Map.of("version", jdbc.queryForObject(
                "SELECT version FROM shops WHERE id = ?", Long.class, salon), "reason", "Moving"), owner);
        assertThat(closed.status()).as(closed.text()).isEqualTo(200);
        assertThat(GOOGLE).anyMatch(g -> g.contains("gp-token-5:cancel"));
        assertThat(count("salon_subscriptions WHERE play_purchase_token IS NOT NULL")).isZero();
        assertThat(count("play_dropped_purchases WHERE NOT cancelled")).isOne();
        // Tried again by the recheck until Google takes it
        cancelStatus = 200;
        CLOCK.advance(Duration.ofHours(1));
        play.recheck();
        assertThat(count("play_dropped_purchases WHERE cancelled")).isOne();
        // Google's notice about the cancellation never brings it back
        PURCHASES.put("gp-token-5", purchase(salon, TUESDAY_10_00.plus(Duration.ofDays(60)), false, true)
                .replace("SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_CANCELED"));
        assertThat(push(PUSH_SECRET, "gp-token-5").body()).containsEntry("result", "UNKNOWN");
        assertThat(count("salon_subscriptions WHERE play_purchase_token IS NOT NULL")).isZero();
    }

    // ---------- helpers ----------

    private String checkout(String token) {
        Reply r = post("/shops/me/subscription/checkout", token == null ? null
                : Map.of("externalTransactionToken", token), owner);
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        return (String) r.body().get("subscriptionId");
    }

    /** The salon's free trial ended yesterday (the app offers paying from then). */
    private void trialOver() {
        Instant ended = CLOCK.instant().minus(Duration.ofDays(1));
        jdbc.update("UPDATE salon_subscriptions SET trial_ends_at = ?, bookable_until = ?",
                java.sql.Timestamp.from(ended), java.sql.Timestamp.from(ended.plus(Duration.ofDays(4))));
    }

    private Instant paidUntil() {
        return jdbc.queryForObject("SELECT paid_until FROM salon_subscriptions", java.sql.Timestamp.class).toInstant();
    }

    /** A new subscription (the old one is over an hour old) opened from the app. */
    private String checkoutAfterReset() {
        CLOCK.advance(Duration.ofHours(2));
        jdbc.update("UPDATE salon_subscriptions SET razorpay_status = 'CANCELLED', paid_until = NULL");
        trialOver();
        return checkout(APP_TOKEN);
    }

    private static String purchase(UUID shop, Instant expires, boolean autoRenew, boolean acknowledged) {
        return "{\"subscriptionState\":\"SUBSCRIPTION_STATE_ACTIVE\",\"acknowledgementState\":\""
                + (acknowledged ? "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED" : "ACKNOWLEDGEMENT_STATE_PENDING")
                + "\",\"externalAccountIdentifiers\":{\"obfuscatedExternalAccountId\":\"" + shop + "\"},"
                + "\"lineItems\":[{\"productId\":\"" + PRODUCT + "\",\"expiryTime\":\"" + expires + "\","
                + "\"autoRenewingPlan\":{\"autoRenewEnabled\":" + autoRenew + "}}]}";
    }

    private Reply push(String secret, String token) {
        String note = "{\"version\":\"1.0\",\"packageName\":\"com.clozest.partner\",\"subscriptionNotification\":"
                + "{\"notificationType\":2,\"purchaseToken\":\"" + token + "\",\"subscriptionId\":\"" + PRODUCT + "\"}}";
        String body = "{\"message\":{\"data\":\"" + Base64.getEncoder().encodeToString(
                note.getBytes(StandardCharsets.UTF_8)) + "\",\"messageId\":\"1\"},\"subscription\":\"s\"}";
        return http.post().uri("/payments/google-play/notifications?secret=" + secret)
                .contentType(MediaType.APPLICATION_JSON).body(body.getBytes(StandardCharsets.UTF_8))
                .exchange((rq, res) -> read(res));
    }

    private Reply voided(String token) {
        String note = "{\"version\":\"1.0\",\"packageName\":\"com.clozest.partner\",\"voidedPurchaseNotification\":"
                + "{\"purchaseToken\":\"" + token + "\",\"orderId\":\"GPA.1\",\"productType\":1}}";
        String body = "{\"message\":{\"data\":\"" + Base64.getEncoder().encodeToString(
                note.getBytes(StandardCharsets.UTF_8)) + "\",\"messageId\":\"2\"},\"subscription\":\"s\"}";
        return http.post().uri("/payments/google-play/notifications?secret=" + PUSH_SECRET)
                .contentType(MediaType.APPLICATION_JSON).body(body.getBytes(StandardCharsets.UTF_8))
                .exchange((rq, res) -> read(res));
    }

    private static String event(String event, String sub, String status, long currentEnd, long createdAt,
                                String paymentId) {
        return "{\"entity\":\"event\",\"event\":\"" + event + "\",\"payload\":{\"subscription\":{\"entity\":{\"id\":\""
                + sub + "\",\"status\":\"" + status + "\",\"current_end\":"
                + (currentEnd == 0 ? "null" : Long.toString(currentEnd)) + ",\"notes\":{}}}"
                + (paymentId == null ? "" : ",\"payment\":{\"entity\":{\"id\":\"" + paymentId
                + "\",\"amount\":50000}}") + "},\"created_at\":" + createdAt + "}";
    }

    private Reply webhook(String body, String eventId) {
        return http.post().uri("/payments/razorpay/webhook").contentType(MediaType.APPLICATION_JSON).headers(h -> {
            h.set("X-Razorpay-Signature", hmac(WEBHOOK_SECRET, body));
            h.set("X-Razorpay-Event-Id", eventId);
        }).body(body.getBytes(StandardCharsets.UTF_8)).exchange((rq, res) -> read(res));
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

    private long count(String fromWhere) {
        return jdbc.queryForObject("SELECT count(*) FROM " + fromWhere, Long.class);
    }

    private UUID person(String phone) {
        UUID id = UUID.randomUUID();
        TestData.user(jdbc, id, phone);
        return id;
    }

    private String token(UUID who) {
        Boolean admin = jdbc.queryForObject("SELECT admin_role <> 'NONE' FROM users WHERE id = ?", Boolean.class,
                who);
        return jwt.issue(new AuthPrincipal(who, null, null, Boolean.TRUE.equals(admin)),
                sessions.currentGeneration(who)).token();
    }

    private Reply get(String path, UUID who) {
        return call(HttpMethod.GET, path, null, who);
    }

    private Reply post(String path, Object body, UUID who) {
        return call(HttpMethod.POST, path, body, who);
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

    private static Reply read(org.springframework.http.client.ClientHttpResponse res) throws IOException {
        int status = res.getStatusCode().value();
        String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
        Object parsed = text.isBlank() ? Map.of() : JSON.readValue(text, Object.class);
        return new Reply(status, parsed, text);
    }

    private record Reply(int status, Object rawBody, String text) {

        @SuppressWarnings("unchecked")
        Map<String, Object> body() {
            return rawBody instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        }

        String code() {
            return (String) body().get("code");
        }
    }
}
