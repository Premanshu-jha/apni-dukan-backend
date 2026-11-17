package in.apnidukan.kirana;

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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static in.apnidukan.support.CapturingSenders.LAST_EMAIL;
import static in.apnidukan.support.CapturingSenders.LAST_SMS;
import static in.apnidukan.support.CapturingSenders.codeSentTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The kirana counter end to end (V66–V67), against real Postgres and Redis in Docker, signing in through the real OTP
 * flow: the UPI ID and Submit for visit, the catalogue and stock, walk-in bills (with double taps and bills at the same
 * moment), the khata book, cancelling a bill and the day's totals; and that salon people never reach any of it.
 */
@EnabledIf("in.apnidukan.support.TestContainers#dockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CapturingSenders.class)
class KiranaIntegrationTest {

    private static final String OWNER_PHONE = "9848033001";
    private static final String HELPER_PHONE = "9848033002";
    private static final String OTHER_OWNER_PHONE = "9848033003";
    private static final String SALON_OWNER_PHONE = "9848033004";

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        TestContainers.register(registry);
    }

    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;

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

    // ---------- who may use it ----------

    @Test
    void onlyAKiranasPeopleReachTheCounter() {
        String salonOwner = shopOwner(SALON_OWNER_PHONE, "SALON");
        for (String path : List.of("/kirana/products", "/kirana/bills", "/kirana/khata", "/kirana/settings",
                "/kirana/day", "/kirana/products/running-out")) {
            Reply r = get(path, salonOwner);
            assertThat(r.status()).as(path).isEqualTo(409);
            assertThat(r.body().get("code")).as(path).isEqualTo("NOT_A_KIRANA");
        }
        assertThat(post("/kirana/products", product("Parle-G 250 g", null, "PIECE", 2500), salonOwner).body()
                .get("code")).isEqualTo("NOT_A_KIRANA");

        Reply noShop = get("/kirana/products", token(OTHER_OWNER_PHONE));
        assertThat(noShop.status()).isEqualTo(404);
        assertThat(noShop.body().get("code")).isEqualTo("NO_SHOP");
        assertThat(get("/kirana/products", null).status()).isEqualTo(401);
    }

    @Test
    void anotherShopsProductsBillsAndCustomersAreNotFound() {
        String mine = kiranaOwner(OWNER_PHONE);
        String theirs = kiranaOwner(OTHER_OWNER_PHONE);
        String productId = id(post("/kirana/products", product("Tata Salt 1 kg", null, "PIECE", 2800), theirs));
        String customerId = id(post("/kirana/khata", Map.of("name", "Mrs. Khan"), theirs));
        String billId = (String) ((Map<?, ?>) post("/kirana/bills", bill("CASH", null, line(productId, "1")), theirs)
                .body().get("bill")).get("id");

        assertThat(get("/kirana/products/" + productId, mine).body().get("code")).isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(post("/kirana/products/" + productId + "/stock", stock("RESTOCK", 5, null), mine)
                .status()).isEqualTo(404);
        assertThat(get("/kirana/khata/" + customerId, mine).body().get("code")).isEqualTo("KHATA_CUSTOMER_NOT_FOUND");
        assertThat(get("/kirana/bills/" + billId, mine).body().get("code")).isEqualTo("BILL_NOT_FOUND");
        // Their product on my bill: as if it didn't exist
        Reply r = post("/kirana/bills", bill("CASH", null, line(productId, "1")), mine);
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body().get("code")).isEqualTo("PRODUCT_GONE");
        assertThat(getList(get("/kirana/products", mine), "items")).isEmpty();
    }

    // ---------- the UPI ID and Submit for visit ----------

    @Test
    void aKiranaNeedsItsUpiIdBeforeTheVisitAndOnlyTheOwnerSetsIt() {
        String owner = kiranaOwner(OWNER_PHONE);
        String helper = helperOf(owner, HELPER_PHONE);
        UUID shopId = shopId(owner);
        jdbc.update("UPDATE shops SET owner_photo_file_id = ?::uuid, shop_photo_file_id = ?::uuid WHERE id = ?",
                photo(shopId, "OWNER_PHOTO"), photo(shopId, "SHOP_PHOTO"), shopId);

        Reply early = post("/shops/me/submit", Map.of("version", version(owner)), owner);
        assertThat(early.status()).isEqualTo(409);
        assertThat(early.body().get("code")).isEqualTo("REGISTRATION_INCOMPLETE");
        assertThat(keys(early.body().get("details"))).containsExactly("upiId");

        assertThat(get("/kirana/settings", owner).body().get("upiId")).isNull();
        Reply helperTries = put("/kirana/settings/upi-id", Map.of("upiId", "helper@ybl"), helper);
        assertThat(helperTries.status()).isEqualTo(403);
        assertThat(helperTries.body().get("code")).isEqualTo("OWNER_ONLY");
        Reply bad = put("/kirana/settings/upi-id", Map.of("upiId", "sharmastore"), owner);
        assertThat(bad.status()).isEqualTo(400);
        assertThat(keys(bad.body().get("details"))).containsExactly("upiId");

        Reply set = put("/kirana/settings/upi-id", Map.of("upiId", " SharmaStore@OKHDFCBank "), owner);
        assertThat(set.status()).as(set.body().toString()).isEqualTo(200);
        assertThat(set.body().get("upiId")).isEqualTo("sharmastore@okhdfcbank");
        assertThat(set.body().get("shopName")).isEqualTo("Sharma General Store");
        // The helper sees it: he shows the QR at the counter
        assertThat(get("/kirana/settings", helper).body().get("upiId")).isEqualTo("sharmastore@okhdfcbank");

        Reply submitted = post("/shops/me/submit", Map.of("version", version(owner)), owner);
        assertThat(submitted.status()).as(submitted.body().toString()).isEqualTo(200);
        assertThat(submitted.body().get("status")).isEqualTo("PENDING_VISIT");
    }

    // ---------- the catalogue ----------

    @Test
    void productsAreAddedOnceByNameAndByBarcodeAndFoundByAScan() {
        String owner = kiranaOwner(OWNER_PHONE);
        String helper = helperOf(owner, HELPER_PHONE);
        Reply salt = post("/kirana/products", product(" Tata   Salt 1 kg ", " 8904004400205 ", "PIECE", 2800), helper);
        assertThat(salt.status()).as(salt.body().toString()).isEqualTo(201);
        assertThat(salt.body().get("name")).isEqualTo("Tata Salt 1 kg");
        assertThat(pluck(salt.body().get("barcodes"), "code")).containsExactly("8904004400205");
        assertThat(num(salt.body().get("stock"))).isEqualByComparingTo("0");

        Reply sameName = post("/kirana/products", product("TATA SALT 1 KG", null, "PIECE", 2900), owner);
        assertThat(sameName.status()).isEqualTo(409);
        assertThat(sameName.body().get("code")).isEqualTo("NAME_TAKEN");
        Reply sameCode = post("/kirana/products", product("Salt", "8904004400205", "PIECE", 2900), owner);
        assertThat(sameCode.body().get("code")).isEqualTo("BARCODE_TAKEN");
        assertThat(((Map<?, ?>) sameCode.body().get("details")).get("productId")).isEqualTo(id(salt));

        Reply scan = get("/kirana/products/barcode/8904004400205", helper);
        assertThat(((Map<?, ?>) scan.body().get("product")).get("id")).isEqualTo(id(salt));
        assertThat(scan.body().get("pack")).isEqualTo(1);
        Reply unknown = get("/kirana/products/barcode/8901262150019", helper);
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.body().get("code")).isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(get("/kirana/products/barcode/AB", helper).status()).isEqualTo(404);   // a misread scan
        // Digits typed from a pack that won't scan find it too
        assertThat(pluck(getList(search("/kirana/products", "89040044", helper), "items"), "id"))
                .containsExactly(id(salt));

        Reply badUnit = post("/kirana/products", Map.of("name", "Sugar", "unit", "LITRE", "pricePaise", 4800), owner);
        assertThat(badUnit.status()).isEqualTo(400);
        Map<String, Object> half = product("Eggs", null, "PIECE", 700);
        half.put("stock", new BigDecimal("1.5"));
        Reply halfEgg = post("/kirana/products", half, owner);
        assertThat(halfEgg.status()).isEqualTo(400);
        assertThat(keys(halfEgg.body().get("details"))).containsExactly("stock");
    }

    @Test
    void theOwnerChangesProductsAndAHelperOnlyLinksBarcodes() {
        String owner = kiranaOwner(OWNER_PHONE);
        String helper = helperOf(owner, HELPER_PHONE);
        Reply milk = post("/kirana/products", product("Amul Taaza 500 ml", null, "PIECE", 2700), owner);
        String id = id(milk);

        Reply price = patch("/kirana/products/" + id, Map.of("version", 0, "pricePaise", 2800), helper);
        assertThat(price.status()).isEqualTo(403);
        assertThat(price.body().get("code")).isEqualTo("OWNER_ONLY");
        Reply link = post("/kirana/products/" + id + "/barcodes", Map.of("code", "8901262150019"), helper);
        assertThat(link.status()).as(link.body().toString()).isEqualTo(200);
        assertThat(pluck(link.body().get("barcodes"), "code")).containsExactly("8901262150019");

        Reply stale = patch("/kirana/products/" + id, Map.of("version", 1, "pricePaise", 2800), owner);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.body().get("code")).isEqualTo("PRODUCT_CHANGED");
        Reply priced = patch("/kirana/products/" + id, Map.of("version", 0, "pricePaise", 2800, "lowStockAt", 10),
                owner);
        assertThat(priced.body().get("pricePaise")).isEqualTo(2800);
        assertThat(priced.body().get("runningOut")).isEqualTo(true);   // 0 left, alert at 10

        // Removed: off the list and the scan; its name and barcode are free for a new product
        Reply removed = patch("/kirana/products/" + id, Map.of("version", 1, "active", false), owner);
        assertThat(removed.body().get("active")).isEqualTo(false);
        assertThat(get("/kirana/products/barcode/8901262150019", owner).status()).isEqualTo(404);
        assertThat(post("/kirana/products", product("Amul Taaza 500 ml (new pack)", "8901262150019", "PIECE", 2900),
                owner).status()).isEqualTo(201);
        // Putting the old one back: its barcode now belongs to the new one, so it comes back without it
        Reply back = patch("/kirana/products/" + id, Map.of("version", 2, "active", true), owner);
        assertThat(back.status()).as(back.body().toString()).isEqualTo(200);
        assertThat((List<?>) back.body().get("barcodes")).isEmpty();
        assertThat(((Map<?, ?>) get("/kirana/products/barcode/8901262150019", owner).body().get("product"))
                .get("name")).isEqualTo("Amul Taaza 500 ml (new pack)");
        // A name taken meanwhile does stop it coming back
        patch("/kirana/products/" + id, Map.of("version", 3, "active", false), owner);
        post("/kirana/products", product("Amul Taaza 500 ml", null, "PIECE", 2900), owner);
        Reply clash = patch("/kirana/products/" + id, Map.of("version", 4, "active", true), owner);
        assertThat(clash.body().get("code")).isEqualTo("NAME_TAKEN");
    }

    @Test
    void theListComesInNameOrderAPageAtATimeAndSearchesByTheStartOfAName() {
        String owner = kiranaOwner(OWNER_PHONE);
        UUID shopId = shopId(owner);
        UUID by = jdbc.queryForObject("SELECT user_id FROM shop_members WHERE shop_id = ? AND role = 'OWNER'",
                UUID.class, shopId);
        for (int i = 1; i <= 55; i++) {
            jdbc.update("INSERT INTO kirana_products (id, shop_id, name, unit, price_paise, stock, active, version, "
                    + "created_by, created_at, updated_at) VALUES (?, ?, ?, 'PIECE', 1000, 1, true, 0, ?, now(), now())",
                    UUID.randomUUID(), shopId, String.format("Item %02d", i), by);
        }
        post("/kirana/products", product("100% Pure Ghee", null, "PIECE", 60000), owner);
        Reply first = get("/kirana/products", owner);
        List<?> items = getList(first, "items");
        assertThat(items).hasSize(50);
        assertThat(((Map<?, ?>) items.get(0)).get("name")).isEqualTo("100% Pure Ghee");
        assertThat(((Map<?, ?>) items.get(1)).get("name")).isEqualTo("Item 01");
        Reply second = get("/kirana/products?cursor=" + first.body().get("next"), owner);
        assertThat(getList(second, "items")).hasSize(6);
        assertThat(((Map<?, ?>) getList(second, "items").get(5)).get("name")).isEqualTo("Item 55");
        assertThat(second.body().get("next")).isNull();

        assertThat(getList(search("/kirana/products", "item 5", owner), "items")).hasSize(6);   // 50–55
        assertThat(getList(search("/kirana/products", "pure", owner), "items")).hasSize(1);    // a word inside
        // % and _ are letters here, not wildcards
        assertThat(getList(search("/kirana/products", "100%", owner), "items")).hasSize(1);
        assertThat(getList(search("/kirana/products", "_", owner), "items")).isEmpty();
        // A broken token is the first page, not an error
        assertThat(getList(get("/kirana/products?cursor=rubbish", owner), "items")).hasSize(50);
    }

    // ---------- stock ----------

    @Test
    void stockComesInGoesOutDamagedAndIsCountedEachWithItsReason() {
        String owner = kiranaOwner(OWNER_PHONE);
        String helper = helperOf(owner, HELPER_PHONE);
        Map<String, Object> sugar = product("Sugar (loose)", null, "KG", 4800);
        sugar.put("stock", new BigDecimal("10.5"));
        String id = id(post("/kirana/products", sugar, owner));

        Map<String, Object> restock = stock("RESTOCK", 25, "Krishna bill KD-4412");
        assertThat(num(post("/kirana/products/" + id + "/stock", restock, helper).body().get("stock")))
                .isEqualByComparingTo("35.5");
        // The app retries after a lost answer: the stock comes in once
        assertThat(num(post("/kirana/products/" + id + "/stock", restock, helper).body().get("stock")))
                .isEqualByComparingTo("35.5");
        assertThat(num(post("/kirana/products/" + id + "/stock", stock("DAMAGED", new BigDecimal("0.25"), null),
                helper).body().get("stock"))).isEqualByComparingTo("35.25");
        assertThat(num(post("/kirana/products/" + id + "/stock", stock("COUNT", 30, null), owner)
                .body().get("stock"))).isEqualByComparingTo("30");
        // The same count again changes nothing and records nothing
        post("/kirana/products/" + id + "/stock", stock("COUNT", 30, null), owner);
        Reply grams = post("/kirana/products/" + id + "/stock", stock("RESTOCK", new BigDecimal("0.0001"), null),
                owner);
        assertThat(grams.status()).isEqualTo(400);
        assertThat(post("/kirana/products/" + id + "/stock", stock("RESTOCK", 0, null), owner)
                .status()).isEqualTo(400);

        List<?> moves = (List<?>) get("/kirana/products/" + id, owner).body().get("moves");
        assertThat(pluck(moves, "reason"))
                .containsExactly("COUNT", "DAMAGED", "RESTOCK", "COUNT");   // newest first; the first count on adding
        Map<?, ?> count = (Map<?, ?>) moves.get(0);
        assertThat(num(count.get("change"))).isEqualByComparingTo("-5.25");
        assertThat(count.get("byName")).isEqualTo("Test person");
        assertThat(((Map<?, ?>) moves.get(2)).get("note")).isEqualTo("Krishna bill KD-4412");
    }

    // ---------- bills ----------

    @Test
    void aBillUsesTheCatalogPricesTakesTheStockDownAndRecordsEachSale() {
        String owner = kiranaOwner(OWNER_PHONE);
        String helper = helperOf(owner, HELPER_PHONE);
        String milk = id(post("/kirana/products", stocked("Amul Taaza 500 ml", "PIECE", 2700, "20", "5"), owner));
        String sugar = id(post("/kirana/products", stocked("Sugar (loose)", "KG", 4800, "3", null), owner));

        Reply r = post("/kirana/bills", bill("UPI", null, line(milk, "2"), line(sugar, "0.25"), line(milk, "1")), helper);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        Map<?, ?> bill = (Map<?, ?>) r.body().get("bill");
        assertThat(bill.get("billNo")).isEqualTo(1);
        assertThat(bill.get("totalPaise")).isEqualTo(2 * 2700 + 1200 + 2700);
        assertThat(bill.get("payment")).isEqualTo("UPI");
        assertThat(bill.get("byName")).isEqualTo("Test person");
        assertThat(pluck(bill.get("lines"), "amountPaise"))
                .containsExactly(5400, 1200, 2700);
        assertThat(r.body().get("repeated")).isEqualTo(false);
        List<?> stock = (List<?>) r.body().get("stock");
        assertThat(stock).hasSize(2);
        Map<?, ?> milkStock = stock.stream().map(s -> (Map<?, ?>) s)
                .filter(s -> milk.equals(s.get("productId"))).findFirst().orElseThrow();
        assertThat(num(milkStock.get("before"))).isEqualByComparingTo("20");
        assertThat(num(milkStock.get("after"))).isEqualByComparingTo("17");
        Map<?, ?> sugarNow = (Map<?, ?>) get("/kirana/products/" + sugar, owner).body().get("product");
        assertThat(num(sugarNow.get("stock"))).isEqualByComparingTo("2.75");

        // A price change later never changes this bill
        patch("/kirana/products/" + milk, Map.of("version", 0, "pricePaise", 3000), owner);
        Reply again = get("/kirana/bills/" + bill.get("id"), owner);
        assertThat(again.body().get("totalPaise")).isEqualTo(9300);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM kirana_stock_moves WHERE reason = 'SALE'", Integer.class))
                .isEqualTo(2);   // one per product, not per line
        Reply second = post("/kirana/bills", bill("CASH", null, line(milk, "1")), owner);
        assertThat(((Map<?, ?>) second.body().get("bill")).get("billNo")).isEqualTo(2);
    }

    @Test
    void aBillCanTakeStockBelowZeroAndSaysTheItemIsRunningOut() {
        String owner = kiranaOwner(OWNER_PHONE);
        String eggs = id(post("/kirana/products", stocked("Farm Eggs 6 pcs", "PIECE", 4800, "1", null), owner));
        Reply r = post("/kirana/bills", bill("CASH", null, line(eggs, "3")), owner);
        assertThat(r.status()).isEqualTo(201);
        Map<?, ?> level = (Map<?, ?>) ((List<?>) r.body().get("stock")).get(0);
        assertThat(num(level.get("after"))).isEqualByComparingTo("-2");
        assertThat(level.get("runningOut")).isEqualTo(true);
        assertThat(pluck(getListBody(get("/kirana/products/running-out", owner)), "id"))
                .containsExactly(eggs);
    }

    @Test
    void aBillWithAWrongQuantityOrARemovedProductIsRefusedAndChangesNothing() {
        String owner = kiranaOwner(OWNER_PHONE);
        String milk = id(post("/kirana/products", stocked("Amul Taaza 500 ml", "PIECE", 2700, "20", null), owner));
        String bread = id(post("/kirana/products", stocked("Harvest Gold 400 g", "PIECE", 4500, "5", null), owner));

        Reply half = post("/kirana/bills", bill("CASH", null, line(bread, "1"), line(milk, "1.5")), owner);
        assertThat(half.status()).isEqualTo(400);
        assertThat(keys(half.body().get("details"))).containsExactly("lines[1].quantity");
        Reply none = post("/kirana/bills", bill("CASH", null), owner);
        assertThat(none.status()).isEqualTo(400);
        Reply khataWithout = post("/kirana/bills", bill("KHATA", null, line(milk, "1")), owner);
        assertThat(keys(khataWithout.body().get("details"))).containsExactly("khataCustomerId");

        patch("/kirana/products/" + bread, Map.of("version", 0, "active", false), owner);
        Reply gone = post("/kirana/bills", bill("CASH", null, line(milk, "1"), line(bread, "1")), owner);
        assertThat(gone.status()).isEqualTo(409);
        assertThat(gone.body().get("code")).isEqualTo("PRODUCT_GONE");
        assertThat(objects(((Map<?, ?>) gone.body().get("details")).get("productIds"))).containsExactly(bread);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM kirana_bills", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT stock FROM kirana_products WHERE id = ?::uuid", BigDecimal.class, milk))
                .isEqualByComparingTo("20");
    }

    @Test
    void theSameBillSentTwiceIsBilledOnceEvenAtTheSameMoment() throws Exception {
        String owner = kiranaOwner(OWNER_PHONE);
        String milk = id(post("/kirana/products", stocked("Amul Taaza 500 ml", "PIECE", 2700, "20", null), owner));
        Map<String, Object> body = bill("CASH", null, line(milk, "2"));

        List<Reply> replies = together(8, () -> post("/kirana/bills", body, owner));
        assertThat(replies).extracting(Reply::status).containsOnly(200, 201);
        assertThat(replies.stream().filter(r -> r.status() == 201)).hasSize(1);
        assertThat(replies.stream().map(r -> ((Map<?, ?>) r.body().get("bill")).get("id")).distinct()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT stock FROM kirana_products WHERE id = ?::uuid", BigDecimal.class, milk))
                .isEqualByComparingTo("18");
        // A repeat later says so, bills nothing, and shows what the first one did to the stock
        Reply later = post("/kirana/bills", body, owner);
        assertThat(later.status()).isEqualTo(200);
        assertThat(later.body().get("repeated")).isEqualTo(true);
        Map<?, ?> level = (Map<?, ?>) ((List<?>) later.body().get("stock")).get(0);
        assertThat(num(level.get("before"))).isEqualByComparingTo("20");
        assertThat(num(level.get("after"))).isEqualByComparingTo("18");
    }

    @Test
    void manyBillsAtOnceEachGetTheirOwnNumberAndEverySaleCounts() throws Exception {
        String owner = kiranaOwner(OWNER_PHONE);
        String helper = helperOf(owner, HELPER_PHONE);
        String milk = id(post("/kirana/products", stocked("Amul Taaza 500 ml", "PIECE", 2700, "100", null), owner));
        String bread = id(post("/kirana/products", stocked("Harvest Gold 400 g", "PIECE", 4500, "100", null), owner));

        List<Reply> replies = together(10, () -> post("/kirana/bills",
                bill("CASH", null, line(bread, "1"), line(milk, "1")), Math.random() < 0.5 ? owner : helper));
        assertThat(replies).extracting(Reply::status).containsOnly(201);
        assertThat(replies.stream().map(r -> ((Number) ((Map<?, ?>) r.body().get("bill")).get("billNo")).intValue())
                .sorted().toList())
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(jdbc.queryForList("SELECT stock FROM kirana_products ORDER BY name", BigDecimal.class))
                .allSatisfy(s -> assertThat(s).isEqualByComparingTo("90"));
    }

    @Test
    void theOwnerCancelsAnyBillAndItsStockComesBack() {
        String owner = kiranaOwner(OWNER_PHONE);
        String helper = helperOf(owner, HELPER_PHONE);
        String milk = id(post("/kirana/products", stocked("Amul Taaza 500 ml", "PIECE", 2700, "20", null), owner));
        String billId = (String) ((Map<?, ?>) post("/kirana/bills", bill("CASH", null, line(milk, "3")), owner)
                .body().get("bill")).get("id");

        // Not his bill: only the owner may cancel it
        Reply byHelper = post("/kirana/bills/" + billId + "/void", Map.of("reason", "Wrong item"), helper);
        assertThat(byHelper.status()).isEqualTo(403);
        assertThat(byHelper.body().get("code")).isEqualTo("OWNER_ONLY");
        Reply noReason = post("/kirana/bills/" + billId + "/void", Map.of("reason", " "), owner);
        assertThat(noReason.status()).isEqualTo(400);

        Reply voided = post("/kirana/bills/" + billId + "/void", Map.of("reason", "Customer returned it"), owner);
        assertThat(voided.status()).as(voided.body().toString()).isEqualTo(200);
        Map<?, ?> cancelledBill = (Map<?, ?>) voided.body().get("bill");
        assertThat(cancelledBill.get("voidReason")).isEqualTo("Customer returned it");
        assertThat(cancelledBill.get("voidedAt")).isNotNull();
        assertThat(num(((Map<?, ?>) ((List<?>) voided.body().get("stock")).get(0)).get("after")))
                .isEqualByComparingTo("20");
        assertThat(jdbc.queryForObject("SELECT change FROM kirana_stock_moves WHERE reason = 'VOID'", BigDecimal.class))
                .isEqualByComparingTo("3");
        assertThat(jdbc.queryForObject("SELECT stock FROM kirana_products WHERE id = ?::uuid", BigDecimal.class, milk))
                .isEqualByComparingTo("20");
        Reply twice = post("/kirana/bills/" + billId + "/void", Map.of("reason", "Again"), owner);
        assertThat(twice.status()).isEqualTo(409);
        assertThat(twice.body().get("code")).isEqualTo("BILL_VOIDED");

        Map<?, ?> listed = (Map<?, ?>) getList(get("/kirana/bills", owner), "items").get(0);
        assertThat(listed.get("voided")).isEqualTo(true);
        assertThat(listed.get("preview")).isEqualTo("Amul Taaza 500 ml");
        // The bill stays on its day; the cancellation shows too, so the drawer's cash adds up
        Map<?, ?> day = get("/kirana/day", owner).body();
        assertThat(day.get("bills")).isEqualTo(1);
        assertThat(day.get("cancelledBills")).isEqualTo(1);
        assertThat(day.get("cancelledCashPaise")).isEqualTo(8100);
        assertThat(day.get("cashInPaise")).isEqualTo(0);
    }

    // ---------- the khata book ----------

    @Test
    void khataSalesStayWithinTheLimitAndPaymentsBringTheBalanceDown() {
        String owner = kiranaOwner(OWNER_PHONE);
        String helper = helperOf(owner, HELPER_PHONE);
        String atta = id(post("/kirana/products", stocked("Aashirvaad Atta 5 kg", "PIECE", 26500, "10", null), owner));

        Reply limitByHelper = post("/kirana/khata", Map.of("name", "Mr. Reddy", "creditLimitPaise", 100000), helper);
        assertThat(limitByHelper.body().get("code")).isEqualTo("OWNER_ONLY");
        Reply reddy = post("/kirana/khata", Map.of("name", "Mr. Reddy", "phone", "9876543210", "note", "Tower C 310",
                "creditLimitPaise", 60000), owner);
        assertThat(reddy.status()).as(reddy.body().toString()).isEqualTo(201);
        String reddyId = id(reddy);
        assertThat(post("/kirana/khata", Map.of("name", "Someone else", "phone", "9876543210"), helper).body()
                .get("code")).isEqualTo("PHONE_TAKEN");

        Reply two = post("/kirana/bills", bill("KHATA", reddyId, line(atta, "2")), helper);
        assertThat(two.status()).as(two.body().toString()).isEqualTo(201);
        assertThat(((Map<?, ?>) ((Map<?, ?>) two.body().get("bill")).get("khataCustomer")).get("name"))
                .isEqualTo("Mr. Reddy");
        Reply third = post("/kirana/bills", bill("KHATA", reddyId, line(atta, "1")), helper);
        assertThat(third.status()).isEqualTo(409);
        assertThat(third.body().get("code")).isEqualTo("KHATA_LIMIT");
        assertThat(((Map<?, ?>) third.body().get("details")).get("availablePaise")).isEqualTo(7000);
        assertThat(jdbc.queryForObject("SELECT stock FROM kirana_products", BigDecimal.class))
                .isEqualByComparingTo("8");   // the refused bill took nothing

        Reply tooMuch = post("/kirana/khata/" + reddyId + "/payments", payment(60000, "CASH"), helper);
        assertThat(tooMuch.status()).isEqualTo(409);
        assertThat(tooMuch.body().get("code")).isEqualTo("MORE_THAN_OWED");
        Map<String, Object> pay = payment(20000, "UPI");
        Reply paid = post("/kirana/khata/" + reddyId + "/payments", pay, helper);
        assertThat(paid.status()).as(paid.body().toString()).isEqualTo(201);
        assertThat(((Map<?, ?>) paid.body().get("customer")).get("balancePaise")).isEqualTo(33000);
        assertThat(((Map<?, ?>) paid.body().get("entry")).get("method")).isEqualTo("UPI");
        Reply repeat = post("/kirana/khata/" + reddyId + "/payments", pay, helper);
        assertThat(repeat.status()).isEqualTo(200);
        assertThat(repeat.body().get("repeated")).isEqualTo(true);
        assertThat(((Map<?, ?>) repeat.body().get("customer")).get("balancePaise")).isEqualTo(33000);

        Reply book = get("/kirana/khata?owing=true", owner);
        assertThat(book.body().get("owedPaise")).isEqualTo(33000);
        assertThat(getList(book, "items")).hasSize(1);
        Reply detail = get("/kirana/khata/" + reddyId, owner);
        assertThat(pluck(detail.body().get("entries"), "kind"))
                .containsExactly("PAYMENT", "SALE");
        assertThat(((Map<?, ?>) ((List<?>) detail.body().get("entries")).get(1)).get("billNo")).isEqualTo(1);
        assertThat(((Map<?, ?>) detail.body().get("customer")).get("availablePaise")).isEqualTo(27000);

        Map<?, ?> day = get("/kirana/day", owner).body();
        assertThat(day.get("khataPaise")).isEqualTo(53000);
        assertThat(day.get("khataReceivedPaise")).isEqualTo(20000);
        assertThat(day.get("salesPaise")).isEqualTo(53000);
    }

    @Test
    void cancellingAKhataBillReversesWhatTheyOweAndSomeoneWhoOwesCantLeaveTheBook() {
        String owner = kiranaOwner(OWNER_PHONE);
        String oil = id(post("/kirana/products", stocked("Fortune Oil 1 L", "PIECE", 17500, "10", null), owner));
        String khan = id(post("/kirana/khata", Map.of("name", "Mrs. Khan"), owner));
        String billId = (String) ((Map<?, ?>) post("/kirana/bills", bill("KHATA", khan, line(oil, "2")), owner)
                .body().get("bill")).get("id");
        post("/kirana/khata/" + khan + "/payments", payment(35000, "CASH"), owner);   // paid in full

        Reply leave = patch("/kirana/khata/" + khan, Map.of("version", 0, "active", false), owner);
        assertThat(leave.status()).as(leave.body().toString()).isEqualTo(200);   // owes nothing: may leave
        String reddy = id(post("/kirana/khata", Map.of("name", "Mr. Reddy"), owner));
        post("/kirana/bills", bill("KHATA", reddy, line(oil, "1")), owner);
        Reply owes = patch("/kirana/khata/" + reddy, Map.of("version", 0, "active", false), owner);
        assertThat(owes.status()).isEqualTo(409);
        assertThat(owes.body().get("code")).isEqualTo("STILL_OWES");

        // Mrs. Khan had paid: cancelling her bill means the shop now owes her
        post("/kirana/bills/" + billId + "/void", Map.of("reason", "Returned both"), owner);
        assertThat(jdbc.queryForObject("SELECT balance_paise FROM kirana_khata_customers WHERE id = ?::uuid",
                Long.class, khan)).isEqualTo(-35000L);
        assertThat(jdbc.queryForList("SELECT kind FROM kirana_khata_entries WHERE customer_id = ?::uuid "
                + "ORDER BY created_at, kind", String.class, khan)).containsExactlyInAnyOrder("SALE", "PAYMENT", "VOID");
    }

    // ---------- the answers the counter screens need ----------

    @Test
    void aBillsAnswerCarriesItsUpiQrOrKhataBalanceAndARepeatShowsTheSameStock() {
        String owner = kiranaOwner(OWNER_PHONE);
        put("/kirana/settings/upi-id", Map.of("upiId", "sharmastore@okhdfcbank"), owner);
        String milk = id(post("/kirana/products", stocked("Amul Taaza 500 ml", "PIECE", 2750, "20", null), owner));

        Map<String, Object> body = bill("UPI", null, line(milk, "2"));
        Reply upi = post("/kirana/bills", body, owner);
        Map<?, ?> qr = (Map<?, ?>) upi.body().get("upi");
        assertThat(qr.get("upiId")).isEqualTo("sharmastore@okhdfcbank");
        assertThat(qr.get("amountPaise")).isEqualTo(5500);
        assertThat((String) qr.get("uri")).isEqualTo("upi://pay?pa=sharmastore%40okhdfcbank&pn=Sharma%20General%20Store"
                + "&am=55.00&cu=INR&tn=Bill%201");
        assertThat(upi.body().get("khata")).isNull();

        Reply repeat = post("/kirana/bills", body, owner);
        assertThat(repeat.body().get("repeated")).isEqualTo(true);
        Map<?, ?> level = (Map<?, ?>) ((List<?>) repeat.body().get("stock")).get(0);
        assertThat(num(level.get("before"))).isEqualByComparingTo("20");
        assertThat(num(level.get("after"))).isEqualByComparingTo("18");
        assertThat(repeat.body().get("upi")).isNotNull();
        // Whole numbers go out as plain numbers, never 1.8E+1
        assertThat(level.get("after")).isEqualTo(18);

        String khan = id(post("/kirana/khata", Map.of("name", "Mrs. Khan", "creditLimitPaise", 10000), owner));
        Reply onKhata = post("/kirana/bills", bill("KHATA", khan, line(milk, "1")), owner);
        Map<?, ?> customer = (Map<?, ?>) onKhata.body().get("khata");
        assertThat(customer.get("balancePaise")).isEqualTo(2750);
        assertThat(customer.get("availablePaise")).isEqualTo(7250);
        assertThat(onKhata.body().get("upi")).isNull();

        Reply cashWithCustomer = post("/kirana/bills", bill("CASH", khan, line(milk, "1")), owner);
        assertThat(cashWithCustomer.status()).isEqualTo(400);
        assertThat(keys(cashWithCustomer.body().get("details"))).containsExactly("khataCustomerId");
        Reply strangerOnKhata = post("/kirana/bills", bill("KHATA", UUID.randomUUID().toString(), line(milk, "1")),
                owner);
        assertThat(strangerOnKhata.body().get("code")).isEqualTo("KHATA_CUSTOMER_NOT_FOUND");
    }

    @Test
    void theDaysTotalsFollowTheIndianDayAndShowACancellationOnTheDayItWasMade() {
        String owner = kiranaOwner(OWNER_PHONE);
        String milk = id(post("/kirana/products", stocked("Amul Taaza 500 ml", "PIECE", 2700, "50", null), owner));
        String reddy = id(post("/kirana/khata", Map.of("name", "Mr. Reddy"), owner));
        String lateCash = billId(post("/kirana/bills", bill("CASH", null, line(milk, "1")), owner));
        String earlyUpi = billId(post("/kirana/bills", bill("UPI", null, line(milk, "2")), owner));
        post("/kirana/bills", bill("KHATA", reddy, line(milk, "4")), owner);
        post("/kirana/khata/" + reddy + "/payments", payment(5000, "CASH"), owner);
        post("/kirana/khata/" + reddy + "/payments", payment(3000, "UPI"), owner);
        // 23:59 and 00:01 India time either side of a midnight a month ago
        java.time.ZoneId india = java.time.ZoneId.of("Asia/Kolkata");
        java.time.LocalDate tenthDay = java.time.LocalDate.now(india).minusDays(30);
        java.time.LocalDate eleventhDay = tenthDay.plusDays(1);
        jdbc.update("UPDATE kirana_bills SET created_at = ? WHERE id = ?::uuid",
                java.sql.Timestamp.from(tenthDay.atTime(23, 59).atZone(india).toInstant()), lateCash);
        jdbc.update("UPDATE kirana_bills SET created_at = ? WHERE id = ?::uuid",
                java.sql.Timestamp.from(eleventhDay.atTime(0, 1).atZone(india).toInstant()), earlyUpi);

        Map<?, ?> tenth = get("/kirana/day?date=" + tenthDay, owner).body();
        assertThat(tenth.get("bills")).isEqualTo(1);
        assertThat(tenth.get("cashPaise")).isEqualTo(2700);
        assertThat(tenth.get("upiPaise")).isEqualTo(0);
        Map<?, ?> eleventh = get("/kirana/day?date=" + eleventhDay, owner).body();
        assertThat(eleventh.get("bills")).isEqualTo(1);
        assertThat(eleventh.get("upiPaise")).isEqualTo(5400);
        assertThat(eleventh.get("cashPaise")).isEqualTo(0);

        Map<?, ?> today = get("/kirana/day", owner).body();
        assertThat(today.get("khataPaise")).isEqualTo(10800);
        assertThat(today.get("khataReceivedCashPaise")).isEqualTo(5000);
        assertThat(today.get("khataReceivedUpiPaise")).isEqualTo(3000);
        assertThat(today.get("cashInPaise")).isEqualTo(5000);

        // That day's cash bill cancelled today: that day's figures stay as counted; today hands the cash back
        post("/kirana/bills/" + lateCash + "/void", Map.of("reason", "Wrong entry"), owner);
        Map<?, ?> tenthAgain = get("/kirana/day?date=" + tenthDay, owner).body();
        assertThat(tenthAgain.get("bills")).isEqualTo(1);
        assertThat(tenthAgain.get("cashInPaise")).isEqualTo(2700);
        Map<?, ?> afterCancel = get("/kirana/day", owner).body();
        assertThat(afterCancel.get("cancelledBills")).isEqualTo(1);
        assertThat(afterCancel.get("cancelledPaise")).isEqualTo(2700);
        assertThat(afterCancel.get("cashInPaise")).isEqualTo(5000 - 2700);
        Reply farAway = get("/kirana/day?date=1999-01-01", owner);
        assertThat(farAway.status()).isEqualTo(400);
        assertThat(keys(farAway.body().get("details"))).containsExactly("date");
    }

    @Test
    void theSamePaymentSentEightTimesAtOnceIsRecordedOnce() throws Exception {
        String owner = kiranaOwner(OWNER_PHONE);
        String atta = id(post("/kirana/products", stocked("Aashirvaad Atta 5 kg", "PIECE", 26500, "10", null), owner));
        String reddy = id(post("/kirana/khata", Map.of("name", "Mr. Reddy"), owner));
        post("/kirana/bills", bill("KHATA", reddy, line(atta, "2")), owner);
        Map<String, Object> pay = payment(20000, "CASH");
        List<Reply> replies = together(8, () -> post("/kirana/khata/" + reddy + "/payments", pay, owner));
        assertThat(replies.stream().filter(r -> r.status() == 201)).hasSize(1);
        assertThat(replies).extracting(Reply::status).containsOnly(200, 201);
        assertThat(jdbc.queryForObject("SELECT balance_paise FROM kirana_khata_customers WHERE id = ?::uuid",
                Long.class, reddy)).isEqualTo(33000L);
    }

    @Test
    void phonesTypedAsPeopleDoAreKeptAsTenDigitsAndTheBookIsSearchedByNameNoteOrPhone() {
        String owner = kiranaOwner(OWNER_PHONE);
        Reply khan = post("/kirana/khata", Map.of("name", "Mrs. Khan", "phone", "+91 98765-43210",
                "note", "Tower B 1204"), owner);
        assertThat(khan.status()).as(khan.body().toString()).isEqualTo(201);
        assertThat(khan.body().get("phone")).isEqualTo("9876543210");
        assertThat(post("/kirana/khata", Map.of("name", "Someone", "phone", "09876543210"), owner).body().get("code"))
                .isEqualTo("PHONE_TAKEN");
        Reply bad = post("/kirana/khata", Map.of("name", "Someone", "phone", "12345"), owner);
        assertThat(bad.status()).isEqualTo(400);
        assertThat(keys(bad.body().get("details"))).containsExactly("phone");
        post("/kirana/khata", Map.of("name", "Mr. Reddy"), owner);

        assertThat(pluck(getList(search("/kirana/khata", "1204", owner), "items"), "name")).containsExactly("Mrs. Khan");
        assertThat(pluck(getList(search("/kirana/khata", "43210", owner), "items"), "name")).containsExactly("Mrs. Khan");
        assertThat(pluck(getList(search("/kirana/khata", "khan", owner), "items"), "name")).containsExactly("Mrs. Khan");
        Reply removed = patch("/kirana/khata/" + id(khan), Map.of("version", 0, "phone", ""), owner);
        assertThat(removed.body().get("phone")).isNull();
    }

    @Test
    void aVerifiedKiranasUpiIdChangesOnlyWithTheEditCode() {
        String owner = kiranaOwner(OWNER_PHONE);
        put("/kirana/settings/upi-id", Map.of("upiId", "sharmastore@okhdfcbank"), owner);
        jdbc.update("UPDATE shops SET status = 'APPROVED', ever_approved = true");
        Reply locked = put("/kirana/settings/upi-id", Map.of("upiId", "someoneelse@ybl"), owner);
        assertThat(locked.status()).isEqualTo(403);
        assertThat(locked.body().get("code")).isEqualTo("EDIT_LOCKED");
        // The same ID again changes nothing, so it needs no code
        assertThat(put("/kirana/settings/upi-id", Map.of("upiId", "sharmastore@okhdfcbank"), owner).status())
                .isEqualTo(200);
        unlockEditing(owner);
        Reply changed = put("/kirana/settings/upi-id", Map.of("upiId", "sharma.new@ybl"), owner);
        assertThat(changed.status()).as(changed.body().toString()).isEqualTo(200);
        assertThat(changed.body().get("upiId")).isEqualTo("sharma.new@ybl");
    }

    @Test
    void handingTheShopOverForgetsTheOldOwnersUpiIdAndKeepsEverythingElse() {
        String owner = kiranaOwner(OWNER_PHONE);
        put("/kirana/settings/upi-id", Map.of("upiId", "sharmastore@okhdfcbank"), owner);
        post("/kirana/products", stocked("Tata Salt 1 kg", "PIECE", 2800, "5", null), owner);
        String newOwner = token(OTHER_OWNER_PHONE);
        Reply asked = post("/shops/me/handover", Map.of("phone", OTHER_OWNER_PHONE, "version", version(owner)), owner);
        assertThat(asked.status()).as(asked.body().toString()).isEqualTo(202);
        Reply waiting = get("/me/handover", newOwner);
        String handoverId = (String) ((Map<?, ?>) waiting.list().get(0)).get("id");
        Reply accepted = post("/me/handover/" + handoverId + "/accept", Map.of(), newOwner);
        assertThat(accepted.status()).as(accepted.body().toString()).isEqualTo(200);

        // The counter reads the membership from the database on every call: the same sign-in works at once
        assertThat(get("/kirana/settings", newOwner).body().get("upiId")).isNull();
        assertThat(getList(get("/kirana/products", newOwner), "items")).hasSize(1);
    }

    @Test
    void cancellingAPaidBillOfSomeoneTakenOutOfTheBookBringsThemBack() {
        String owner = kiranaOwner(OWNER_PHONE);
        String oil = id(post("/kirana/products", stocked("Fortune Oil 1 L", "PIECE", 17500, "10", null), owner));
        String khan = id(post("/kirana/khata", Map.of("name", "Mrs. Khan", "phone", "9876543210"), owner));
        String billId = billId(post("/kirana/bills", bill("KHATA", khan, line(oil, "1")), owner));
        post("/kirana/khata/" + khan + "/payments", payment(17500, "CASH"), owner);
        patch("/kirana/khata/" + khan, Map.of("version", 0, "active", false), owner);
        post("/kirana/khata", Map.of("name", "New Khan", "phone", "9876543210"), owner);   // her number, now someone else's

        Reply cancelled = post("/kirana/bills/" + billId + "/void", Map.of("reason", "Returned"), owner);
        Map<?, ?> back = (Map<?, ?>) cancelled.body().get("khata");
        assertThat(back.get("balancePaise")).isEqualTo(-17500);
        assertThat(back.get("active")).isEqualTo(true);
        assertThat(back.get("phone")).isNull();
        assertThat(pluck(getList(get("/kirana/khata", owner), "items"), "name")).contains("Mrs. Khan");
    }

    @Test
    void theBillListComesNewestFirstAPageAtATimeWithThreeNames() {
        String owner = kiranaOwner(OWNER_PHONE);
        String a = id(post("/kirana/products", stocked("A one", "PIECE", 100, "999", null), owner));
        String b = id(post("/kirana/products", stocked("B two", "PIECE", 100, "999", null), owner));
        String c = id(post("/kirana/products", stocked("C three", "PIECE", 100, "999", null), owner));
        String d = id(post("/kirana/products", stocked("D four", "PIECE", 100, "999", null), owner));
        for (int i = 0; i < 50; i++) {
            post("/kirana/bills", bill("CASH", null, line(a, "1")), owner);
        }
        post("/kirana/bills", bill("CASH", null, line(a, "1"), line(b, "1"), line(c, "1"), line(d, "1")), owner);
        Reply first = get("/kirana/bills", owner);
        List<?> items = getList(first, "items");
        assertThat(items).hasSize(50);
        Map<?, ?> newest = (Map<?, ?>) items.get(0);
        assertThat(newest.get("billNo")).isEqualTo(51);
        assertThat(newest.get("lines")).isEqualTo(4);
        assertThat(newest.get("preview")).isEqualTo("A one, B two, C three");
        Reply second = get("/kirana/bills?cursor=" + first.body().get("next"), owner);
        assertThat(pluck(getList(second, "items"), "billNo")).containsExactly(1);
        assertThat(second.body().get("next")).isNull();
    }

    // ---------- several barcodes, packs ----------

    @Test
    void aProductHasSeveralBarcodesAndACartonsScanCountsItsPieces() {
        String owner = kiranaOwner(OWNER_PHONE);
        String helper = helperOf(owner, HELPER_PHONE);
        String maggi = id(post("/kirana/products", product("Maggi 70 g", "8901058000017", "PIECE", 1400), owner));

        Reply carton = post("/kirana/products/" + maggi + "/barcodes", Map.of("code", "8901058900010", "pack", 12),
                helper);
        assertThat(carton.status()).as(carton.body().toString()).isEqualTo(200);
        assertThat(pluck(carton.body().get("barcodes"), "code")).containsExactly("8901058000017", "8901058900010");
        assertThat(pluck(carton.body().get("barcodes"), "pack")).containsExactly(1, 12);
        Reply scan = get("/kirana/products/barcode/8901058900010", helper);
        assertThat(((Map<?, ?>) scan.body().get("product")).get("id")).isEqualTo(maggi);
        assertThat(scan.body().get("pack")).isEqualTo(12);
        // The digits of either barcode find it
        assertThat(pluck(getList(search("/kirana/products", "89010589", helper), "items"), "id"))
                .containsExactly(maggi);

        Reply half = post("/kirana/products/" + maggi + "/barcodes", Map.of("code", "8901058900027",
                "pack", new BigDecimal("1.5")), helper);
        assertThat(half.status()).isEqualTo(400);
        assertThat(keys(half.body().get("details"))).containsExactly("pack");
        assertThat(post("/kirana/products/" + maggi + "/barcodes", Map.of("code", "8901058900010", "pack", 12), helper)
                .status()).isEqualTo(200);   // linked again as it is: nothing changes
        Reply otherPack = post("/kirana/products/" + maggi + "/barcodes", Map.of("code", "8901058900010", "pack", 6),
                helper);
        assertThat(otherPack.body().get("code")).isEqualTo("BARCODE_TAKEN");   // the helper asks the owner
        Reply corrected = post("/kirana/products/" + maggi + "/barcodes", Map.of("code", "8901058900010", "pack", 24),
                owner);
        assertThat(pluck(corrected.body().get("barcodes"), "pack")).containsExactly(1, 24);
        assertThat(keys(post("/kirana/products/" + maggi + "/barcodes", Map.of("code", "AB"), helper).body()
                .get("details"))).containsExactly("code");
        String noodles = id(post("/kirana/products", product("Yippee 70 g", null, "PIECE", 1300), owner));
        Reply taken = post("/kirana/products/" + noodles + "/barcodes", Map.of("code", "8901058900010"), helper);
        assertThat(taken.status()).isEqualTo(409);
        assertThat(((Map<?, ?>) taken.body().get("details")).get("productId")).isEqualTo(maggi);

        Reply byHelper = delete("/kirana/products/" + maggi + "/barcodes/8901058900010", helper);
        assertThat(byHelper.body().get("code")).isEqualTo("OWNER_ONLY");
        Reply unlinked = delete("/kirana/products/" + maggi + "/barcodes/8901058900010", owner);
        assertThat(pluck(unlinked.body().get("barcodes"), "code")).containsExactly("8901058000017");
        assertThat(delete("/kirana/products/" + maggi + "/barcodes/8901058900010", owner).body().get("code"))
                .isEqualTo("BARCODE_NOT_FOUND");

        Map<String, Object> packWithoutCode = product("Toor Dal", null, "KG", 16000);
        packWithoutCode.put("pack", 5);
        assertThat(keys(post("/kirana/products", packWithoutCode, owner).body().get("details"))).containsExactly("pack");
        Map<String, Object> bag = product("Sona Masoori Rice", "8906001234567", "KG", 6000);
        bag.put("pack", 25);   // a 25 kg bag of rice sold by the kg
        Reply rice = post("/kirana/products", bag, owner);
        assertThat(rice.status()).as(rice.body().toString()).isEqualTo(201);
        assertThat(pluck(rice.body().get("barcodes"), "pack")).containsExactly(25);
    }

    // ---------- loose by amount, items not in the list, discounts ----------

    @Test
    void aBillTakesLooseItemsByAmountItemsNotInTheListAndADiscount() {
        String owner = kiranaOwner(OWNER_PHONE);
        String helper = helperOf(owner, HELPER_PHONE);
        String jeera = id(post("/kirana/products", stocked("Jeera (loose)", "KG", 40000, "2", null), owner));
        String maggi = id(post("/kirana/products", stocked("Maggi 70 g", "PIECE", 1400, "50", null), owner));

        Reply r = post("/kirana/bills", bill("CASH", null, amountLine(jeera, 2000), itemLine("Agarbatti", 3000),
                line(maggi, "2")), helper);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        Map<?, ?> b = (Map<?, ?>) r.body().get("bill");
        List<?> lines = (List<?>) b.get("lines");
        assertThat(num(((Map<?, ?>) lines.get(0)).get("quantity"))).isEqualByComparingTo("0.05");   // ₹20 at ₹400/kg
        assertThat(((Map<?, ?>) lines.get(0)).get("amountPaise")).isEqualTo(2000);
        assertThat(((Map<?, ?>) lines.get(1)).get("productId")).isNull();
        assertThat(((Map<?, ?>) lines.get(1)).get("name")).isEqualTo("Agarbatti");
        assertThat(b.get("totalPaise")).isEqualTo(2000 + 3000 + 2800);
        assertThat((List<?>) r.body().get("stock")).hasSize(2);   // the item not in the list has no stock
        assertThat(jdbc.queryForObject("SELECT stock FROM kirana_products WHERE id = ?::uuid", BigDecimal.class, jeera))
                .isEqualByComparingTo("1.95");

        assertThat(keys(post("/kirana/bills", bill("CASH", null, amountLine(maggi, 1400)), owner).body()
                .get("details"))).containsExactly("lines[0].amountPaise");   // a piece isn't sold by amount
        Map<String, Object> both = new HashMap<>(line(jeera, "1"));
        both.put("amountPaise", 2000);
        assertThat(keys(post("/kirana/bills", bill("CASH", null, both), owner).body().get("details")))
                .containsExactly("lines[0].quantity");
        assertThat(keys(post("/kirana/bills", bill("CASH", null, Map.of("name", "Agarbatti")), owner).body()
                .get("details"))).containsExactly("lines[0].amountPaise");
        assertThat(keys(post("/kirana/bills", bill("CASH", null, amountLine(jeera, 1)), owner).body()
                .get("details"))).containsExactly("lines[0].amountPaise");   // less than a gram
        // Three packets of an item not in the list: one line of 3, so one can come back later
        Map<String, Object> three = new HashMap<>(itemLine("Agarbatti", 1000));
        three.put("quantity", 3);
        Map<?, ?> packets = (Map<?, ?>) ((List<?>) ((Map<?, ?>) post("/kirana/bills", bill("CASH", null, three), owner)
                .body().get("bill")).get("lines")).get(0);
        assertThat(packets.get("amountPaise")).isEqualTo(3000);
        assertThat(packets.get("quantity")).isEqualTo(3);
        // A listed product typed as an item not in the list (its price and stock would be skipped)
        Reply listed = post("/kirana/bills", bill("CASH", null, itemLine("maggi 70 G", 100)), helper);
        assertThat(listed.status()).isEqualTo(409);
        assertThat(listed.body().get("code")).isEqualTo("ITEM_IN_LIST");
        assertThat(((Map<?, ?>) listed.body().get("details")).get("productId")).isEqualTo(maggi);

        // A helper rounds off up to ₹10; more is the owner's, and never more than the bill
        Map<String, Object> roundOff = bill("CASH", null, line(maggi, "1"), itemLine("Bag", 1000));
        roundOff.put("discountPaise", 1000);
        Reply rounded = post("/kirana/bills", roundOff, helper);
        assertThat(((Map<?, ?>) rounded.body().get("bill")).get("totalPaise")).isEqualTo(1400);
        assertThat(((Map<?, ?>) rounded.body().get("bill")).get("subtotalPaise")).isEqualTo(2400);
        Map<String, Object> bigger = bill("CASH", null, line(maggi, "1"), itemLine("Bag", 1000));
        bigger.put("discountPaise", 1001);
        Reply refused = post("/kirana/bills", bigger, helper);
        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.body().get("code")).isEqualTo("OWNER_ONLY");
        assertThat(post("/kirana/bills", bigger, owner).status()).isEqualTo(201);
        Map<String, Object> tooMuch = bill("CASH", null, line(maggi, "1"));
        tooMuch.put("discountPaise", 1500);
        assertThat(keys(post("/kirana/bills", tooMuch, owner).body().get("details"))).containsExactly("discountPaise");
    }

    // ---------- part paid now, the rest on khata ----------

    @Test
    void aKhataBillCanBePartPaidNowAndOnlyTheRestGoesOnTheKhata() {
        String owner = kiranaOwner(OWNER_PHONE);
        put("/kirana/settings/upi-id", Map.of("upiId", "sharmastore@okhdfcbank"), owner);
        String oil = id(post("/kirana/products", stocked("Fortune Oil 1 L", "PIECE", 17500, "10", null), owner));
        String khan = id(post("/kirana/khata", Map.of("name", "Mrs. Khan"), owner));

        Map<String, Object> part = bill("KHATA", khan, line(oil, "2"));
        part.put("paidNowPaise", 10000);
        part.put("paidNowBy", "UPI");
        Reply r = post("/kirana/bills", part, owner);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        Map<?, ?> b = (Map<?, ?>) r.body().get("bill");
        assertThat(b.get("paidNowPaise")).isEqualTo(10000);
        assertThat(b.get("onKhataPaise")).isEqualTo(25000);
        assertThat(((Map<?, ?>) r.body().get("khata")).get("balancePaise")).isEqualTo(25000);
        assertThat(((Map<?, ?>) r.body().get("upi")).get("amountPaise")).isEqualTo(10000);   // the QR for what's paid now

        Map<String, Object> all = bill("KHATA", khan, line(oil, "1"));
        all.put("paidNowPaise", 17500);
        all.put("paidNowBy", "CASH");
        assertThat(keys(post("/kirana/bills", all, owner).body().get("details"))).containsExactly("paidNowPaise");
        Map<String, Object> cash = bill("CASH", null, line(oil, "1"));
        cash.put("paidNowPaise", 500);
        cash.put("paidNowBy", "CASH");
        assertThat(keys(post("/kirana/bills", cash, owner).body().get("details"))).containsExactly("paidNowPaise");
        Map<String, Object> how = bill("KHATA", khan, line(oil, "1"));
        how.put("paidNowPaise", 500);
        assertThat(keys(post("/kirana/bills", how, owner).body().get("details"))).containsExactly("paidNowBy");

        Map<?, ?> day = get("/kirana/day", owner).body();
        assertThat(day.get("salesPaise")).isEqualTo(35000);
        assertThat(day.get("upiPaise")).isEqualTo(10000);
        assertThat(day.get("khataPaise")).isEqualTo(25000);
    }

    // ---------- changing how a bill was paid ----------

    @Test
    void aBillsPaymentIsChangedByTheOwnerOrByItsHelperWithinTenMinutes() {
        String owner = kiranaOwner(OWNER_PHONE);
        String helper = helperOf(owner, HELPER_PHONE);
        String milk = id(post("/kirana/products", stocked("Amul Taaza 500 ml", "PIECE", 2700, "50", null), owner));
        String khan = id(post("/kirana/khata", Map.of("name", "Mrs. Khan"), owner));
        String reddy = id(post("/kirana/khata", Map.of("name", "Mr. Reddy", "creditLimitPaise", 3000), owner));

        // UPI failed, they paid cash: the helper fixes his own bill on the spot
        String mine = billId(post("/kirana/bills", bill("UPI", null, line(milk, "2")), helper));
        Reply toCash = changePay(mine, Map.of("payment", "CASH"), helper);
        assertThat(toCash.status()).as(toCash.body().toString()).isEqualTo(200);
        Map<?, ?> changed = (Map<?, ?>) toCash.body().get("bill");
        assertThat(changed.get("payment")).isEqualTo("CASH");
        assertThat(changed.get("billNo")).isEqualTo(1);
        assertThat(pluck(changed.get("changes"), "fromPayment")).containsExactly("UPI");
        assertThat(post("/kirana/bills/" + mine + "/payment", Map.of("version", 0, "payment", "CASH"), helper).status())
                .isEqualTo(200);   // the same again (a retry with the old version) changes nothing
        Reply stale = post("/kirana/bills/" + mine + "/payment", Map.of("version", 0, "payment", "UPI"), helper);
        assertThat(stale.body().get("code")).isEqualTo("BILL_CHANGED");
        assertThat(pluck(get("/kirana/bills/" + mine, owner).body().get("changes"), "toPayment")).containsExactly("CASH");

        String owners = billId(post("/kirana/bills", bill("CASH", null, line(milk, "1")), owner));
        assertThat(changePay(owners, Map.of("payment", "UPI"), helper).body().get("code")).isEqualTo("OWNER_ONLY");
        jdbc.update("UPDATE kirana_bills SET created_at = created_at - interval '11 minutes' WHERE id = ?::uuid", mine);
        Reply late = changePay(mine, Map.of("payment", "UPI"), helper);
        assertThat(late.status()).isEqualTo(403);
        assertThat(late.body().get("code")).isEqualTo("OWNER_ONLY");

        // The owner puts it on Mrs. Khan's khata, then on Mr. Reddy's with part paid now
        Reply onKhata = changePay(mine, Map.of("payment", "KHATA", "khataCustomerId", khan), owner);
        assertThat(onKhata.status()).as(onKhata.body().toString()).isEqualTo(200);
        assertThat(pluck(onKhata.body().get("khata"), "balancePaise")).containsExactly(5400);
        Reply overLimit = changePay(mine, Map.of("payment", "KHATA", "khataCustomerId", reddy), owner);
        assertThat(overLimit.status()).isEqualTo(409);
        assertThat(overLimit.body().get("code")).isEqualTo("KHATA_LIMIT");
        assertThat(balance(khan)).isEqualTo(5400L);   // refused: nothing moved
        assertThat(balance(reddy)).isZero();
        Reply moved = changePay(mine, Map.of("payment", "KHATA", "khataCustomerId", reddy,
                "paidNowPaise", 2400, "paidNowBy", "CASH"), owner);
        assertThat(moved.status()).as(moved.body().toString()).isEqualTo(200);
        assertThat(balance(khan)).isZero();
        assertThat(balance(reddy)).isEqualTo(3000L);
        assertThat(jdbc.queryForList("SELECT kind FROM kirana_khata_entries WHERE customer_id = ?::uuid "
                + "ORDER BY created_at", String.class, khan)).containsExactly("CHANGE", "CHANGE");

        // Its day stays the day it was made (moved back to now, so the check doesn't straddle midnight)
        jdbc.update("UPDATE kirana_bills SET created_at = now() WHERE id = ?::uuid", mine);

        // Same customer, more paid now: owes less, so the limit isn't checked again
        Reply less = changePay(mine, Map.of("payment", "KHATA", "khataCustomerId", reddy,
                "paidNowPaise", 4000, "paidNowBy", "CASH"), owner);
        assertThat(less.status()).as(less.body().toString()).isEqualTo(200);
        assertThat(balance(reddy)).isEqualTo(1400L);

        // The bill counts as it was paid when made (UPI); each change on the day it was made
        Map<?, ?> day = get("/kirana/day", owner).body();
        assertThat(day.get("upiPaise")).isEqualTo(5400);
        assertThat(day.get("cashPaise")).isEqualTo(2700);
        assertThat(day.get("paymentChanges")).isEqualTo(4);
        assertThat(day.get("upiInPaise")).isEqualTo(0);
        assertThat(day.get("cashInPaise")).isEqualTo(2700 + 4000);
    }

    @Test
    void aPaymentChangedOrABillCancelledTodayLeavesYesterdaysFiguresAsTheyWereCounted() {
        String owner = kiranaOwner(OWNER_PHONE);
        String milk = id(post("/kirana/products", stocked("Amul Taaza 500 ml", "PIECE", 2700, "50", null), owner));
        String upiBill = billId(post("/kirana/bills", bill("UPI", null, line(milk, "2")), owner));
        String cashBill = billId(post("/kirana/bills", bill("CASH", null, line(milk, "1")), owner));
        java.time.LocalDate yesterday = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).minusDays(1);
        jdbc.update("UPDATE kirana_bills SET created_at = created_at - interval '1 day'");

        assertThat(changePay(upiBill, Map.of("payment", "CASH"), owner).status()).isEqualTo(200);
        post("/kirana/bills/" + cashBill + "/void", Map.of("reason", "Wrong entry"), owner);

        Map<?, ?> before = get("/kirana/day?date=" + yesterday, owner).body();
        assertThat(before.get("upiInPaise")).isEqualTo(5400);
        assertThat(before.get("cashInPaise")).isEqualTo(2700);
        Map<?, ?> today = get("/kirana/day", owner).body();
        assertThat(today.get("bills")).isEqualTo(0);
        assertThat(today.get("changedCashPaise")).isEqualTo(5400);
        assertThat(today.get("changedUpiPaise")).isEqualTo(-5400);
        assertThat(today.get("cancelledCashPaise")).isEqualTo(2700);
        assertThat(today.get("cashInPaise")).isEqualTo(5400 - 2700);
        assertThat(today.get("upiInPaise")).isEqualTo(-5400);
    }

    @Test
    void takingAPaidKhataBillOffTheKhataAsksBeforeLeavingAnAdvance() {
        String owner = kiranaOwner(OWNER_PHONE);
        String milk = id(post("/kirana/products", stocked("Amul Taaza 500 ml", "PIECE", 3000, "50", null), owner));
        String khan = id(post("/kirana/khata", Map.of("name", "Mrs. Khan"), owner));
        String billId = billId(post("/kirana/bills", bill("KHATA", khan, line(milk, "1")), owner));
        post("/kirana/khata/" + khan + "/payments", payment(3000, "CASH"), owner);

        Reply asked = changePay(billId, Map.of("payment", "CASH"), owner);
        assertThat(asked.status()).isEqualTo(409);
        assertThat(asked.body().get("code")).isEqualTo("KHATA_ALREADY_PAID");
        assertThat(((Map<?, ?>) asked.body().get("details")).get("advanceAfterPaise")).isEqualTo(3000);
        assertThat(balance(khan)).isZero();
        Reply kept = changePay(billId, Map.of("payment", "CASH", "advance", true), owner);
        assertThat(kept.status()).as(kept.body().toString()).isEqualTo(200);
        assertThat(balance(khan)).isEqualTo(-3000L);
    }

    // ---------- returns ----------

    @Test
    void itemsComeBackPartlyOrAllWithTheirShareOfTheDiscountAndTheStock() {
        String owner = kiranaOwner(OWNER_PHONE);
        String helper = helperOf(owner, HELPER_PHONE);
        String oil = id(post("/kirana/products", stocked("Fortune Oil 1 L", "PIECE", 17500, "10", null), owner));
        String rice = id(post("/kirana/products", stocked("Sona Masoori Rice", "KG", 6000, "10", null), owner));
        Map<String, Object> b = bill("CASH", null, line(oil, "2"), line(rice, "1.5"), itemLine("Agarbatti", 2000));
        b.put("discountPaise", 1000);
        String billId = billId(post("/kirana/bills", b, owner));   // 35000 + 9000 + 2000 - 1000 = 45000

        assertThat(post("/kirana/bills/" + billId + "/returns", ret(line(1, "1")), helper).body().get("code"))
                .isEqualTo("OWNER_ONLY");   // the owner's bill
        Map<String, Object> one = ret(line(1, "1"));
        Reply first = post("/kirana/bills/" + billId + "/returns", one, owner);
        assertThat(first.status()).as(first.body().toString()).isEqualTo(201);
        assertThat(((Map<?, ?>) first.body().get("returned")).get("refundPaise")).isEqualTo(17120);   // 17500 × 45000/46000
        assertThat(((Map<?, ?>) first.body().get("returned")).get("handBackBy")).isEqualTo("CASH");
        assertThat(((Map<?, ?>) first.body().get("returned")).get("handBackPaise")).isEqualTo(17120);
        assertThat(stockOf(oil)).isEqualByComparingTo("9");
        Reply again = post("/kirana/bills/" + billId + "/returns", one, owner);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().get("repeated")).isEqualTo(true);
        assertThat(stockOf(oil)).isEqualByComparingTo("9");

        assertThat(keys(post("/kirana/bills/" + billId + "/returns", ret(line(1, "2")), owner).body().get("details")))
                .containsExactly("lines[0].quantity");   // only 1 left
        assertThat(keys(post("/kirana/bills/" + billId + "/returns", ret(line(9, "1")), owner).body().get("details")))
                .containsExactly("lines[0].lineNo");
        assertThat(keys(post("/kirana/bills/" + billId + "/returns", ret(line(1, "1"), line(1, "1")), owner).body()
                .get("details"))).containsExactly("lines[1].lineNo");
        assertThat(post("/kirana/bills/" + billId + "/void", Map.of("reason", "Wrong bill"), owner).body().get("code"))
                .isEqualTo("BILL_HAS_RETURNS");
        assertThat(changePay(billId, Map.of("payment", "UPI"), owner).body().get("code")).isEqualTo("BILL_HAS_RETURNS");

        // The rest: exactly what is left of the total comes back
        Reply rest = post("/kirana/bills/" + billId + "/returns", ret(line(1, "1"), line(2, "1.5"), line(3, "1")),
                owner);
        assertThat(rest.status()).as(rest.body().toString()).isEqualTo(201);
        assertThat(((Map<?, ?>) rest.body().get("returned")).get("refundPaise")).isEqualTo(45000 - 17120);
        Map<?, ?> after = (Map<?, ?>) rest.body().get("bill");
        assertThat(after.get("refundedPaise")).isEqualTo(45000);
        assertThat(pluck(after.get("returns"), "refundPaise")).containsExactly(17120, 27880);
        assertThat(stockOf(oil)).isEqualByComparingTo("10");
        assertThat(stockOf(rice)).isEqualByComparingTo("10");
        assertThat(keys(post("/kirana/bills/" + billId + "/returns", ret(line(3, "1")), owner).body().get("details")))
                .containsExactly("lines[0].quantity");   // all of it came back already

        Map<?, ?> day = get("/kirana/day", owner).body();
        assertThat(day.get("salesPaise")).isEqualTo(45000);
        assertThat(day.get("returns")).isEqualTo(2);
        assertThat(day.get("returnedCashPaise")).isEqualTo(45000);
        assertThat(day.get("cashInPaise")).isEqualTo(0);
    }

    @Test
    void aReturnOnAPartPaidKhataBillComesOffTheKhataFirstAndTheRestIsHandedBack() {
        String owner = kiranaOwner(OWNER_PHONE);
        String oil = id(post("/kirana/products", stocked("Fortune Oil 1 L", "PIECE", 17500, "10", null), owner));
        String reddy = id(post("/kirana/khata", Map.of("name", "Mr. Reddy"), owner));
        Map<String, Object> b = bill("KHATA", reddy, line(oil, "1"));
        b.put("paidNowPaise", 5000);
        b.put("paidNowBy", "CASH");
        String billId = billId(post("/kirana/bills", b, owner));
        assertThat(balance(reddy)).isEqualTo(12500L);

        // Off his khata first (₹125), the ₹50 he paid in cash handed back: no advance he didn't ask for
        Reply back = post("/kirana/bills/" + billId + "/returns", ret(line(1, "1")), owner);
        assertThat(back.status()).as(back.body().toString()).isEqualTo(201);
        Map<?, ?> returned = (Map<?, ?>) back.body().get("returned");
        assertThat(returned.get("offKhataPaise")).isEqualTo(12500);
        assertThat(returned.get("handBackPaise")).isEqualTo(5000);
        assertThat(returned.get("handBackBy")).isEqualTo("CASH");
        assertThat(((Map<?, ?>) back.body().get("khata")).get("balancePaise")).isEqualTo(0);
        Map<?, ?> day = get("/kirana/day", owner).body();
        assertThat(day.get("returnedKhataPaise")).isEqualTo(12500);
        assertThat(day.get("returnedCashPaise")).isEqualTo(5000);
        assertThat(day.get("cashInPaise")).isEqualTo(0);   // ₹50 taken, ₹50 handed back
    }

    @Test
    void theSameReturnSentEightTimesAtOnceIsMadeOnce() throws Exception {
        String owner = kiranaOwner(OWNER_PHONE);
        String oil = id(post("/kirana/products", stocked("Fortune Oil 1 L", "PIECE", 17500, "10", null), owner));
        String billId = billId(post("/kirana/bills", bill("CASH", null, line(oil, "4")), owner));
        Map<String, Object> one = ret(line(1, "1"));
        List<Reply> replies = together(8, () -> post("/kirana/bills/" + billId + "/returns", one, owner));
        assertThat(replies.stream().filter(r -> r.status() == 201)).hasSize(1);
        assertThat(replies).extracting(Reply::status).containsOnly(200, 201);
        assertThat(stockOf(oil)).isEqualByComparingTo("7");
        assertThat(jdbc.queryForObject("SELECT refunded_paise FROM kirana_bills", Long.class)).isEqualTo(17500L);
    }

    // ---------- khata: default limit, advances, undoing a payment ----------

    @Test
    void aNewCustomerStartsWithATwoThousandLimitUnlessTheOwnerSaysOtherwise() {
        String owner = kiranaOwner(OWNER_PHONE);
        String helper = helperOf(owner, HELPER_PHONE);
        Reply byHelper = post("/kirana/khata", Map.of("name", "Mr. Reddy"), helper);
        assertThat(byHelper.body().get("creditLimitPaise")).isEqualTo(200000);
        assertThat(post("/kirana/khata", Map.of("name", "Mrs. Khan", "noLimit", true), helper).body().get("code"))
                .isEqualTo("OWNER_ONLY");
        Reply none = post("/kirana/khata", Map.of("name", "Mrs. Khan", "noLimit", true), owner);
        assertThat(none.status()).as(none.body().toString()).isEqualTo(201);
        assertThat(none.body().get("creditLimitPaise")).isNull();
    }

    @Test
    void moreThanOwedIsKeptAsAnAdvanceOnlyWhenAskedAndTheOwnerUndoesAWrongPayment() {
        String owner = kiranaOwner(OWNER_PHONE);
        String helper = helperOf(owner, HELPER_PHONE);
        String oil = id(post("/kirana/products", stocked("Fortune Oil 1 L", "PIECE", 17500, "10", null), owner));
        String reddy = id(post("/kirana/khata", Map.of("name", "Mr. Reddy"), owner));
        post("/kirana/bills", bill("KHATA", reddy, line(oil, "1")), owner);

        Reply ask = post("/kirana/khata/" + reddy + "/payments", payment(20000, "CASH"), helper);
        assertThat(ask.status()).isEqualTo(409);
        assertThat(ask.body().get("code")).isEqualTo("MORE_THAN_OWED");
        assertThat(((Map<?, ?>) ask.body().get("details")).get("advancePaise")).isEqualTo(2500);
        Map<String, Object> advance = payment(20000, "CASH");
        advance.put("advance", true);
        Reply kept = post("/kirana/khata/" + reddy + "/payments", advance, helper);
        assertThat(kept.status()).as(kept.body().toString()).isEqualTo(201);
        assertThat(((Map<?, ?>) kept.body().get("customer")).get("balancePaise")).isEqualTo(-2500);
        post("/kirana/bills", bill("KHATA", reddy, line(oil, "1")), owner);
        assertThat(balance(reddy)).isEqualTo(15000L);   // the advance used up

        // Recorded on him by mistake (by the owner): only the owner undoes it
        Reply wrong = post("/kirana/khata/" + reddy + "/payments", payment(15000, "UPI"), owner);
        String wrongId = (String) ((Map<?, ?>) wrong.body().get("entry")).get("id");
        Reply helperUndo = post("/kirana/khata/" + reddy + "/payments/" + wrongId + "/reverse",
                Map.of("reason", "Was Mrs. Khan's"), helper);
        assertThat(helperUndo.body().get("code")).isEqualTo("OWNER_ONLY");
        Reply undone = post("/kirana/khata/" + reddy + "/payments/" + wrongId + "/reverse",
                Map.of("reason", "Was Mrs. Khan's"), owner);
        assertThat(undone.status()).as(undone.body().toString()).isEqualTo(201);
        assertThat(((Map<?, ?>) undone.body().get("customer")).get("balancePaise")).isEqualTo(15000);
        assertThat(((Map<?, ?>) undone.body().get("entry")).get("kind")).isEqualTo("REVERSAL");
        assertThat(((Map<?, ?>) undone.body().get("entry")).get("reversesId")).isEqualTo(wrongId);
        assertThat(post("/kirana/khata/" + reddy + "/payments/" + wrongId + "/reverse", Map.of("reason", "Again"),
                owner).body().get("code")).isEqualTo("PAYMENT_REVERSED");
        Map<?, ?> wrongNow = ((List<?>) get("/kirana/khata/" + reddy, owner).body().get("entries")).stream()
                .map(e -> (Map<?, ?>) e).filter(e -> wrongId.equals(e.get("id"))).findFirst().orElseThrow();
        assertThat(wrongNow.get("reversed")).isEqualTo(true);
        String saleId = jdbc.queryForObject("SELECT id::text FROM kirana_khata_entries WHERE kind = 'SALE' LIMIT 1",
                String.class);
        assertThat(post("/kirana/khata/" + reddy + "/payments/" + saleId + "/reverse", Map.of("reason", "No"), owner)
                .body().get("code")).isEqualTo("KHATA_ENTRY_NOT_FOUND");

        // A helper undoes his own typing mistake on the spot
        Reply typo = post("/kirana/khata/" + reddy + "/payments", payment(5000, "CASH"), helper);
        String typoId = (String) ((Map<?, ?>) typo.body().get("entry")).get("id");
        assertThat(post("/kirana/khata/" + reddy + "/payments/" + typoId + "/reverse", Map.of("reason", "Typed 50"),
                helper).status()).isEqualTo(201);

        Map<?, ?> day = get("/kirana/day", owner).body();
        assertThat(day.get("khataReceivedCashPaise")).isEqualTo(20000);
        assertThat(day.get("khataReceivedUpiPaise")).isEqualTo(0);   // recorded and undone the same day
    }

    @Test
    void theOwnerGivesAnAdvanceBackAndThenTheCustomerMayLeaveTheBook() {
        String owner = kiranaOwner(OWNER_PHONE);
        String helper = helperOf(owner, HELPER_PHONE);
        String reddy = id(post("/kirana/khata", Map.of("name", "Mr. Reddy"), owner));
        Map<String, Object> advance = payment(30000, "CASH");
        advance.put("advance", true);
        post("/kirana/khata/" + reddy + "/payments", advance, owner);
        Reply book = get("/kirana/khata", owner);
        assertThat(book.body().get("advancesPaise")).isEqualTo(30000);
        assertThat(((Map<?, ?>) getList(book, "items").get(0)).get("advancePaise")).isEqualTo(30000);
        assertThat(patch("/kirana/khata/" + reddy, Map.of("version", 0, "active", false), owner).body().get("code"))
                .isEqualTo("STILL_OWES");

        Map<String, Object> back = new HashMap<>(Map.of("clientRef", UUID.randomUUID().toString(),
                "amountPaise", 30000, "method", "CASH"));
        assertThat(post("/kirana/khata/" + reddy + "/advance-back", back, helper).body().get("code"))
                .isEqualTo("OWNER_ONLY");
        Map<String, Object> tooMuch = new HashMap<>(back);
        tooMuch.put("clientRef", UUID.randomUUID().toString());
        tooMuch.put("amountPaise", 30001);
        assertThat(post("/kirana/khata/" + reddy + "/advance-back", tooMuch, owner).body().get("code"))
                .isEqualTo("MORE_THAN_ADVANCE");
        Reply given = post("/kirana/khata/" + reddy + "/advance-back", back, owner);
        assertThat(given.status()).as(given.body().toString()).isEqualTo(201);
        assertThat(((Map<?, ?>) given.body().get("customer")).get("balancePaise")).isEqualTo(0);
        assertThat(post("/kirana/khata/" + reddy + "/advance-back", back, owner).status()).isEqualTo(200);
        assertThat(get("/kirana/day", owner).body().get("khataReceivedCashPaise")).isEqualTo(0);
        assertThat(patch("/kirana/khata/" + reddy, Map.of("version", 0, "active", false), owner).status())
                .isEqualTo(200);
    }

    @Test
    void aLooseItemByAnAmountThatIsntWholeGramsComesBackInPartsAndTheRefundsAddUpToTheTotal() {
        String owner = kiranaOwner(OWNER_PHONE);
        String jeera = id(post("/kirana/products", stocked("Jeera (loose)", "KG", 45000, "2", null), owner));
        Map<String, Object> b = bill("CASH", null, amountLine(jeera, 2000), itemLine("Bag", 500));
        b.put("discountPaise", 100);
        Reply r = post("/kirana/bills", b, owner);   // ₹20 at ₹450/kg is 0.044 kg; 2000 + 500 - 100 = 2400
        String billId = billId(r);
        assertThat(num(((Map<?, ?>) ((List<?>) ((Map<?, ?>) r.body().get("bill")).get("lines")).get(0))
                .get("quantity"))).isEqualByComparingTo("0.044");
        assertThat(stockOf(jeera)).isEqualByComparingTo("1.956");

        long first = refund(post("/kirana/bills/" + billId + "/returns", ret(line(1, "0.011")), owner));
        long second = refund(post("/kirana/bills/" + billId + "/returns", ret(line(1, "0.033")), owner));
        long third = refund(post("/kirana/bills/" + billId + "/returns", ret(line(2, "1")), owner));
        assertThat(first + second + third).isEqualTo(2400);
        assertThat(stockOf(jeera)).isEqualByComparingTo("2");
        Reply over = post("/kirana/bills/" + billId + "/returns", ret(line(1, "0.001")), owner);
        assertThat(keys(over.body().get("details"))).containsExactly("lines[0].quantity");
    }

    @Test
    void aHelperCancelsOrTakesAReturnOnHisOwnBillWithinTenMinutesOnly() {
        String owner = kiranaOwner(OWNER_PHONE);
        String helper = helperOf(owner, HELPER_PHONE);
        String oil = id(post("/kirana/products", stocked("Fortune Oil 1 L", "PIECE", 17500, "10", null), owner));
        String mine = billId(post("/kirana/bills", bill("CASH", null, line(oil, "2")), helper));
        String other = billId(post("/kirana/bills", bill("CASH", null, line(oil, "1")), helper));

        assertThat(post("/kirana/bills/" + mine + "/returns", ret(line(1, "1")), helper).status()).isEqualTo(201);
        assertThat(post("/kirana/bills/" + other + "/void", Map.of("reason", "Wrong item"), helper).status())
                .isEqualTo(200);
        jdbc.update("UPDATE kirana_bills SET created_at = created_at - interval '11 minutes' WHERE id = ?::uuid", mine);
        Reply lateReturn = post("/kirana/bills/" + mine + "/returns", ret(line(1, "1")), helper);
        assertThat(lateReturn.status()).isEqualTo(403);
        assertThat(lateReturn.body().get("code")).isEqualTo("OWNER_ONLY");
        assertThat(post("/kirana/bills/" + mine + "/returns", ret(line(1, "1")), owner).status()).isEqualTo(201);
        assertThat(post("/kirana/bills/" + other + "/returns", ret(line(1, "1")), owner).body().get("code"))
                .isEqualTo("BILL_VOIDED");
    }

    @Test
    void cancellingAPartPaidKhataBillHandsBackWhatWasPaidAndTakesTheRestOffTheKhata() {
        String owner = kiranaOwner(OWNER_PHONE);
        String oil = id(post("/kirana/products", stocked("Fortune Oil 1 L", "PIECE", 17500, "10", null), owner));
        String reddy = id(post("/kirana/khata", Map.of("name", "Mr. Reddy"), owner));
        Map<String, Object> b = bill("KHATA", reddy, line(oil, "2"));
        b.put("paidNowPaise", 15000);
        b.put("paidNowBy", "CASH");
        String billId = billId(post("/kirana/bills", b, owner));
        Reply cancelled = post("/kirana/bills/" + billId + "/void", Map.of("reason", "Changed his mind"), owner);
        assertThat(((Map<?, ?>) cancelled.body().get("khata")).get("balancePaise")).isEqualTo(0);
        Map<?, ?> day = get("/kirana/day", owner).body();
        assertThat(day.get("cancelledCashPaise")).isEqualTo(15000);
        assertThat(day.get("cancelledKhataPaise")).isEqualTo(20000);
        assertThat(day.get("cashInPaise")).isEqualTo(0);
    }

    @Test
    void aProductsUnitIsChangedOnlyBeforeItIsFirstSold() {
        String owner = kiranaOwner(OWNER_PHONE);
        String dal = id(post("/kirana/products", stocked("Toor Dal", "PIECE", 16000, "5", null), owner));
        Reply kg = patch("/kirana/products/" + dal, Map.of("version", 0, "unit", "KG"), owner);
        assertThat(kg.status()).as(kg.body().toString()).isEqualTo(200);
        assertThat(kg.body().get("unit")).isEqualTo("KG");
        post("/kirana/bills", bill("CASH", null, line(dal, "0.5")), owner);
        Reply back = patch("/kirana/products/" + dal, Map.of("version", 1, "unit", "PIECE"), owner);
        assertThat(back.status()).isEqualTo(409);
        assertThat(back.body().get("code")).isEqualTo("PRODUCT_SOLD");
    }

    // ---------- helpers ----------

    private static Map<String, Object> product(String name, String barcode, String unit, long price) {
        Map<String, Object> p = new HashMap<>();
        p.put("name", name);
        if (barcode != null) {
            p.put("barcode", barcode);
        }
        p.put("unit", unit);
        p.put("pricePaise", price);
        return p;
    }

    private static Map<String, Object> stocked(String name, String unit, long price, String stock, String low) {
        Map<String, Object> p = product(name, null, unit, price);
        p.put("stock", new BigDecimal(stock));
        if (low != null) {
            p.put("lowStockAt", new BigDecimal(low));
        }
        return p;
    }

    private static Map<String, Object> stock(String reason, Object quantity, String note) {
        Map<String, Object> b = new HashMap<>();
        b.put("clientRef", UUID.randomUUID().toString());
        b.put("reason", reason);
        b.put("quantity", quantity);
        if (note != null) {
            b.put("note", note);
        }
        return b;
    }

    private static Map<String, Object> line(String productId, String quantity) {
        return Map.of("productId", productId, "quantity", new BigDecimal(quantity));
    }

    /** A loose (kg) product billed by the amount asked for. */
    private static Map<String, Object> amountLine(String productId, long paise) {
        return Map.of("productId", productId, "amountPaise", paise);
    }

    /** An item not in the list, by its name and price. */
    private static Map<String, Object> itemLine(String name, long paise) {
        return Map.of("name", name, "amountPaise", paise);
    }

    /** A return line: the bill's line and how much of it comes back. */
    private static Map<String, Object> line(int lineNo, String quantity) {
        return Map.of("lineNo", lineNo, "quantity", new BigDecimal(quantity));
    }

    @SafeVarargs
    private static Map<String, Object> ret(Map<String, Object>... lines) {
        Map<String, Object> r = new HashMap<>();
        r.put("clientRef", UUID.randomUUID().toString());
        r.put("lines", List.of(lines));
        return r;
    }

    /** Changes a bill's payment, sending the bill's version as read just before (owner reads it). */
    private Reply changePay(String billId, Map<String, Object> body, String token) {
        Object version = jdbc.queryForObject("SELECT version FROM kirana_bills WHERE id = ?::uuid", Long.class, billId);
        Map<String, Object> b = new HashMap<>(body);
        b.put("version", version);
        return post("/kirana/bills/" + billId + "/payment", b, token);
    }

    private static long refund(Reply r) {
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        return ((Number) ((Map<?, ?>) r.body().get("returned")).get("refundPaise")).longValue();
    }

    private Long balance(String customerId) {
        return jdbc.queryForObject("SELECT balance_paise FROM kirana_khata_customers WHERE id = ?::uuid", Long.class,
                customerId);
    }

    private BigDecimal stockOf(String productId) {
        return jdbc.queryForObject("SELECT stock FROM kirana_products WHERE id = ?::uuid", BigDecimal.class, productId);
    }

    @SafeVarargs
    private static Map<String, Object> bill(String payment, String khataCustomerId, Map<String, Object>... lines) {
        Map<String, Object> b = new HashMap<>();
        b.put("clientRef", UUID.randomUUID().toString());
        b.put("payment", payment);
        b.put("lines", List.of(lines));
        if (khataCustomerId != null) {
            b.put("khataCustomerId", khataCustomerId);
        }
        return b;
    }

    private static String billId(Reply r) {
        assertThat(r.status()).as(r.body().toString()).isIn(200, 201);
        return (String) ((Map<?, ?>) r.body().get("bill")).get("id");
    }

    private static Map<String, Object> payment(long paise, String method) {
        return new HashMap<>(Map.of("clientRef", UUID.randomUUID().toString(), "amountPaise", paise, "method", method));
    }

    /** Runs the call this many times at once (released together) and returns every reply. */
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

    /**
     * Editing unlocked for this sign-in for a while, as if its owner had entered the code from POST /auth/me/edit-code
     * (EditUnlockStore keys it by the user and the token's session, both read from the token here).
     */
    private void unlockEditing(String accessToken) {
        String payload = new String(java.util.Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]),
                java.nio.charset.StandardCharsets.UTF_8);
        String sub = field(payload, "sub");
        String sid = field(payload, "sid");
        redis.opsForValue().set("edit-unlock:" + sub + ":" + (sid == null ? "-" : sid),
                Long.toString(java.time.Instant.parse("9999-01-01T00:00:00Z").toEpochMilli()),
                java.time.Duration.ofHours(1));
    }

    /** A string field of a flat JSON object (the token's payload), or null. */
    private static String field(String json, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private String kiranaOwner(String phone) {
        return shopOwner(phone, "KIRANA");
    }

    private String shopOwner(String phone, String type) {
        String access = token(phone);
        Map<String, Object> shop = new HashMap<>();
        shop.put("name", "SALON".equals(type) ? "Sharma Hair Studio" : "Sharma General Store");
        shop.put("ownerName", "Ramesh Sharma");
        shop.put("addressLine", "Shop 4, Green Meadows main gate");
        shop.put("area", "Kompally");
        shop.put("city", "Hyderabad");
        shop.put("pincode", "500014");
        shop.put("latitude", new BigDecimal("17.535123"));
        shop.put("longitude", new BigDecimal("78.484456"));
        shop.put("type", type);
        Reply r = post("/shops", shop, access);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        return access;
    }

    private String helperOf(String ownerToken, String phone) {
        UUID shopId = shopId(ownerToken);
        Map<?, ?> session = signInBody(phone);
        UUID helperId = UUID.fromString((String) ((Map<?, ?>) session.get("user")).get("id"));
        TestData.member(jdbc, shopId, helperId, "HELPER");
        return (String) session.get("accessToken");
    }

    private UUID shopId(String token) {
        return UUID.fromString((String) get("/shops/me", token).body().get("id"));
    }

    private long version(String token) {
        return ((Number) get("/shops/me", token).body().get("version")).longValue();
    }

    private String photo(UUID shopId, String purpose) {
        UUID file = UUID.randomUUID();
        UUID owner = jdbc.queryForObject("SELECT user_id FROM shop_members WHERE shop_id = ? AND role = 'OWNER'",
                UUID.class, shopId);
        jdbc.update("INSERT INTO files (id, shop_id, purpose, storage_key, content_type, size_bytes, sha256, "
                        + "uploaded_by, created_at) VALUES (?, ?, ?, ?, 'image/jpeg', 10, ?, ?, now())", file, shopId,
                purpose, "test/" + file + ".jpg", file.toString().replace("-", "") + "0".repeat(32), owner);
        return file.toString();
    }

    private Map<?, ?> signInBody(String phone) {
        post("/auth/otp/send", Map.of("phone", phone), null);
        Reply r = post("/auth/otp/verify", Map.of("phone", phone, "code", codeSentTo(LAST_SMS, phone)), null);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        TestData.completeProfile(jdbc, phone);   // the email and name screens
        return r.body();
    }

    private String token(String phone) {
        return (String) signInBody(phone).get("accessToken");
    }

    private static String id(Reply r) {
        assertThat(r.status()).as(r.body().toString()).isIn(200, 201);
        return (String) r.body().get("id");
    }

    /** A JSON object's field names, typed so AssertJ can compare them with plain values. */
    private static List<Object> keys(Object jsonObject) {
        return new ArrayList<>(((Map<?, ?>) jsonObject).keySet());
    }

    /** A JSON array, typed so AssertJ can compare its items with plain values. */
    private static List<Object> objects(Object jsonArray) {
        return new ArrayList<>((List<?>) jsonArray);
    }

    /** One field of every object in a JSON array. */
    private static List<Object> pluck(Object jsonArray, String field) {
        List<Object> out = new ArrayList<>();
        for (Object o : (List<?>) jsonArray) {
            out.add(((Map<?, ?>) o).get(field));
        }
        return out;
    }

    private static BigDecimal num(Object o) {
        return new BigDecimal(String.valueOf(o));
    }

    private static List<?> getList(Reply r, String field) {
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return (List<?>) r.body().get(field);
    }

    private static List<?> getListBody(Reply r) {
        assertThat(r.status()).isEqualTo(200);
        return r.list();
    }

    private Reply post(String path, Object body, String token) {
        return http.post().uri(path).headers(h -> bearer(h, token)).contentType(MediaType.APPLICATION_JSON)
                .body(body).exchange((rq, res) -> reply(res));
    }

    private Reply patch(String path, Object body, String token) {
        return http.patch().uri(path).headers(h -> bearer(h, token)).contentType(MediaType.APPLICATION_JSON)
                .body(body).exchange((rq, res) -> reply(res));
    }

    private Reply delete(String path, String token) {
        return http.delete().uri(path).headers(h -> bearer(h, token)).exchange((rq, res) -> reply(res));
    }

    private Reply put(String path, Object body, String token) {
        return http.put().uri(path).headers(h -> bearer(h, token)).contentType(MediaType.APPLICATION_JSON)
                .body(body).exchange((rq, res) -> reply(res));
    }

    /** GET with q encoded by us (a path string would be taken as a template and its % encoded again). */
    private Reply search(String path, String q, String token) {
        java.net.URI uri = java.net.URI.create("http://localhost:" + environment.getProperty("local.server.port")
                + "/api/v1" + path + "?q=" + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8));
        return http.get().uri(uri).headers(h -> bearer(h, token)).exchange((rq, res) -> reply(res));
    }

    private Reply get(String path, String token) {
        return http.get().uri(path).headers(h -> bearer(h, token)).exchange((rq, res) -> reply(res));
    }

    private static void bearer(org.springframework.http.HttpHeaders headers, String token) {
        if (token != null) {
            headers.setBearerAuth(token);
        }
    }

    /** A reply: its status, and its JSON as an object (body) or a list (list); the other is empty. */
    private static Reply reply(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse res)
            throws java.io.IOException {
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
