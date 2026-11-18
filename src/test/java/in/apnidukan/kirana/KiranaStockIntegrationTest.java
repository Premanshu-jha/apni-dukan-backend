package in.apnidukan.kirana;

import com.sun.net.httpserver.HttpServer;
import in.apnidukan.files.storage.FileStorage;
import in.apnidukan.kirana.service.ReadJob;
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static in.apnidukan.support.CapturingSenders.LAST_EMAIL;
import static in.apnidukan.support.CapturingSenders.LAST_SMS;
import static in.apnidukan.support.CapturingSenders.codeSentTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kirana stock from photos and scans (round 3, V72–V73) end to end against real Postgres, Redis and S3Mock, and a fake
 * AI that answers what each test registered for the photo's text: distributors' bills read into drafts, matched and
 * added to stock; scanning while shelving (packs, misreads, loose labels, retries, ticking an open bill); unknown
 * barcodes with their label read once across shops and linked in one tap; the owner's corrections; the shared
 * catalogue's trust, review list and product-master import; counter suggestions; paper bills.
 */
@EnabledIf("in.apnidukan.support.TestContainers#dockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CapturingSenders.class)
class KiranaStockIntegrationTest {

    private static final String OWNER = "9848055001";
    private static final String HELPER = "9848055002";
    private static final String OTHER_OWNER = "9848055003";
    private static final String THIRD_OWNER = "9848055004";
    private static final String ADMIN = "9848055005";

    /** What the fake AI answers for a photo whose bytes are this text (the answer's JSON object). */
    static final Map<String, String> ANSWERS = new ConcurrentHashMap<>();
    static final AtomicInteger AI_CALLS = new AtomicInteger();
    /** AI calls that sent the bill as text, not as a picture. */
    static final AtomicInteger TEXT_READS = new AtomicInteger();
    private static final HttpServer AI = fakeAi();

    private static HttpServer fakeAi() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                AI_CALLS.incrementAndGet();
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Matcher data = Pattern.compile("\"data\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
                String text = data.find() ? new String(Base64.getDecoder().decode(data.group(1)),
                        StandardCharsets.UTF_8) : "";
                String facts = ANSWERS.get(text);
                if (facts == null) {
                    // A bill sent as text (a PDF with text, a sheet, a document): its words are in the request
                    facts = ANSWERS.entrySet().stream().filter(e -> body.contains(e.getKey())).map(Map.Entry::getValue)
                            .findFirst().orElse("{\"readable\":false}");
                    TEXT_READS.incrementAndGet();
                }
                String answer = "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":"
                        + quote(facts) + "}]}";
                byte[] out = answer.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        TestContainers.register(registry);
        registry.add("app.kirana.ai-url", () -> "http://127.0.0.1:" + AI.getAddress().getPort() + "/v1/messages");
        registry.add("app.kirana.ai-key", () -> "test-key");
        registry.add("app.kirana.jobs-delay-ms", () -> "3600000");   // the tests run the jobs themselves
    }

    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired FileStorage storage;
    @Autowired ReadJob readJob;

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
        ANSWERS.clear();
        AI_CALLS.set(0);
        TEXT_READS.set(0);
    }

    // ---------- distributors' bills ----------

    @Test
    void aDistributorsBillIsReadMatchedCheckedAndAddedToStockOnceWithItsCost() {
        String owner = kirana(OWNER, "Sharma General Store");
        String helper = helperOf(owner, HELPER);
        String milk = id(post("/kirana/products", coded("Amul Taaza Toned Milk 500 ml", 2900, "4", ean("890126215001")),
                owner));
        String sugar = id(post("/kirana/products", product("Sugar 1 kg", "PIECE", 5200, "0"), owner));
        String wash = id(post("/kirana/products", product("Dettol Hand Wash 200 ml", "PIECE", 9900, "0"), owner));
        ANSWERS.put("bill-1", purchase("M/S. Sharma Agencies", "INV-77", "[" + billLine("AMUL TAZA 500ML TM", "Amul",
                "Taaza Toned Milk", "500 ml", "24", "26", "29") + "," + billLine("SUGAR 1KG", null, "Sugar", "1 kg",
                "10", "48", "52") + "," + billLine("DTL HW REFL 175ML", null, "Hand Wash Refill", "175 ml", "6", "80", "99")
                + "]"));

        Reply read = post("/kirana/reads", newRead("PURCHASE", upload(owner, "BILL", "bill-1")), helper);
        assertThat(read.status()).as(read.body().toString()).isEqualTo(201);
        assertThat(read.body().get("status")).isEqualTo("READING");
        readJob.run();
        Reply done = get("/kirana/reads/" + id(read), helper);
        assertThat(done.body().get("status")).as(done.body().toString()).isEqualTo("READ");
        String purchase = (String) done.body().get("purchaseId");

        Reply draft = get("/kirana/purchases/" + purchase, helper);
        List<?> lines = (List<?>) draft.body().get("lines");
        assertThat(pluck(lines, "productId")).containsExactly(milk, sugar, null);   // another size: the shop picks
        assertThat(pluck(lines, "pick")).containsExactly("SURE", "SURE", "NONE");
        assertThat(((Map<?, ?>) lines.get(2)).get("toCheck")).isEqualTo(true);

        // Nothing is added while a line is still to check; a stale version is refused
        long v = ((Number) draft.body().get("version")).longValue();
        assertThat(post("/kirana/purchases/" + purchase + "/add", Map.of("version", v), helper).body().get("code"))
                .isEqualTo("LINES_TO_CHECK");
        Reply picked = patch("/kirana/purchases/" + purchase + "/lines/3", Map.of("version", v, "productId", wash),
                helper);
        assertThat(picked.status()).as(picked.body().toString()).isEqualTo(200);
        assertThat(patch("/kirana/purchases/" + purchase + "/lines/1", Map.of("version", v, "quantity", 20), helper)
                .body().get("code")).isEqualTo("PURCHASE_CHANGED");
        long v2 = ((Number) picked.body().get("version")).longValue();

        Reply added = post("/kirana/purchases/" + purchase + "/add", Map.of("version", v2), helper);
        assertThat(added.status()).as(added.body().toString()).isEqualTo(200);
        assertThat(added.body().get("status")).isEqualTo("ADDED");
        assertThat(stock(milk)).isEqualByComparingTo("28");
        assertThat(stock(sugar)).isEqualByComparingTo("10");
        assertThat(stock(wash)).isEqualByComparingTo("6");
        // Adding again changes nothing
        assertThat(post("/kirana/purchases/" + purchase + "/add", Map.of("version", v2 + 1), helper).body()
                .get("code")).isEqualTo("PURCHASE_DONE");
        assertThat(stock(milk)).isEqualByComparingTo("28");

        // The cost is the owner's only
        assertThat(((Map<?, ?>) get("/kirana/products/" + milk, owner).body().get("product")).get("costPaise"))
                .isEqualTo(2600);
        assertThat(((Map<?, ?>) get("/kirana/products/" + milk, helper).body().get("product")).get("costPaise"))
                .isNull();

        // The same bill photographed again: the misspelt wording is remembered; the same bill number is flagged
        ANSWERS.put("bill-2", ANSWERS.get("bill-1").replace("M/S. Sharma Agencies", "Sharma Agencies"));
        Reply again = post("/kirana/reads", newRead("PURCHASE", upload(owner, "BILL", "bill-2")), helper);
        readJob.run();
        String second = (String) get("/kirana/reads/" + id(again), helper).body().get("purchaseId");
        Reply secondDraft = get("/kirana/purchases/" + second, helper);
        assertThat(pluck(secondDraft.body().get("lines"), "pick")).containsExactly("REMEMBERED", "REMEMBERED",
                "REMEMBERED");
        long sv = ((Number) secondDraft.body().get("version")).longValue();
        Reply twice = post("/kirana/purchases/" + second + "/add", Map.of("version", sv), helper);
        assertThat(twice.body().get("code")).as(twice.body().toString()).isEqualTo("BILL_ALREADY_ADDED");
        assertThat(post("/kirana/purchases/" + second + "/add", Map.of("version", sv, "addAgain", true), helper)
                .status()).isEqualTo(200);
        assertThat(stock(milk)).isEqualByComparingTo("52");
    }

    @Test
    void aPhotoThatIsNotABillFailsWithAReasonAndAnotherShopCantSeeIt() {
        String owner = kirana(OWNER, "Sharma General Store");
        String other = kirana(OTHER_OWNER, "Gupta Kirana");
        ANSWERS.put("selfie", "{\"readable\":true,\"bill\":false,\"lines\":[]}");
        Reply read = post("/kirana/reads", newRead("PURCHASE", upload(owner, "BILL", "selfie")), owner);
        readJob.run();
        Reply done = get("/kirana/reads/" + id(read), owner);
        assertThat(done.body().get("status")).isEqualTo("FAILED");
        assertThat((String) done.body().get("failure")).contains("distributor's bill");
        assertThat(get("/kirana/reads/" + id(read), other).body().get("code")).isEqualTo("READ_NOT_FOUND");
        // Another shop's photo can't be sent as one's own
        Reply theirs = post("/kirana/reads", newRead("PURCHASE", upload(other, "BILL", "x")), owner);
        assertThat(theirs.status()).isEqualTo(400);
    }

    @Test
    void aBillLineForAProductTheShopLacksIsAddedAsANewProductFromTheCatalogueInOneGo() {
        String other = kirana(OTHER_OWNER, "Gupta Kirana");
        id(post("/kirana/products", coded("Britannia Good Day Cashew 100 g", 3000, "0", ean("890106300001")), other));
        String owner = kirana(OWNER, "Sharma General Store");
        ANSWERS.put("bill", purchase("Sharma Agencies", "12", "[" + billLine("BRIT GOODDAY CASHEW 100G",
                "Britannia", "Good Day Cashew", "100 g", "24", "25", "30") + "]"));
        Reply read = post("/kirana/reads", newRead("PURCHASE", upload(owner, "BILL", "bill")), owner);
        readJob.run();
        String purchase = (String) get("/kirana/reads/" + id(read), owner).body().get("purchaseId");
        Reply draft = get("/kirana/purchases/" + purchase, owner);
        Map<?, ?> line = (Map<?, ?>) ((List<?>) draft.body().get("lines")).get(0);
        assertThat(line.get("productId")).isNull();
        Map<?, ?> suggestion = (Map<?, ?>) ((List<?>) line.get("suggestions")).get(0);
        assertThat(suggestion.get("productId")).isNull();   // the catalogue's, not the shop's
        Reply picked = patch("/kirana/purchases/" + purchase + "/lines/1", Map.of("version", draft.body().get("version"),
                "newProduct", Map.of("itemId", suggestion.get("itemId"), "pricePaise", 3000)), owner);
        assertThat(picked.status()).as(picked.body().toString()).isEqualTo(200);
        Map<?, ?> now = (Map<?, ?>) ((List<?>) picked.body().get("lines")).get(0);
        assertThat(now.get("productName")).isEqualTo("Britannia Good Day Cashew 100 g");
        assertThat(now.get("pick")).isEqualTo("PICKED");
    }

    @Test
    void billsSentAsSpreadsheetsCsvOrGstEInvoicesAreReadInCodeWithNoAi() throws Exception {
        String owner = kirana(OWNER, "Sharma General Store");
        String milk = id(post("/kirana/products", coded("Amul Taaza Toned Milk 500 ml", 2900, "0",
                ean("890126215001")), owner));
        // An Excel bill from the distributor's billing software
        byte[] xlsx;
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook book = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet sheet = book.createSheet("Invoice");
            String[][] cells = {{"Sharma Agencies"}, {"Invoice No:", "SA/1001"}, {"Date:", "15/11/2026"}, {},
                    {"Sl", "Item Description", "Qty", "Free", "Rate", "Amount"},
                    {"1", "AMUL TAZA 500ML TM", "24", "1", "26.00", "624.00"}, {"", "Total", "", "", "", "624.00"}};
            for (int r = 0; r < cells.length; r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(r);
                for (int c = 0; c < cells[r].length; c++) {
                    row.createCell(c).setCellValue(cells[r][c]);
                }
            }
            book.write(out);
            xlsx = out.toByteArray();
        }
        Reply sheetRead = post("/kirana/reads", newRead("PURCHASE", upload(owner, "BILL", xlsx,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")), owner);
        readJob.run();
        Reply sheetDone = get("/kirana/reads/" + id(sheetRead), owner);
        assertThat(sheetDone.body().get("status")).as(sheetDone.body().toString()).isEqualTo("READ");
        Reply fromSheet = get("/kirana/purchases/" + sheetDone.body().get("purchaseId"), owner);
        assertThat(fromSheet.body().get("billNo")).isEqualTo("SA/1001");
        assertThat(fromSheet.body().get("billDate")).isEqualTo("2026-11-15");
        assertThat(pluck(fromSheet.body().get("lines"), "quantity")).containsExactly(25);   // 24 + 1 free
        assertThat(pluck(fromSheet.body().get("lines"), "productId")).containsExactly(milk);

        // A CSV export
        String csv = "Particulars,Qty,Rate\n\"AMUL TAZA 500ML TM\",12,26\n";
        Reply csvRead = post("/kirana/reads", newRead("PURCHASE", upload(owner, "BILL",
                csv.getBytes(StandardCharsets.UTF_8), "text/csv")), owner);
        readJob.run();
        Reply csvDone = get("/kirana/reads/" + id(csvRead), owner);
        assertThat(pluck(get("/kirana/purchases/" + csvDone.body().get("purchaseId"), owner).body().get("lines"),
                "quantity")).containsExactly(12);

        // A GST e-invoice's JSON, signed by the IRP
        String invoice = "{\"DocDtls\":{\"Typ\":\"INV\",\"No\":\"GST/77\",\"Dt\":\"16/11/2026\"},"
                + "\"SellerDtls\":{\"LglNm\":\"Sharma Agencies Pvt Ltd\",\"TrdNm\":\"Sharma Agencies\"},"
                + "\"ItemList\":[{\"PrdDesc\":\"AMUL TAZA 500ML TM\",\"Qty\":6,\"UnitPrice\":26,"
                + "\"TotItemVal\":163.8}],\"ValDtls\":{\"TotInvVal\":163.8}}";
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(("{\"data\":" + quote(invoice) + "}")
                .getBytes(StandardCharsets.UTF_8));
        String signed = "{\"AckNo\":1,\"SignedInvoice\":\"eyJhbGciOiJSUzI1NiJ9." + payload + ".c2ln\"}";
        Reply jsonRead = post("/kirana/reads", newRead("PURCHASE", upload(owner, "BILL",
                signed.getBytes(StandardCharsets.UTF_8), "application/json")), owner);
        readJob.run();
        Reply jsonDone = get("/kirana/reads/" + id(jsonRead), owner);
        Reply fromJson = get("/kirana/purchases/" + jsonDone.body().get("purchaseId"), owner);
        assertThat(fromJson.body().get("distributor")).isEqualTo("Sharma Agencies");
        assertThat(fromJson.body().get("billNo")).isEqualTo("GST/77");
        assertThat(pluck(fromJson.body().get("lines"), "quantity")).containsExactly(6);

        assertThat(AI_CALLS.get()).isZero();
    }

    @Test
    void aPdfBillWithTextIsSentToTheAiAsTextNotAsPictures() throws Exception {
        String owner = kirana(OWNER, "Sharma General Store");
        byte[] pdf;
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument();
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
            doc.addPage(page);
            try (org.apache.pdfbox.pdmodel.PDPageContentStream text =
                         new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                text.beginText();
                text.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 10);
                text.newLineAtOffset(40, 700);
                text.showText("Gupta Distributors Invoice PDF-TEXT-BILL Sugar 1 kg Qty 10 Rate 48 Amount 480 Total");
                text.endText();
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        ANSWERS.put("PDF-TEXT-BILL", purchase("Gupta Distributors", "G-5", "[" + billLine("Sugar 1 kg", null,
                "Sugar", "1 kg", "10", "48", "52") + "]"));
        Reply read = post("/kirana/reads", newRead("PURCHASE", upload(owner, "BILL", pdf, "application/pdf")), owner);
        readJob.run();
        assertThat(get("/kirana/reads/" + id(read), owner).body().get("status")).isEqualTo("READ");
        assertThat(AI_CALLS.get()).isEqualTo(1);
        assertThat(TEXT_READS.get()).isEqualTo(1);
    }

    // ---------- scanning while shelving ----------

    @Test
    void scansAddPacksRefuseMisreadsCountLooseLabelsAndARetryCountsNothingTwice() {
        String owner = kirana(OWNER, "Sharma General Store");
        String milkCode = ean("890126215001");
        String cartonCode = ean("890126215002");
        String milk = id(post("/kirana/products", coded("Amul Taaza 500 ml", 2900, "0", milkCode), owner));
        assertThat(post("/kirana/products/" + milk + "/barcodes", Map.of("code", cartonCode, "pack", 12), owner)
                .status()).isEqualTo(200);
        String rice = id(post("/kirana/products", product("Sona Masoori Rice", "KG", 6000, "0"), owner));
        assertThat(post("/kirana/products/" + rice + "/barcodes", Map.of("code", "2012345"), owner).status())
                .isEqualTo(200);
        String wrong = milkCode.substring(0, 12) + ((milkCode.charAt(12) - '0' + 1) % 10);
        String looseLabel = ean("201234501250");   // the shop's scale: 1,250 g of rice

        UUID batch = UUID.randomUUID();
        Map<String, Object> body = Map.of("clientRef", batch.toString(), "scans", List.of(
                Map.of("code", milkCode, "times", 3), Map.of("code", cartonCode), Map.of("code", wrong),
                Map.of("code", looseLabel)));
        Reply r = post("/kirana/scans", body, owner);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(pluck(r.body().get("results"), "outcome")).containsExactly("ADDED", "ADDED", "REJECTED", "ADDED");
        assertThat(stock(milk)).isEqualByComparingTo("15");
        assertThat(stock(rice)).isEqualByComparingTo("1.25");

        // The same batch again (the answer was lost): nothing is added twice
        Reply retry = post("/kirana/scans", body, owner);
        assertThat(retry.body().get("repeated")).isEqualTo(true);
        assertThat(stock(milk)).isEqualByComparingTo("15");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kirana_stock_moves WHERE reason = 'RESTOCK' "
                + "AND barcode IS NOT NULL", Integer.class)).isEqualTo(3);   // one per product and barcode
    }

    @Test
    void manyScanBatchesAtOnceAddEveryPieceExactlyOnce() throws Exception {
        String owner = kirana(OWNER, "Sharma General Store");
        String code = ean("890126215001");
        String milk = id(post("/kirana/products", coded("Amul Taaza 500 ml", 2900, "0", code), owner));
        List<Reply> replies = together(8, () -> post("/kirana/scans", Map.of("clientRef", UUID.randomUUID().toString(),
                "scans", List.of(Map.of("code", code, "times", 2), Map.of("code", ean("890999900001")))), owner));
        assertThat(replies).allSatisfy(r -> assertThat(r.status()).as(r.body().toString()).isEqualTo(200));
        assertThat(stock(milk)).isEqualByComparingTo("16");
        // The unknown barcode is one entry with every scan counted
        assertThat(jdbc.queryForObject("SELECT scans FROM kirana_unknown_scans", BigDecimal.class))
                .isEqualByComparingTo("8");
    }

    @Test
    void scansWhileShelvingAnOpenBillTickItsLinesAndTheBillAddsTheStockOnce() {
        String owner = kirana(OWNER, "Sharma General Store");
        String code = ean("890126215001");
        String milk = id(post("/kirana/products", coded("Amul Taaza Toned Milk 500 ml", 2900, "0", code), owner));
        ANSWERS.put("bill", purchase("Sharma Agencies", "9", "[" + billLine("AMUL TAZA 500ML", "Amul",
                "Taaza Toned Milk", "500 ml", "12", "26", "29") + "]"));
        Reply read = post("/kirana/reads", newRead("PURCHASE", upload(owner, "BILL", "bill")), owner);
        readJob.run();
        String purchase = (String) get("/kirana/reads/" + id(read), owner).body().get("purchaseId");

        Reply r = post("/kirana/scans", Map.of("clientRef", UUID.randomUUID().toString(), "purchaseId", purchase,
                "scans", List.of(Map.of("code", code, "times", 12))), owner);
        assertThat(pluck(r.body().get("results"), "outcome")).containsExactly("TICKED");
        assertThat(pluck(r.body().get("results"), "lineQuantity")).containsExactly(12);
        assertThat(stock(milk)).isEqualByComparingTo("0");
        Reply draft = get("/kirana/purchases/" + purchase, owner);
        assertThat(pluck(draft.body().get("lines"), "scanned")).containsExactly(12);
        post("/kirana/purchases/" + purchase + "/add", Map.of("version", draft.body().get("version")), owner);
        assertThat(stock(milk)).isEqualByComparingTo("12");
        // Once added, the bill can't be scanned against
        assertThat(post("/kirana/scans", Map.of("clientRef", UUID.randomUUID().toString(), "purchaseId", purchase,
                "scans", List.of(Map.of("code", code))), owner).body().get("code")).isEqualTo("PURCHASE_DONE");
    }

    @Test
    void scansWithABillAreNeverCountedTwiceWhateverHappensToTheBill() {
        String owner = kirana(OWNER, "Sharma General Store");
        String milkCode = ean("890126215001");
        String teaCode = ean("890126215005");
        String milk = id(post("/kirana/products", coded("Heritage Toned Milk 500 ml", 2900, "0", milkCode), owner));
        String tea = id(post("/kirana/products", coded("Red Label Tea 250 g", 14000, "0", teaCode), owner));
        // The bill's line can't be matched by itself (another name); the helper shelves before it is picked
        ANSWERS.put("bill-a", purchase("Sharma Agencies", "41", "[" + billLine("HTG TM 500", null, "Dairy Milk Pouch",
                "500 ml", "12", "26", "29") + "]"));
        Reply read = post("/kirana/reads", newRead("PURCHASE", upload(owner, "BILL", "bill-a")), owner);
        readJob.run();
        String bill = (String) get("/kirana/reads/" + id(read), owner).body().get("purchaseId");
        Reply scanned = post("/kirana/scans", Map.of("clientRef", UUID.randomUUID().toString(), "purchaseId", bill,
                "scans", List.of(Map.of("code", milkCode, "times", 12), Map.of("code", teaCode, "times", 2),
                        Map.of("code", ean("890177700009"), "times", 3))), owner);
        assertThat(pluck(scanned.body().get("results"), "outcome")).containsExactly("HELD", "HELD", "UNKNOWN");
        assertThat(stock(milk)).isEqualByComparingTo("0");
        Reply draft = get("/kirana/purchases/" + bill, owner);
        assertThat(pluck(draft.body().get("held"), "productId")).containsExactlyInAnyOrder(milk, tea);
        assertThat(draft.body().get("openUnknowns")).isEqualTo(1);
        Reply picked = patch("/kirana/purchases/" + bill + "/lines/1", Map.of("version", draft.body().get("version"),
                "productId", milk), owner);
        assertThat(pluck(picked.body().get("lines"), "scanned")).containsExactly(12);
        Reply added = post("/kirana/purchases/" + bill + "/add", Map.of("version", picked.body().get("version")), owner);
        assertThat(added.status()).as(added.body().toString()).isEqualTo(200);
        assertThat(stock(milk)).isEqualByComparingTo("12");   // the bill's 12, not 24
        assertThat(stock(tea)).isEqualByComparingTo("2");     // scanned, on no line: it came too

        // The unknown pack scanned with the bill is linked after it was added: the bill's line brought it already
        String unknown = (String) ((Map<?, ?>) ((List<?>) get("/kirana/scans/unknown", owner).body().get("items"))
                .get(0)).get("id");
        Reply link = post("/kirana/scans/unknown/" + unknown + "/link", Map.of("productId", milk), owner);
        assertThat(link.status()).as(link.body().toString()).isEqualTo(200);
        assertThat(link.body().get("quantity")).isEqualTo(0);
        assertThat(stock(milk)).isEqualByComparingTo("12");
    }

    @Test
    void aWrongPickCorrectedAfterAddingMovesTheStockOnceEvenWhenTheRightProductWasScanned() {
        String owner = kirana(OWNER, "Sharma General Store");
        String helper = helperOf(owner, HELPER);
        String wrong = id(post("/kirana/products", product("Yippee Noodles 70 g", "PIECE", 1400, "0"), owner));
        String maggiCode = ean("890105800001");
        String maggi = id(post("/kirana/products", coded("Maggi Masala Noodles 70 g", 1400, "0", maggiCode), owner));
        ANSWERS.put("noodles", purchase("Sharma Agencies", "N-1", "[" + billLine("NDLS 70G X12", null, "Noodles",
                "70 g", "12", "12", "14") + "]"));
        Reply read = post("/kirana/reads", newRead("PURCHASE", upload(owner, "BILL", "noodles")), helper);
        readJob.run();
        String bill = (String) get("/kirana/reads/" + id(read), helper).body().get("purchaseId");
        Reply draft = get("/kirana/purchases/" + bill, helper);
        Reply picked = patch("/kirana/purchases/" + bill + "/lines/1", Map.of("version", draft.body().get("version"),
                "productId", wrong), helper);   // the wrong one
        post("/kirana/scans", Map.of("clientRef", UUID.randomUUID().toString(), "purchaseId", bill,
                "scans", List.of(Map.of("code", maggiCode, "times", 12))), helper);   // HELD: on no line
        post("/kirana/purchases/" + bill + "/add", Map.of("version", picked.body().get("version")), helper);
        assertThat(stock(wrong)).isEqualByComparingTo("12");
        assertThat(stock(maggi)).isEqualByComparingTo("12");
        assertThat(post("/kirana/purchases/" + bill + "/lines/1/correct", Map.of("productId", maggi), helper)
                .status()).isEqualTo(403);
        Reply fixed = post("/kirana/purchases/" + bill + "/lines/1/correct", Map.of("productId", maggi), owner);
        assertThat(fixed.status()).as(fixed.body().toString()).isEqualTo(200);
        assertThat(stock(wrong)).isEqualByComparingTo("0");
        assertThat(stock(maggi)).isEqualByComparingTo("12");   // 12 on the shelf, not 24
    }

    @Test
    void aBarcodePutAwayByMistakeIsBroughtBackWithItsScans() {
        String owner = kirana(OWNER, "Sharma General Store");
        String code = ean("890177700005");
        Reply scan = post("/kirana/scans", Map.of("clientRef", UUID.randomUUID().toString(),
                "scans", List.of(Map.of("code", code, "times", 4))), owner);
        String unknown = (String) ((Map<?, ?>) ((List<?>) scan.body().get("results")).get(0)).get("unknownId");
        post("/kirana/scans/unknown/" + unknown + "/dismiss", Map.of(), owner);
        assertThat(pluck(get("/kirana/scans/unknown?dismissed=true", owner).body().get("items"), "id"))
                .containsExactly(unknown);
        // Scanned again meanwhile: a new entry; bringing the old one back adds its scans to it
        post("/kirana/scans", Map.of("clientRef", UUID.randomUUID().toString(),
                "scans", List.of(Map.of("code", code, "times", 2))), owner);
        Reply back = post("/kirana/scans/unknown/" + unknown + "/reopen", Map.of(), owner);
        assertThat(back.status()).as(back.body().toString()).isEqualTo(200);
        assertThat(back.body().get("scans")).isEqualTo(6);
        assertThat((List<?>) get("/kirana/scans/unknown", owner).body().get("items")).hasSize(1);
    }

    @Test
    void aBillThrownAwayAfterShelvingPutsWhatWasScannedIntoStock() {
        String owner = kirana(OWNER, "Sharma General Store");
        String code = ean("890126215001");
        String milk = id(post("/kirana/products", coded("Amul Taaza Toned Milk 500 ml", 2900, "0", code), owner));
        ANSWERS.put("wrong-bill", purchase("Sharma Agencies", "7", "[" + billLine("AMUL TAZA 500ML", "Amul",
                "Taaza Toned Milk", "500 ml", "24", "26", "29") + "]"));
        Reply read = post("/kirana/reads", newRead("PURCHASE", upload(owner, "BILL", "wrong-bill")), owner);
        readJob.run();
        String bill = (String) get("/kirana/reads/" + id(read), owner).body().get("purchaseId");
        post("/kirana/scans", Map.of("clientRef", UUID.randomUUID().toString(), "purchaseId", bill,
                "scans", List.of(Map.of("code", code, "times", 10))), owner);
        assertThat(stock(milk)).isEqualByComparingTo("0");
        // Thrown away from the photo's screen: the 10 shelved go into stock; the photo is deleted
        Reply gone = post("/kirana/reads/" + id(read) + "/discard", Map.of(), owner);
        assertThat(gone.body().get("status")).as(gone.body().toString()).isEqualTo("DISCARDED");
        assertThat(get("/kirana/purchases/" + bill, owner).body().get("status")).isEqualTo("DISCARDED");
        assertThat(stock(milk)).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM files WHERE purpose = 'BILL'", Integer.class)).isZero();
    }

    @Test
    void twoCopiesOfOneBillAddedAtTheSameMomentAddItsStockOnce() throws Exception {
        String owner = kirana(OWNER, "Sharma General Store");
        String helper = helperOf(owner, HELPER);
        String milk = id(post("/kirana/products", coded("Amul Taaza Toned Milk 500 ml", 2900, "0",
                ean("890126215001")), owner));
        String facts = purchase("Sharma Agencies", "INV-9", "[" + billLine("AMUL TAZA 500ML", "Amul",
                "Taaza Toned Milk", "500 ml", "24", "26", "29") + "]");
        ANSWERS.put("copy-1", facts);
        ANSWERS.put("copy-2", facts);
        Reply r1 = post("/kirana/reads", newRead("PURCHASE", upload(owner, "BILL", "copy-1")), owner);
        Reply r2 = post("/kirana/reads", newRead("PURCHASE", upload(helper, "BILL", "copy-2")), helper);
        readJob.run();
        String p1 = (String) get("/kirana/reads/" + id(r1), owner).body().get("purchaseId");
        String p2 = (String) get("/kirana/reads/" + id(r2), owner).body().get("purchaseId");
        AtomicInteger n = new AtomicInteger();
        List<Reply> replies = together(2, () -> {
            String p = n.getAndIncrement() == 0 ? p1 : p2;
            return post("/kirana/purchases/" + p + "/add", Map.of("version", 0), owner);
        });
        assertThat(replies.stream().map(Reply::status).sorted().toList()).containsExactly(200, 409);
        List<Object> codes = new ArrayList<>(replies.stream().map(x -> (Object) x.body().get("code"))
                .filter(java.util.Objects::nonNull).toList());
        assertThat(codes).containsExactly("BILL_ALREADY_ADDED");
        assertThat(stock(milk)).isEqualByComparingTo("24");
    }

    @Test
    void aShopsDailyReadsWaitForTomorrowWithoutLosingATry() {
        String owner = kirana(OWNER, "Sharma General Store");
        jdbc.update("INSERT INTO kirana_shop_ai_calls (shop_id, day, calls) VALUES (?, ?, 30)", shopId(owner),
                LocalDate.now(ZoneId.of("Asia/Kolkata")));
        ANSWERS.put("late", purchase("Sharma Agencies", "1", "[" + billLine("SUGAR 1KG", null, "Sugar", "1 kg", "1",
                "48", "52") + "]"));
        Reply read = post("/kirana/reads", newRead("PURCHASE", upload(owner, "BILL", "late")), owner);
        readJob.run();
        assertThat(get("/kirana/reads/" + id(read), owner).body().get("status")).isEqualTo("READING");
        assertThat(AI_CALLS.get()).isZero();
        assertThat(jdbc.queryForObject("SELECT tries FROM kirana_reads WHERE id = ?::uuid", Integer.class, id(read)))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT next_try_at > now() + interval '1 minute' FROM kirana_reads "
                + "WHERE id = ?::uuid", Boolean.class, id(read))).isTrue();
    }

    @Test
    void aPhotoTwoProductsShareIsKeptWhenOneChangesItsPhoto() {
        String owner = kirana(OWNER, "Sharma General Store");
        String a = id(post("/kirana/products", product("Rice 1 kg", "PIECE", 6000, "0"), owner));
        String b = id(post("/kirana/products", product("Rice 5 kg", "PIECE", 28000, "0"), owner));
        String shared = upload(owner, "PRODUCT_PHOTO", "rice-bag");
        assertThat(put("/kirana/products/" + a + "/photo", Map.of("clientRef", UUID.randomUUID().toString(),
                "fileId", shared), owner).status()).isEqualTo(200);
        Reply onB = put("/kirana/products/" + b + "/photo", Map.of("clientRef", UUID.randomUUID().toString(),
                "fileId", shared), owner);
        assertThat(onB.body().get("photoUrl")).as(onB.body().toString()).isNotNull();
        put("/kirana/products/" + a + "/photo", Map.of("clientRef", UUID.randomUUID().toString(),
                "fileId", upload(owner, "PRODUCT_PHOTO", "rice-bag-2")), owner);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM files WHERE id = ?::uuid", Integer.class, shared))
                .isEqualTo(1);
        // ... and can't be deleted from the files screen while used
        String signedIn = token(OWNER);   // a token naming the shop (the first was made before it was registered)
        assertThat(http.delete().uri("/files/" + shared).headers(h -> h.setBearerAuth(signedIn))
                .exchange((rq, res) -> reply(res)).status()).isEqualTo(409);
    }

    // ---------- unknown barcodes ----------

    @Test
    void anUnknownPacksLabelIsReadOnceAcrossShopsAndOneTapLinksItWithItsScansInStock() {
        String owner = kirana(OWNER, "Sharma General Store");
        String other = kirana(OTHER_OWNER, "Gupta Kirana");
        String code = ean("890177700001");
        String biscuit = id(post("/kirana/products", product("Parle G Biscuit 70 g", "PIECE", 1000, "0"), owner));

        Reply scan = post("/kirana/scans", Map.of("clientRef", UUID.randomUUID().toString(),
                "scans", List.of(Map.of("code", code, "times", 5))), owner);
        Map<?, ?> result = (Map<?, ?>) ((List<?>) scan.body().get("results")).get(0);
        assertThat(result.get("outcome")).isEqualTo("UNKNOWN");
        assertThat(result.get("needsLabel")).isEqualTo(true);
        String unknown = (String) result.get("unknownId");

        ANSWERS.put("frame-1", "{\"readable\":true,\"product\":true,\"brand\":\"Parle\",\"name\":\"G Biscuit\","
                + "\"size\":\"70 g\",\"mrp\":10,\"category\":\"snacks\"}");
        Reply label = post("/kirana/scans/unknown/" + unknown + "/label", Map.of("clientRef",
                UUID.randomUUID().toString(), "fileId", upload(owner, "PRODUCT_PHOTO", "frame-1")), owner);
        assertThat(label.status()).as(label.body().toString()).isEqualTo(200);
        readJob.run();
        assertThat(AI_CALLS.get()).isEqualTo(1);

        Reply needs = get("/kirana/scans/unknown", owner);
        Map<?, ?> first = (Map<?, ?>) ((List<?>) needs.body().get("items")).get(0);
        assertThat(((Map<?, ?>) first.get("label")).get("name")).isEqualTo("G Biscuit");
        assertThat(first.get("sure")).isEqualTo(true);
        assertThat(pluck(first.get("suggestions"), "productId").get(0)).isEqualTo(biscuit);

        // Another shop scanning the same pack reuses the read: no photo asked, no AI call
        Reply theirs = post("/kirana/scans", Map.of("clientRef", UUID.randomUUID().toString(),
                "scans", List.of(Map.of("code", code))), other);
        assertThat(((Map<?, ?>) ((List<?>) theirs.body().get("results")).get(0)).get("needsLabel")).isEqualTo(false);
        Map<?, ?> theirsListed = (Map<?, ?>) ((List<?>) get("/kirana/scans/unknown", other).body().get("items"))
                .get(0);
        assertThat(((Map<?, ?>) theirsListed.get("label")).get("brand")).isEqualTo("Parle");
        assertThat(AI_CALLS.get()).isEqualTo(1);

        Reply linked = post("/kirana/scans/unknown/" + unknown + "/link", Map.of("productId", biscuit,
                "oneTap", true), owner);
        assertThat(linked.status()).as(linked.body().toString()).isEqualTo(200);
        assertThat(linked.body().get("quantity")).isEqualTo(5);
        assertThat(linked.body().get("needsPhoto")).isEqualTo(true);
        assertThat(stock(biscuit)).isEqualByComparingTo("5");
        assertThat(post("/kirana/scans/unknown/" + unknown + "/link", Map.of("productId", biscuit), owner).body()
                .get("code")).isEqualTo("UNKNOWN_DONE");
        // The next scan of it is a known product
        Reply next = post("/kirana/scans", Map.of("clientRef", UUID.randomUUID().toString(),
                "scans", List.of(Map.of("code", code))), owner);
        assertThat(pluck(next.body().get("results"), "outcome")).containsExactly("ADDED");

        // The other shop's list now offers the catalogue item the first shop linked
        Map<?, ?> offered = (Map<?, ?>) ((List<?>) get("/kirana/scans/unknown", other).body().get("items")).get(0);
        assertThat(((Map<?, ?>) offered.get("catalogue")).get("name")).isEqualTo("Parle G Biscuit 70 g");
        assertThat(offered.get("needsLabel")).isEqualTo(false);

        // ... and adding it as a new product takes the catalogue's name and links its barcode
        Reply made = post("/kirana/scans/unknown/" + offered.get("id") + "/link", Map.of("itemId",
                ((Map<?, ?>) offered.get("catalogue")).get("itemId"), "pricePaise", 1000, "unit", "PIECE"), other);
        assertThat(made.status()).as(made.body().toString()).isEqualTo(200);
        assertThat(((Map<?, ?>) made.body().get("product")).get("name")).isEqualTo("Parle G Biscuit 70 g");
        assertThat(jdbc.queryForObject("SELECT confirmed FROM kirana_catalogue_barcodes WHERE code = ?",
                Boolean.class, code)).isTrue();   // two shops agree: trusted

        String admin = superAdmin();
        List<?> stats = get("/admin/kirana/scans/stats?days=7", admin).list();
        Map<?, ?> today = (Map<?, ?>) stats.get(0);
        assertThat(((Number) today.get("scans")).longValue()).isEqualTo(7);
        assertThat(((Number) today.get("unknown")).longValue()).isEqualTo(6);
        assertThat(((Number) today.get("sure")).longValue()).isEqualTo(1);
        assertThat(((Number) today.get("created")).longValue()).isEqualTo(1);
    }

    @Test
    void anUnknownBarcodeCanBeDismissedAndAnotherShopsUnknownsStayPrivate() {
        String owner = kirana(OWNER, "Sharma General Store");
        String other = kirana(OTHER_OWNER, "Gupta Kirana");
        Reply scan = post("/kirana/scans", Map.of("clientRef", UUID.randomUUID().toString(),
                "scans", List.of(Map.of("code", ean("890177700002")))), owner);
        String unknown = (String) ((Map<?, ?>) ((List<?>) scan.body().get("results")).get(0)).get("unknownId");
        assertThat(get("/kirana/scans/unknown/" + unknown, other).body().get("code")).isEqualTo("UNKNOWN_NOT_FOUND");
        assertThat(post("/kirana/scans/unknown/" + unknown + "/dismiss", Map.of(), other).status()).isEqualTo(404);
        assertThat(post("/kirana/scans/unknown/" + unknown + "/dismiss", Map.of(), owner).status()).isEqualTo(204);
        assertThat((List<?>) get("/kirana/scans/unknown", owner).body().get("items")).isEmpty();
    }

    // ---------- owner corrections ----------

    @Test
    void theOwnerMovesAWrongBarcodeAndItsStockAndTheCatalogueFollowsAShopOnlyLink() {
        String owner = kirana(OWNER, "Sharma General Store");
        String helper = helperOf(owner, HELPER);
        String code = ean("890126215001");
        String wrong = id(post("/kirana/products", coded("Amul Gold 500 ml", 3300, "0", code), owner));
        String right = id(post("/kirana/products", product("Amul Taaza 500 ml", "PIECE", 2900, "2"), owner));
        post("/kirana/scans", Map.of("clientRef", UUID.randomUUID().toString(),
                "scans", List.of(Map.of("code", code, "times", 6))), owner);
        assertThat(stock(wrong)).isEqualByComparingTo("6");

        assertThat(post("/kirana/products/" + wrong + "/move-barcode", Map.of("code", code, "toProductId", right),
                helper).status()).isEqualTo(403);
        Reply moved = post("/kirana/products/" + wrong + "/move-barcode", Map.of("code", code, "toProductId", right),
                owner);
        assertThat(moved.status()).as(moved.body().toString()).isEqualTo(200);
        assertThat(moved.body().get("suggestedQuantity")).isEqualTo(6);
        // The catalogue had only this shop's say: it follows
        assertThat(jdbc.queryForObject("SELECT i.name FROM kirana_catalogue_barcodes b JOIN kirana_catalogue_items i "
                + "ON i.id = b.item_id WHERE b.code = ?", String.class, code)).isEqualTo("Amul Taaza 500 ml");

        UUID ref = UUID.randomUUID();
        Map<String, Object> move = Map.of("clientRef", ref.toString(), "toProductId", right, "quantity", 6);
        assertThat(post("/kirana/products/" + wrong + "/move-stock", move, owner).status()).isEqualTo(200);
        assertThat(post("/kirana/products/" + wrong + "/move-stock", move, owner).status()).isEqualTo(200);   // retry
        assertThat(stock(wrong)).isEqualByComparingTo("0");
        assertThat(stock(right)).isEqualByComparingTo("8");

        String loose = id(post("/kirana/products", product("Rice", "KG", 6000, "1"), owner));
        assertThat(post("/kirana/products/" + right + "/move-stock", Map.of("clientRef", UUID.randomUUID().toString(),
                "toProductId", loose, "quantity", 1), owner).body().get("code")).isEqualTo("UNITS_DIFFER");
    }

    // ---------- the shared catalogue ----------

    @Test
    void shopsThatDisagreeOnABarcodeGoToTheReviewListAndTheTeamDecides() {
        String a = kirana(OWNER, "Sharma General Store");
        String b = kirana(OTHER_OWNER, "Gupta Kirana");
        String code = ean("890126215009");
        id(post("/kirana/products", coded("Amul Taaza 500 ml", 2900, "0", code), a));
        // b's product has its own catalogue item (from another barcode of it)
        String bProduct = id(post("/kirana/products", coded("Mother Dairy Toned 500 ml", 2800, "0",
                ean("890400000001")), b));
        // b's shop says it is another item: a review, and b keeps its own link
        assertThat(post("/kirana/products/" + bProduct + "/barcodes", Map.of("code", code), b).status())
                .isEqualTo(200);
        String admin = superAdmin();
        List<?> reviews = get("/admin/kirana/catalogue/reviews", admin).list();
        assertThat(pluck(reviews, "kind")).containsExactly("CONFLICT");
        Map<?, ?> review = (Map<?, ?>) reviews.get(0);
        Object other = ((Map<?, ?>) review.get("other")).get("id");
        assertThat(post("/admin/kirana/catalogue/reviews/" + review.get("id"), Map.of("itemId", other), admin)
                .status()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT source || ':' || confirmed FROM kirana_catalogue_barcodes WHERE code = ?",
                String.class, code)).isEqualTo("ADMIN:true");
        assertThat(post("/admin/kirana/catalogue/reviews/" + review.get("id"), Map.of("itemId", other), admin).body()
                .get("code")).isEqualTo("REVIEW_DONE");
        assertThat(post("/admin/kirana/catalogue/reviews/" + review.get("id"), Map.of("itemId", other), a).status())
                .isEqualTo(403);
    }

    @Test
    void aProductMasterImportAddsTrustedBarcodesListsBadRowsAndReviewsClashes() {
        String shop = kirana(OWNER, "Sharma General Store");
        String clash = ean("890126215003");
        id(post("/kirana/products", coded("Amul Taaza 500 ml", 2900, "0", clash), shop));
        String admin = superAdmin();
        String csv = "Barcode,Item Name,Brand,Size,MRP\n"
                + ean("890100000001") + ",\"Good Day Cashew, 100 g\",Britannia,100 g,30\n"
                + ean("890100000003").substring(0, 12) + "0,Wrong check digit,,,\n"
                + ean("890100000002") + ",,Parle,70 g,10\n"
                + clash + ",Head & Shoulders Shampoo,P&G,180 ml,320\n";
        org.springframework.util.LinkedMultiValueMap<String, Object> form = new org.springframework.util.LinkedMultiValueMap<>();
        form.add("file", new org.springframework.core.io.ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "master.csv";
            }
        });
        Reply r = http.post().uri("/admin/kirana/catalogue/import").headers(h -> h.setBearerAuth(admin))
                .contentType(MediaType.MULTIPART_FORM_DATA).body(form).exchange((rq, res) -> reply(res));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(r.body().get("added")).isEqualTo(1);
        assertThat(r.body().get("reviews")).isEqualTo(1);
        assertThat(r.body().get("problemCount")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT source || ':' || confirmed FROM kirana_catalogue_barcodes WHERE code = ?",
                String.class, ean("890100000001"))).isEqualTo("MASTER:true");
        // A shop scanning it gets the trusted name
        Reply known = get("/kirana/catalogue/barcode/" + ean("890100000001"), shop);
        assertThat(known.body().get("name")).as(known.body().toString()).isEqualTo("Good Day Cashew, 100 g");
        assertThat(known.body().get("trusted")).isEqualTo(true);
    }

    // ---------- counter suggestions ----------

    @Test
    void theCounterSuggestsWhatSellsNowTheCustomersUsualAndWhatGoesTogether() {
        String owner = kirana(OWNER, "Sharma General Store");
        String milk = id(post("/kirana/products", product("Milk 500 ml", "PIECE", 2900, "50"), owner));
        String bread = id(post("/kirana/products", product("Bread", "PIECE", 4500, "50"), owner));
        String eggs = id(post("/kirana/products", product("Eggs 6", "PIECE", 4200, "1"), owner));
        String jam = id(post("/kirana/products", product("Jam", "PIECE", 9000, "5"), owner));
        Reply khata = post("/kirana/khata", Map.of("name", "Ravi Kumar"), owner);
        String ravi = id(khata);
        for (int i = 0; i < 3; i++) {
            bill(owner, List.of(line(milk, "1"), line(bread, "1")), "CASH", null);
        }
        // Sold too, but now out of stock (eggs) or removed (jam): never offered
        bill(owner, List.of(line(eggs, "1"), line(jam, "1")), "CASH", null);
        unlockEditing(owner);
        Reply removed = patch("/kirana/products/" + jam, Map.of("version", 0, "active", false), owner);
        assertThat(removed.status()).as(removed.body().toString()).isEqualTo(200);
        bill(owner, List.of(line(bread, "2")), "KHATA", ravi);
        Reply s = get("/kirana/suggestions?khataCustomerId=" + ravi + "&with=" + milk, owner);
        assertThat(s.status()).as(s.body().toString()).isEqualTo(200);
        assertThat(pluck(s.body().get("now"), "id")).containsExactly(bread);   // milk is on the bill already
        assertThat(pluck(get("/kirana/suggestions", owner).body().get("now"), "id"))
                .containsExactlyInAnyOrder(milk, bread);
        assertThat(pluck(s.body().get("usual"), "id")).containsExactly(bread);
        assertThat(pluck(s.body().get("together"), "id")).containsExactly(bread);
        assertThat(pluck(s.body().get("now"), "id")).doesNotContain(eggs, jam);
    }

    // ---------- paper bills ----------

    @Test
    void aPaperBillBecomesABillWithItsDayAndPhotoOnceEvenWhenConfirmedTwice() {
        String owner = kirana(OWNER, "Sharma General Store");
        String milk = id(post("/kirana/products", product("Amul Taaza Toned Milk 500 ml", "PIECE", 2900, "10"),
                owner));
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        ANSWERS.put("paper", "{\"readable\":true,\"bill\":true,\"date\":\"" + today.minusDays(2) + "\",\"total\":58,"
                + "\"lines\":[{\"wording\":\"taza dudh\",\"brand\":\"Amul\",\"name\":\"Taaza Toned Milk\","
                + "\"size\":\"500 ml\",\"quantity\":2,\"byWeight\":false,\"price\":29,\"amount\":58}]}");
        Reply read = post("/kirana/reads", newRead("PAPER_BILL", upload(owner, "BILL", "paper")), owner);
        readJob.run();
        Reply draft = get("/kirana/paper-bills/" + id(read), owner);
        assertThat(draft.body().get("writtenOn")).isEqualTo(today.minusDays(2).toString());
        assertThat(pluck(draft.body().get("lines"), "productId")).containsExactly(milk);

        Map<String, Object> confirm = new HashMap<>();
        confirm.put("writtenOn", today.minusDays(8).toString());
        confirm.put("lines", List.of(line(milk, "2")));
        confirm.put("payment", "CASH");
        assertThat(post("/kirana/paper-bills/" + id(read) + "/confirm", confirm, owner).status()).isEqualTo(400);
        confirm.put("writtenOn", today.minusDays(2).toString());
        Reply made = post("/kirana/paper-bills/" + id(read) + "/confirm", confirm, owner);
        assertThat(made.status()).as(made.body().toString()).isEqualTo(200);
        Map<?, ?> bill = (Map<?, ?>) made.body().get("bill");
        assertThat(bill.get("writtenOn")).isEqualTo(today.minusDays(2).toString());
        assertThat(bill.get("paperPhotoFileId")).isNotNull();
        assertThat(stock(milk)).isEqualByComparingTo("8");
        Reply again = post("/kirana/paper-bills/" + id(read) + "/confirm", confirm, owner);
        assertThat(again.body().get("repeated")).isEqualTo(true);
        assertThat(stock(milk)).isEqualByComparingTo("8");
        assertThat(get("/kirana/reads/" + id(read), owner).body().get("billId")).isEqualTo(bill.get("id"));
    }

    // ---------- helpers ----------

    /** Editing unlocked for this sign-in, as if its owner had entered the code (EditUnlockStore: user and session). */
    private void unlockEditing(String accessToken) {
        String payload = new String(java.util.Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]),
                StandardCharsets.UTF_8);
        Matcher sub = Pattern.compile("\"sub\"\\s*:\\s*\"([^\"]+)\"").matcher(payload);
        Matcher sid = Pattern.compile("\"sid\"\\s*:\\s*\"([^\"]+)\"").matcher(payload);
        String user = sub.find() ? sub.group(1) : "";
        String session = sid.find() ? sid.group(1) : "-";
        redis.opsForValue().set("edit-unlock:" + user + ":" + session,
                Long.toString(java.time.Instant.parse("9999-01-01T00:00:00Z").toEpochMilli()),
                java.time.Duration.ofHours(1));
    }

    /** An EAN-13 with its check digit. */
    static String ean(String twelve) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int d = twelve.charAt(i) - '0';
            sum += i % 2 == 0 ? d : d * 3;
        }
        return twelve + ((10 - sum % 10) % 10);
    }

    private static String purchase(String distributor, String billNo, String lines) {
        return "{\"readable\":true,\"bill\":true,\"distributor\":\"" + distributor + "\",\"billNo\":\"" + billNo
                + "\",\"billDate\":\"2026-11-15\",\"total\":1000,\"lines\":" + lines + "}";
    }

    private static String billLine(String wording, String brand, String name, String size, String qty, String rate,
                                   String mrp) {
        return "{\"wording\":\"" + wording + "\",\"brand\":" + (brand == null ? "null" : "\"" + brand + "\"")
                + ",\"name\":\"" + name + "\",\"size\":\"" + size + "\",\"quantity\":" + qty + ",\"rate\":" + rate
                + ",\"mrp\":" + mrp + ",\"amount\":100}";
    }

    private static Map<String, Object> newRead(String kind, String fileId) {
        return Map.of("clientRef", UUID.randomUUID().toString(), "kind", kind, "fileIds", List.of(fileId));
    }

    /** A file this shop uploaded: its bytes are the text (what the fake AI answers to). */
    private String upload(String token, String purpose, String text) {
        return upload(token, purpose, text.getBytes(StandardCharsets.UTF_8), "image/jpeg");
    }

    /** A file this shop uploaded, of this type. */
    private String upload(String token, String purpose, byte[] bytes, String contentType) {
        UUID shop = shopId(token);
        UUID id = UUID.randomUUID();
        String key = "shops/" + shop + "/" + purpose.toLowerCase() + "/" + id;
        storage.put(key, new ByteArrayInputStream(bytes), bytes.length, contentType);
        UUID owner = jdbc.queryForObject("SELECT user_id FROM shop_members WHERE shop_id = ? AND role = 'OWNER'",
                UUID.class, shop);
        jdbc.update("INSERT INTO files (id, shop_id, purpose, storage_key, content_type, size_bytes, sha256, "
                        + "uploaded_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())", id, shop,
                purpose, key, contentType, bytes.length, id.toString().replace("-", "") + "0".repeat(32), owner);
        return id.toString();
    }

    private void bill(String token, List<Map<String, Object>> lines, String payment, String khataId) {
        Map<String, Object> b = new HashMap<>();
        b.put("clientRef", UUID.randomUUID().toString());
        b.put("lines", lines);
        b.put("payment", payment);
        if (khataId != null) {
            b.put("khataCustomerId", khataId);
        }
        Reply r = post("/kirana/bills", b, token);
        assertThat(r.status()).as(r.body().toString()).isIn(200, 201);
    }

    private String kirana(String phone, String name) {
        String access = token(phone);
        Map<String, Object> shop = new HashMap<>();
        shop.put("name", name);
        shop.put("ownerName", "Ramesh Sharma");
        shop.put("addressLine", "Shop 4, Green Meadows main gate");
        shop.put("area", "Kompally");
        shop.put("city", "Hyderabad");
        shop.put("pincode", "500014");
        shop.put("latitude", new BigDecimal("17.535123"));
        shop.put("longitude", new BigDecimal("78.484456"));
        shop.put("type", "KIRANA");
        Reply r = post("/shops", shop, access);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        TestData.kiranaUpiId(jdbc, shopId(access));
        return access;
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

    private BigDecimal stock(String productId) {
        return jdbc.queryForObject("SELECT stock FROM kirana_products WHERE id = ?::uuid", BigDecimal.class, productId);
    }

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

    private static List<Object> pluck(Object jsonArray, String field) {
        List<Object> out = new ArrayList<>();
        for (Object o : (List<?>) jsonArray) {
            out.add(((Map<?, ?>) o).get(field));
        }
        return out;
    }

    private Reply post(String path, Object body, String token) {
        return http.post().uri(path).headers(h -> bearer(h, token)).contentType(MediaType.APPLICATION_JSON)
                .body(body).exchange((rq, res) -> reply(res));
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
