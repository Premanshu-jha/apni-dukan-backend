package in.apnidukan.kirana;

import com.sun.net.httpserver.HttpServer;
import in.apnidukan.kirana.service.DataKart;
import in.apnidukan.kirana.service.Gs1Job;
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

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static in.apnidukan.support.CapturingSenders.LAST_SMS;
import static in.apnidukan.support.CapturingSenders.codeSentTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * GS1 DataKart (V79–V80) end to end against real Postgres, Redis and S3Mock, a fake DataKart and a fake AI: a
 * catalogue item's official picture by its barcode (checked against the item, then shared with every shop), GS1's
 * product refused when it isn't ours, a barcode found by name for a product added without one (only on one clear
 * match), and nothing looked up again too soon.
 */
@EnabledIf("in.apnidukan.support.TestContainers#dockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CapturingSenders.class)
class Gs1IntegrationTest {

    private static final String OWNER = "9848066001";
    private static final String OTHER_OWNER = "9848066002";

    /** What the fake DataKart answers, by path (with its query); missing: 404. */
    static final Map<String, String> GS1 = new ConcurrentHashMap<>();
    static final AtomicInteger GS1_CALLS = new AtomicInteger();
    /** What the fake AI says it saw in any picture. */
    static final AtomicReference<String> SEEN = new AtomicReference<>("{}");
    static final AtomicInteger AI_CALLS = new AtomicInteger();
    /** Answers the fake AI gives first, one per call, before {@link #SEEN}. */
    static final java.util.Queue<String> SEEN_NEXT = new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** The fake DataKart refuses every key (as a wrong GS1_DATAKART_KEY). */
    static final java.util.concurrent.atomic.AtomicBoolean REFUSE_KEY = new java.util.concurrent.atomic.AtomicBoolean();
    private static final byte[] PICTURE = picture();
    private static final HttpServer DATAKART = server(true);
    private static final HttpServer AI = server(false);

    private static HttpServer server(boolean gs1) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                byte[] out;
                int status = 200;
                String type = "application/json";
                if (gs1) {
                    GS1_CALLS.incrementAndGet();
                    String path = exchange.getRequestURI().getPath().replaceFirst("^/api", "")
                            + (exchange.getRequestURI().getRawQuery() == null ? ""
                            : "?" + exchange.getRequestURI().getRawQuery());
                    boolean authorised = "Bearer gs1-key".equals(exchange.getRequestHeaders()
                            .getFirst("Authorization"));
                    if (path.startsWith("/pictures/")) {
                        out = PICTURE;
                        type = "image/png";
                    } else if (!authorised || REFUSE_KEY.get()) {
                        out = "{}".getBytes(StandardCharsets.UTF_8);
                        status = 401;
                    } else if (GS1.containsKey(path) && GS1.get(path).startsWith("!")) {
                        status = Integer.parseInt(GS1.get(path).substring(1));   // "!500": that status
                        out = "{}".getBytes(StandardCharsets.UTF_8);
                    } else if (GS1.containsKey(path)) {
                        out = GS1.get(path).getBytes(StandardCharsets.UTF_8);
                    } else {
                        out = "{}".getBytes(StandardCharsets.UTF_8);
                        status = 404;
                    }
                } else {
                    AI_CALLS.incrementAndGet();
                    exchange.getRequestBody().readAllBytes();
                    out = ("{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":"
                            + quote(java.util.Optional.ofNullable(SEEN_NEXT.poll()).orElseGet(SEEN::get)) + "}]}")
                            .getBytes(StandardCharsets.UTF_8);
                }
                exchange.getResponseHeaders().add("Content-Type", type);
                exchange.sendResponseHeaders(status, out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A real small PNG (pictures are drawn again as a JPEG before they are stored). */
    private static byte[] picture() {
        BufferedImage image = new BufferedImage(120, 160, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, 120, 160);
        g.dispose();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String gs1Url() {
        return "http://127.0.0.1:" + DATAKART.getAddress().getPort() + "/api";
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        TestContainers.register(registry, true); // has kirana cases: kirana on
        registry.add("app.kirana.ai-url", () -> "http://127.0.0.1:" + AI.getAddress().getPort() + "/v1/messages");
        registry.add("app.kirana.ai-key", () -> "test-key");
        registry.add("app.kirana.gs1.url", Gs1IntegrationTest::gs1Url);
        registry.add("app.kirana.gs1.key", () -> "gs1-key");
        registry.add("app.kirana.jobs-delay-ms", () -> "3600000");   // the tests run the job themselves
    }

    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired Gs1Job job;

    RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.builder()
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + environment.getProperty("local.server.port") + "/api/v1")
                .build();
        TestData.clean(jdbc);
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        LAST_SMS.clear();
        GS1.clear();
        GS1_CALLS.set(0);
        AI_CALLS.set(0);
        SEEN_NEXT.clear();
        REFUSE_KEY.set(false);
    }

    @Test
    void aCatalogueItemGetsGs1sOfficialPictureCheckedAgainstItAndEveryShopSellingItSeesIt() {
        String owner = kirana(OWNER, "Sharma General Store");
        String code = KiranaStockIntegrationTest.ean("890126215001");
        String milk = id(post("/kirana/products", coded("Amul Taaza Toned Milk 500 ml", code), owner));
        GS1.put("/products/" + code, "{\"gtin\":\"" + code + "\",\"brandName\":\"Amul\",\"productDescription\":"
                + "\"Taaza Toned Milk\",\"netContent\":\"500\",\"netContentUom\":\"ML\",\"productImageUrl\":\""
                + gs1Url() + "/pictures/" + code + ".png\"}");
        SEEN.set(seen("Amul", "Taaza Toned Milk", "500 ml", true));
        assertThat(get("/kirana/setup", owner).body().get("photosMissing")).isEqualTo(1);

        job.run();
        assertThat(AI_CALLS.get()).isEqualTo(1);
        assertThat(status(code)).isEqualTo("FOUND");
        Map<?, ?> product = (Map<?, ?>) get("/kirana/products/" + milk, owner).body().get("product");
        assertThat(product.get("photoUrl")).as(product.toString()).isNotNull();
        assertThat(get("/kirana/setup", owner).body().get("photosMissing")).isEqualTo(0);
        // Stored as the platform's own picture (no shop's), and GS1's brand filled in
        assertThat(jdbc.queryForObject("SELECT f.purpose || ':' || (f.shop_id IS NULL) FROM kirana_catalogue_items i "
                + "JOIN files f ON f.id = i.photo_file_id", String.class)).isEqualTo("CATALOGUE_PHOTO:true");
        assertThat(jdbc.queryForObject("SELECT brand FROM kirana_catalogue_items", String.class)).isEqualTo("Amul");

        // Another shop adding the same pack has its picture at once: nothing more is looked up
        String other = kirana(OTHER_OWNER, "Gupta Kirana");
        post("/kirana/products", coded("Amul Taaza 500 ml", code), other);
        assertThat(get("/kirana/setup", other).body().get("photosMissing")).isEqualTo(0);
        int calls = GS1_CALLS.get();
        job.run();
        assertThat(GS1_CALLS.get()).isEqualTo(calls);
    }

    @Test
    void gs1sProductOrPictureThatIsntOursIsRefusedAndOneNotKnownIsLookedUpAgainAMonthLater() {
        String owner = kirana(OWNER, "Sharma General Store");
        String gold = KiranaStockIntegrationTest.ean("890126215002");
        String curd = KiranaStockIntegrationTest.ean("890126215003");
        String unknown = KiranaStockIntegrationTest.ean("890126215004");
        String elsewhere = KiranaStockIntegrationTest.ean("890126215005");
        post("/kirana/products", coded("Amul Taaza Toned Milk 500 ml", gold), owner);
        post("/kirana/products", coded("Amul Masti Dahi 400 g", curd), owner);
        post("/kirana/products", coded("Haldiram Bhujia 200 g", unknown), owner);
        post("/kirana/products", coded("Parle G 70 g", elsewhere), owner);
        // GS1 says the barcode is another size
        GS1.put("/products/" + gold, "{\"brandName\":\"Amul\",\"productName\":\"Gold Milk\",\"netContent\":\"1 L\","
                + "\"productImageUrl\":\"" + gs1Url() + "/pictures/gold.png\"}");
        // The right product, but its picture shows something else
        GS1.put("/products/" + curd, "{\"brandName\":\"Amul\",\"productName\":\"Masti Dahi\",\"netContent\":\"400 g\","
                + "\"productImageUrl\":\"" + gs1Url() + "/pictures/curd.png\"}");
        // A picture on a host DataKart may not send us to
        GS1.put("/products/" + elsewhere, "{\"brandName\":\"Parle\",\"productName\":\"G Biscuit\",\"netContent\":"
                + "\"70 g\",\"productImageUrl\":\"https://pictures.example.com/parle.png\"}");
        SEEN.set(seen("Amul", "Gold Milk", "1 L", false));

        job.run();
        assertThat(status(gold)).isEqualTo("REFUSED");
        assertThat(note(gold)).contains("1 L");
        assertThat(status(curd)).isEqualTo("REFUSED");
        assertThat(note(curd)).startsWith("GS1's picture");
        assertThat(status(unknown)).isEqualTo("NOT_FOUND");
        assertThat(status(elsewhere)).isEqualTo("NOT_FOUND");   // "no picture" it may fetch
        assertThat(AI_CALLS.get()).isEqualTo(1);   // only the curd's picture was checked
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kirana_catalogue_items WHERE photo_file_id IS NOT NULL",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM files WHERE purpose = 'CATALOGUE_PHOTO'", Integer.class))
                .isZero();   // the refused picture was thrown away
        // Not looked up again before a month
        int calls = GS1_CALLS.get();
        job.run();
        assertThat(GS1_CALLS.get()).isEqualTo(calls);
        assertThat(jdbc.queryForObject("SELECT next_try_at > now() + interval '29 days' FROM kirana_gs1_lookups "
                + "WHERE code = ?", Boolean.class, unknown)).isTrue();
    }

    @Test
    void aProductAddedWithoutABarcodeIsFoundByNameOnlyOnOneClearMatchAndGetsItsPicture() {
        String owner = kirana(OWNER, "Sharma General Store");
        String salt = id(post("/kirana/products", plain("Tata Salt 1 kg"), owner));
        String sugar = id(post("/kirana/products", plain("Madhur Sugar 1 kg"), owner));
        String code = KiranaStockIntegrationTest.ean("890400440020");
        String lite = KiranaStockIntegrationTest.ean("890400440021");
        GS1.put("/products?q=Tata+Salt+1+kg", "{\"products\":[{\"gtin\":\"" + code + "\",\"brandName\":\"Tata\","
                + "\"productName\":\"Salt\",\"netContent\":\"1 kg\"},{\"gtin\":\"" + lite + "\",\"brandName\":\"Tata\","
                + "\"productName\":\"Salt Lite Low Sodium Iodised\",\"netContent\":\"1 kg\"},{\"gtin\":\"1\",\"productName\":\"Tata Salt 1 kg\"}]}");
        // Two products as likely: never a guess
        GS1.put("/products?q=Madhur+Sugar+1+kg", "{\"products\":[{\"gtin\":\"" + KiranaStockIntegrationTest
                .ean("890400440030") + "\",\"brandName\":\"Madhur\",\"productName\":\"Sugar\",\"netContent\":\"1 kg\"},"
                + "{\"gtin\":\"" + KiranaStockIntegrationTest.ean("890400440030") + "\",\"brandName\":\"Madhur\","
                + "\"productName\":\"Sugar\",\"netContent\":\"1 kg\"},"
                + "{\"gtin\":\"" + KiranaStockIntegrationTest.ean("890400440031") + "\",\"brandName\":\"Madhur\","
                + "\"productName\":\"Sugar\",\"netContent\":\"1 kg\"}]}");
        GS1.put("/products/" + code, "{\"gtin\":\"" + code + "\",\"brandName\":\"Tata\",\"productName\":\"Salt\","
                + "\"netContent\":\"1 kg\",\"images\":[{\"url\":\"" + gs1Url() + "/pictures/salt.png\"}]}");
        SEEN.set(seen("Tata", "Salt", "1 kg", true));
        // Before GS1 has looked: nothing for the shop to do yet for these two ("Finding its picture…")
        Map<?, ?> setup = get("/kirana/setup", owner).body();
        assertThat(setup.get("photosMissing")).isEqualTo(2);
        assertThat(setup.get("photosFinding")).isEqualTo(2);
        assertThat(setup.get("nextStep")).as(setup.toString()).isEqualTo("WAIT");
        assertThat(((Map<?, ?>) get("/kirana/products/" + salt, owner).body().get("product")).get("findingPicture"))
                .isEqualTo(true);

        job.run();   // by name: the barcode found, the product linked to its catalogue item
        assertThat(jdbc.queryForObject("SELECT code FROM kirana_gs1_lookups WHERE product_id = ?::uuid", String.class,
                salt)).isEqualTo(code);
        assertThat(jdbc.queryForObject("SELECT b.source || ':' || b.confirmed FROM kirana_catalogue_barcodes b "
                + "WHERE b.code = ?", String.class, code)).isEqualTo("GS1:true");
        assertThat(jdbc.queryForObject("SELECT catalogue_item_id IS NOT NULL FROM kirana_products WHERE id = ?::uuid",
                Boolean.class, salt)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM kirana_gs1_lookups WHERE product_id = ?::uuid",
                String.class, sugar)).isEqualTo("NOT_FOUND");
        // The product's own barcodes stay the shop's to set (its first scan offers this one in a tap)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kirana_barcodes WHERE product_id = ?::uuid",
                Integer.class, salt)).isZero();

        // Sugar wasn't found: its photo is the shop's to take now
        setup = get("/kirana/setup", owner).body();
        assertThat(setup.get("photosFinding")).as(setup.toString()).isEqualTo(1);
        assertThat(setup.get("nextStep")).isEqualTo("PHOTOS");

        job.run();   // the new catalogue barcode: its picture
        Map<?, ?> product = (Map<?, ?>) get("/kirana/products/" + salt, owner).body().get("product");
        assertThat(product.get("photoUrl")).as(product.toString()).isNotNull();
        assertThat(product.get("pictureFrom")).isEqualTo("CATALOGUE");
        assertThat(product.get("suggestedBarcode")).isEqualTo(code);   // "Is this its barcode? Tap to add"
        assertThat(product.get("findingPicture")).isEqualTo(false);

        // "Not this product": its picture and suggestion go, and it isn't looked for again under this name
        Reply wrong = post("/kirana/products/" + salt + "/not-this-product", Map.of(), owner);
        assertThat(wrong.status()).as(wrong.body().toString()).isEqualTo(200);
        assertThat(wrong.body().get("photoUrl")).isNull();
        assertThat(wrong.body().get("suggestedBarcode")).isNull();
        assertThat(wrong.body().get("findingPicture")).isEqualTo(false);
        assertThat(jdbc.queryForObject("SELECT status FROM kirana_gs1_lookups WHERE product_id = ?::uuid",
                String.class, salt)).isEqualTo("REFUSED");
        assertThat(post("/kirana/products/" + salt + "/not-this-product", Map.of(), owner).body().get("code"))
                .isEqualTo("NOT_MATCHED");
        int calls = GS1_CALLS.get();
        job.run();
        assertThat(GS1_CALLS.get()).isEqualTo(calls);

        // Renamed: looked for afresh under the new name
        Map<?, ?> before = (Map<?, ?>) get("/kirana/products/" + salt, owner).body().get("product");
        Reply renamed = patch("/kirana/products/" + salt, Map.of("name", "Tata Salt Lite 1 kg",
                "version", before.get("version")), owner);
        assertThat(renamed.status()).as(renamed.body().toString()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status || ':' || tries FROM kirana_gs1_lookups "
                + "WHERE product_id = ?::uuid", String.class, salt)).isEqualTo("WAITING:0");
        // Renamed to something without a size: not looked for at all
        Map<?, ?> again = (Map<?, ?>) get("/kirana/products/" + sugar, owner).body().get("product");
        patch("/kirana/products/" + sugar, Map.of("name", "Madhur Sugar", "version", again.get("version")), owner);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kirana_gs1_lookups WHERE product_id = ?::uuid",
                Integer.class, sugar)).isZero();
    }

    @Test
    void aMatchByBarcodeCantBeUnlinkedByNameAndAHelperCant() {
        String owner = kirana(OWNER, "Sharma General Store");
        String code = KiranaStockIntegrationTest.ean("890126215009");
        String milk = id(post("/kirana/products", coded("Amul Taaza Toned Milk 500 ml", code), owner));
        assertThat(post("/kirana/products/" + milk + "/not-this-product", Map.of(), owner).body().get("code"))
                .isEqualTo("HAS_BARCODE");
        // Added with a barcode: never looked for by name
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kirana_gs1_lookups WHERE kind = 'NAME'", Integer.class))
                .isZero();
        // Loose goods aren't looked for either
        Map<String, Object> rice = plain("Sona Masoori Rice");
        rice.put("unit", "KG");
        post("/kirana/products", rice, owner);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kirana_gs1_lookups WHERE kind = 'NAME'", Integer.class))
                .isZero();
    }

    @Test
    void aPictureTheAiCantMakeOutIsSkippedAndThrownAwayNotTriedForever() {
        String owner = kirana(OWNER, "Sharma General Store");
        String code = KiranaStockIntegrationTest.ean("890126215010");
        post("/kirana/products", coded("Amul Taaza Toned Milk 500 ml", code), owner);
        GS1.put("/products/" + code, "{\"brandName\":\"Amul\",\"productName\":\"Taaza Toned Milk\","
                + "\"netContent\":\"500 ml\",\"productImageUrl\":\"" + gs1Url() + "/pictures/milk.png\"}");
        SEEN.set("I can't tell");   // not JSON

        job.run();
        assertThat(AI_CALLS.get()).isEqualTo(1);
        assertThat(status(code)).isEqualTo("NOT_FOUND");   // looked at again later, not every minute
        assertThat(jdbc.queryForObject("SELECT count(*) FROM files WHERE purpose = 'CATALOGUE_PHOTO'", Integer.class))
                .isZero();
        job.run();
        assertThat(AI_CALLS.get()).isEqualTo(1);
    }

    @Test
    void anUnreadablePictureIsSkippedForTheNextOne() {
        String owner = kirana(OWNER, "Sharma General Store");
        String code = KiranaStockIntegrationTest.ean("890126215013");
        post("/kirana/products", coded("Amul Taaza Toned Milk 500 ml", code), owner);
        GS1.put("/products/" + code, "{\"brandName\":\"Amul\",\"productName\":\"Taaza Toned Milk\","
                + "\"netContent\":\"500 ml\",\"images\":[\"" + gs1Url() + "/pictures/a.png\",\"" + gs1Url()
                + "/pictures/b.png\"]}");
        SEEN_NEXT.add("I can't tell");
        SEEN.set(seen("Amul", "Taaza Toned Milk", "500 ml", true));
        job.run();
        assertThat(AI_CALLS.get()).isEqualTo(2);
        assertThat(status(code)).isEqualTo("FOUND");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM files WHERE purpose = 'CATALOGUE_PHOTO'", Integer.class))
                .isEqualTo(1);   // the unreadable one thrown away
    }

    @Test
    void theDaysGs1ChecksUsedHoldTheLookupUntilTomorrowWithoutTouchingShopsPhotos() {
        String owner = kirana(OWNER, "Sharma General Store");
        String code = KiranaStockIntegrationTest.ean("890126215014");
        post("/kirana/products", coded("Amul Taaza Toned Milk 500 ml", code), owner);
        GS1.put("/products/" + code, "{\"brandName\":\"Amul\",\"productName\":\"Taaza Toned Milk\","
                + "\"netContent\":\"500 ml\",\"productImageUrl\":\"" + gs1Url() + "/pictures/milk.png\"}");
        jdbc.update("INSERT INTO kirana_gs1_ai_calls (day, calls) VALUES ((now() AT TIME ZONE 'Asia/Kolkata')::date, "
                + "100000)");
        job.run();
        assertThat(AI_CALLS.get()).isZero();
        assertThat(status(code)).isEqualTo("WAITING");
        assertThat(jdbc.queryForObject("SELECT next_try_at > now() + interval '1 minute' FROM kirana_gs1_lookups "
                + "WHERE code = ?", Boolean.class, code)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM files WHERE purpose = 'CATALOGUE_PHOTO'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kirana_photo_ai_calls", Integer.class)).isZero();
    }

    @Test
    void gs1NotAnsweringForADayLeavesTheLookupForAMonthAndTheShopIsntKeptWaiting() {
        String owner = kirana(OWNER, "Sharma General Store");
        String code = KiranaStockIntegrationTest.ean("890126215015");
        post("/kirana/products", coded("Amul Taaza Toned Milk 500 ml", code), owner);
        GS1.put("/products/" + code, "!503");
        assertThat(get("/kirana/setup", owner).body().get("photosFinding")).isEqualTo(1);
        job.run();
        assertThat(status(code)).isEqualTo("WAITING");
        jdbc.update("UPDATE kirana_gs1_lookups SET tries = 3, next_try_at = now()");
        Map<?, ?> setup = get("/kirana/setup", owner).body();
        assertThat(setup.get("photosFinding")).as(setup.toString()).isEqualTo(0);   // failed 3 times: photograph it
        assertThat(setup.get("nextStep")).isEqualTo("PHOTOS");
        jdbc.update("UPDATE kirana_gs1_lookups SET tries = ?, next_try_at = now()", 48);   // Gs1Job.MAX_WAITS: a day of runs
        job.run();
        assertThat(status(code)).isEqualTo("NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT tries FROM kirana_gs1_lookups", Integer.class)).isZero();
    }

    @Test
    void aWrongKeyStopsTheRunAndLeavesEveryLookupAsItWas() {
        String owner = kirana(OWNER, "Sharma General Store");
        String code = KiranaStockIntegrationTest.ean("890126215016");
        post("/kirana/products", coded("Amul Taaza Toned Milk 500 ml", code), owner);
        post("/kirana/products", plain("Tata Salt 1 kg"), owner);
        REFUSE_KEY.set(true);
        job.run();
        assertThat(GS1_CALLS.get()).isEqualTo(1);   // stopped at the first refusal
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kirana_gs1_lookups WHERE status = 'WAITING' AND tries = 0",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void aProductTheShopLinkedItselfIsntUnlinkedByARenameOrNotThisProduct() {
        String owner = kirana(OWNER, "Sharma General Store");
        String salt = id(post("/kirana/products", plain("Tata Salt 1 kg"), owner));
        jdbc.update("INSERT INTO kirana_catalogue_items (id, name, unit, created_at, updated_at, version) "
                + "VALUES (gen_random_uuid(), 'Tata Salt 1 kg', 'PIECE', now(), now(), 0)");
        jdbc.update("UPDATE kirana_products SET catalogue_item_id = (SELECT id FROM kirana_catalogue_items) "
                + "WHERE id = ?::uuid", salt);
        job.run();   // the shop had linked it: nothing to do
        assertThat(jdbc.queryForObject("SELECT status FROM kirana_gs1_lookups", String.class)).isEqualTo("FOUND");
        assertThat(post("/kirana/products/" + salt + "/not-this-product", Map.of(), owner).body().get("code"))
                .isEqualTo("NOT_MATCHED");
        Map<?, ?> before = (Map<?, ?>) get("/kirana/products/" + salt, owner).body().get("product");
        patch("/kirana/products/" + salt, Map.of("name", "Tata Salt Iodised 1 kg", "version", before.get("version")),
                owner);
        assertThat(jdbc.queryForObject("SELECT catalogue_item_id IS NOT NULL FROM kirana_products WHERE id = ?::uuid",
                Boolean.class, salt)).isTrue();
    }

    @Test
    void aBusyDataKartIsntTakenAsNoMatch() {
        String owner = kirana(OWNER, "Sharma General Store");
        String salt = id(post("/kirana/products", plain("Tata Salt 1 kg"), owner));
        GS1.put("/products?q=Tata+Salt+1+kg", "!429");
        job.run();
        assertThat(jdbc.queryForObject("SELECT status FROM kirana_gs1_lookups WHERE product_id = ?::uuid",
                String.class, salt)).isEqualTo("WAITING");
    }

    @Test
    void aWrongKeyIsRefusedByEveryCall() {
        DataKart wrongKey = new DataKart(gs1Url(), "not-the-key", "");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> wrongKey.byGtin("8901262150019"))
                .isInstanceOf(DataKart.Refused.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> wrongKey.search("Tata Salt 1 kg"))
                .isInstanceOf(DataKart.Refused.class);
    }

    @Test
    void aCatalogueItemRemovedTakesItsLookupWithIt() {
        String owner = kirana(OWNER, "Sharma General Store");
        String code = KiranaStockIntegrationTest.ean("890126215011");
        String milk = id(post("/kirana/products", coded("Amul Taaza Toned Milk 500 ml", code), owner));
        assertThat(status(code)).isEqualTo("WAITING");
        jdbc.update("UPDATE kirana_products SET catalogue_item_id = NULL WHERE id = ?::uuid", milk);
        jdbc.update("DELETE FROM kirana_barcode_votes");
        jdbc.update("DELETE FROM kirana_catalogue_barcodes WHERE code = ?", code);
        jdbc.update("DELETE FROM kirana_catalogue_items");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kirana_gs1_lookups", Integer.class)).isZero();
    }

    @Test
    void theAdminSeesLookupsByStatusWithCounts() {
        String owner = kirana(OWNER, "Sharma General Store");
        String unknown = KiranaStockIntegrationTest.ean("890126215012");
        post("/kirana/products", coded("Haldiram Bhujia 200 g", unknown), owner);
        job.run();
        assertThat(get("/admin/kirana/gs1", owner).status()).isEqualTo(403);
        String admin = superAdmin();
        Map<?, ?> page = get("/admin/kirana/gs1?status=NOT_FOUND", admin).body();
        assertThat(page.get("switchedOn")).as(page.toString()).isEqualTo(true);
        assertThat(((Map<?, ?>) page.get("counts")).get("NOT_FOUND")).isEqualTo(1);
        java.util.List<?> items = (java.util.List<?>) page.get("items");
        assertThat(items).hasSize(1);
        Map<?, ?> row = (Map<?, ?>) items.get(0);
        assertThat(row.get("code")).isEqualTo(unknown);
        assertThat(row.get("note")).isEqualTo("GS1 doesn't know this barcode");
        assertThat(page.get("next")).isNull();
        // A page at a time: 51 rows, two pages, none twice
        jdbc.update("INSERT INTO kirana_gs1_lookups (id, kind, code, item_id, status, next_try_at, created_at, "
                + "updated_at) SELECT gen_random_uuid(), 'CODE', '99' || g, (SELECT min(id::text)::uuid FROM "
                + "kirana_catalogue_items), 'REFUSED', now(), now(), now() - g * interval '1 second' "
                + "FROM generate_series(1, 51) g");
        Map<?, ?> first = get("/admin/kirana/gs1", admin).body();
        assertThat((java.util.List<?>) first.get("items")).hasSize(50);
        Map<?, ?> second = get("/admin/kirana/gs1?cursor=" + first.get("next"), admin).body();
        assertThat((java.util.List<?>) second.get("items")).hasSize(1);
        assertThat(((Map<?, ?>) ((java.util.List<?>) second.get("items")).get(0)).get("code")).isEqualTo("9951");
        assertThat(second.get("next")).isNull();
        assertThat(get("/admin/kirana/gs1?status=LOST", admin).status()).isEqualTo(400);
    }

    @Test
    void withoutItsSettingsGs1IsOff() {
        assertThat(new DataKart("", "", "").configured()).isFalse();
        assertThat(new DataKart(gs1Url(), "", "").configured()).isFalse();
        assertThat(new DataKart(gs1Url(), "gs1-key", "").configured()).isTrue();
    }

    // ---------- helpers ----------

    private static String seen(String brand, String name, String size, boolean matches) {
        return "{\"goods\":true,\"people\":false,\"oneKind\":true,\"sharp\":true,\"front\":true,\"matches\":" + matches
                + ",\"brand\":\"" + brand + "\",\"name\":\"" + name + "\",\"size\":\"" + size + "\"}";
    }

    private String status(String code) {
        return jdbc.queryForObject("SELECT status FROM kirana_gs1_lookups WHERE code = ? AND kind = 'CODE'",
                String.class, code);
    }

    private String note(String code) {
        return jdbc.queryForObject("SELECT note FROM kirana_gs1_lookups WHERE code = ? AND kind = 'CODE'",
                String.class, code);
    }

    private static Map<String, Object> plain(String name) {
        Map<String, Object> p = new HashMap<>();
        p.put("name", name);
        p.put("unit", "PIECE");
        p.put("pricePaise", 2800);
        p.put("stock", new BigDecimal("5"));
        return p;
    }

    private static Map<String, Object> coded(String name, String barcode) {
        Map<String, Object> p = plain(name);
        p.put("barcode", barcode);
        return p;
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
        return access;
    }

    private String token(String phone) {
        post("/auth/otp/send", Map.of("phone", phone), null);
        Reply r = post("/auth/otp/verify", Map.of("phone", phone, "code", codeSentTo(LAST_SMS, phone)), null);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        TestData.completeProfile(jdbc, phone);
        return (String) r.body().get("accessToken");
    }

    private static String id(Reply r) {
        assertThat(r.status()).as(r.body().toString()).isIn(200, 201);
        return (String) r.body().get("id");
    }

    private Reply post(String path, Object body, String token) {
        return http.post().uri(path).headers(h -> {
            if (token != null) {
                h.setBearerAuth(token);
            }
        }).contentType(MediaType.APPLICATION_JSON).body(body).exchange((rq, res) -> reply(res));
    }

    private Reply patch(String path, Object body, String token) {
        return http.patch().uri(path).headers(h -> h.setBearerAuth(token)).contentType(MediaType.APPLICATION_JSON)
                .body(body).exchange((rq, res) -> reply(res));
    }

    private String superAdmin() {
        token("9848066099");
        jdbc.update("UPDATE users SET admin_role = 'SUPER_ADMIN' WHERE phone = ?", "9848066099");
        return token("9848066099");
    }

    private Reply get(String path, String token) {
        return http.get().uri(path).headers(h -> h.setBearerAuth(token)).exchange((rq, res) -> reply(res));
    }

    private static Reply reply(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse res) throws IOException {
        Object body = res.bodyTo(Object.class);
        return new Reply(res.getStatusCode().value(), body instanceof Map<?, ?> m ? m : Map.of());
    }

    private record Reply(int status, Map<?, ?> body) {
    }
}
