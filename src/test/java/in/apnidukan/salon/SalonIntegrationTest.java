package in.apnidukan.salon;

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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static in.apnidukan.support.CapturingSenders.LAST_EMAIL;
import static in.apnidukan.support.CapturingSenders.LAST_SMS;
import static in.apnidukan.support.CapturingSenders.codeSentTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Salon setup end to end, against real Postgres and Redis in Docker, signing in through the real OTP flow.
 * Also checks V4's own rules (same-salon keys, no overlapping shifts) and the history tables.
 */
@EnabledIf("in.apnidukan.support.TestContainers#dockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CapturingSenders.class)
class SalonIntegrationTest {

    private static final String OWNER_PHONE = "9848022345";
    private static final String HELPER_PHONE = "9848022346";
    private static final String OTHER_OWNER_PHONE = "9848022347";

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        TestContainers.register(registry);
    }

    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PlatformTransactionManager transactions;

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

    // ---------- shop type ----------

    @Test
    void aShopRegisteredAsASalonSaysSoAndAShopWithoutATypeIsAKirana() {
        String owner = token(OWNER_PHONE);
        Reply salon = post("/shops", shop("SALON"), owner);
        assertThat(salon.status()).as(salon.body().toString()).isEqualTo(201);
        assertThat(salon.body().get("type")).isEqualTo("SALON");
        assertThat(get("/shops/me", owner).body().get("type")).isEqualTo("SALON");

        Reply kirana = post("/shops", shop(null), token(OTHER_OWNER_PHONE));
        assertThat(kirana.body().get("type")).isEqualTo("KIRANA");
        // The column is recorded in the shops history too
        assertThat(jdbc.queryForList("SELECT type FROM shops_aud ORDER BY rev", String.class))
                .containsExactly("SALON", "KIRANA");
    }

    @Test
    void anUnknownShopTypeIsRefused() {
        Reply r = post("/shops", shop("BAKERY"), token(OWNER_PHONE));
        assertThat(r.status()).isEqualTo(400);
    }

    @Test
    void salonSetupIsOnlyForSalonsAndNeedsSignIn() {
        String kiranaOwner = token(OWNER_PHONE);
        post("/shops", shop(null), kiranaOwner);
        Reply kirana = get("/salon/services", kiranaOwner);
        assertThat(kirana.status()).isEqualTo(409);
        assertThat(kirana.body().get("code")).isEqualTo("NOT_A_SALON");
        assertThat(post("/salon/services", service("Haircut", 30, 15000, 0), kiranaOwner).body().get("code"))
                .isEqualTo("NOT_A_SALON");

        Reply noShop = get("/salon/staff", token(OTHER_OWNER_PHONE));
        assertThat(noShop.status()).isEqualTo(404);
        assertThat(noShop.body().get("code")).isEqualTo("NO_SHOP");

        assertThat(get("/salon/settings", null).status()).isEqualTo(401);
    }

    // ---------- services ----------

    @Test
    void ownerAddsServicesAndTheListComesInTheOwnersOrder() {
        String owner = salonOwner(OWNER_PHONE);
        Reply haircut = post("/salon/services", service("  Haircut  ", 30, 15000, 2), owner);
        assertThat(haircut.status()).as(haircut.body().toString()).isEqualTo(201);
        assertThat(haircut.body().get("name")).isEqualTo("Haircut");
        assertThat(haircut.body().get("active")).isEqualTo(true);
        assertThat(haircut.body().get("priceIsFrom")).isEqualTo(false);
        Map<String, Object> colour = service("Hair colour", 90, 80000, 1);
        colour.put("priceIsFrom", true);
        post("/salon/services", colour, owner);

        List<?> list = getList("/salon/services", owner);
        List<Object> names = new ArrayList<>();
        for (Object s : list) {
            names.add(((Map<?, ?>) s).get("name"));
        }
        assertThat(names).containsExactly("Hair colour", "Haircut");
        assertThat(((Map<?, ?>) list.get(0)).get("priceIsFrom")).isEqualTo(true);
        assertThat(((Number) ((Map<?, ?>) list.get(1)).get("pricePaise")).longValue()).isEqualTo(15000);
    }

    @Test
    void twoActiveServicesCantShareANameButASwitchedOffOneFreesIt() {
        String owner = salonOwner(OWNER_PHONE);
        Map<?, ?> first = post("/salon/services", service("Haircut", 30, 15000, 0), owner).body();
        Reply same = post("/salon/services", service("HAIRCUT", 45, 20000, 0), owner);
        assertThat(same.status()).isEqualTo(409);
        assertThat(same.body().get("code")).isEqualTo("SERVICE_NAME_TAKEN");

        Reply off = patch("/salon/services/" + first.get("id"), Map.of("version", 0, "active", false), owner);
        assertThat(off.status()).as(off.body().toString()).isEqualTo(200);
        assertThat(post("/salon/services", service("Haircut", 45, 20000, 0), owner).status()).isEqualTo(201);

        // Switching the old one back on would make two active "Haircut"s: refused
        Reply backOn = patch("/salon/services/" + first.get("id"), Map.of("version", 1, "active", true), owner);
        assertThat(backOn.status()).isEqualTo(409);
        assertThat(backOn.body().get("code")).isEqualTo("SERVICE_NAME_TAKEN");
    }

    @Test
    void wrongServiceDetailsAreRefusedWithTheFieldNamed() {
        String owner = salonOwner(OWNER_PHONE);
        assertInvalidService(owner, service("Haircut", 7, 15000, 0), "durationMinutes");    // not a 5-minute step
        assertInvalidService(owner, service("Haircut", 485, 15000, 0), "durationMinutes");  // over 8 hours
        assertInvalidService(owner, service("Haircut", 30, -1, 0), "pricePaise");
        assertInvalidService(owner, service("Haircut", 30, 10_000_001, 0), "pricePaise");   // over ₹1,00,000
        assertInvalidService(owner, service("!!", 30, 15000, 0), "name");
        assertInvalidService(owner, service("Hair\u200Bcut", 30, 15000, 0), "name");       // hidden character
        assertInvalidService(owner, service("Haircut", 30, 15000, 1000), "sortOrder");
    }

    @Test
    void aStaleServiceEditIsRefusedAndSavingWithoutChangesAddsNoVersion() {
        String owner = salonOwner(OWNER_PHONE);
        String id = (String) post("/salon/services", service("Haircut", 30, 15000, 0), owner).body().get("id");

        Reply first = patch("/salon/services/" + id, Map.of("version", 0, "pricePaise", 18000), owner);
        assertThat(((Number) first.body().get("version")).longValue()).isEqualTo(1);
        Reply stale = patch("/salon/services/" + id, Map.of("version", 0, "pricePaise", 20000), owner);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.body().get("code")).isEqualTo("SERVICE_CHANGED");

        Reply same = patch("/salon/services/" + id, Map.of("version", 1, "pricePaise", 18000), owner);
        assertThat(((Number) same.body().get("version")).longValue()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM salon_services_aud", Integer.class)).isEqualTo(2);
    }

    @Test
    void aHelperSeesTheSetupButOnlyTheOwnerChangesIt() {
        String owner = salonOwner(OWNER_PHONE);
        String serviceId = (String) post("/salon/services", service("Haircut", 30, 15000, 0), owner).body().get("id");
        String staffId = (String) post("/salon/staff", Map.of("name", "Raju"), owner).body().get("id");
        String helper = helperOf(owner, HELPER_PHONE);

        assertThat(getList("/salon/services", helper)).hasSize(1);
        assertThat(getList("/salon/staff", helper)).hasSize(1);
        assertThat(get("/salon/settings", helper).status()).isEqualTo(200);

        assertOwnerOnly(post("/salon/services", service("Shave", 15, 5000, 0), helper));
        assertOwnerOnly(patch("/salon/services/" + serviceId, Map.of("version", 0, "active", false), helper));
        assertOwnerOnly(post("/salon/staff", Map.of("name", "Sunil"), helper));
        assertOwnerOnly(patch("/salon/staff/" + staffId, Map.of("version", 0, "name", "Raj"), helper));
        assertOwnerOnly(put("/salon/staff/" + staffId + "/services",
                Map.of("version", 0, "serviceIds", List.of(serviceId)), helper));
        assertOwnerOnly(put("/salon/staff/" + staffId + "/hours", Map.of("version", 0, "hours", List.of()),
                helper));
        assertOwnerOnly(patch("/salon/settings", Map.of("maxDaysAhead", 3), helper));
    }

    @Test
    void oneSalonCantSeeOrChangeAnothersServicesOrBarbers() {
        String ownerA = salonOwner(OWNER_PHONE);
        String serviceA = (String) post("/salon/services", service("Haircut", 30, 15000, 0), ownerA).body().get("id");
        String staffA = (String) post("/salon/staff", Map.of("name", "Raju"), ownerA).body().get("id");
        String ownerB = salonOwner(OTHER_OWNER_PHONE);
        String staffB = (String) post("/salon/staff", Map.of("name", "Imran"), ownerB).body().get("id");

        assertThat(getList("/salon/services", ownerB)).isEmpty();
        assertThat(patch("/salon/services/" + serviceA, Map.of("version", 0, "active", false), ownerB).body()
                .get("code")).isEqualTo("SERVICE_NOT_FOUND");
        assertThat(patch("/salon/staff/" + staffA, Map.of("version", 0, "name", "Taken"), ownerB).body()
                .get("code")).isEqualTo("STAFF_NOT_FOUND");
        // B's own barber can't be given A's service
        Reply cross = put("/salon/staff/" + staffB + "/services",
                Map.of("version", 0, "serviceIds", List.of(serviceA)), ownerB);
        assertThat(cross.status()).isEqualTo(400);
        assertThat(((Map<?, ?>) cross.body().get("details")).containsKey("serviceIds")).isTrue();
    }

    // ---------- barbers ----------

    @Test
    void ownerAddsABarberAndSetsHisServicesAndWeek() {
        String owner = salonOwner(OWNER_PHONE);
        String haircut = (String) post("/salon/services", service("Haircut", 30, 15000, 0), owner).body().get("id");
        String shave = (String) post("/salon/services", service("Shave", 15, 5000, 0), owner).body().get("id");
        Reply added = post("/salon/staff", Map.of("name", " Raju  Kumar "), owner);
        assertThat(added.status()).as(added.body().toString()).isEqualTo(201);
        assertThat(added.body().get("name")).isEqualTo("Raju Kumar");
        String raju = (String) added.body().get("id");

        Reply skills = put("/salon/staff/" + raju + "/services",
                Map.of("version", 0, "serviceIds", List.of(shave, haircut, shave)),
                owner);
        assertThat(skills.status()).as(skills.body().toString()).isEqualTo(200);
        assertThat(objects(skills.body().get("serviceIds"))).containsExactlyInAnyOrder(haircut, shave);

        assertThat(((Number) skills.body().get("version")).longValue()).isEqualTo(1);
        Reply week = put("/salon/staff/" + raju + "/hours", Map.of("version", 1, "hours", List.of(
                shift(2, "16:00", "21:00"), shift(1, "10:00", "14:00"), shift(2, "10:00", "14:00"),
                shift(6, "14:00", "24:00"))), owner);
        assertThat(week.status()).as(week.body().toString()).isEqualTo(200);
        assertThat(objects(week.body().get("hours"))).containsExactly(
                shift(1, "10:00", "14:00"), shift(2, "10:00", "14:00"), shift(2, "16:00", "21:00"),
                shift(6, "14:00", "24:00"));
        assertThat(jdbc.queryForObject("SELECT end_min FROM staff_hours WHERE weekday = 6", Integer.class))
                .isEqualTo(1440);

        Map<?, ?> listed = (Map<?, ?>) getList("/salon/staff", owner).get(0);
        assertThat((List<?>) listed.get("hours")).hasSize(4);
        assertThat((List<?>) listed.get("serviceIds")).hasSize(2);
    }

    @Test
    void replacingTheWeekWritesOnlyTheDifferencesAndAnEmptyWeekClearsIt() {
        String owner = salonOwner(OWNER_PHONE);
        String raju = (String) post("/salon/staff", Map.of("name", "Raju"), owner).body().get("id");
        put("/salon/staff/" + raju + "/hours", Map.of("version", 0, "hours", List.of(shift(1, "10:00", "13:00"),
                shift(3, "10:00", "14:00"))), owner);
        // Monday grows to 14:00 (overlaps the old 10:00-13:00, so the old row must go first); Wednesday stays
        Reply grown = put("/salon/staff/" + raju + "/hours", Map.of("version", 1, "hours", List.of(
                shift(1, "10:00", "14:00"),
                shift(3, "10:00", "14:00"))), owner);
        assertThat(grown.status()).as(grown.body().toString()).isEqualTo(200);
        // History: 2 added, then 1 removed + 1 added; Wednesday untouched
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_hours_aud", Integer.class)).isEqualTo(4);

        Reply cleared = put("/salon/staff/" + raju + "/hours", Map.of("version", 2, "hours", List.of()), owner);
        assertThat((List<?>) cleared.body().get("hours")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_hours", Integer.class)).isZero();
    }

    @Test
    void wrongHoursAreRefusedWithTheProblemNamed() {
        String owner = salonOwner(OWNER_PHONE);
        String raju = (String) post("/salon/staff", Map.of("name", "Raju"), owner).body().get("id");
        assertInvalidHours(owner, raju, List.of(shift(1, "10:00", "14:00"), shift(1, "13:00", "18:00")), "hours");
        assertInvalidHours(owner, raju, List.of(shift(1, "10:00", "14:00"), shift(1, "10:00", "14:00")), "hours");
        assertInvalidHours(owner, raju, List.of(shift(1, "14:00", "10:00")), "hours[0].end");
        assertInvalidHours(owner, raju, List.of(shift(1, "10:10", "14:00")), "hours[0].start");   // not :00/:15/...
        assertInvalidHours(owner, raju, List.of(shift(1, "9:00", "14:00")), "hours[0].start");    // not HH:mm
        assertInvalidHours(owner, raju, List.of(shift(1, "10:00", "24:15")), "hours[0].end");
        assertInvalidHours(owner, raju, List.of(shift(1, "24:00", "24:00")), "hours[0].start");
        assertInvalidHours(owner, raju, List.of(shift(8, "10:00", "14:00")), "hours[0].weekday");
        // Touching blocks are fine: a split day
        assertThat(put("/salon/staff/" + raju + "/hours", Map.of("version", 0, "hours", List.of(
                shift(1, "10:00", "14:00"),
                shift(1, "14:00", "18:00"))), owner).status()).isEqualTo(200);
    }

    @Test
    void aSwitchedOffServiceCantBeGivenToABarber() {
        String owner = salonOwner(OWNER_PHONE);
        String old = (String) post("/salon/services", service("Old style", 30, 10000, 0), owner).body().get("id");
        patch("/salon/services/" + old, Map.of("version", 0, "active", false), owner);
        String raju = (String) post("/salon/staff", Map.of("name", "Raju"), owner).body().get("id");
        Reply r = put("/salon/staff/" + raju + "/services", Map.of("version", 0, "serviceIds", List.of(old)), owner);
        assertThat(r.status()).isEqualTo(400);
    }

    @Test
    void twoWeekEditsFromTheSameOldScreenNeverOverwriteEachOther() throws Exception {
        String owner = salonOwner(OWNER_PHONE);
        String raju = (String) post("/salon/staff", Map.of("name", "Raju"), owner).body().get("id");
        List<List<Map<String, Object>>> weeks = List.of(
                List.of(shift(1, "10:00", "14:00"), shift(2, "10:00", "14:00")),
                List.of(shift(1, "11:00", "15:00"), shift(2, "11:00", "15:00")));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Reply>> replies = new ArrayList<>();
            for (List<Map<String, Object>> week : weeks) {
                replies.add(pool.submit(() -> {
                    start.await();
                    // Both phones loaded Raju at version 0
                    return put("/salon/staff/" + raju + "/hours", Map.of("version", 0, "hours", week), owner);
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Reply> f : replies) {
                statuses.add(f.get(10, TimeUnit.SECONDS).status());
            }
            // The barber's row lock makes them run one after the other; the second sees version 1 and is refused
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        List<Integer> starts = jdbc.queryForList("SELECT start_min FROM staff_hours ORDER BY weekday", Integer.class);
        assertThat(starts).isIn(List.of(600, 600), List.of(660, 660));
    }

    @Test
    void aWeekSaveWaitsForTheBarbersLockAndThenSeesTheNewerVersion() throws Exception {
        String owner = salonOwner(OWNER_PHONE);
        String raju = (String) post("/salon/staff", Map.of("name", "Raju"), owner).body().get("id");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        // Another edit of Raju has his row locked and has raised his version, but hasn't committed yet
        try (HeldTransaction other = new HeldTransaction(() -> jdbc.update(
                "UPDATE salon_staff SET version = version + 1 WHERE id = ?", UUID.fromString(raju)))) {
            Future<Reply> save = pool.submit(() -> put("/salon/staff/" + raju + "/hours",
                    Map.of("version", 0, "hours", List.of(shift(1, "10:00", "14:00"))), owner));
            other.waitUntilBlocking("select");   // the save's SELECT ... FOR UPDATE is queued behind the lock
            other.commit();
            Reply r = save.get(10, TimeUnit.SECONDS);
            // Without the lock it would have read version 0, passed the check, and failed later with a 500
            assertThat(r.status()).as(r.body().toString()).isEqualTo(409);
            assertThat(r.body().get("code")).isEqualTo("STAFF_CHANGED");
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_hours", Integer.class)).isZero();
    }

    @Test
    void aStaleServicesOrHoursSaveIsRefused() {
        String owner = salonOwner(OWNER_PHONE);
        String haircut = (String) post("/salon/services", service("Haircut", 30, 15000, 0), owner).body().get("id");
        String raju = (String) post("/salon/staff", Map.of("name", "Raju"), owner).body().get("id");
        assertThat(put("/salon/staff/" + raju + "/hours", Map.of("version", 0, "hours",
                List.of(shift(1, "10:00", "14:00"))), owner).status()).isEqualTo(200);

        Reply staleHours = put("/salon/staff/" + raju + "/hours", Map.of("version", 0, "hours", List.of()), owner);
        assertThat(staleHours.status()).isEqualTo(409);
        assertThat(staleHours.body().get("code")).isEqualTo("STAFF_CHANGED");
        Reply staleServices = put("/salon/staff/" + raju + "/services",
                Map.of("version", 0, "serviceIds", List.of(haircut)), owner);
        assertThat(staleServices.body().get("code")).isEqualTo("STAFF_CHANGED");
        // Nothing was changed by the refused saves
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_hours", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_services", Integer.class)).isZero();
    }

    @Test
    void aSwitchedOffServiceAlreadyOnTheBarbersListStaysWhenTheListIsSavedAgain() {
        String owner = salonOwner(OWNER_PHONE);
        String old = (String) post("/salon/services", service("Old style", 30, 10000, 0), owner).body().get("id");
        String raju = (String) post("/salon/staff", Map.of("name", "Raju"), owner).body().get("id");
        put("/salon/staff/" + raju + "/services", Map.of("version", 0, "serviceIds", List.of(old)), owner);
        patch("/salon/services/" + old, Map.of("version", 0, "active", false), owner);
        String fade = (String) post("/salon/services", service("Fade", 30, 20000, 0), owner).body().get("id");

        // The app sends back the list it shows, plus the new one
        Reply r = put("/salon/staff/" + raju + "/services", Map.of("version", 1, "serviceIds", List.of(old, fade)),
                owner);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(objects(r.body().get("serviceIds"))).containsExactlyInAnyOrder(old, fade);
    }

    @Test
    void onlyActiveServicesAndBarbersCountTowardsTheLimits() {
        String owner = salonOwner(OWNER_PHONE);
        UUID shopId = UUID.fromString((String) get("/shops/me", owner).body().get("id"));
        for (int i = 0; i < 100; i++) {
            jdbc.update("INSERT INTO salon_services (id, shop_id, name, duration_min, price_paise, price_is_from, "
                    + "active, sort_order, version, created_at, updated_at) VALUES (?, ?, ?, 30, 10000, false, true, "
                    + "0, 0, now(), now())", UUID.randomUUID(), shopId, "Service " + i);
        }
        Reply full = post("/salon/services", service("One more", 30, 10000, 0), owner);
        assertThat(full.status()).isEqualTo(409);
        assertThat(full.body().get("code")).isEqualTo("TOO_MANY_SERVICES");
        jdbc.update("UPDATE salon_services SET active = false WHERE name = 'Service 0'");
        assertThat(post("/salon/services", service("One more", 30, 10000, 0), owner).status()).isEqualTo(201);

        for (int i = 0; i < 50; i++) {
            jdbc.update("INSERT INTO salon_staff (id, shop_id, name, active, version, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, 0, now(), now())", UUID.randomUUID(), shopId, "Barber " + i, i > 0);
        }
        assertThat(post("/salon/staff", Map.of("name", "Imran"), owner).status()).isEqualTo(201);
        Reply fullStaff = post("/salon/staff", Map.of("name", "Sunil"), owner);
        assertThat(fullStaff.body().get("code")).isEqualTo("TOO_MANY_STAFF");
    }

    @Test
    void switchingOneBackOnCountsLikeAddingIt() {
        String owner = salonOwner(OWNER_PHONE);
        UUID shopId = UUID.fromString((String) get("/shops/me", owner).body().get("id"));
        Map<?, ?> old = post("/salon/services", service("Old style", 30, 10000, 0), owner).body();
        patch("/salon/services/" + old.get("id"), Map.of("version", 0, "active", false), owner);
        Map<?, ?> left = post("/salon/staff", Map.of("name", "Raju"), owner).body();
        patch("/salon/staff/" + left.get("id"), Map.of("version", 0, "active", false), owner);
        for (int i = 0; i < 100; i++) {
            jdbc.update("INSERT INTO salon_services (id, shop_id, name, duration_min, price_paise, price_is_from, "
                    + "active, sort_order, version, created_at, updated_at) VALUES (?, ?, ?, 30, 10000, false, true, "
                    + "0, 0, now(), now())", UUID.randomUUID(), shopId, "Service " + i);
        }
        for (int i = 0; i < 50; i++) {
            jdbc.update("INSERT INTO salon_staff (id, shop_id, name, active, version, created_at, updated_at) "
                    + "VALUES (?, ?, ?, true, 0, now(), now())", UUID.randomUUID(), shopId, "Barber " + i);
        }
        Reply service = patch("/salon/services/" + old.get("id"), Map.of("version", 1, "active", true), owner);
        assertThat(service.status()).isEqualTo(409);
        assertThat(service.body().get("code")).isEqualTo("TOO_MANY_SERVICES");
        Reply barber = patch("/salon/staff/" + left.get("id"), Map.of("version", 1, "active", true), owner);
        assertThat(barber.status()).isEqualTo(409);
        assertThat(barber.body().get("code")).isEqualTo("TOO_MANY_STAFF");
        // Other edits of a switched-off one still work at the limit
        assertThat(patch("/salon/staff/" + left.get("id"), Map.of("version", 1, "name", "Raju K"), owner).status())
                .isEqualTo(200);
    }

    @Test
    void aSalonWithNoBarbersYetGetsAnEmptyList() {
        assertThat(getList("/salon/staff", salonOwner(OWNER_PHONE))).isEmpty();
    }

    @Test
    void aStaleBarberEditIsRefused() {
        String owner = salonOwner(OWNER_PHONE);
        String raju = (String) post("/salon/staff", Map.of("name", "Raju"), owner).body().get("id");
        assertThat(patch("/salon/staff/" + raju, Map.of("version", 0, "active", false), owner).body().get("active"))
                .isEqualTo(false);
        Reply stale = patch("/salon/staff/" + raju, Map.of("version", 0, "name", "Raj"), owner);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.body().get("code")).isEqualTo("STAFF_CHANGED");
    }

    // ---------- settings ----------

    @Test
    void settingsStartAsTheDefaultsAndTheFirstSaveCreatesThem() {
        String owner = salonOwner(OWNER_PHONE);
        Map<?, ?> defaults = get("/salon/settings", owner).body();
        assertThat(defaults.get("minLeadMinutes")).isEqualTo(30);
        assertThat(defaults.get("maxDaysAhead")).isEqualTo(7);
        assertThat(defaults.get("version")).isNull();

        Map<String, Object> first = new HashMap<>();
        first.put("version", null);
        first.put("maxDaysAhead", 14);
        Reply saved = patch("/salon/settings", first, owner);
        assertThat(saved.status()).as(saved.body().toString()).isEqualTo(200);
        assertThat(saved.body().get("maxDaysAhead")).isEqualTo(14);
        assertThat(saved.body().get("minLeadMinutes")).isEqualTo(30);
        assertThat(((Number) saved.body().get("version")).longValue()).isZero();

        // Someone who still has the defaults on screen can't overwrite it
        Reply stale = patch("/salon/settings", first, owner);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.body().get("code")).isEqualTo("SETTINGS_CHANGED");

        Reply next = patch("/salon/settings", Map.of("version", 0, "minLeadMinutes", 60), owner);
        assertThat(next.body().get("minLeadMinutes")).isEqualTo(60);
        assertThat(next.body().get("maxDaysAhead")).isEqualTo(14);
        assertThat(patch("/salon/settings", Map.of("version", 1, "maxDaysAhead", 31), owner).status()).isEqualTo(400);
    }

    @Test
    void twoFirstSettingsSavesAtTheSameMomentKeepOne() throws Exception {
        String owner = salonOwner(OWNER_PHONE);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Reply>> replies = new ArrayList<>();
            for (int days : List.of(10, 20)) {
                replies.add(pool.submit(() -> {
                    start.await();
                    Map<String, Object> body = new HashMap<>();
                    body.put("version", null);
                    body.put("maxDaysAhead", days);
                    return patch("/salon/settings", body, owner);
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Reply> f : replies) {
                statuses.add(f.get(10, TimeUnit.SECONDS).status());
            }
            // The second insert hits the primary key: 409 SETTINGS_CHANGED, not a 500
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM salon_settings", Integer.class)).isEqualTo(1);
    }

    @Test
    void aFirstSettingsSaveThatLosesTheInsertRaceGets409() throws Exception {
        String owner = salonOwner(OWNER_PHONE);
        UUID shopId = UUID.fromString((String) get("/shops/me", owner).body().get("id"));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        // Another first save has inserted the row but hasn't committed: the request can't see it yet
        try (HeldTransaction other = new HeldTransaction(() -> jdbc.update("INSERT INTO salon_settings (shop_id, "
                + "min_lead_min, max_days_ahead, version, updated_at) VALUES (?, 30, 10, 0, now())", shopId))) {
            Map<String, Object> body = new HashMap<>();
            body.put("version", null);
            body.put("maxDaysAhead", 20);
            Future<Reply> save = pool.submit(() -> patch("/salon/settings", body, owner));
            other.waitUntilBlocking("insert");   // its INSERT waits on the other one's primary key
            other.commit();
            Reply r = save.get(10, TimeUnit.SECONDS);
            // The primary key refusal becomes 409, not a 500
            assertThat(r.status()).as(r.body().toString()).isEqualTo(409);
            assertThat(r.body().get("code")).isEqualTo("SETTINGS_CHANGED");
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT max_days_ahead FROM salon_settings", Integer.class)).isEqualTo(10);
    }

    // ---------- the database's own rules ----------

    @Test
    void theDatabaseItselfRefusesCrossSalonLinksAndOverlappingShifts() {
        UUID ownerA = UUID.randomUUID();
        UUID ownerB = UUID.randomUUID();
        TestData.user(jdbc, ownerA, "9000000401");
        TestData.user(jdbc, ownerB, "9000000402");
        UUID salonA = UUID.randomUUID();
        UUID salonB = UUID.randomUUID();
        TestData.salon(jdbc, salonA, ownerA);
        TestData.salon(jdbc, salonB, ownerB);
        UUID serviceA = UUID.randomUUID();
        jdbc.update("INSERT INTO salon_services (id, shop_id, name, duration_min, price_paise, price_is_from, active, "
                + "sort_order, version, created_at, updated_at) VALUES (?, ?, 'Haircut', 30, 15000, false, true, 0, 0, "
                + "now(), now())", serviceA, salonA);
        UUID staffB = UUID.randomUUID();
        jdbc.update("INSERT INTO salon_staff (id, shop_id, name, active, version, created_at, updated_at) "
                + "VALUES (?, ?, 'Imran', true, 0, now(), now())", staffB, salonB);

        // B's barber, A's service: no shop_id makes both keys match
        assertThatThrownBy(() -> jdbc.update("INSERT INTO staff_services (id, shop_id, staff_id, service_id) "
                + "VALUES (?, ?, ?, ?)", UUID.randomUUID(), salonB, staffB, serviceA))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO staff_services (id, shop_id, staff_id, service_id) "
                + "VALUES (?, ?, ?, ?)", UUID.randomUUID(), salonA, staffB, serviceA))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("INSERT INTO staff_hours (id, staff_id, weekday, start_min, end_min) VALUES (?, ?, 1, 600, 840)",
                UUID.randomUUID(), staffB);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO staff_hours (id, staff_id, weekday, start_min, end_min) "
                + "VALUES (?, ?, 1, 780, 1080)", UUID.randomUUID(), staffB))
                .isInstanceOf(DataIntegrityViolationException.class);
        // Touching is allowed, and the same times on another day too
        jdbc.update("INSERT INTO staff_hours (id, staff_id, weekday, start_min, end_min) VALUES (?, ?, 1, 840, 1080)",
                UUID.randomUUID(), staffB);
        jdbc.update("INSERT INTO staff_hours (id, staff_id, weekday, start_min, end_min) VALUES (?, ?, 2, 600, 840)",
                UUID.randomUUID(), staffB);
    }

    // ---------- helpers ----------

    /**
     * Runs some SQL in a database transaction on its own thread and keeps that transaction open, with its locks,
     * until commit() or close(). Lets a test line up a race exactly instead of hoping two requests overlap.
     */
    private final class HeldTransaction implements AutoCloseable {

        private final CountDownLatch held = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final ExecutorService thread = Executors.newSingleThreadExecutor();
        private final Future<Object> finished;
        private volatile int pid;
        private volatile boolean committed;
        private volatile boolean abandoned;   // the test ended without commit(): roll the held SQL back

        HeldTransaction(Runnable sql) throws Exception {
            finished = thread.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                sql.run();
                Integer backend = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                pid = backend == null ? 0 : backend;
                held.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    abandoned = true;
                    Thread.currentThread().interrupt();
                }
                if (abandoned) {
                    status.setRollbackOnly();
                }
                return null;
            }));
            for (int i = 0; !held.await(50, TimeUnit.MILLISECONDS); i++) {
                if (finished.isDone() || i >= 200) {   // the held SQL failed, or 10 s went by
                    close();
                    try {
                        finished.get(0, TimeUnit.SECONDS);
                    } catch (java.util.concurrent.ExecutionException e) {
                        throw new AssertionError("The held SQL failed", e.getCause());
                    } catch (java.util.concurrent.TimeoutException e) {
                        throw new AssertionError("The held transaction never started within 10 s", e);
                    }
                    throw new AssertionError("The held transaction never started");
                }
            }
        }

        /**
         * Waits (up to 10 s) until a session is blocked by this transaction while running a statement starting with
         * the given word (select / insert), so the test knows the request is queued exactly where it expects.
         */
        void waitUntilBlocking(String statement) throws InterruptedException {
            for (int i = 0; i < 200; i++) {
                Integer waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity "
                                + "WHERE ? = ANY(pg_blocking_pids(pid)) AND query ILIKE ?",
                        Integer.class, pid, statement + "%");
                if (waiting != null && waiting > 0) {
                    return;
                }
                Thread.sleep(50);
            }
            throw new AssertionError("No request was ever blocked by the held transaction in a " + statement);
        }

        void commit() throws Exception {
            committed = true;
            release.countDown();
            finished.get(10, TimeUnit.SECONDS);
        }

        /**
         * Without commit() (the test failed early), rolls the held SQL back. Waits up to 10 s for the thread to end,
         * so its transaction is finished before the next test cleans the tables.
         */
        @Override
        public void close() {
            if (!committed) {
                abandoned = true;
            }
            release.countDown();
            thread.shutdownNow();
            try {
                thread.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Map<String, Object> shop(String type) {
        Map<String, Object> shop = new HashMap<>();
        shop.put("name", "Sharma Hair Studio");
        shop.put("ownerName", "Ramesh Sharma");
        shop.put("addressLine", "Shop 4, Lake View Apartments");
        shop.put("area", "Kondapur");
        shop.put("city", "Hyderabad");
        shop.put("pincode", "500084");
        shop.put("latitude", new BigDecimal("17.465123"));
        shop.put("longitude", new BigDecimal("78.364456"));
        if (type != null) {
            shop.put("type", type);
        }
        return shop;
    }

    private static Map<String, Object> service(String name, int minutes, long paise, int sortOrder) {
        Map<String, Object> s = new HashMap<>();
        s.put("name", name);
        s.put("durationMinutes", minutes);
        s.put("pricePaise", paise);
        s.put("sortOrder", sortOrder);
        return s;
    }

    private static Map<String, Object> shift(int weekday, String start, String end) {
        return Map.of("weekday", weekday, "start", start, "end", end);
    }

    /** Signs in and registers a salon; returns the owner's access token. */
    private String salonOwner(String phone) {
        String access = token(phone);
        Reply r = post("/shops", shop("SALON"), access);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        return access;
    }

    /** Signs a second person in and makes them a helper in the owner's salon; returns their access token. */
    private String helperOf(String ownerToken, String phone) {
        UUID shopId = UUID.fromString((String) get("/shops/me", ownerToken).body().get("id"));
        Map<?, ?> session = signIn(phone);
        UUID helperId = UUID.fromString((String) ((Map<?, ?>) session.get("user")).get("id"));
        TestData.member(jdbc, shopId, helperId, "HELPER");
        return (String) session.get("accessToken");
    }

    private void assertInvalidService(String owner, Map<String, Object> body, String field) {
        Reply r = post("/salon/services", body, owner);
        assertThat(r.status()).as(body + " \u2192 " + r.body()).isEqualTo(400);
        assertThat(((Map<?, ?>) r.body().get("details")).containsKey(field)).as(r.body().toString()).isTrue();
    }

    private void assertInvalidHours(String owner, String staffId, List<Map<String, Object>> week, String field) {
        Reply r = put("/salon/staff/" + staffId + "/hours", Map.of("version", 0, "hours", week), owner);
        assertThat(r.status()).as(week + " \u2192 " + r.body()).isEqualTo(400);
        assertThat(((Map<?, ?>) r.body().get("details")).containsKey(field)).as(r.body().toString()).isTrue();
    }

    private static void assertOwnerOnly(Reply r) {
        assertThat(r.status()).as(r.body().toString()).isEqualTo(403);
        assertThat(r.body().get("code")).isEqualTo("OWNER_ONLY");
    }

    private Map<?, ?> signIn(String phone) {
        post("/auth/otp/send", Map.of("phone", phone), null);
        Reply r = post("/auth/otp/verify", Map.of("phone", phone, "code", codeSentTo(LAST_SMS, phone)), null);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        in.apnidukan.support.TestData.completeProfile(jdbc, phone);   // the email and name screens
        return r.body();
    }

    private String token(String phone) {
        return (String) signIn(phone).get("accessToken");
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

    /** A JSON array from a reply, typed so AssertJ can compare its items with plain values. */
    private static List<Object> objects(Object jsonArray) {
        return new ArrayList<>((List<?>) jsonArray);
    }

    /** For endpoints that return a JSON list. */
    private List<?> getList(String path, String token) {
        return http.get().uri(path).headers(h -> bearer(h, token)).retrieve().body(List.class);
    }

    private static void bearer(org.springframework.http.HttpHeaders headers, String token) {
        if (token != null) {
            headers.setBearerAuth(token);
        }
    }

    private static Reply reply(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse res)
            throws java.io.IOException {
        int status = res.getStatusCode().value();
        Map<?, ?> body = status == 202 || status == 204 ? Map.of() : res.bodyTo(Map.class);
        return new Reply(status, body == null ? Map.of() : body);
    }

    private record Reply(int status, Map<?, ?> body) {
    }
}
