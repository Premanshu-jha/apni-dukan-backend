package in.apnidukan.kirana;

import com.sun.net.httpserver.HttpServer;
import in.apnidukan.kirana.service.OrderJobs;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static in.apnidukan.support.CapturingSenders.LAST_EMAIL;
import static in.apnidukan.support.CapturingSenders.LAST_SMS;
import static in.apnidukan.support.CapturingSenders.codeSentTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kirana online orders end to end (V68–V69), against real Postgres and Redis and a fake Razorpay/RazorpayX: a shop
 * getting ready (approval, UPI ID, FSSAI, its switch), the shared catalogue, shops near the customer, the quote with
 * combined and separate plans, checkout and payment (the checkout's signed answer and the webhook), the shop accepting,
 * packing, handing over and delivering against the code, timeouts, rejections, missing items and cancellations with
 * their refunds, payouts to the shops' UPI IDs, and double taps.
 */
@EnabledIf("in.apnidukan.support.TestContainers#dockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CapturingSenders.class)
class KiranaOrdersIntegrationTest {

    private static final String A_OWNER = "9848044001";
    private static final String B_OWNER = "9848044002";
    private static final String C_OWNER = "9848044003";
    private static final String CUSTOMER = "9848044004";
    private static final String ADMIN = "9848044005";
    private static final String A_HELPER = "9848044006";
    private static final String KEY_SECRET = "test_key_secret";
    private static final String WEBHOOK_SECRET = "test_webhook_secret";
    private static final String X_WEBHOOK_SECRET = "test_x_webhook_secret";

    // Where things are: A, B 555 m north of A, C 1.33 km south of A; the customer 444 m south of A (C is 888 m away)
    private static final BigDecimal A_LAT = new BigDecimal("17.535123");
    private static final BigDecimal LNG = new BigDecimal("78.484456");
    private static final BigDecimal B_LAT = new BigDecimal("17.540123");
    private static final BigDecimal C_LAT = new BigDecimal("17.523123");
    private static final BigDecimal ME_LAT = new BigDecimal("17.531123");

    static final List<String> CALLS = new CopyOnWriteArrayList<>();
    static final AtomicInteger IDS = new AtomicInteger();
    private static final HttpServer RAZORPAY = fakeRazorpay();

    private static HttpServer fakeRazorpay() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String key = exchange.getRequestHeaders().getFirst("X-Payout-Idempotency");
                CALLS.add(exchange.getRequestMethod() + " " + path + (key == null ? "" : " key=" + key));
                exchange.getRequestBody().readAllBytes();
                int n = IDS.incrementAndGet();
                String answer;
                if (path.equals("/v1/orders")) {
                    answer = "{\"id\":\"order_T" + n + "\",\"status\":\"created\"}";
                } else if (path.endsWith("/refund")) {
                    answer = "{\"id\":\"rfnd_T" + n + "\",\"status\":\"processed\"}";
                } else if (path.endsWith("/refunds")) {
                    answer = "{\"items\":[]}";
                } else if (path.equals("/v1/contacts")) {
                    answer = "{\"id\":\"cont_T" + n + "\"}";
                } else if (path.equals("/v1/fund_accounts")) {
                    answer = "{\"id\":\"fa_T" + n + "\"}";
                } else if (path.equals("/v1/payouts")) {
                    answer = "{\"id\":\"pout_" + key.replace("-", "").substring(0, 20) + "\",\"status\":\"processing\"}";
                } else {
                    answer = "{}";
                }
                byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
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
        TestContainers.register(registry, true); // has kirana cases: kirana on
        String fake = "http://127.0.0.1:" + RAZORPAY.getAddress().getPort();
        registry.add("app.razorpay.endpoint", () -> fake);
        registry.add("app.razorpay.key-id", () -> "rzp_test_kirana");
        registry.add("app.razorpay.key-secret", () -> KEY_SECRET);
        registry.add("app.razorpay.webhook-secret", () -> WEBHOOK_SECRET);
        registry.add("app.razorpayx.endpoint", () -> fake);
        registry.add("app.razorpayx.key-id", () -> "rzpx_test");
        registry.add("app.razorpayx.key-secret", () -> "rzpx_secret");
        registry.add("app.razorpayx.account-number", () -> "2323230000000001");
        registry.add("app.razorpayx.webhook-secret", () -> X_WEBHOOK_SECRET);
        registry.add("app.kirana.jobs-delay-ms", () -> "3600000");   // the tests run the jobs themselves
    }

    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired OrderJobs jobs;

    RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.builder()
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + environment.getProperty("local.server.port") + "/api/v1")
                .build();
        TestData.clean(jdbc);
        jdbc.update("DELETE FROM kirana_ai_calls");
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        LAST_SMS.clear();
        LAST_EMAIL.clear();
        CALLS.clear();
    }

    // ---------- a shop gets ready ----------

    @Test
    void aShopTakesOrdersOnlyOnceApprovedWithItsUpiIdAVerifiedFssaiLicenceAndItsSwitchOn() {
        String owner = kirana(A_OWNER, "Sharma General Store", A_LAT);
        String helper = helperOf(owner, A_HELPER);
        UUID shop = shopId(owner);
        Map<?, ?> first = get("/kirana/online", owner).body();
        assertThat(objects(first.get("missing"))).containsExactly("APPROVAL", "UPI_ID", "FSSAI", "SWITCHED_OFF");
        Reply early = put("/kirana/online/taking-orders", Map.of("on", true), owner);
        assertThat(early.status()).isEqualTo(409);
        assertThat(early.body().get("code")).isEqualTo("NOT_READY");

        jdbc.update("UPDATE shops SET status = 'APPROVED', ever_approved = true WHERE id = ?", shop);
        TestData.kiranaUpiId(jdbc, shop);
        assertThat(keys(put("/kirana/online/fssai", Map.of("number", "1234", "fileId", file(shop)), owner).body()
                .get("details"))).containsExactly("number");
        String otherShopFile = file(shopId(kirana(B_OWNER, "Gupta Kirana", B_LAT)));
        assertThat(keys(put("/kirana/online/fssai", Map.of("number", "13624013000111", "fileId", otherShopFile), owner)
                .body().get("details"))).containsExactly("fileId");
        assertThat(put("/kirana/online/fssai", Map.of("number", "13624013000111", "fileId", file(shop)), helper)
                .body().get("code")).isEqualTo("OWNER_ONLY");
        Reply added = put("/kirana/online/fssai", Map.of("number", "1362 4013 0001 11", "fileId", file(shop)), owner);
        assertThat(added.status()).as(added.body().toString()).isEqualTo(200);
        assertThat(((Map<?, ?>) added.body().get("fssai")).get("status")).isEqualTo("CHECKING");

        // A super admin decides (the AI reads it when its key is set)
        String admin = superAdmin();
        assertThat(post("/admin/kirana/shops/" + shop + "/fssai", Map.of("verified", true), owner).status())
                .isEqualTo(403);
        assertThat(keys(post("/admin/kirana/shops/" + shop + "/fssai", Map.of("verified", true), admin).body()
                .get("details"))).containsExactly("validUntil");   // a verified licence always has its last day
        Reply decided = post("/admin/kirana/shops/" + shop + "/fssai", Map.of("verified", true,
                "validUntil", LocalDate.now().plusYears(1).toString()), admin);
        assertThat(decided.status()).as(decided.body().toString()).isEqualTo(200);
        assertThat(decided.body().get("status")).isEqualTo("VERIFIED");

        assertThat(put("/kirana/online/taking-orders", Map.of("on", true), helper).body().get("code"))
                .isEqualTo("OWNER_ONLY");
        Reply on = put("/kirana/online/taking-orders", Map.of("on", true), owner);
        assertThat(on.body().get("ready")).isEqualTo(true);
        assertThat(objects(on.body().get("missing"))).isEmpty();

        // An expired licence stops orders again
        jdbc.update("UPDATE kirana_online SET fssai_valid_until = current_date - 1 WHERE shop_id = ?", shop);
        assertThat(objects(get("/kirana/online", owner).body().get("missing"))).containsExactly("FSSAI");
    }

    // ---------- the shared catalogue ----------

    @Test
    void theFirstShopToAddABarcodeMakesTheCatalogueEntryAndTheNextShopsScanFillsItIn() {
        String a = kirana(A_OWNER, "Sharma General Store", A_LAT);
        String b = kirana(B_OWNER, "Gupta Kirana", B_LAT);
        Map<String, Object> milk = product("Amul Taaza 500 ml", "PIECE", 2700, "10");
        milk.put("barcode", "8901262150019");
        milk.put("mrpPaise", 2900);
        String aMilk = id(post("/kirana/products", milk, a));

        Reply scan = get("/kirana/catalogue/barcode/8901262150019", b);
        assertThat(scan.status()).as(scan.body().toString()).isEqualTo(200);
        assertThat(scan.body().get("name")).isEqualTo("Amul Taaza 500 ml");
        assertThat(scan.body().get("mrpPaise")).isEqualTo(2900);
        assertThat(get("/kirana/catalogue/barcode/8901262150026", b).body().get("code")).isEqualTo("CATALOGUE_NOT_FOUND");
        Map<String, Object> bMilk = product("Amul Taaza", "PIECE", 2800, "4");
        bMilk.put("barcode", "8901262150019");
        String bMilkId = id(post("/kirana/products", bMilk, b));
        assertThat(catalogueOf(aMilk)).isNotNull().isEqualTo(catalogueOf(bMilkId));

        // A shop linking a second barcode adds it to the same entry
        post("/kirana/products/" + aMilk + "/barcodes", Map.of("code", "8901262150033", "pack", 6), a);
        assertThat(get("/kirana/catalogue/barcode/8901262150033", b).body().get("pack")).isEqualTo(6);

        String admin = superAdmin();
        String item = catalogueOf(aMilk).toString();
        assertThat(patch("/admin/kirana/catalogue/" + item, Map.of("version", 0, "name", "Amul Taaza Toned Milk 500 ml"),
                a).status()).isEqualTo(403);
        Reply edited = patch("/admin/kirana/catalogue/" + item, Map.of("version", 0,
                "name", "Amul Taaza Toned Milk 500 ml", "size", "500 ml"), admin);
        assertThat(edited.status()).as(edited.body().toString()).isEqualTo(200);
        assertThat(get("/kirana/catalogue/barcode/8901262150019", b).body().get("name"))
                .isEqualTo("Amul Taaza Toned Milk 500 ml");
        assertThat(patch("/admin/kirana/catalogue/" + item, Map.of("version", 0, "name", "Old"), admin).body()
                .get("code")).isEqualTo("CATALOGUE_CHANGED");
        assertThat(pluck(getList(get("/admin/kirana/catalogue?q=taaza", admin), "items"), "name"))
                .containsExactly("Amul Taaza Toned Milk 500 ml");
    }

    // ---------- the customer finds shops and items ----------

    @Test
    void theCustomerSeesReadyShopsWithin2KmAndItemsTheShopsNearTheirShopHave() {
        Shops s = network();
        String me = token(CUSTOMER);
        Reply near = get("/kirana/nearby?lat=" + ME_LAT + "&lng=" + LNG, me);
        assertThat(near.status()).as(near.body().toString()).isEqualTo(200);
        assertThat(pluck(near.body().get("items"), "name")).containsExactly("Sharma General Store", "Bansal Stores",
                "Gupta Kirana");
        put("/kirana/online/taking-orders", Map.of("on", false), s.cOwner);
        assertThat(pluck(get("/kirana/nearby?lat=" + ME_LAT + "&lng=" + LNG, me).body().get("items"), "name"))
                .containsExactly("Sharma General Store", "Gupta Kirana");

        // A product never counted isn't offered (the shop doesn't know it has it)
        Map<String, Object> uncounted = new HashMap<>(Map.of("name", "Good Day 100 g", "unit", "PIECE", "pricePaise",
                3000));
        assertThat(post("/kirana/products", uncounted, s.aOwner).status()).isEqualTo(201);
        Reply products = get("/kirana/shops/" + s.a + "/products", me);
        assertThat(pluck(products.body().get("items"), "name")).containsExactly("Amul Taaza 500 ml", "Harvest Gold 400 g");
        // Curd isn't at A; B (555 m from A) has it, C (1.33 km) is too far to hand it over
        Reply nearby = get("/kirana/shops/" + s.a + "/nearby-items", me);
        assertThat(pluck(nearby.body().get("items"), "name")).containsExactly("Nandini Curd 500 g");
        assertThat(get("/kirana/shops/" + s.c + "/products", me).body().get("code")).isEqualTo("SHOP_NOT_TAKING_ORDERS");
    }

    // ---------- a combined order from start to finish ----------

    @Test
    void aCombinedOrderIsPaidAcceptedByBothShopsHandedOverDeliveredAgainstTheCodeAndPaidOut() {
        Shops s = network();
        String me = token(CUSTOMER);
        // 7 milk (A has 5: 2 short) and 1 curd (A has none)
        List<Map<String, Object>> cart = List.of(line(s.aMilk, "7"), catalogueLine(s.curdItem, "1"));
        Reply quote = post("/kirana/quote", quote(s.a, cart), me);
        assertThat(quote.status()).as(quote.body().toString()).isEqualTo(200);
        assertThat(quote.body().get("choose")).isEqualTo(true);
        Map<?, ?> combined = (Map<?, ?>) quote.body().get("combined");
        // A: 5 × 27 = 135; B: 2 milk × 28 + 1 curd × 30 = 86; + ₹10
        assertThat(combined.get("itemsPaise")).isEqualTo(13500 + 5600 + 3000);
        assertThat(combined.get("totalPaise")).isEqualTo(22100 + 1000);
        assertThat((List<?>) combined.get("orders")).hasSize(1);

        Map<String, Object> wrongTotal = place(s.a, cart, "COMBINED", 1);
        Reply changed = post("/kirana/checkouts", wrongTotal, me);
        assertThat(changed.body().get("code")).isEqualTo("PRICE_CHANGED");

        Map<String, Object> body = place(s.a, cart, "COMBINED", 23100);
        Reply pay = post("/kirana/checkouts", body, me);
        assertThat(pay.status()).as(pay.body().toString()).isEqualTo(200);
        assertThat(pay.body().get("status")).isEqualTo("PENDING");
        assertThat(pay.body().get("keyId")).isEqualTo("rzp_test_kirana");
        String checkout = (String) pay.body().get("checkoutId");
        String rzOrder = (String) pay.body().get("razorpayOrderId");
        // The same checkout sent again: the same one
        assertThat(post("/kirana/checkouts", body, me).body().get("checkoutId")).isEqualTo(checkout);

        Reply forged = post("/kirana/checkouts/" + checkout + "/paid", Map.of("razorpayOrderId", rzOrder,
                "razorpayPaymentId", "pay_T1", "razorpaySignature", "00"), me);
        assertThat(forged.body().get("code")).isEqualTo("BAD_SIGNATURE");
        Reply paid = post("/kirana/checkouts/" + checkout + "/paid", Map.of("razorpayOrderId", rzOrder,
                "razorpayPaymentId", "pay_T1", "razorpaySignature", sign(KEY_SECRET, rzOrder + "|pay_T1")), me);
        assertThat(paid.body().get("status")).isEqualTo("PAID");
        String orderId = (String) ((Map<?, ?>) ((List<?>) paid.body().get("orders")).get(0)).get("id");

        String aPart = partOf(orderId, s.a);
        String bPart = partOf(orderId, s.b);
        // B never sees the customer: only the shop to hand the items to
        Map<?, ?> bView = get("/kirana/shop-orders/" + bPart, s.bOwner).body();
        assertThat(bView.get("customer")).isNull();
        assertThat(bView.get("deliverToShop")).isEqualTo("Sharma General Store");
        assertThat(get("/kirana/shop-orders/" + bPart, s.aOwner).status()).isEqualTo(404);

        assertThat(post("/kirana/shop-orders/" + aPart + "/accept", Map.of(), s.aOwner).body().get("status"))
                .isEqualTo("ACCEPTED");
        assertThat(stock(s.aMilk)).isEqualByComparingTo("0");
        post("/kirana/shop-orders/" + bPart + "/accept", Map.of(), s.bOwner);
        assertThat(stock(s.bMilk)).isEqualByComparingTo("8");
        assertThat(get("/kirana/orders/" + orderId, me).body().get("canCancel")).isEqualTo(false);
        post("/kirana/shop-orders/" + aPart + "/packed", Map.of(), s.aOwner);
        Reply early = post("/kirana/shop-orders/" + aPart + "/out", Map.of(), s.aOwner);
        assertThat(early.body().get("code")).isEqualTo("WAITING_FOR_SHOP");
        post("/kirana/shop-orders/" + bPart + "/handed", Map.of(), s.bOwner);
        assertThat(post("/kirana/shop-orders/" + aPart + "/out", Map.of(), s.aOwner).body().get("orderStatus"))
                .isEqualTo("OUT_FOR_DELIVERY");

        Map<?, ?> mine = get("/kirana/orders/" + orderId, me).body();
        String code = (String) mine.get("otp");
        assertThat(pluck(mine.get("lines"), "fromNearbyShop")).containsExactly(false, true, true);
        Reply wrong = post("/kirana/shop-orders/" + aPart + "/deliver", Map.of("otp", wrongCode(code)), s.aOwner);
        assertThat(wrong.status()).isEqualTo(400);
        assertThat(((Map<?, ?>) wrong.body().get("details")).get("triesLeft")).isEqualTo(4);
        Reply delivered = post("/kirana/shop-orders/" + aPart + "/deliver", Map.of("otp", code), s.aOwner);
        assertThat(delivered.status()).as(delivered.body().toString()).isEqualTo(200);
        assertThat(delivered.body().get("orderStatus")).isEqualTo("DELIVERED");
        assertThat(get("/kirana/orders/" + orderId, me).body().get("otp")).isNull();

        // Each shop earns its own items, due 2 working days later; Clozest keeps the ₹10
        assertThat(jdbc.queryForList("SELECT amount_paise FROM kirana_earnings ORDER BY amount_paise", Long.class))
                .containsExactly(8600L, 13500L);
        Reply rated = post("/kirana/orders/" + orderId + "/rating", Map.of("stars", 5), me);
        assertThat(rated.body().get("rating")).isEqualTo(5);
        assertThat(post("/kirana/orders/" + orderId + "/rating", Map.of("stars", 1), me).body().get("code"))
                .isEqualTo("ALREADY_RATED");

        // Payouts once due: one per shop, to its UPI ID, with our id as RazorpayX's idempotency key
        jdbc.update("UPDATE kirana_earnings SET due_on = current_date");
        jobs.payouts();
        assertThat(CALLS.stream().filter(c -> c.startsWith("POST /v1/payouts key=")).count()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT status FROM kirana_payouts", String.class)).containsOnly("SENT");
        Map<?, ?> earned = get("/kirana/online", s.aOwner).body();
        assertThat(pluck(earned.get("payouts"), "amountPaise")).containsExactly(13500);
        assertThat(earned.get("duePaise")).isEqualTo(0);
        jobs.payouts();   // nothing left to pay
        assertThat(CALLS.stream().filter(c -> c.startsWith("POST /v1/payouts")).count()).isEqualTo(2);

        String payoutRz = jdbc.queryForObject("SELECT razorpay_payout_id FROM kirana_payouts WHERE amount_paise = 13500",
                String.class);
        String event = "{\"event\":\"payout.processed\",\"payload\":{\"payout\":{\"entity\":{\"id\":\"" + payoutRz
                + "\",\"status\":\"processed\"}}}}";
        assertThat(xWebhook(event, "evt_x1").body().get("result")).isEqualTo("UPDATED");
        assertThat(xWebhook(event, "evt_x1").body().get("result")).isEqualTo("DUPLICATE");
        assertThat(postRaw("/payments/razorpayx/webhook", event, "bad", "evt_x2").status()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT status FROM kirana_payouts WHERE amount_paise = 13500", String.class))
                .isEqualTo("PROCESSED");
        // A late or out-of-order event never undoes it
        String late = "{\"event\":\"payout.failed\",\"payload\":{\"payout\":{\"entity\":{\"id\":\"" + payoutRz
                + "\",\"status\":\"failed\"}}}}";
        xWebhook(late, "evt_x3");
        assertThat(jdbc.queryForObject("SELECT status FROM kirana_payouts WHERE amount_paise = 13500", String.class))
                .isEqualTo("PROCESSED");
    }

    @Test
    void aSeparateOrderLetsTheOtherShopDeliverItsItemsAndTheTenRupeesIsPaidOnce() {
        Shops s = network();
        String me = token(CUSTOMER);
        jdbc.update("UPDATE kirana_products SET stock = 0 WHERE id = ?::uuid", s.bCurd);   // only C has curd now
        List<Map<String, Object>> cart = List.of(line(s.aMilk, "1"), catalogueLine(s.curdItem, "2"));
        Map<?, ?> quote = post("/kirana/quote", quote(s.a, cart), me).body();
        assertThat(pluck(quote.get("unavailable"), "name")).containsExactly("Nandini Curd 500 g");   // too far from A
        Map<?, ?> separate = (Map<?, ?>) quote.get("separate");
        assertThat(pluck(separate.get("orders"), "shopName")).containsExactly("Sharma General Store", "Bansal Stores");
        assertThat(separate.get("totalPaise")).isEqualTo(2700 + 2 * 3200 + 1000);

        String checkout = payFor(me, place(s.a, cart, "SEPARATE", 2700 + 6400 + 1000));
        List<Map<String, Object>> orders = jdbc.queryForList("SELECT shop_id, platform_paise FROM kirana_orders "
                + "WHERE checkout_id = ?::uuid ORDER BY order_no", checkout);
        assertThat(orders).hasSize(2);
        assertThat(orders.get(0).get("platform_paise")).isEqualTo(1000L);
        assertThat(orders.get(1).get("platform_paise")).isEqualTo(0L);
        assertThat(orders.get(1).get("shop_id").toString()).isEqualTo(s.c.toString());
    }

    // ---------- things going wrong, and refunds ----------

    @Test
    void aPartNotAcceptedInThreeMinutesCancelsTheOrderAndEverythingIsRefundedOnce() {
        Shops s = network();
        String me = token(CUSTOMER);
        String checkout = payFor(me, place(s.a, List.of(line(s.aMilk, "2")), "COMBINED", 5400 + 1000));
        String orderId = orderOf(checkout);
        String aPart = partOf(orderId, s.a);
        jdbc.update("UPDATE kirana_order_parts SET accept_by = now() - interval '1 second'");
        assertThat(post("/kirana/shop-orders/" + aPart + "/accept", Map.of(), s.aOwner).body().get("code"))
                .isEqualTo("TOO_LATE");
        jobs.timeouts();
        Map<?, ?> order = get("/kirana/orders/" + orderId, me).body();
        assertThat(order.get("status")).isEqualTo("CANCELLED");
        assertThat(pluck(order.get("refunds"), "amountPaise")).containsExactly(6400);   // the ₹10 too
        jobs.refunds();
        jobs.refunds();
        assertThat(CALLS.stream().filter(c -> c.endsWith("/refund")).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM kirana_refunds", String.class)).isEqualTo("PROCESSED");
        assertThat(stock(s.aMilk)).isEqualByComparingTo("5");
    }

    @Test
    void aSupplierRejectingOnlyRefundsItsItemsAndMissingItemsAreRefundedWhilePacking() {
        Shops s = network();
        String me = token(CUSTOMER);
        List<Map<String, Object>> cart = List.of(line(s.aMilk, "3"), line(s.aBread, "1"), catalogueLine(s.curdItem, "1"));
        String checkout = payFor(me, place(s.a, cart, "COMBINED", 3 * 2700 + 4500 + 3000 + 1000));
        String orderId = orderOf(checkout);
        String aPart = partOf(orderId, s.a);
        String bPart = partOf(orderId, s.b);
        Reply rejected = post("/kirana/shop-orders/" + bPart + "/reject", Map.of("reason", "Curd finished"), s.bOwner);
        assertThat(rejected.body().get("status")).isEqualTo("REJECTED");
        assertThat(get("/kirana/orders/" + orderId, me).body().get("status")).isEqualTo("PLACED");   // A's part goes on

        post("/kirana/shop-orders/" + aPart + "/accept", Map.of(), s.aOwner);
        assertThat(stock(s.aBread)).isEqualByComparingTo("1");
        Reply missing = post("/kirana/shop-orders/" + aPart + "/missing", Map.of("lineNos", List.of(2)), s.aOwner);
        assertThat(missing.status()).as(missing.body().toString()).isEqualTo(200);
        assertThat(missing.body().get("itemsPaise")).isEqualTo(3 * 2700);
        assertThat(stock(s.aBread)).isEqualByComparingTo("2");
        // Its count didn't hold: not offered online until the shop counts it again
        assertThat(pluck(get("/kirana/shops/" + s.a + "/products", me).body().get("items"), "name"))
                .containsExactly("Amul Taaza 500 ml");
        assertThat(post("/kirana/products/" + s.aBread + "/stock", Map.of("clientRef", UUID.randomUUID().toString(),
                "reason", "COUNT", "quantity", 2), s.aOwner).status()).isEqualTo(200);
        assertThat(pluck(get("/kirana/shops/" + s.a + "/products", me).body().get("items"), "name"))
                .containsExactly("Amul Taaza 500 ml", "Harvest Gold 400 g");
        Map<?, ?> order = get("/kirana/orders/" + orderId, me).body();
        assertThat(pluck(order.get("refunds"), "amountPaise")).containsExactly(3000, 4500);
        assertThat(order.get("totalPaise")).isEqualTo(3 * 2700 + 1000);
        assertThat(keys(post("/kirana/shop-orders/" + aPart + "/missing", Map.of("lineNos", List.of(9)), s.aOwner)
                .body().get("details"))).containsExactly("lineNos");

        // The last item missing too: nothing is left, so the order is cancelled and the ₹10 refunded
        post("/kirana/shop-orders/" + aPart + "/missing", Map.of("lineNos", List.of(1)), s.aOwner);
        order = get("/kirana/orders/" + orderId, me).body();
        assertThat(order.get("status")).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT refunded_paise FROM kirana_checkouts", Long.class))
                .isEqualTo((long) 3 * 2700 + 4500 + 3000 + 1000);
    }

    @Test
    void theCustomerCancelsBeforeTheShopAcceptsButNotAfter() {
        Shops s = network();
        String me = token(CUSTOMER);
        String first = orderOf(payFor(me, place(s.a, List.of(line(s.aMilk, "1")), "COMBINED", 2700 + 1000)));
        Reply cancelled = post("/kirana/orders/" + first + "/cancel", Map.of("reason", "Ordered by mistake"), me);
        assertThat(cancelled.body().get("status")).isEqualTo("CANCELLED");
        assertThat(pluck(cancelled.body().get("refunds"), "amountPaise")).containsExactly(3700);
        assertThat(post("/kirana/orders/" + first + "/cancel", Map.of(), token(B_OWNER)).status()).isEqualTo(404);

        String second = orderOf(payFor(me, place(s.a, List.of(line(s.aMilk, "1")), "COMBINED", 2700 + 1000)));
        post("/kirana/shop-orders/" + partOf(second, s.a) + "/accept", Map.of(), s.aOwner);
        assertThat(post("/kirana/orders/" + second + "/cancel", Map.of(), me).body().get("code"))
                .isEqualTo("CANT_CANCEL");
    }

    @Test
    void theWebhookMarksAPaymentOnceAndAPaymentAfterTheCheckoutExpiredIsRefundedInFull() {
        Shops s = network();
        String me = token(CUSTOMER);
        Reply pay = post("/kirana/checkouts", place(s.a, List.of(line(s.aMilk, "1")), "COMBINED", 3700), me);
        String rzOrder = (String) pay.body().get("razorpayOrderId");
        String event = captured(rzOrder, "pay_W1");
        assertThat(webhook(event, "evt_1").body().get("result")).isEqualTo("PAID");
        assertThat(webhook(event, "evt_1").body().get("result")).isEqualTo("DUPLICATE");
        assertThat(webhook(event, "evt_2").body().get("result")).isEqualTo("OLD");
        assertThat(jdbc.queryForObject("SELECT paid_method FROM kirana_checkouts", String.class)).isEqualTo("upi");
        assertThat(jdbc.queryForObject("SELECT status FROM kirana_order_parts", String.class)).isEqualTo("WAITING");

        Reply late = post("/kirana/checkouts", place(s.a, List.of(line(s.aMilk, "1")), "COMBINED", 3700), me);
        String lateOrder = (String) late.body().get("razorpayOrderId");
        jdbc.update("UPDATE kirana_checkouts SET created_at = now() - interval '36 minutes' WHERE status = 'PENDING'");
        jobs.expiry();
        assertThat(get("/kirana/checkouts/" + late.body().get("checkoutId"), me).body().get("status"))
                .isEqualTo("EXPIRED");
        assertThat(webhook(captured(lateOrder, "pay_W2"), "evt_3").body().get("result")).isEqualTo("LATE");
        assertThat(jdbc.queryForObject("SELECT amount_paise FROM kirana_refunds WHERE reason = 'NOT_PAID_IN_TIME'",
                Long.class)).isEqualTo(3700L);
        assertThat(postRaw("/payments/razorpay/webhook", event, "bad", "evt_4").status()).isEqualTo(403);
    }

    @Test
    void aPayoutThatFailedIsSentAgainToTheNewUpiIdOnceTheFieldTeamChecksIt() {
        Shops s = network();
        UUID shop = s.a;
        String me = token(CUSTOMER);
        String orderId = orderOf(payFor(me, place(s.a, List.of(line(s.aMilk, "1")), "COMBINED", 3700)));
        deliver(s.aOwner, partOf(orderId, s.a), orderId, me);
        jdbc.update("UPDATE kirana_earnings SET due_on = current_date");
        jobs.payouts();
        String payoutRz = jdbc.queryForObject("SELECT razorpay_payout_id FROM kirana_payouts", String.class);
        String failed = "{\"event\":\"payout.failed\",\"payload\":{\"payout\":{\"entity\":{\"id\":\"" + payoutRz
                + "\",\"status\":\"failed\",\"failure_reason\":\"Invalid VPA\"}}}}";
        assertThat(xWebhook(failed, "evt_f1").body().get("result")).isEqualTo("UPDATED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_jobs WHERE type = 'KIRANA_PAYOUT_FAILED'",
                Integer.class)).isEqualTo(1);
        jobs.payouts();   // not sent again to the same UPI ID by itself
        assertThat(CALLS.stream().filter(c -> c.startsWith("POST /v1/payouts")).count()).isEqualTo(1);

        unlockEditing(s.aOwner);
        Reply changed = put("/kirana/settings/upi-id", Map.of("upiId", "sharma.new@ybl"), s.aOwner);
        assertThat(changed.status()).as(changed.body().toString()).isEqualTo(200);
        assertThat(get("/kirana/online", s.aOwner).body().get("upiRecheck")).isEqualTo(true);
        jobs.payouts();
        assertThat(CALLS.stream().filter(c -> c.startsWith("POST /v1/payouts")).count()).isEqualTo(1);

        String admin = superAdmin();
        assertThat(pluck(get("/admin/kirana/upi-checks", admin).list(), "upiId")).containsExactly("sharma.new@ybl");
        assertThat(post("/admin/kirana/shops/" + shop + "/upi-check", Map.of(), admin).status()).isEqualTo(204);
        assertThat(post("/admin/kirana/shops/" + shop + "/upi-check", Map.of(), admin).body().get("code"))
                .isEqualTo("NOTHING_TO_CHECK");
        jobs.payouts();
        assertThat(CALLS.stream().filter(c -> c.startsWith("POST /v1/payouts")).count()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT upi_id FROM kirana_payouts WHERE status = 'SENT'", String.class))
                .containsExactly("sharma.new@ybl");
        assertThat(jdbc.queryForObject("SELECT sum(amount_paise) FROM kirana_payouts WHERE status = 'SENT'", Long.class))
                .isEqualTo(2700L);
    }

    @Test
    void aSupplierHandsOverOnlyOnceTheShopAcceptedAndTheShopCanCancelAnOrderItCantFinish() {
        Shops s = network();
        String me = token(CUSTOMER);
        List<Map<String, Object>> cart = List.of(line(s.aMilk, "7"), catalogueLine(s.curdItem, "1"));
        String orderId = orderOf(payFor(me, place(s.a, cart, "COMBINED", 23100)));
        String aPart = partOf(orderId, s.a);
        String bPart = partOf(orderId, s.b);
        // Before accepting, the shop doesn't see who the customer is
        assertThat(get("/kirana/shop-orders/" + aPart, s.aOwner).body().get("customer")).isNull();
        post("/kirana/shop-orders/" + bPart + "/accept", Map.of(), s.bOwner);
        assertThat(post("/kirana/shop-orders/" + bPart + "/handed", Map.of(), s.bOwner).body().get("code"))
                .isEqualTo("NOT_ACCEPTED_YET");
        post("/kirana/shop-orders/" + aPart + "/accept", Map.of(), s.aOwner);
        Map<?, ?> aView = get("/kirana/shop-orders/" + aPart, s.aOwner).body();
        assertThat(((Map<?, ?>) aView.get("customer")).get("phone")).isEqualTo(CUSTOMER);
        assertThat(pluck(aView.get("otherParts"), "shopPhone")).containsExactly(B_OWNER);
        assertThat(get("/kirana/shop-orders/" + bPart, s.bOwner).body().get("deliverToShopPhone")).isEqualTo(A_OWNER);
        assertThat(get("/kirana/orders/" + orderId, me).body().get("shopPhone")).isEqualTo(A_OWNER);
        post("/kirana/shop-orders/" + bPart + "/handed", Map.of(), s.bOwner);
        post("/kirana/shop-orders/" + aPart + "/packed", Map.of(), s.aOwner);
        assertThat(post("/kirana/shop-orders/" + bPart + "/cancel", Map.of("reason", "Late"), s.bOwner).body()
                .get("code")).isEqualTo("WRONG_STATUS");   // handed over already

        Reply cancelled = post("/kirana/shop-orders/" + aPart + "/cancel", Map.of("reason", "Customer not reachable"),
                s.aOwner);
        assertThat(cancelled.status()).as(cancelled.body().toString()).isEqualTo(200);
        Map<?, ?> order = get("/kirana/orders/" + orderId, me).body();
        assertThat(order.get("status")).isEqualTo("CANCELLED");
        assertThat((String) order.get("cancelReason")).contains("Customer not reachable");
        assertThat(order.get("shopPhone")).isNull();
        assertThat(jdbc.queryForObject("SELECT refunded_paise FROM kirana_checkouts", Long.class)).isEqualTo(23100L);
        assertThat(stock(s.aMilk)).isEqualByComparingTo("5");
        assertThat(stock(s.bMilk)).isEqualByComparingTo("10");   // the handed items go back to the supplier
        assertThat(stock(s.bCurd)).isEqualByComparingTo("6");
        assertThat((List<?>) get("/kirana/shop-orders?open=true", s.aOwner).body().get("items")).isEmpty();
        assertThat(pluck(get("/kirana/shop-orders?open=false", s.bOwner).body().get("items"), "status"))
                .containsExactly("CANCELLED");
    }

    @Test
    void anOrderLeftWithNothingIsCancelledAndTheTenRupeesRefunded() {
        Shops s = network();
        String me = token(CUSTOMER);
        // A has no curd: its own part is empty and B hands everything over
        String orderId = orderOf(payFor(me, place(s.a, List.of(catalogueLine(s.curdItem, "1")), "COMBINED", 4000)));
        post("/kirana/shop-orders/" + partOf(orderId, s.b) + "/reject", Map.of("reason", "Finished"), s.bOwner);
        Map<?, ?> order = get("/kirana/orders/" + orderId, me).body();
        assertThat(order.get("status")).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT refunded_paise FROM kirana_checkouts", Long.class)).isEqualTo(4000L);
    }

    @Test
    void aSecondPaymentOfTheSameCheckoutIsRefundedInFullOnce() {
        Shops s = network();
        String me = token(CUSTOMER);
        String checkout = payFor(me, place(s.a, List.of(line(s.aMilk, "1")), "COMBINED", 3700));
        String rzOrder = jdbc.queryForObject("SELECT razorpay_order_id FROM kirana_checkouts", String.class);
        assertThat(webhook(captured(rzOrder, "pay_TWICE"), "evt_t1").body().get("result")).isEqualTo("PAID_TWICE");
        assertThat(webhook(captured(rzOrder, "pay_TWICE"), "evt_t2").body().get("result")).isEqualTo("PAID_TWICE");
        assertThat(jdbc.queryForList("SELECT payment_id FROM kirana_refunds WHERE reason = 'PAID_TWICE'", String.class))
                .containsExactly("pay_TWICE");
        assertThat(pluck(get("/kirana/checkouts/" + checkout, me).body().get("refunds"), "reason"))
                .containsExactly("PAID_TWICE");
        jobs.refunds();
        assertThat(CALLS).contains("POST /v1/payments/pay_TWICE/refund");
        // The first payment's order goes on untouched
        assertThat(jdbc.queryForObject("SELECT refunded_paise FROM kirana_checkouts", Long.class)).isZero();
    }

    @Test
    void aShopThatStoppedTakingOrdersBeforeThePaymentGetsNoOrderAndTheCustomerIsRefunded() {
        Shops s = network();
        String me = token(CUSTOMER);
        Reply pay = post("/kirana/checkouts", place(s.a, List.of(line(s.aMilk, "1")), "COMBINED", 3700), me);
        put("/kirana/online/taking-orders", Map.of("on", false), s.aOwner);
        String rzOrder = (String) pay.body().get("razorpayOrderId");
        post("/kirana/checkouts/" + pay.body().get("checkoutId") + "/paid", Map.of("razorpayOrderId", rzOrder,
                "razorpayPaymentId", "pay_OFF", "razorpaySignature", sign(KEY_SECRET, rzOrder + "|pay_OFF")), me);
        String orderId = orderOf((String) pay.body().get("checkoutId"));
        assertThat(get("/kirana/orders/" + orderId, me).body().get("status")).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT refunded_paise FROM kirana_checkouts", Long.class)).isEqualTo(3700L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_jobs WHERE type = 'KIRANA_ORDER_NEW'",
                Integer.class)).isZero();
    }

    @Test
    void theTeamSeesOrdersLeftOpenAndASuperAdminCancelsOne() {
        Shops s = network();
        String me = token(CUSTOMER);
        String orderId = orderOf(payFor(me, place(s.a, List.of(line(s.aMilk, "2")), "COMBINED", 6400)));
        post("/kirana/shop-orders/" + partOf(orderId, s.a) + "/accept", Map.of(), s.aOwner);
        String admin = superAdmin();
        assertThat(get("/admin/kirana/orders/open", admin).list()).isEmpty();
        jdbc.update("UPDATE kirana_orders SET placed_at = now() - interval '3 hours'");
        assertThat(pluck(get("/admin/kirana/orders/open", admin).list(), "shopPhone")).containsExactly(A_OWNER);
        assertThat(post("/admin/kirana/orders/" + orderId + "/cancel", Map.of("reason", "Shop not answering"),
                s.aOwner).status()).isEqualTo(403);
        assertThat(post("/admin/kirana/orders/" + orderId + "/cancel", Map.of("reason", "Shop not answering"), admin)
                .status()).isEqualTo(204);
        assertThat(get("/kirana/orders/" + orderId, me).body().get("status")).isEqualTo("CANCELLED");
        assertThat(stock(s.aMilk)).isEqualByComparingTo("5");
        assertThat(post("/admin/kirana/orders/" + orderId + "/cancel", Map.of("reason", "Again"), admin).body()
                .get("code")).isEqualTo("WRONG_STATUS");
    }

    @Test
    void fiveWrongCodesLockDeliveryForAQuarterOfAnHour() {
        Shops s = network();
        String me = token(CUSTOMER);
        String orderId = orderOf(payFor(me, place(s.a, List.of(line(s.aMilk, "1")), "COMBINED", 3700)));
        String part = partOf(orderId, s.a);
        post("/kirana/shop-orders/" + part + "/accept", Map.of(), s.aOwner);
        post("/kirana/shop-orders/" + part + "/packed", Map.of(), s.aOwner);
        post("/kirana/shop-orders/" + part + "/out", Map.of(), s.aOwner);
        String wrong = wrongCode((String) get("/kirana/orders/" + orderId, me).body().get("otp"));
        for (int i = 0; i < 4; i++) {
            assertThat(post("/kirana/shop-orders/" + part + "/deliver", Map.of("otp", wrong), s.aOwner).body()
                    .get("code")).isEqualTo("WRONG_CODE");
        }
        assertThat(post("/kirana/shop-orders/" + part + "/deliver", Map.of("otp", wrong), s.aOwner).status())
                .isEqualTo(429);
        String right = (String) get("/kirana/orders/" + orderId, me).body().get("otp");
        assertThat(post("/kirana/shop-orders/" + part + "/deliver", Map.of("otp", right), s.aOwner).body().get("code"))
                .isEqualTo("CODE_LOCKED");
    }

    @Test
    void aRenewedLicenceBeingCheckedOrRefusedKeepsTheShopOpenUntilTheEarlierOneEnds() {
        Shops s = network();
        String me = token(CUSTOMER);
        String keptUntil = LocalDate.now().plusDays(365).toString();
        Reply renewed = put("/kirana/online/fssai", Map.of("number", "13624013000111", "fileId", file(s.a)), s.aOwner);
        assertThat(renewed.status()).as(renewed.body().toString()).isEqualTo(200);
        Map<?, ?> fssai = (Map<?, ?>) renewed.body().get("fssai");
        assertThat(fssai.get("status")).isEqualTo("CHECKING");
        assertThat(fssai.get("currentValidUntil")).isEqualTo(keptUntil);
        assertThat(objects(renewed.body().get("missing"))).isEmpty();
        assertThat(pluck(get("/kirana/nearby?lat=" + ME_LAT + "&lng=" + LNG, me).body().get("items"), "name"))
                .contains("Sharma General Store");

        String admin = superAdmin();
        assertThat(pluck(get("/admin/kirana/fssai?status=CHECKING", admin).list(), "keptUntil")).isEmpty();   // AI's
        post("/admin/kirana/shops/" + s.a + "/fssai", Map.of("verified", false, "reason", "Blurred"), admin);
        Map<?, ?> refused = get("/kirana/online", s.aOwner).body();
        assertThat(objects(refused.get("missing"))).isEmpty();   // the earlier one holds
        fssai = (Map<?, ?>) refused.get("fssai");
        assertThat(fssai.get("status")).isEqualTo("REJECTED");
        assertThat(fssai.get("currentValidUntil")).isEqualTo(keptUntil);
        assertThat((String) fssai.get("reason")).startsWith("Blurred").contains("keeps your shop open until");
        assertThat(jdbc.queryForObject("SELECT body FROM notification_jobs WHERE type = 'KIRANA_FSSAI' "
                + "ORDER BY created_at DESC LIMIT 1", String.class)).contains("keeps your shop open until");
        assertThat(pluck(get("/admin/kirana/fssai?status=REJECTED", admin).list(), "keptUntil"))
                .containsExactly(keptUntil);

        jdbc.update("UPDATE kirana_online SET fssai_kept_until = current_date - 1 WHERE shop_id = ?", s.a);
        assertThat(objects(get("/kirana/online", s.aOwner).body().get("missing"))).containsExactly("FSSAI");
        assertThat(pluck(get("/kirana/nearby?lat=" + ME_LAT + "&lng=" + LNG, me).body().get("items"), "name"))
                .doesNotContain("Sharma General Store");

        post("/admin/kirana/shops/" + s.a + "/fssai", Map.of("verified", true,
                "validUntil", LocalDate.now().plusYears(5).toString()), admin);
        assertThat(jdbc.queryForList("SELECT fssai_kept_file_id FROM kirana_online WHERE shop_id = ? "
                + "AND fssai_kept_until IS NULL", UUID.class, s.a)).containsExactly((UUID) null);
        assertThat(objects(get("/kirana/online", s.aOwner).body().get("missing"))).isEmpty();
    }

    @Test
    void anotherLicenceNumberOrTheTeamEndingTheEarlierOneStopsOrders() {
        Shops s = network();
        // Another licence (not a renewal of this one): nothing kept, orders wait for it
        put("/kirana/online/fssai", Map.of("number", "13624013000222", "fileId", file(s.a)), s.aOwner);
        assertThat(objects(get("/kirana/online", s.aOwner).body().get("missing"))).containsExactly("FSSAI");

        put("/kirana/online/fssai", Map.of("number", "13624013000111", "fileId", file(s.b)), s.bOwner);
        assertThat(objects(get("/kirana/online", s.bOwner).body().get("missing"))).isEmpty();
        String admin = superAdmin();
        post("/admin/kirana/shops/" + s.b + "/fssai", Map.of("verified", false, "reason", "Licence cancelled",
                "endCurrent", true), admin);
        assertThat(objects(get("/kirana/online", s.bOwner).body().get("missing"))).containsExactly("FSSAI");
    }

    @Test
    void theCustomerCorrectsTheAddressUntilTheOrderGoesOutAndTheOwnerSeesWhatEachOrderEarned() {
        Shops s = network();
        String me = token(CUSTOMER);
        String orderId = orderOf(payFor(me, place(s.a, List.of(line(s.aMilk, "2")), "COMBINED", 6400)));
        String part = partOf(orderId, s.a);
        String changes = "SELECT count(*) FROM notification_jobs WHERE type = 'KIRANA_ORDER_CHANGED'";
        // Before the shop accepts: changed, the shop not told (it doesn't see the customer's address yet)
        Reply early = patch("/kirana/orders/" + orderId + "/address", Map.of("addressLine", "Tower B, Flat 1205"), me);
        assertThat(early.body().get("canChangeAddress")).isEqualTo(true);
        assertThat(jdbc.queryForObject(changes, Integer.class)).isZero();
        post("/kirana/shop-orders/" + part + "/accept", Map.of(), s.aOwner);
        Reply changed = patch("/kirana/orders/" + orderId + "/address", Map.of("addressLine", "Tower C, Flat 804"), me);
        assertThat(changed.status()).as(changed.body().toString()).isEqualTo(200);
        assertThat(changed.body().get("addressLine")).isEqualTo("Tower C, Flat 804");
        assertThat(jdbc.queryForObject(changes, Integer.class)).isPositive();
        assertThat(((Map<?, ?>) get("/kirana/shop-orders/" + part, s.aOwner).body().get("customer")).get("addressLine"))
                .isEqualTo("Tower C, Flat 804");
        int told = jdbc.queryForObject(changes, Integer.class);
        patch("/kirana/orders/" + orderId + "/address", Map.of("addressLine", "Tower C, Flat 804"), me);   // the same
        assertThat(jdbc.queryForObject(changes, Integer.class)).isEqualTo(told);
        assertThat(patch("/kirana/orders/" + orderId + "/address", Map.of("addressLine", "x"), me).status())
                .isEqualTo(400);
        assertThat(patch("/kirana/orders/" + orderId + "/address", Map.of("addressLine", "Tower D"), token(B_OWNER))
                .status()).isEqualTo(404);
        Reply third = patch("/kirana/orders/" + orderId + "/address", Map.of("addressLine", "Tower C, Flat 805"), me);
        assertThat(third.body().get("canChangeAddress")).isEqualTo(false);
        assertThat(patch("/kirana/orders/" + orderId + "/address", Map.of("addressLine", "Tower E"), me).body()
                .get("code")).isEqualTo("CANT_CHANGE_ADDRESS");
        post("/kirana/shop-orders/" + part + "/packed", Map.of(), s.aOwner);
        post("/kirana/shop-orders/" + part + "/out", Map.of(), s.aOwner);
        assertThat(patch("/kirana/orders/" + orderId + "/address", Map.of("addressLine", "Tower D"), me).body()
                .get("code")).isEqualTo("CANT_CHANGE_ADDRESS");

        String code = (String) get("/kirana/orders/" + orderId, me).body().get("otp");
        post("/kirana/shop-orders/" + part + "/deliver", Map.of("otp", code), s.aOwner);
        Map<?, ?> earned = get("/kirana/online/earnings", s.aOwner).body();
        assertThat(pluck(earned.get("items"), "amountPaise")).containsExactly(5400);
        Map<?, ?> row = (Map<?, ?>) ((List<?>) earned.get("items")).get(0);
        assertThat(row.get("payoutStatus")).isNull();
        assertThat(row.get("dueOn")).isNotNull();
        jdbc.update("UPDATE kirana_earnings SET due_on = current_date");
        jobs.payouts();
        row = (Map<?, ?>) ((List<?>) get("/kirana/online/earnings", s.aOwner).body().get("items")).get(0);
        assertThat(row.get("payoutStatus")).isEqualTo("SENT");
        assertThat(row.get("payoutUpiId")).isEqualTo("teststore@okhdfcbank");
        String helper = helperOf(s.aOwner, A_HELPER);
        assertThat(get("/kirana/online/earnings", helper).body().get("code")).isEqualTo("OWNER_ONLY");
    }

    @Test
    void manyTapsAtOnceMakeOneCheckoutAndAcceptAPartOnce() throws Exception {
        Shops s = network();
        String me = token(CUSTOMER);
        Map<String, Object> body = place(s.a, List.of(line(s.aMilk, "2")), "COMBINED", 6400);
        List<Reply> replies = together(8, () -> post("/kirana/checkouts", body, me));
        assertThat(replies).extracting(Reply::status).containsOnly(200);
        assertThat(replies.stream().map(r -> r.body().get("checkoutId")).distinct()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kirana_checkouts", Integer.class)).isEqualTo(1);

        String checkout = (String) replies.get(0).body().get("checkoutId");
        String rzOrder = (String) replies.get(0).body().get("razorpayOrderId");
        post("/kirana/checkouts/" + checkout + "/paid", Map.of("razorpayOrderId", rzOrder, "razorpayPaymentId", "pay_M",
                "razorpaySignature", sign(KEY_SECRET, rzOrder + "|pay_M")), me);
        String part = partOf(orderOf(checkout), s.a);
        List<Reply> accepts = together(8, () -> post("/kirana/shop-orders/" + part + "/accept", Map.of(), s.aOwner));
        assertThat(accepts.stream().filter(r -> r.status() == 200)).hasSize(1);
        assertThat(stock(s.aMilk)).isEqualByComparingTo("3");
    }

    // ---------- the network: A (milk, bread), B near A (milk, curd), C near the customer (curd) ----------

    private record Shops(UUID a, UUID b, UUID c, String aOwner, String bOwner, String cOwner, String aMilk,
                         String aBread, String bMilk, String bCurd, UUID curdItem) {
    }

    private Shops network() {
        String a = ready(A_OWNER, "Sharma General Store", A_LAT);
        String b = ready(B_OWNER, "Gupta Kirana", B_LAT);
        String c = ready(C_OWNER, "Bansal Stores", C_LAT);
        String aMilk = id(post("/kirana/products", coded("Amul Taaza 500 ml", 2700, "5", "8901262150019"), a));
        String aBread = id(post("/kirana/products", product("Harvest Gold 400 g", "PIECE", 4500, "2"), a));
        String bMilk = id(post("/kirana/products", coded("Amul Taaza", 2800, "10", "8901262150019"), b));
        String bCurd = id(post("/kirana/products", coded("Nandini Curd 500 g", 3000, "6", "8901110200019"), b));
        id(post("/kirana/products", coded("Curd 500 g", 3200, "6", "8901110200019"), c));
        return new Shops(shopId(a), shopId(b), shopId(c), a, b, c, aMilk, aBread, bMilk, bCurd, catalogueOf(bCurd));
    }

    /** A kirana that takes online orders: approved, its UPI ID, a verified FSSAI licence, its switch on. */
    private String ready(String phone, String name, BigDecimal lat) {
        String owner = kirana(phone, name, lat);
        UUID shop = shopId(owner);
        jdbc.update("UPDATE shops SET status = 'APPROVED', ever_approved = true WHERE id = ?", shop);
        TestData.kiranaUpiId(jdbc, shop);
        jdbc.update("INSERT INTO kirana_online (shop_id, taking_orders, fssai_number, fssai_file_id, fssai_status, "
                + "fssai_valid_until, fssai_tries, upi_recheck, rating_sum, rating_count, updated_at) "
                + "VALUES (?, true, '13624013000111', ?::uuid, 'VERIFIED', current_date + 365, 0, false, 0, 0, now())",
                shop, file(shop));
        return owner;
    }

    private String kirana(String phone, String name, BigDecimal lat) {
        String access = token(phone);
        Map<String, Object> shop = new HashMap<>();
        shop.put("name", name);
        shop.put("ownerName", "Ramesh Sharma");
        shop.put("addressLine", "Shop 4, Green Meadows main gate");
        shop.put("area", "Kompally");
        shop.put("city", "Hyderabad");
        shop.put("pincode", "500014");
        shop.put("latitude", lat);
        shop.put("longitude", LNG);
        shop.put("type", "KIRANA");
        Reply r = post("/shops", shop, access);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        return access;
    }

    private String file(UUID shopId) {
        UUID file = UUID.randomUUID();
        UUID owner = jdbc.queryForObject("SELECT user_id FROM shop_members WHERE shop_id = ? AND role = 'OWNER'",
                UUID.class, shopId);
        jdbc.update("INSERT INTO files (id, shop_id, purpose, storage_key, content_type, size_bytes, sha256, "
                        + "uploaded_by, created_at) VALUES (?, ?, 'REG_DOC', ?, 'image/jpeg', 10, ?, ?, now())", file,
                shopId, "test/" + file + ".jpg", file.toString().replace("-", "") + "0".repeat(32), owner);
        return file.toString();
    }

    private String superAdmin() {
        token(ADMIN);
        jdbc.update("UPDATE users SET admin_role = 'SUPER_ADMIN' WHERE phone = ?", ADMIN);
        return token(ADMIN);
    }

    private String helperOf(String ownerToken, String phone) {
        UUID shopId = shopId(ownerToken);
        Map<?, ?> session = signInBody(phone);
        UUID helperId = UUID.fromString((String) ((Map<?, ?>) session.get("user")).get("id"));
        TestData.member(jdbc, shopId, helperId, "HELPER");
        return (String) session.get("accessToken");
    }

    // ---------- orders ----------

    private static Map<String, Object> product(String name, String unit, long price, String stock) {
        Map<String, Object> p = new HashMap<>();
        p.put("name", name);
        p.put("unit", unit);
        p.put("pricePaise", price);
        p.put("stock", new BigDecimal(stock));
        return p;
    }

    private static Map<String, Object> coded(String name, long price, String stock, String barcode) {
        Map<String, Object> p = product(name, "PIECE", price, stock);
        p.put("barcode", barcode);
        return p;
    }

    private static Map<String, Object> line(String productId, String quantity) {
        return Map.of("productId", productId, "quantity", new BigDecimal(quantity));
    }

    private static Map<String, Object> catalogueLine(UUID itemId, String quantity) {
        return Map.of("catalogueItemId", itemId.toString(), "quantity", new BigDecimal(quantity));
    }

    private static Map<String, Object> quote(UUID shop, List<Map<String, Object>> lines) {
        return Map.of("shopId", shop.toString(), "latitude", ME_LAT, "longitude", LNG, "lines", lines);
    }

    private static Map<String, Object> place(UUID shop, List<Map<String, Object>> lines, String plan, long total) {
        Map<String, Object> b = new HashMap<>();
        b.put("clientRef", UUID.randomUUID().toString());
        b.put("shopId", shop.toString());
        b.put("latitude", ME_LAT);
        b.put("longitude", LNG);
        b.put("addressLine", "Tower B, Flat 1204, Green Meadows");
        b.put("lines", lines);
        b.put("plan", plan);
        b.put("method", "UPI");
        b.put("expectedTotalPaise", total);
        return b;
    }

    /** Places the checkout and pays it (the checkout's signed answer); its id. */
    private String payFor(String token, Map<String, Object> body) {
        Reply pay = post("/kirana/checkouts", body, token);
        assertThat(pay.status()).as(pay.body().toString()).isEqualTo(200);
        String checkout = (String) pay.body().get("checkoutId");
        String rzOrder = (String) pay.body().get("razorpayOrderId");
        String payment = "pay_" + UUID.randomUUID().toString().substring(0, 8);
        Reply paid = post("/kirana/checkouts/" + checkout + "/paid", Map.of("razorpayOrderId", rzOrder,
                "razorpayPaymentId", payment, "razorpaySignature", sign(KEY_SECRET, rzOrder + "|" + payment)), token);
        assertThat(paid.body().get("status")).as(paid.body().toString()).isEqualTo("PAID");
        return checkout;
    }

    private void deliver(String ownerToken, String part, String orderId, String customer) {
        post("/kirana/shop-orders/" + part + "/accept", Map.of(), ownerToken);
        post("/kirana/shop-orders/" + part + "/packed", Map.of(), ownerToken);
        post("/kirana/shop-orders/" + part + "/out", Map.of(), ownerToken);
        String code = (String) get("/kirana/orders/" + orderId, customer).body().get("otp");
        Reply r = post("/kirana/shop-orders/" + part + "/deliver", Map.of("otp", code), ownerToken);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    }

    private String orderOf(String checkout) {
        return jdbc.queryForObject("SELECT id::text FROM kirana_orders WHERE checkout_id = ?::uuid ORDER BY order_no "
                + "LIMIT 1", String.class, checkout);
    }

    private String partOf(String orderId, UUID shop) {
        return jdbc.queryForObject("SELECT id::text FROM kirana_order_parts WHERE order_id = ?::uuid AND shop_id = ?",
                String.class, orderId, shop);
    }

    private UUID catalogueOf(String productId) {
        return jdbc.queryForObject("SELECT catalogue_item_id FROM kirana_products WHERE id = ?::uuid", UUID.class,
                productId);
    }

    private BigDecimal stock(String productId) {
        return jdbc.queryForObject("SELECT stock FROM kirana_products WHERE id = ?::uuid", BigDecimal.class, productId);
    }

    private static String wrongCode(String code) {
        return code.equals("0000") ? "1111" : "0000";
    }

    private static String captured(String rzOrder, String paymentId) {
        return "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"" + paymentId
                + "\",\"order_id\":\"" + rzOrder + "\",\"method\":\"upi\",\"amount\":3700,\"status\":\"captured\"}}}}";
    }

    private Reply webhook(String body, String eventId) {
        return postRaw("/payments/razorpay/webhook", body, sign(WEBHOOK_SECRET, body), eventId);
    }

    private Reply xWebhook(String body, String eventId) {
        return postRaw("/payments/razorpayx/webhook", body, sign(X_WEBHOOK_SECRET, body), eventId);
    }

    private static String sign(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Editing unlocked for this sign-in, as if its owner had entered the code (EditUnlockStore: user and session). */
    private void unlockEditing(String accessToken) {
        String payload = new String(java.util.Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]),
                StandardCharsets.UTF_8);
        java.util.regex.Matcher sub = java.util.regex.Pattern.compile("\"sub\"\\s*:\\s*\"([^\"]+)\"").matcher(payload);
        java.util.regex.Matcher sid = java.util.regex.Pattern.compile("\"sid\"\\s*:\\s*\"([^\"]+)\"").matcher(payload);
        String user = sub.find() ? sub.group(1) : "";
        String session = sid.find() ? sid.group(1) : "-";
        redis.opsForValue().set("edit-unlock:" + user + ":" + session,
                Long.toString(java.time.Instant.parse("9999-01-01T00:00:00Z").toEpochMilli()),
                java.time.Duration.ofHours(1));
    }

    // ---------- plumbing ----------

    private static List<Reply> together(int n, Callable<Reply> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Reply>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return call.call();
                }));
            }
            go.countDown();
            List<Reply> out = new ArrayList<>();
            for (Future<Reply> f : futures) {
                out.add(f.get(60, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private Map<?, ?> signInBody(String phone) {
        post("/auth/otp/send", Map.of("phone", phone), null);
        Reply r = post("/auth/otp/verify", Map.of("phone", phone, "code", codeSentTo(LAST_SMS, phone)), null);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        TestData.completeProfile(jdbc, phone);
        return r.body();
    }

    private String token(String phone) {
        return (String) signInBody(phone).get("accessToken");
    }

    private UUID shopId(String token) {
        return UUID.fromString((String) get("/shops/me", token).body().get("id"));
    }

    private static String id(Reply r) {
        assertThat(r.status()).as(r.body().toString()).isIn(200, 201);
        return (String) r.body().get("id");
    }

    private static List<Object> keys(Object jsonObject) {
        return new ArrayList<>(((Map<?, ?>) jsonObject).keySet());
    }

    private static List<Object> objects(Object jsonArray) {
        return new ArrayList<>((List<?>) jsonArray);
    }

    private static List<Object> pluck(Object jsonArray, String field) {
        List<Object> out = new ArrayList<>();
        for (Object o : (List<?>) jsonArray) {
            out.add(((Map<?, ?>) o).get(field));
        }
        return out;
    }

    private static List<?> getList(Reply r, String field) {
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return (List<?>) r.body().get(field);
    }

    private Reply post(String path, Object body, String token) {
        return http.post().uri(path).headers(h -> bearer(h, token)).contentType(MediaType.APPLICATION_JSON)
                .body(body).exchange((rq, res) -> reply(res));
    }

    private Reply postRaw(String path, String body, String signature, String eventId) {
        return http.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                .headers(h -> {
                    h.add("X-Razorpay-Signature", signature);
                    h.add("X-Razorpay-Event-Id", eventId);
                })
                .body(body.getBytes(StandardCharsets.UTF_8)).exchange((rq, res) -> reply(res));
    }

    private Reply patch(String path, Object body, String token) {
        return http.patch().uri(path).headers(h -> bearer(h, token)).contentType(MediaType.APPLICATION_JSON)
                .body(body).exchange((rq, res) -> reply(res));
    }

    private Reply put(String path, Object body, String token) {
        return http.put().uri(path).headers(h -> bearer(h, token)).contentType(MediaType.APPLICATION_JSON)
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

    private static Reply reply(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse res) throws IOException {
        int status = res.getStatusCode().value();
        if (status == 204) {
            return new Reply(status, Map.of(), List.of());
        }
        Object body = res.bodyTo(Object.class);
        if (body instanceof List<?> l) {
            return new Reply(status, Map.of(), l);
        }
        return new Reply(status, body instanceof Map<?, ?> m ? m : Map.of(), List.of());
    }

    private record Reply(int status, Map<?, ?> body, List<?> list) {
    }
}
