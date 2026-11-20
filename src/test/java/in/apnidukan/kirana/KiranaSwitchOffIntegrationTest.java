package in.apnidukan.kirana;

import in.apnidukan.common.jobs.JobLeases;
import in.apnidukan.common.security.AuthPrincipal;
import in.apnidukan.common.security.JwtService;
import in.apnidukan.common.security.SessionRevocations;
import in.apnidukan.kirana.service.CheckoutService;
import in.apnidukan.kirana.service.DataKart;
import in.apnidukan.kirana.service.FssaiCheckJob;
import in.apnidukan.kirana.service.Gs1Job;
import in.apnidukan.kirana.service.KiranaDocumentAi;
import in.apnidukan.kirana.service.KiranaPaymentEvents;
import in.apnidukan.kirana.service.KiranaPayoutGateway;
import in.apnidukan.kirana.service.KiranaRazorpay;
import in.apnidukan.kirana.service.OrderJobs;
import in.apnidukan.kirana.service.ReadJob;
import in.apnidukan.shops.repo.LicenceCheckStore;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The server as it runs until kirana launches: app.kirana.enabled=false (KiranaSwitch). The kirana API answers 404
 * KIRANA_OFF to everyone, signed in or not; no shop registers as, or changes to, a kirana; the kirana jobs do nothing
 * and call nothing outside; salons register and take bookings as always. Against real Postgres and Redis in Docker,
 * with a clock the test sets ("now" is Tuesday 10 Nov 2026, 10:00 in India).
 */
@EnabledIf("in.apnidukan.support.TestContainers#dockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({CapturingSenders.class, KiranaSwitchOffIntegrationTest.Fakes.class})
class KiranaSwitchOffIntegrationTest {

    static final Instant TUESDAY_10_00 = Instant.parse("2026-11-10T04:30:00Z");
    static final TestClock CLOCK = new TestClock(TUESDAY_10_00);
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

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
        TestContainers.register(registry, false);   // kirana switched off, as on the server until it launches
    }

    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired JwtService jwt;
    @Autowired SessionRevocations sessions;
    @Autowired ReadJob readJob;
    @Autowired FssaiCheckJob fssaiJob;
    @Autowired OrderJobs orderJobs;
    @Autowired Gs1Job gs1Job;
    @Autowired KiranaPaymentEvents paymentEvents;
    @Autowired CheckoutService checkouts;
    @Autowired LicenceCheckStore licences;

    // Everything that reaches outside for a kirana: never touched while it is off
    @MockitoSpyBean KiranaDocumentAi ai;
    @MockitoSpyBean DataKart dataKart;
    @MockitoSpyBean KiranaRazorpay razorpay;
    @MockitoSpyBean KiranaPayoutGateway razorpayX;
    @MockitoSpyBean JobLeases leases;

    RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.builder()
                // JDK client: the Apache client pulled in by Testcontainers silently retries some replies
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + environment.getProperty("local.server.port") + "/api/v1")
                .build();
        TestData.clean(jdbc);
        redis.execute((RedisCallback<Object>) (RedisConnection c) -> {
            c.serverCommands().flushAll();
            return null;
        });
        CLOCK.set(TUESDAY_10_00);
    }

    // ---------- the kirana API ----------

    @Test
    void everyKiranaPathAnswers404KiranaOffSignedInOrNotAdminOrNot() {
        UUID customer = person("9848044001");
        UUID admin = person("9848044002");
        jdbc.update("UPDATE users SET admin_role = 'ADMIN', admin_designation = 'FIELD_AGENT' WHERE id = ?", admin);
        UUID owner = person("9848044003");
        UUID salon = UUID.randomUUID();
        TestData.salon(jdbc, salon, owner);
        String ownerToken = shopToken(owner, salon);

        List<Object[]> calls = List.of(
                new Object[] {HttpMethod.GET, "/kirana/settings", null},
                new Object[] {HttpMethod.GET, "/kirana/nearby?lat=17.466&lng=78.365", null},
                new Object[] {HttpMethod.GET, "/kirana/products", null},
                new Object[] {HttpMethod.POST, "/kirana/bills", Map.of("clientRef", "x")},
                new Object[] {HttpMethod.GET, "/kirana", null},
                new Object[] {HttpMethod.GET, "/kirana/no-such-page", null},
                new Object[] {HttpMethod.GET, "/admin/kirana/catalogue", null},
                new Object[] {HttpMethod.POST, "/admin/kirana/catalogue/import", Map.of()},
                new Object[] {HttpMethod.POST, "/payments/razorpayx/webhook", Map.of("event", "payout.processed")});
        List<String> tokens = new ArrayList<>();
        tokens.add(null);   // not signed in
        tokens.add(token(customer, false));
        tokens.add(token(admin, true));
        tokens.add(ownerToken);
        tokens.add("not-a-token");
        for (Object[] c : calls) {
            for (String t : tokens) {
                Reply r = call((HttpMethod) c[0], (String) c[1], c[2], t);
                assertThat(r.status()).as(c[0] + " " + c[1] + " " + (t == null ? "signed out" : "signed in"))
                        .isEqualTo(404);
                assertThat(r.body().get("code")).as(r.text()).isEqualTo("KIRANA_OFF");
                assertThat(r.body().keySet().stream().map(String::valueOf).toList()).contains("message", "details");
            }
        }
        // Next to them, other paths go on as always: no token is 401, a near name isn't the kirana API
        assertThat(call(HttpMethod.GET, "/kiranaX", null, null).body().get("code")).isEqualTo("NOT_SIGNED_IN");
        assertThat(call(HttpMethod.GET, "/shops/me", null, null).status()).isEqualTo(401);
        assertThat(call(HttpMethod.GET, "/kiranaX", null, token(customer, false)).status()).isEqualTo(404);
        assertThat(call(HttpMethod.GET, "/kiranaX", null, token(customer, false)).body().get("code"))
                .isNotEqualTo("KIRANA_OFF");
    }

    // ---------- registration ----------

    @Test
    void noShopRegistersAsAKiranaOrChangesToOneButASalonDoes() {
        UUID owner = person("9848044011");
        String access = token(owner, false);

        Map<String, Object> kirana = shopBody();
        kirana.put("type", "KIRANA");
        Reply refused = call(HttpMethod.POST, "/shops", kirana, access);
        assertThat(refused.status()).as(refused.text()).isEqualTo(400);
        assertThat(refused.body().get("code")).isEqualTo("KIRANA_OFF");
        assertThat(count("shops")).isZero();
        assertThat(count("shop_members")).isZero();

        // Left out: a salon while kirana is off (an app that never sent a type still registers its salon)
        Reply created = call(HttpMethod.POST, "/shops", shopBody(), access);
        assertThat(created.status()).as(created.text()).isEqualTo(201);
        assertThat(created.body().get("type")).isEqualTo("SALON");
        assertThat(created.body().get("status")).isEqualTo("DRAFT");

        TestData.unlockEditing(jdbc, redis);
        Reply change = call(HttpMethod.PUT, "/shops/me/type", Map.of("version", 0, "type", "KIRANA"), access);
        assertThat(change.status()).as(change.text()).isEqualTo(400);
        assertThat(change.body().get("code")).isEqualTo("KIRANA_OFF");
        assertThat(jdbc.queryForObject("SELECT type FROM shops", String.class)).isEqualTo("SALON");
        // The same type again is as before: 200, nothing changes
        Reply same = call(HttpMethod.PUT, "/shops/me/type", Map.of("version", 0, "type", "SALON"), access);
        assertThat(same.status()).as(same.text()).isEqualTo(200);
    }

    @Test
    void aKiranaMadeBeforeTheSwitchWentOffIsntSubmittedForItsVisit() {
        UUID owner = person("9848044021");
        UUID shop = UUID.randomUUID();
        TestData.shop(jdbc, shop, owner);   // a kirana row written directly
        TestData.unlockEditing(jdbc, redis);
        Reply r = call(HttpMethod.POST, "/shops/me/submit", Map.of("version", 0), shopToken(owner, shop));
        assertThat(r.status()).as(r.text()).isEqualTo(409);
        assertThat(r.body().get("code")).isEqualTo("KIRANA_OFF");
        assertThat(jdbc.queryForObject("SELECT status FROM shops WHERE id = ?", String.class, shop)).isEqualTo("DRAFT");
    }

    @Test
    void aKiranasBillAndPackPhotosArentUploaded() {
        UUID owner = person("9848044031");
        UUID salon = UUID.randomUUID();
        TestData.salon(jdbc, salon, owner);
        for (String purpose : List.of("BILL", "PRODUCT_PHOTO")) {
            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            form.add("purpose", purpose);
            form.add("file", new ByteArrayResource(fakeJpeg()) {
                @Override
                public String getFilename() {
                    return "photo.jpg";
                }
            });
            Reply r = http.post().uri("/files")
                    .header("Authorization", "Bearer " + shopToken(owner, salon))
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .exchange((rq, res) -> read(res));
            assertThat(r.status()).as(purpose + " " + r.text()).isEqualTo(400);
            assertThat(r.body().get("code")).isEqualTo("KIRANA_OFF");
        }
        assertThat(count("files")).isZero();
    }

    // ---------- salons as always ----------

    @Test
    void aSalonIsFoundAndBookedAsAlways() {
        UUID owner = person("9848044041");
        UUID customer = person("9848044042");
        UUID salon = UUID.randomUUID();
        TestData.salon(jdbc, salon, owner);
        jdbc.update("UPDATE shops SET status = 'APPROVED' WHERE id = ?", salon);
        String ownerToken = token(owner, false);
        UUID haircut = id(call(HttpMethod.POST, "/salon/services",
                Map.of("name", "Haircut", "durationMinutes", 30, "pricePaise", 15000), ownerToken));
        UUID raju = id(call(HttpMethod.POST, "/salon/staff", Map.of("name", "Raju"), ownerToken));
        Reply services = call(HttpMethod.PUT, "/salon/staff/" + raju + "/services",
                Map.of("version", 0, "serviceIds", List.of(haircut)), ownerToken);
        assertThat(services.status()).as(services.text()).isEqualTo(200);
        List<Map<String, Object>> week = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            week.add(Map.of("weekday", day, "start", "10:00", "end", "21:00"));
        }
        Reply hours = call(HttpMethod.PUT, "/salon/staff/" + raju + "/hours",
                Map.of("version", services.body().get("version"), "hours", week), ownerToken);
        assertThat(hours.status()).as(hours.text()).isEqualTo(200);

        String customerToken = token(customer, false);
        Reply near = call(HttpMethod.GET, "/salons?lat=17.466&lng=78.365", null, customerToken);
        assertThat(near.status()).as(near.text()).isEqualTo(200);
        assertThat(near.list()).hasSize(1);
        assertThat(((Map<?, ?>) near.list().get(0)).get("id")).isEqualTo(salon.toString());

        Instant noon = LocalDate.of(2026, 11, 10).atTime(LocalTime.NOON).atZone(INDIA).toInstant();
        Reply booked = http.post().uri("/bookings")
                .headers(h -> {
                    h.setBearerAuth(customerToken);
                    h.set("Idempotency-Key", UUID.randomUUID().toString());
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("salonId", salon.toString(), "serviceIds", List.of(haircut), "startAt", noon.toString()))
                .exchange((rq, res) -> read(res));
        assertThat(booked.status()).as(booked.text()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT staff_id FROM appointments WHERE id = ?", UUID.class,
                UUID.fromString((String) booked.body().get("id")))).isEqualTo(raju);
        Reply day = call(HttpMethod.GET, "/salon/day?date=2026-11-10", null, ownerToken);
        assertThat(day.status()).isEqualTo(200);
        assertThat(day.text()).contains((String) booked.body().get("id"));
    }

    // ---------- jobs and shared code ----------

    @Test
    void theKiranaJobsDoNothingAndCallNothingOutside() {
        readJob.run();
        fssaiJob.run();
        gs1Job.run();
        orderJobs.timeouts();
        orderJobs.expiry();
        orderJobs.refunds();
        orderJobs.payouts();

        verify(leases, never()).runExclusively(startsWith("kirana-"), any(), any());
        verifyNoInteractions(ai, dataKart, razorpay, razorpayX);
    }

    @Test
    void razorpayEventsArentAKiranasAndTheCustomerListIsEmpty() {
        byte[] captured = ("{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_1\","
                + "\"order_id\":\"order_1\",\"amount\":10000}}}}").getBytes(StandardCharsets.UTF_8);
        assertThat(paymentEvents.handle("payment.captured", captured, "evt_1")).isNull();   // someone else's
        assertThat(paymentEvents.handle("refund.processed", captured, "evt_2")).isNull();

        assertThat(checkouts.shops(new BigDecimal("17.466"), new BigDecimal("78.365"), null).items()).isEmpty();
        assertThat(checkouts.products(UUID.randomUUID(), null, null).items()).isEmpty();
        assertThat(checkouts.nearbyItems(UUID.randomUUID(), null, null).items()).isEmpty();
        verifyNoInteractions(razorpay, razorpayX);
    }

    @Test
    void aKiranasLicenceIsntReadOrRemindedAboutButASalonsIs() {
        UUID salon = UUID.randomUUID();
        TestData.salon(jdbc, salon, person("9848044051"));
        UUID kirana = UUID.randomUUID();
        TestData.shop(jdbc, kirana, person("9848044052"));
        Instant now = CLOCK.instant();
        pending(salon, now.minusSeconds(60));
        pending(kirana, now.minusSeconds(120));   // due first: still never taken

        List<LicenceCheckStore.Due> due = licences.claim(now, now.plusSeconds(600), 10, false);
        assertThat(due).extracting(LicenceCheckStore.Due::shopId).containsExactly(salon);
        assertThat(jdbc.queryForObject("SELECT licence_check_attempts FROM shops WHERE id = ?", Integer.class, kirana))
                .isZero();

        UUID salon2 = UUID.randomUUID();
        TestData.salon(jdbc, salon2, person("9848044053"));
        UUID kirana2 = UUID.randomUUID();
        TestData.shop(jdbc, kirana2, person("9848044054"));
        LocalDate today = LocalDate.ofInstant(now, INDIA);
        passed(salon2, "TL5001", today.plusDays(10));
        passed(kirana2, "TL5002", today.plusDays(5));
        assertThat(licences.expiring(today, today.plusDays(30), 10, false))
                .extracting(LicenceCheckStore.Expiring::shopId).containsExactly(salon2);
        // On: both, as before
        assertThat(licences.expiring(today, today.plusDays(30), 10, true))
                .extracting(LicenceCheckStore.Expiring::shopId).containsExactly(kirana2, salon2);
    }

    // ---------- helpers ----------

    /** A trade licence (a REG_DOC file) waiting for its check, due at {@code dueAt}. */
    private void pending(UUID shopId, Instant dueAt) {
        UUID doc = regDoc(shopId);
        jdbc.update("UPDATE shops SET registration_doc_type = 'TRADE_LICENCE', registration_doc_file_id = ?, "
                + "licence_check_status = 'PENDING', licence_check_due_at = ?, licence_check_since = ? WHERE id = ?",
                doc, Timestamp.from(dueAt), Timestamp.from(dueAt), shopId);
    }

    /** A trade licence that passed its check, valid until {@code until}, no reminder sent yet. */
    private void passed(UUID shopId, String number, LocalDate until) {
        UUID doc = regDoc(shopId);
        jdbc.update("UPDATE shops SET registration_doc_type = 'TRADE_LICENCE', registration_doc_file_id = ?, "
                + "licence_check_status = 'PASSED', licence_number = ?, licence_valid_until = ?, "
                + "licence_expiry_notice = 0 WHERE id = ?", doc, number, until, shopId);
    }

    private UUID regDoc(UUID shopId) {
        UUID doc = UUID.randomUUID();
        UUID owner = jdbc.queryForObject("SELECT user_id FROM shop_members WHERE shop_id = ? AND role = 'OWNER'",
                UUID.class, shopId);
        jdbc.update("INSERT INTO files (id, shop_id, purpose, storage_key, content_type, size_bytes, sha256, "
                + "uploaded_by, created_at) VALUES (?, ?, 'REG_DOC', ?, 'image/jpeg', 10, ?, ?, now())", doc, shopId,
                "test/" + doc + ".jpg", doc.toString().replace("-", "") + "0".repeat(32), owner);
        return doc;
    }

    private static Map<String, Object> shopBody() {
        Map<String, Object> shop = new HashMap<>();
        shop.put("name", "Sharma Store");
        shop.put("ownerName", "Ramesh Sharma");
        shop.put("addressLine", "Shop 4, Lake View Apartments");
        shop.put("area", "Kondapur");
        shop.put("city", "Hyderabad");
        shop.put("pincode", "500084");
        shop.put("latitude", new BigDecimal("17.465123"));
        shop.put("longitude", new BigDecimal("78.364456"));
        return shop;
    }

    private static byte[] fakeJpeg() {
        byte[] bytes = new byte[2048];
        new java.util.Random(42).nextBytes(bytes);
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        return bytes;
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }

    private UUID person(String phone) {
        UUID id = UUID.randomUUID();
        TestData.user(jdbc, id, phone);
        return id;
    }

    private String token(UUID who, boolean admin) {
        return jwt.issue(new AuthPrincipal(who, null, null, admin), sessions.currentGeneration(who)).token();
    }

    private String shopToken(UUID who, UUID shopId) {
        return jwt.issue(new AuthPrincipal(who, shopId, "OWNER", false), sessions.currentGeneration(who)).token();
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
        String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
        Object parsed = text.isBlank() ? Map.of() : JSON.readValue(text, Object.class);
        return new Reply(status, parsed, text);
    }

    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

    private static UUID id(Reply r) {
        assertThat(r.status()).as(r.text()).isBetween(200, 201);
        return UUID.fromString((String) r.body().get("id"));
    }

    private record Reply(int status, Object json, String text) {

        Map<?, ?> body() {
            return json instanceof Map<?, ?> m ? m : Map.of();
        }

        List<?> list() {
            return json instanceof List<?> l ? l : List.of();
        }
    }
}
