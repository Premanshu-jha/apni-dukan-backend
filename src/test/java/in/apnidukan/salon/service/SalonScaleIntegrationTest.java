package in.apnidukan.salon.service;

import in.apnidukan.common.context.ShopChangeListener;
import in.apnidukan.common.context.ShopDirectory;
import in.apnidukan.common.jobs.LongStatements;
import in.apnidukan.common.notify.MessageText;
import in.apnidukan.common.security.AuthPrincipal;
import in.apnidukan.common.security.JwtService;
import in.apnidukan.common.security.SessionRevocations;
import in.apnidukan.salon.domain.Appointment;
import in.apnidukan.salon.domain.AppointmentKind;
import in.apnidukan.salon.domain.AppointmentStatus;
import in.apnidukan.support.CapturingSenders;
import in.apnidukan.support.TestClock;
import in.apnidukan.support.TestContainers;
import in.apnidukan.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The salon's scale fixes against real Postgres and Redis: the change counter (salon_versions) and the ETags of the
 * day board and upcoming list, keyset pages (upcoming, history, past bookings), the set-based reminder rewrite, closed
 * days in batches, the cached setup and the read limit for customers, mark-all-read in slices, and the statement
 * timeout. Every statement the app sends is counted (a DataSource wrapper), so "a 304 runs one query" is checked, not
 * assumed.
 *
 * The salon: Raju, Sunil and Arif do Haircut (30 min) and Beard (15); all work every day, all day. "Now" is Tuesday
 * 10 Nov 2026, 10:00 in India.
 */
@EnabledIf("in.apnidukan.support.TestContainers#dockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({CapturingSenders.class, SalonScaleIntegrationTest.Fakes.class})
class SalonScaleIntegrationTest {

    static final Instant TUESDAY_10_00 = Instant.parse("2026-11-10T04:30:00Z");
    static final TestClock CLOCK = new TestClock(TUESDAY_10_00);
    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    /** Statements sent to the database by the app (prepared, plain or callable), counted by the DataSource wrapper. */
    static final AtomicInteger STATEMENTS = new AtomicInteger();

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        TestClock testClock() {
            return CLOCK;
        }

        /** Wraps the app's DataSource so every statement it prepares is counted. */
        @Bean
        static BeanPostProcessor countStatements() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof DataSource ds) || Proxy.isProxyClass(bean.getClass())) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                            (proxy, method, args) -> {
                                Object result = call(ds, method, args);
                                return result instanceof Connection c ? counted(c) : result;
                            });
                }
            };
        }

        private static Connection counted(Connection c) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        String m = method.getName();
                        if (m.equals("prepareStatement") || m.equals("createStatement") || m.equals("prepareCall")) {
                            STATEMENTS.incrementAndGet();
                        }
                        return call(c, method, args);
                    });
        }

        private static Object call(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        TestContainers.register(registry);
        // Reminders also go out as SMS here, so the rewritten SMS variables are checked too
        registry.add("app.sms.template.booking-reminder", () -> "tpl-reminder");
        // Slices of three: mark-all-read goes through its loop
        registry.add("app.notifications.mark-all-slice", () -> "3");
    }

    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired JwtService jwt;
    @Autowired SessionRevocations sessions;
    @Autowired List<ShopChangeListener> shopListeners;
    @Autowired ShopDirectory shopDirectory;
    @Autowired SalonNotices notices;
    @Autowired LongStatements longStatements;
    @Autowired PlatformTransactionManager transactions;
    @Autowired org.flywaydb.core.Flyway flyway;
    @Autowired List<in.apnidukan.common.context.AccountDeletionListener> deletionListeners;

    RestClient http;
    UUID owner;
    UUID salon;
    UUID raju;
    UUID sunil;
    UUID arif;
    UUID haircut;
    UUID beard;
    UUID customer;
    int bookingNo;

    @BeforeEach
    void setUp() {
        http = RestClient.builder()
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + environment.getProperty("local.server.port") + "/api/v1")
                .build();
        TestData.clean(jdbc);
        redis.execute((RedisCallback<Object>) c -> {
            c.serverCommands().flushAll();
            return null;
        });
        CLOCK.set(TUESDAY_10_00);
        owner = person("9000000001");
        customer = person("9000000101");
        salon = UUID.randomUUID();
        TestData.salon(jdbc, salon, owner);
        jdbc.update("UPDATE shops SET status = 'APPROVED' WHERE id = ?", salon);
        haircut = id(post("/salon/services", Map.of("name", "Haircut", "durationMinutes", 30, "pricePaise", 15000),
                owner));
        beard = id(post("/salon/services", Map.of("name", "Beard trim", "durationMinutes", 15, "pricePaise", 8000),
                owner));
        raju = barber("Raju");
        CLOCK.advance(Duration.ofSeconds(1));
        sunil = barber("Sunil");
        CLOCK.advance(Duration.ofSeconds(1));
        arif = barber("Arif");
        CLOCK.set(TUESDAY_10_00);
    }

    // ---------- the change counter and ETags ----------

    @Test
    void anUnchangedDayIsA304AfterOnePrimaryKeyReadAndNothingElse() {
        id(book(customer, at("12:00")));
        String tag = etag(get("/salon/day", owner));
        assertThat(tag).startsWith("\"s1-");   // our own tag, not EtagConfig's fingerprint ("0...")

        String token = token(owner);   // made before counting: the test's own queries don't count
        STATEMENTS.set(0);
        Reply same = getWithTag("/salon/day", token, tag);
        assertThat(same.status()).isEqualTo(304);
        assertThat(same.text()).isEmpty();
        assertThat(same.etag()).isEqualTo(tag);
        assertThat(STATEMENTS.get()).isEqualTo(1);   // SELECT version ... FROM salon_versions WHERE shop_id = ?

        // The upcoming list too
        String upcoming = etag(get("/salon/upcoming", owner));
        STATEMENTS.set(0);
        assertThat(getWithTag("/salon/upcoming", token, upcoming).status()).isEqualTo(304);
        assertThat(STATEMENTS.get()).isEqualTo(1);

        // A tag that isn't ours, or no tag, costs nothing extra and builds the answer
        STATEMENTS.set(0);
        assertThat(getWithTag("/salon/day", token, "\"0abc\"").status()).isEqualTo(200);
        assertThat(STATEMENTS.get()).isGreaterThan(1);
    }

    @Test
    void everyKindOfChangeMovesTheCounterAndGivesA200() {
        UUID booked = id(book(customer, at("12:00")));
        // Appointments and their services
        changes("a booking", () -> id(book(person("9000000102"), at("13:00"))), false);
        changes("a salon cancel", () -> assertThat(post("/salon/appointments/" + booked + "/cancel",
                Map.of("reason", "Shop closing early"), owner).status()).isEqualTo(200), false);
        changes("a walk-in", () -> id(walkIn(raju)), false);
        changes("a break", () -> assertThat(post("/salon/staff/" + sunil + "/blocks", Map.of("type", "BREAK",
                "startAt", at("16:00").toString(), "endAt", at("16:30").toString()), owner).status()).isEqualTo(201),
                false);
        // The setup
        changes("a barber renamed", () -> assertThat(patch("/salon/staff/" + arif, Map.of("version",
                staffVersion(arif), "name", "Arif Khan"), owner).status()).isEqualTo(200), true);
        changes("his services", () -> assertThat(put("/salon/staff/" + arif + "/services", Map.of("version",
                staffVersion(arif), "serviceIds", List.of(haircut)), owner).status()).isEqualTo(200), true);
        changes("his hours", () -> assertThat(put("/salon/staff/" + arif + "/hours", Map.of("version",
                staffVersion(arif), "hours", List.of(Map.of("weekday", 2, "start", "10:00", "end", "20:00"))), owner)
                .status()).isEqualTo(200), true);
        changes("a service", () -> assertThat(patch("/salon/services/" + beard, Map.of("version",
                serviceVersion(beard), "pricePaise", 9000), owner).status()).isEqualTo(200), true);
        changes("the settings", () -> assertThat(patch("/salon/settings", Map.of("maxDaysAhead", 10), owner)
                .status()).isEqualTo(200), true);
        // Who works there and what they may do
        UUID helper = person("9000000301");
        changes("a helper added", () -> assertThat(post("/shops/me/helpers", Map.of("phone", "9000000301"), owner)
                .status()).isBetween(200, 201), false);
        changes("walk-in access", () -> assertThat(put("/salon/members/" + helper + "/walk-in-access",
                Map.of("allowed", true), owner).status()).isEqualTo(200), false);
        changes("a pause", () -> assertThat(post("/salon/pause", Map.of(), owner).status()).isEqualTo(200), false);
        // A customer's name shows on the board
        changes("a customer renamed", () -> assertThat(patch("/auth/me", Map.of("name", "Ravi Kumar"), customer)
                .status()).isEqualTo(200), false);
    }

    /**
     * The day board's tag before and after the change: a new one, a 200 for the old tag, and the counters moved
     * (setup_version only for setup changes).
     */
    private void changes(String what, Runnable change, boolean setup) {
        String tag = etag(get("/salon/day", owner));
        long[] before = versions();
        change.run();
        long[] after = versions();
        assertThat(after[0]).as(what).isNotEqualTo(before[0]);
        assertThat(after[1] != before[1]).as(what + " moves setup_version").isEqualTo(setup);
        Reply again = getWithTag("/salon/day", token(owner), tag);
        assertThat(again.status()).as(what).isEqualTo(200);
        assertThat(again.etag()).as(what).isNotEqualTo(tag);
    }

    @Test
    void aBarberAndTheOwnerGetDifferentTagsAndOneCantUseTheOther() {
        id(book(customer, at("12:00")));
        put("/salon/staff/" + raju + "/login", Map.of("phone", "9000000201"), owner);
        UUID rajuUser = userWithPhone("9000000201");
        Reply his = get("/salon/day", rajuUser);
        Reply mine = get("/salon/day", owner);
        assertThat(etag(his)).isNotEqualTo(etag(mine));
        // He sees only his own column; the owner sees all three
        assertThat((List<?>) field(his.body(), "barbers")).hasSize(1);
        assertThat((List<?>) field(mine.body(), "barbers")).hasSize(3);
        assertThat(getWithTag("/salon/day", token(owner), etag(his)).status()).isEqualTo(200);
        assertThat(getWithTag("/salon/day", token(rajuUser), etag(mine)).status()).isEqualTo(200);
        assertThat(getWithTag("/salon/day", token(rajuUser), etag(his)).status()).isEqualTo(304);
        // Another date is another answer
        assertThat(getWithTag("/salon/day?date=2026-11-11", token(owner), etag(mine)).status()).isEqualTo(200);
        // A tag edited by hand (a later validUntil) no longer matches its fingerprint
        String forged = etag(mine).replaceFirst("s1-\\d+-", "s1-9999999999-");
        assertThat(getWithTag("/salon/day", token(owner), forged).status()).isEqualTo(200);
    }

    @Test
    void timeAloneChangesTheAnswerOnlyAtAStartAnEndOrMidnightAndThenTheTagStops() {
        UUID b = id(book(customer, at("11:00")));   // 11:00-11:30
        CLOCK.set(at("10:58"));
        String day = etag(get("/salon/day", owner));
        String upcoming = etag(get("/salon/upcoming", owner));
        CLOCK.set(at("10:59:59"));
        assertThat(getWithTag("/salon/day", token(owner), day).status()).isEqualTo(304);
        assertThat(getWithTag("/salon/upcoming", token(owner), upcoming).status()).isEqualTo(304);
        // 11:00: the booking hasn't started, so it is "not started" now
        CLOCK.set(at("11:00"));
        Reply now = getWithTag("/salon/day", token(owner), day);
        assertThat(now.status()).isEqualTo(200);
        assertThat(now.text()).contains("\"notStarted\":true");
        assertThat(getWithTag("/salon/upcoming", token(owner), upcoming).status()).isEqualTo(200);
        // Its end: it leaves the upcoming list
        upcoming = etag(get("/salon/upcoming", owner));
        CLOCK.set(at("11:30"));
        Reply gone = getWithTag("/salon/upcoming", token(owner), upcoming);
        assertThat(gone.status()).isEqualTo(200);
        assertThat(gone.text()).doesNotContain(b.toString());
        // "Today" (no date) is another day after midnight
        String today = etag(get("/salon/day", owner));
        CLOCK.set(at("00:00").plus(Duration.ofDays(1)));
        Reply tomorrow = getWithTag("/salon/day", token(owner), today);
        assertThat(tomorrow.status()).isEqualTo(200);
        assertThat(field(tomorrow.body(), "date")).isEqualTo("2026-11-11");
    }

    @Test
    void aTokenWithoutTheShopStillGetsEtagConfigsFingerprintAnd304() {
        id(book(customer, at("12:00")));
        String bare = bareToken(owner);
        Reply first = call(HttpMethod.GET, "/salon/day", null, bare, null);
        assertThat(first.etag()).startsWith("\"0");   // ShallowEtagHeaderFilter's
        assertThat(getWithTag("/salon/day", bare, first.etag()).status()).isEqualTo(304);
    }

    @Test
    void aRenameTakesTheSalonsCountersBeforeThePersonsRowSoItCanOnlyWaitNeverDeadlock() throws Exception {
        id(book(customer, at("12:00")));
        long before = versions()[0];
        TransactionTemplate tx = new TransactionTemplate(transactions);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            // A salon change in progress holds the counter (as a cancel does, before its message checks the person)
            Future<?> salonChange = threads.submit(() -> tx.executeWithoutResult(s -> {
                jdbc.queryForList("SELECT version FROM salon_versions WHERE shop_id = ? FOR UPDATE", salon);
                holding.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Reply> rename = threads.submit(() -> patch("/auth/me", Map.of("name", "Ravi Kumar"), customer));
            for (int i = 0; i < 100 && count("pg_stat_activity WHERE wait_event_type = 'Lock' "
                    + "AND query LIKE '%salon_versions_bump%'") == 0; i++) {
                Thread.sleep(50);
            }
            assertThat(count("pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE '%salon_versions_bump%'"))
                    .isEqualTo(1);   // the rename waits for the counter...
            // ...and doesn't hold the person's row meanwhile: the salon change could still take it
            org.springframework.transaction.support.TransactionCallback<List<Map<String, Object>>> keyShare =
                    s -> jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR KEY SHARE NOWAIT", customer);
            List<Map<String, Object>> taken = tx.execute(keyShare);
            assertThat(taken).hasSize(1);
            release.countDown();
            salonChange.get(30, TimeUnit.SECONDS);
            assertThat(rename.get(30, TimeUnit.SECONDS).status()).isEqualTo(200);
        } finally {
            release.countDown();
            threads.shutdown();
        }
        assertThat(versions()[0]).isNotEqualTo(before);
    }

    @Test
    void anAccountDeletionTakesTheBookingRowBeforeTheCounterSoASalonCancelMakesItWaitNotDeadlock() throws Exception {
        UUID b = id(book(customer, at("12:00")));
        SalonAccountDeletion deletion = deletionListeners.stream().filter(SalonAccountDeletion.class::isInstance)
                .map(SalonAccountDeletion.class::cast).findFirst().orElseThrow();
        TransactionTemplate tx = new TransactionTemplate(transactions);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            // The salon is cancelling that booking: it holds the row, and its update will move the counter next
            Future<?> salonCancel = threads.submit(() -> tx.executeWithoutResult(s -> {
                jdbc.queryForList("SELECT id FROM appointments WHERE id = ? FOR UPDATE", b);
                holding.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                jdbc.update("UPDATE appointments SET status = 'CANCELLED_BY_SALON', cancel_reason = 'Closed' "
                        + "WHERE id = ?", b);
            }));
            assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> deleting = threads.submit(() -> tx.executeWithoutResult(s ->
                    deletion.accountDeleted(customer, at("10:00"))));
            for (int i = 0; i < 100 && count("pg_stat_activity WHERE wait_event_type = 'Lock' "
                    + "AND query LIKE '%customer_id = $1 AND status IN%FOR UPDATE%'") == 0; i++) {
                Thread.sleep(50);
            }
            assertThat(count("pg_stat_activity WHERE wait_event_type = 'Lock' "
                    + "AND query LIKE '%customer_id = $1 AND status IN%FOR UPDATE%'")).isEqualTo(1);
            // The deletion waits for the booking row and holds no salon counter meanwhile
            org.springframework.transaction.support.TransactionCallback<List<Map<String, Object>>> counter =
                    s -> jdbc.queryForList("SELECT version FROM salon_versions WHERE shop_id = ? FOR UPDATE NOWAIT",
                            salon);
            List<Map<String, Object>> free = tx.execute(counter);
            assertThat(free).hasSize(1);
            release.countDown();
            salonCancel.get(30, TimeUnit.SECONDS);   // its counter move goes through: no deadlock
            deleting.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            threads.shutdown();
        }
        assertThat(jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, b))
                .isEqualTo("CANCELLED_BY_SALON");
    }

    @Test
    void aRenameWaitsForABookingInProgressSoTheNewSalonsCounterMovesTooAndAnUnchangedNameMovesNothing()
            throws Exception {
        UUID owner2 = person("9000000002");
        UUID salon2 = UUID.randomUUID();
        TestData.salon(jdbc, salon2, owner2);
        UUID barber2 = UUID.randomUUID();
        jdbc.update("INSERT INTO salon_staff (id, shop_id, name, active, version, created_at, updated_at) VALUES "
                + "(?, ?, 'Kiran', true, 0, now(), now())", barber2, salon2);
        TransactionTemplate tx = new TransactionTemplate(transactions);
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        long[] versionInBooking = new long[1];
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            // A booking at salon2 in progress: the customer's lock, its row, salon2's counter, not committed yet
            Future<?> booking = threads.submit(() -> tx.executeWithoutResult(s -> {
                jdbc.query("SELECT pg_advisory_xact_lock(7101, hashtext(?))", rs -> null, customer.toString());
                jdbc.update("INSERT INTO appointments (id, shop_id, staff_id, kind, customer_id, booking_no, "
                        + "start_code, starts_at, ends_at, status, created_by, created_at, updated_at) VALUES (?, ?, ?, "
                        + "'BOOKING', ?, 'TRACE01', '1234', ?, ?, 'CONFIRMED', ?, now(), now())", UUID.randomUUID(),
                        salon2, barber2, customer, Timestamp.from(at("15:00")), Timestamp.from(at("15:30")), customer);
                versionInBooking[0] = jdbc.queryForObject("SELECT version FROM salon_versions WHERE shop_id = ?",
                        Long.class, salon2);
                inserted.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Reply> rename = threads.submit(() -> patch("/auth/me", Map.of("name", "Ravi Kumar"), customer));
            for (int i = 0; i < 100 && count("pg_stat_activity WHERE wait_event_type = 'Lock' "
                    + "AND query LIKE '%pg_advisory_xact_lock%'") == 0; i++) {
                Thread.sleep(50);
            }
            assertThat(count("pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE "
                    + "'%pg_advisory_xact_lock%'")).isEqualTo(1);   // the rename waits for the booking
            release.countDown();
            booking.get(30, TimeUnit.SECONDS);
            assertThat(rename.get(30, TimeUnit.SECONDS).status()).isEqualTo(200);
        } finally {
            release.countDown();
            threads.shutdown();
        }
        long after = jdbc.queryForObject("SELECT version FROM salon_versions WHERE shop_id = ?", Long.class, salon2);
        assertThat(after).isNotEqualTo(versionInBooking[0]);   // the new salon's board shows the new name

        long[] before = versions();
        assertThat(patch("/auth/me", Map.of("name", "Ravi Kumar"), customer).status()).isEqualTo(200);
        assertThat(versions()).isEqualTo(before);   // the same name: nothing to tell
    }

    // ---------- pages ----------

    @Test
    void theUpcomingListComes100APageWithTiesAcrossTheEdgeNothingTwiceOrMissing() {
        // 230 bookings, three barbers at each start: the page edges fall inside a group of three
        Set<String> all = new HashSet<>();
        for (int i = 0; i < 230; i++) {
            UUID barber = List.of(raju, sunil, arif).get(i % 3);
            all.add(insertBooking(customer, barber, at("11:00").plus(Duration.ofMinutes(30L * (i / 3))), 30,
                    "CONFIRMED").toString());
        }
        List<Object> seen = new ArrayList<>();
        List<?> page = (List<?>) get("/salon/upcoming", owner).body();
        List<Integer> sizes = new ArrayList<>();
        while (true) {
            sizes.add(page.size());
            seen.addAll(page);
            if (page.size() < 100) {
                break;
            }
            Object last = page.get(page.size() - 1);
            Reply next = get("/salon/upcoming?afterStart=" + field(last, "startsAt") + "&afterId=" + field(last, "id"),
                    owner);
            assertThat(next.status()).as(next.text()).isEqualTo(200);
            page = (List<?>) next.body();
        }
        assertThat(sizes).containsExactly(100, 100, 30);
        assertThat(seen).extracting(x -> (String) field(x, "id")).containsExactlyInAnyOrderElementsOf(all);
        List<String> order = seen.stream().map(x -> field(x, "startsAt") + "/" + field(x, "id")).toList();
        assertThat(order).isSortedAccordingTo((a, b) -> {
            int byStart = Instant.parse(a.split("/")[0]).compareTo(Instant.parse(b.split("/")[0]));
            return byStart != 0 ? byStart : a.split("/")[1].compareTo(b.split("/")[1]);
        });
        // Each page has its own tag
        Object last = seen.get(99);
        String path = "/salon/upcoming?afterStart=" + field(last, "startsAt") + "&afterId=" + field(last, "id");
        String second = etag(get(path, owner));
        assertThat(getWithTag(path, token(owner), second).status()).isEqualTo(304);
        assertThat(getWithTag("/salon/upcoming", token(owner), second).status()).isEqualTo(200);
    }

    @Test
    void exactly100UpcomingGiveAnEmptySecondPageAndBadCursorsAreRefused() {
        for (int i = 0; i < 100; i++) {
            insertBooking(customer, List.of(raju, sunil).get(i % 2), at("11:00").plus(Duration.ofMinutes(30L * (i / 2))),
                    30, "CONFIRMED");
        }
        List<?> first = (List<?>) get("/salon/upcoming", owner).body();
        assertThat(first).hasSize(100);
        Object last = first.get(99);
        assertThat((List<?>) get("/salon/upcoming?afterStart=" + field(last, "startsAt") + "&afterId="
                + field(last, "id"), owner).body()).isEmpty();
        Reply alone = get("/salon/upcoming?afterId=" + UUID.randomUUID(), owner);
        assertThat(alone.status()).isEqualTo(400);
        assertThat(field(alone.body(), "code")).isEqualTo("VALIDATION_FAILED");
        Reply unreadable = get("/salon/upcoming?afterStart=yesterday&afterId=" + UUID.randomUUID(), owner);
        assertThat(unreadable.status()).isEqualTo(400);
        assertThat(field(unreadable.body(), "code")).isEqualTo("BAD_REQUEST");
        assertThat(field(get("/salon/upcoming?afterStart=" + at("11:00") + "&afterId=not-an-id", owner).body(),
                "code")).isEqualTo("BAD_REQUEST");
    }

    @Test
    void theHistoryComes100APageWithNextAndTheCountsCoverTheWholeRange() {
        Set<String> all = new HashSet<>();
        Instant monday = at("08:00").minus(Duration.ofDays(1));
        for (int i = 0; i < 205; i++) {
            all.add(insertBooking(customer, List.of(raju, sunil, arif).get(i % 3),
                    monday.plus(Duration.ofMinutes(10L * (i / 3))), 30, "COMPLETED").toString());
        }
        List<Object> seen = new ArrayList<>();
        String path = "/salon/history?from=2026-11-09&to=2026-11-09";
        Reply page = get(path, owner);
        List<Integer> sizes = new ArrayList<>();
        while (true) {
            assertThat(page.status()).as(page.text()).isEqualTo(200);
            assertThat(field(page.body(), "counts")).isEqualTo(Map.of("COMPLETED", 205));   // every page
            List<?> items = (List<?>) field(page.body(), "appointments");
            sizes.add(items.size());
            seen.addAll(items);
            Object next = field(page.body(), "next");
            assertThat(field(page.body(), "truncated")).isEqualTo(next != null);
            if (next == null) {
                break;
            }
            Object last = items.get(items.size() - 1);
            assertThat(field(next, "afterStart")).isEqualTo(field(last, "startsAt"));
            assertThat(field(next, "afterId")).isEqualTo(field(last, "id"));
            page = get(path + "&afterStart=" + field(next, "afterStart") + "&afterId=" + field(next, "afterId"),
                    owner);
        }
        assertThat(sizes).containsExactly(100, 100, 5);
        assertThat(seen).extracting(x -> (String) field(x, "id")).containsExactlyInAnyOrderElementsOf(all);
        // Latest first, ties by id
        List<String> order = seen.stream().map(x -> field(x, "startsAt") + "/" + field(x, "id")).toList();
        assertThat(order).isSortedAccordingTo((a, b) -> {
            int byStart = Instant.parse(b.split("/")[0]).compareTo(Instant.parse(a.split("/")[0]));
            return byStart != 0 ? byStart : a.split("/")[1].compareTo(b.split("/")[1]);
        });
        assertThat(get(path + "&afterStart=" + monday, owner).status()).isEqualTo(400);
    }

    @Test
    void pastBookingsCome30APageAndTheUpcomingListTakesNoCursor() {
        Set<String> all = new HashSet<>();
        Instant lastWeek = at("10:00").minus(Duration.ofDays(7));
        for (int i = 0; i < 65; i++) {
            all.add(insertBooking(customer, List.of(raju, sunil).get(i % 2),
                    lastWeek.plus(Duration.ofMinutes(30L * (i / 2))), 30, "COMPLETED").toString());
        }
        id(book(customer, at("12:00")));   // upcoming: never in the past list
        List<Object> seen = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        List<?> page = (List<?>) get("/me/bookings?when=past", customer).body();
        while (true) {
            sizes.add(page.size());
            seen.addAll(page);
            if (page.size() < 30) {
                break;
            }
            Object last = page.get(page.size() - 1);
            page = (List<?>) get("/me/bookings?when=past&afterStart=" + field(last, "startsAt") + "&afterId="
                    + field(last, "id"), customer).body();
        }
        assertThat(sizes).containsExactly(30, 30, 5);
        assertThat(seen).extracting(x -> (String) field(x, "id")).containsExactlyInAnyOrderElementsOf(all);
        assertThat(seen).allMatch(x -> "Test Shop".equals(field(x, "salonName")));
        assertThat(field(get("/me/bookings?afterStart=" + lastWeek + "&afterId=" + UUID.randomUUID(), customer)
                .body(), "code")).isEqualTo("VALIDATION_FAILED");
        assertThat(get("/me/bookings?when=past&afterId=" + UUID.randomUUID(), customer).status()).isEqualTo(400);
    }

    // ---------- a salon that moves ----------

    @Test
    void aNewAddressRewritesEveryPendingReminderInOneStatementWithExactlyTheTextJavaWrites() {
        // Times that give "h:mm AM" of 7 and 8 characters for the start and the held-until, midnight and noon
        List<String> times = List.of("00:00", "00:45", "01:00", "09:45", "10:30", "11:45", "12:00", "12:45", "13:15",
                "21:50", "23:45");
        List<UUID> bookings = new ArrayList<>();
        for (int day = 1; day <= 30; day++) {   // 330 bookings ahead, each with a waiting reminder (push and SMS)
            for (int t = 0; t < times.size(); t++) {
                Instant start = LocalDate.of(2026, 11, 10).plusDays(day).atTime(LocalTime.parse(times.get(t)))
                        .atZone(INDIA).toInstant();
                UUID b = insertBooking(customer, List.of(raju, sunil, arif).get(t % 3), start, 15, "CONFIRMED");
                insertReminder(b, "PUSH", "PENDING");
                insertReminder(b, "SMS", "PENDING");
                bookings.add(b);
            }
        }
        UUID sent = insertBooking(customer, raju, at("23:00"), 15, "CONFIRMED");
        insertReminder(sent, "PUSH", "SENT");   // already out: left alone
        UUID cancelled = insertBooking(customer, arif, at("23:00"), 15, "CANCELLED_BY_CUSTOMER");
        insertReminder(cancelled, "PUSH", "PENDING");   // not a booking still to come: left alone

        // A long address and an emoji in the name: the body is cut at 500 characters, inside the address
        jdbc.update("UPDATE shops SET name = ?, address_line = ?, area = ? WHERE id = ?",
                "Sharma Hair 💈 Studio & Spa " + "x".repeat(60), "Shop 12, " + "Main Road ".repeat(19),
                "Kondapur " + "Phase 2 ".repeat(11), salon);
        STATEMENTS.set(0);
        new TransactionTemplate(transactions).executeWithoutResult(s ->
                shopListeners.forEach(l -> l.locationChanged(salon)));
        // The shop (one read) and the rewrite (one statement), whatever the number of bookings
        assertThat(STATEMENTS.get()).isLessThanOrEqualTo(3);

        var card = shopDirectory.find(salon).orElseThrow();
        for (UUID b : bookings) {
            Appointment a = appointment(b);
            var expected = SalonNotices.reminder(a, card);
            Map<String, Object> push = jdbc.queryForMap("SELECT title, body, url, sms_variables, read_at FROM "
                    + "notification_jobs WHERE ref_id = ? AND channel = 'PUSH'", b);
            assertThat(push.get("title")).isEqualTo(MessageText.cut(expected.title(), MessageText.MAX_TITLE));
            assertThat(push.get("body")).as(a.startsAt().toString())
                    .isEqualTo(MessageText.cut(expected.body(), MessageText.MAX_BODY));
            assertThat(push.get("url")).isEqualTo(expected.url());
            assertThat(push.get("sms_variables")).isNull();
            assertThat(push.get("read_at")).isNull();
            Map<String, String> fitted = new HashMap<>();
            expected.smsVariables().forEach((k, v) -> fitted.put(k, MessageText.cutPlain(v,
                    MessageText.MAX_SMS_VARIABLE)));
            assertThat(jdbc.queryForObject("SELECT sms_variables FROM notification_jobs WHERE ref_id = ? "
                    + "AND channel = 'SMS'", String.class, b)).isEqualTo(MessageText.smsQuery(fitted));
        }
        assertThat(jdbc.queryForObject("SELECT body FROM notification_jobs WHERE ref_id = ? AND channel = 'PUSH'",
                String.class, bookings.get(0))).hasSize(MessageText.MAX_BODY).endsWith("…");
        assertThat(jdbc.queryForObject("SELECT body FROM notification_jobs WHERE ref_id = ?", String.class, sent))
                .isEqualTo("old text");
        assertThat(jdbc.queryForObject("SELECT body FROM notification_jobs WHERE ref_id = ?", String.class,
                cancelled)).isEqualTo("old text");
    }

    @Test
    void theOwnerMovingTheSalonRewritesItsRemindersThroughTheRealChange() {
        UUID b = id(book(customer, at("10:00").plus(Duration.ofDays(2))));
        long version = jdbc.queryForObject("SELECT version FROM shops WHERE id = ?", Long.class, salon);
        Reply moved = patch("/shops/me", Map.of("version", version, "addressLine", "Shop 9, New Market Road"), owner);
        assertThat(moved.status()).as(moved.text()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT body FROM notification_jobs WHERE ref_id = ? AND type = "
                + "'BOOKING_REMINDER' AND channel = 'PUSH'", String.class, b)).contains("Shop 9, New Market Road")
                .startsWith("10:00 AM at Test Shop. Start code ").contains(". Held until 10:15 AM. ");
    }

    // ---------- closed days ----------

    @Test
    void closedDaysAreListedOncePerDayAndABarberSwitchedOnJoinsThemAllInBatches() {
        for (String date : List.of("2026-11-12", "2026-11-14", "2026-11-20")) {
            assertThat(post("/salon/closures", Map.of("date", date), owner).status()).isEqualTo(201);
        }
        List<?> closures = (List<?>) get("/salon/closures", owner).body();
        assertThat(closures).extracting(c -> field(c, "date")).containsExactly("2026-11-12", "2026-11-14",
                "2026-11-20");
        assertThat(closures).allMatch(c -> ((Number) field(c, "barbers")).intValue() == 3);

        // A fourth barber with a break inside one closed day (from before he was switched off): removed when he
        // joins; he gets all three days
        UUID imran = barber("Imran");
        assertThat((List<?>) get("/salon/closures", owner).body())
                .allMatch(c -> ((Number) field(c, "barbers")).intValue() == 4);
        assertThat(patch("/salon/staff/" + imran, Map.of("version", staffVersion(imran), "active", false), owner)
                .status()).isEqualTo(200);
        jdbc.update("UPDATE appointments SET status = 'REMOVED' WHERE staff_id = ? AND block_type = 'CLOSED'", imran);
        Instant day14 = LocalDate.of(2026, 11, 14).atStartOfDay(INDIA).toInstant();
        UUID inside = insertBlock(imran, day14.plus(Duration.ofHours(11)), day14.plus(Duration.ofHours(12)));
        Reply on = patch("/salon/staff/" + imran, Map.of("version", staffVersion(imran), "active", true), owner);
        assertThat(on.status()).as(on.text()).isEqualTo(200);
        assertThat(count("appointments WHERE staff_id = '" + imran + "' AND block_type = 'CLOSED' "
                + "AND status = 'BLOCKED'")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, inside))
                .isEqualTo("REMOVED");
        assertThat(count("appointment_events WHERE appointment_id = '" + inside + "' AND event = 'BLOCK_REMOVED'"))
                .isEqualTo(1);

        // Leave reaching outside a closed day: refused, nothing changes
        assertThat(patch("/salon/staff/" + imran, Map.of("version", staffVersion(imran), "active", false), owner)
                .status()).isEqualTo(200);
        jdbc.update("UPDATE appointments SET status = 'REMOVED' WHERE staff_id = ? AND block_type = 'CLOSED'", imran);
        insertBlock(imran, day14.minus(Duration.ofHours(2)), day14.plus(Duration.ofHours(2)));
        Reply refused = patch("/salon/staff/" + imran, Map.of("version", staffVersion(imran), "active", true), owner);
        assertThat(field(refused.body(), "code")).isEqualTo("BLOCK_CONFLICTS");
        assertThat(count("appointments WHERE staff_id = '" + imran + "' AND block_type = 'CLOSED' "
                + "AND status = 'BLOCKED'")).isZero();
    }

    // ---------- the day board's barbers ----------

    @Test
    void theDayBoardReadsActiveBarbersPlusSwitchedOffOnesWithAppointmentsThatDay() {
        insertBooking(customer, arif, at("08:00"), 30, "COMPLETED");
        jdbc.update("UPDATE salon_staff SET active = false WHERE id = ?", arif);
        UUID gone = barber("Old Barber");
        jdbc.update("UPDATE salon_staff SET active = false WHERE id = ?", gone);
        List<?> barbers = (List<?>) field(get("/salon/day", owner).body(), "barbers");
        assertThat(barbers).extracting(b -> field(b, "name")).containsExactly("Raju", "Sunil", "Arif");
        assertThat((List<?>) field(get("/salon/day?date=2026-11-11", owner).body(), "barbers"))
                .extracting(b -> field(b, "name")).containsExactly("Raju", "Sunil");
    }

    // ---------- customers' salon pages ----------

    @Test
    void freeTimesComeFromTheSetupKeptInMemoryUntilTheSetupChanges() {
        String path = "/salons/" + salon + "/availability?services=" + haircut + "," + beard + "&date=2026-11-10";
        assertThat(get(path, customer).status()).isEqualTo(200);
        long setup = versions()[1];
        id(book(customer, at("12:00")));   // a booking doesn't move the setup's number
        assertThat(versions()[1]).isEqualTo(setup);
        // Nobody does the beard any more: seen at once, on the next check
        for (UUID b : List.of(raju, sunil, arif)) {
            assertThat(put("/salon/staff/" + b + "/services", Map.of("version", staffVersion(b),
                    "serviceIds", List.of(haircut)), owner).status()).isEqualTo(200);
        }
        assertThat(field(get(path, customer).body(), "code")).isEqualTo("NO_BARBER_FOR_SERVICES");
        assertThat((List<?>) get("/salons/" + salon + "/services", customer).body())
                .extracting(s -> field(s, "name")).containsExactly("Haircut");
        assertThat((List<?>) field(get("/salons/" + salon, customer).body(), "hours")).hasSize(7);
    }

    @Test
    void sixtySalonReadsAMinuteThenA429WithRetryAfter() {
        String path = "/salons/" + salon + "/availability?services=" + haircut + "&date=2026-11-10";
        for (int i = 0; i < 58; i++) {
            assertThat(get(path, customer).status()).isEqualTo(200);
        }
        assertThat(get("/salons/" + salon, customer).status()).isEqualTo(200);
        assertThat(get("/salons/" + salon + "/services", customer).status()).isEqualTo(200);
        Reply over = get(path, customer);
        assertThat(over.status()).isEqualTo(429);
        assertThat(field(over.body(), "code")).isEqualTo("TOO_MANY_REQUESTS");
        assertThat(over.retryAfter()).isNotNull();
        assertThat(Long.parseLong(over.retryAfter())).isBetween(1L, 60L);
        // Per person: someone else still reads
        assertThat(get(path, person("9000000103")).status()).isEqualTo(200);
    }

    // ---------- messages ----------

    @Test
    void markAllReadGoesInSlicesUntilNothingIsLeft() {
        for (int i = 0; i < 7; i++) {
            jdbc.update("INSERT INTO notification_jobs (id, user_id, channel, type, title, body, send_at, status, "
                    + "attempts, next_try_at, created_at) VALUES (?, ?, 'PUSH', 'SALON_NEW_BOOKING', 't', 'b', ?, "
                    + "'SENT', 1, ?, ?)", UUID.randomUUID(), owner, Timestamp.from(TUESDAY_10_00.minusSeconds(60 + i)),
                    Timestamp.from(TUESDAY_10_00), Timestamp.from(TUESDAY_10_00));
        }
        assertThat(((Number) field(get("/me/notifications/unread-count?app=partner", owner).body(), "unread"))
                .intValue()).isEqualTo(7);
        assertThat(post("/me/notifications/read?app=partner", null, owner).status()).isEqualTo(204);
        assertThat(count("notification_jobs WHERE user_id = '" + owner + "' AND read_at IS NULL")).isZero();
    }

    // ---------- statement limits ----------

    @Test
    void aRequestStatementPast5SecondsIsStoppedAndAnswers503WhileJobsMayTakeLonger() throws Exception {
        // Something holds the salon's counter row: the pause's write waits for it, longer than the request limit
        TransactionTemplate tx = new TransactionTemplate(transactions);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        Future<?> held = holder.submit(() -> tx.executeWithoutResult(s -> {
            jdbc.queryForList("SELECT version FROM salon_versions WHERE shop_id = ? FOR UPDATE", salon);
            holding.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        try {
            assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
            long started = System.nanoTime();
            Reply busy = post("/salon/pause", Map.of(), owner);
            assertThat(busy.status()).as(busy.text()).isEqualTo(503);
            assertThat(field(busy.body(), "code")).isEqualTo("SERVICE_BUSY");
            assertThat(busy.retryAfter()).isEqualTo("5");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isBetween(Duration.ofSeconds(4),
                    Duration.ofSeconds(15));
        } finally {
            release.countDown();
            held.get(30, TimeUnit.SECONDS);
            holder.shutdown();
        }
        assertThat(count("salon_pauses")).isZero();   // nothing was saved

        // A plain statement over the limit is stopped; inside a job's slice it may run on
        assertThatThrownBy(() -> jdbc.queryForObject("SELECT pg_sleep(5.5)::text", String.class))
                .isInstanceOfAny(QueryTimeoutException.class, DataAccessResourceFailureException.class);
        assertThat(longStatements.run(() -> jdbc.queryForObject("SELECT pg_sleep(5.5)::text", String.class)))
                .isNotNull();
        // ...and the connection goes back to the pool with the request limit again
        assertThatThrownBy(() -> jdbc.queryForObject("SELECT pg_sleep(5.5)::text", String.class))
                .isInstanceOfAny(QueryTimeoutException.class, DataAccessResourceFailureException.class);
    }

    @Test
    void aJobsLongerLimitHoldsForEveryStatementInItsTransactionAndFlywayRunsWithoutALimit() throws Exception {
        // SET LOCAL lives as long as the transaction: every statement of the slice runs under it, including those of
        // a @Transactional service it calls (they join the same transaction, as SalonDayService.markNoShows does).
        // That is also why it works through Neon's pooler: one transaction stays on one server connection.
        assertThat(longStatements.run(() -> jdbc.queryForObject("SHOW statement_timeout", String.class)))
                .isEqualTo("1min");
        TransactionTemplate joining = new TransactionTemplate(transactions);
        org.springframework.transaction.support.TransactionCallback<String> show =
                s -> jdbc.queryForObject("SHOW statement_timeout", String.class);
        String nested = longStatements.run(() -> java.util.Objects.requireNonNull(joining.execute(show)));
        assertThat(nested).isEqualTo("1min");
        List<Long> transactionIds = longStatements.run(() -> List.of(
                jdbc.queryForObject("SELECT txid_current()", Long.class),
                jdbc.queryForObject("SELECT txid_current()", Long.class)));
        assertThat(transactionIds.get(0)).isEqualTo(transactionIds.get(1));
        assertThat(jdbc.queryForObject("SHOW statement_timeout", String.class)).isEqualTo("5s");

        // A 5 s default on the database role (the checklist's ALTER ROLE) would reach every new connection, Flyway's
        // too; Flyway's init SQL takes it off again before any migration runs
        assertThat(flyway.getConfiguration().getInitSql()).isEqualTo("SET statement_timeout = 0");
        jdbc.execute("ALTER ROLE " + TestContainers.DB_USER + " SET statement_timeout = '5s'");
        try (Connection c = java.sql.DriverManager.getConnection(TestContainers.jdbcUrl(), TestContainers.DB_USER,
                TestContainers.DB_PASSWORD); java.sql.Statement st = c.createStatement()) {
            assertThat(show(st)).isEqualTo("5s");   // what the role gives a fresh connection
            st.execute(flyway.getConfiguration().getInitSql());
            assertThat(show(st)).isEqualTo("0");   // what Flyway runs its migrations with
        } finally {
            jdbc.execute("ALTER ROLE " + TestContainers.DB_USER + " RESET statement_timeout");
        }
    }

    private static String show(java.sql.Statement st) throws java.sql.SQLException {
        try (java.sql.ResultSet rs = st.executeQuery("SHOW statement_timeout")) {
            rs.next();
            return rs.getString(1);
        }
    }

    // ---------- helpers ----------

    private long[] versions() {
        return jdbc.query("SELECT version, setup_version FROM salon_versions WHERE shop_id = ?",
                rs -> rs.next() ? new long[] {rs.getLong(1), rs.getLong(2)} : new long[] {0, 0}, salon);
    }

    private UUID person(String phone) {
        UUID id = UUID.randomUUID();
        TestData.user(jdbc, id, phone);
        return id;
    }

    private UUID userWithPhone(String phone) {
        return jdbc.queryForObject("SELECT id FROM users WHERE phone = ?", UUID.class, phone);
    }

    /** A barber who does both services and works every day, all day. */
    private UUID barber(String name) {
        UUID id = id(post("/salon/staff", Map.of("name", name), owner));
        Reply s = put("/salon/staff/" + id + "/services", Map.of("version", 0, "serviceIds", List.of(haircut, beard)),
                owner);
        List<Map<String, Object>> week = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            week.add(Map.of("weekday", day, "start", "00:00", "end", "24:00"));
        }
        put("/salon/staff/" + id + "/hours", Map.of("version", field(s.body(), "version"), "hours", week), owner);
        return id;
    }

    private long staffVersion(UUID staffId) {
        return jdbc.queryForObject("SELECT version FROM salon_staff WHERE id = ?", Long.class, staffId);
    }

    private long serviceVersion(UUID serviceId) {
        return jdbc.queryForObject("SELECT version FROM salon_services WHERE id = ?", Long.class, serviceId);
    }

    private Reply book(UUID who, Instant start) {
        return call(HttpMethod.POST, "/bookings", Map.of("salonId", salon.toString(), "serviceIds", List.of(haircut),
                "startAt", start.toString()), token(who), UUID.randomUUID());
    }

    private Reply walkIn(UUID barber) {
        return call(HttpMethod.POST, "/salon/walk-ins", Map.of("serviceIds", List.of(haircut),
                "staffId", barber.toString()), token(owner), UUID.randomUUID());
    }

    /** A booking row written straight into the table (as many as a test needs, without the 2-per-customer rule). */
    private UUID insertBooking(UUID who, UUID barber, Instant start, int minutes, String status) {
        UUID id = UUID.randomUUID();
        bookingNo++;
        jdbc.update("INSERT INTO appointments (id, shop_id, staff_id, kind, customer_id, booking_no, start_code, "
                        + "starts_at, ends_at, status, created_by, created_at, updated_at) VALUES (?, ?, ?, 'BOOKING', "
                        + "?, ?, ?, ?, ?, ?, ?, ?, ?)", id, salon, barber, who, "T" + bookingNo,
                String.format("%04d", bookingNo % 10000), Timestamp.from(start),
                Timestamp.from(start.plus(Duration.ofMinutes(minutes))), status, who,
                Timestamp.from(TUESDAY_10_00), Timestamp.from(TUESDAY_10_00));
        return id;
    }

    private UUID insertBlock(UUID barber, Instant from, Instant to) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO appointments (id, shop_id, staff_id, kind, block_type, starts_at, ends_at, status, "
                        + "created_by, created_at, updated_at) VALUES (?, ?, ?, 'BLOCK', 'BREAK', ?, ?, 'BLOCKED', ?, "
                        + "?, ?)", id, salon, barber, Timestamp.from(from), Timestamp.from(to), owner,
                Timestamp.from(TUESDAY_10_00), Timestamp.from(TUESDAY_10_00));
        return id;
    }

    private void insertReminder(UUID booking, String channel, String status) {
        jdbc.update("INSERT INTO notification_jobs (id, user_id, channel, type, ref_id, title, body, url, "
                        + "sms_variables, send_at, status, attempts, next_try_at, created_at, read_at) VALUES (?, ?, ?, "
                        + "'BOOKING_REMINDER', ?, 'old title', 'old text', '/old', 'OLD=1', ?, ?, 0, ?, ?, ?)",
                UUID.randomUUID(), customer, channel, booking, Timestamp.from(TUESDAY_10_00.plusSeconds(86_400)),
                status, Timestamp.from(TUESDAY_10_00.plusSeconds(86_400)), Timestamp.from(TUESDAY_10_00),
                Timestamp.from(TUESDAY_10_00));
    }

    private Appointment appointment(UUID id) {
        return jdbc.queryForObject("SELECT id, shop_id, staff_id, customer_id, booking_no, start_code, starts_at, "
                + "ends_at FROM appointments WHERE id = ?", (rs, i) -> new Appointment(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), AppointmentKind.BOOKING, null, null,
                rs.getObject(4, UUID.class), rs.getString(5), null, rs.getString(6),
                rs.getTimestamp(7).toInstant(), rs.getTimestamp(8).toInstant(), AppointmentStatus.CONFIRMED, null,
                null, null, rs.getObject(4, UUID.class), null, TUESDAY_10_00, TUESDAY_10_00), id);
    }

    private int count(String tableAndWhere) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + tableAndWhere, Integer.class);
        return n == null ? 0 : n;
    }

    /** A moment on Tuesday 10 Nov 2026, India time ("HH:mm" or "HH:mm:ss"). */
    private static Instant at(String time) {
        return LocalDate.of(2026, 11, 10).atTime(LocalTime.parse(time)).atZone(INDIA).toInstant();
    }

    /** A token as sign-in makes it: with the person's shop and role when they work in one. */
    private String token(UUID who) {
        List<Map<String, Object>> m = jdbc.queryForList("SELECT shop_id, role FROM shop_members WHERE user_id = ?",
                who);
        UUID shop = m.isEmpty() ? null : (UUID) m.get(0).get("shop_id");
        String role = m.isEmpty() ? null : (String) m.get(0).get("role");
        return jwt.issue(new AuthPrincipal(who, shop, role, false), sessions.currentGeneration(who)).token();
    }

    /** A token from before the person had a shop (or an old app that never refreshed): no shop in it. */
    private String bareToken(UUID who) {
        return jwt.issue(new AuthPrincipal(who, null, null, false), sessions.currentGeneration(who)).token();
    }

    private static String etag(Reply r) {
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        assertThat(r.etag()).isNotNull();
        return r.etag();
    }

    private Reply get(String path, UUID who) {
        return call(HttpMethod.GET, path, null, token(who), null);
    }

    private Reply getWithTag(String path, String token, String tag) {
        return http.get().uri(path).headers(h -> {
            h.setBearerAuth(token);
            h.setIfNoneMatch(tag);
        }).exchange((rq, res) -> read(res));
    }

    private Reply post(String path, Object body, UUID who) {
        return call(HttpMethod.POST, path, body, token(who), null);
    }

    private Reply put(String path, Object body, UUID who) {
        return call(HttpMethod.PUT, path, body, token(who), null);
    }

    private Reply patch(String path, Object body, UUID who) {
        return call(HttpMethod.PATCH, path, body, token(who), null);
    }

    private Reply call(HttpMethod method, String path, Object body, String token, UUID key) {
        RestClient.RequestBodySpec spec = http.method(method).uri(path).headers(h -> {
            if (token != null) {
                h.setBearerAuth(token);
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

    private static Reply read(org.springframework.http.client.ClientHttpResponse res) throws java.io.IOException {
        int status = res.getStatusCode().value();
        String text = new String(res.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        Object parsed = text.isBlank() ? Map.of() : JSON.readValue(text, Object.class);
        return new Reply(status, parsed, text, res.getHeaders().getETag(), res.getHeaders().getFirst("Retry-After"));
    }

    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

    private static Object field(Object json, String name) {
        return ((Map<?, ?>) json).get(name);
    }

    private static UUID id(Reply r) {
        assertThat(r.status()).as(r.text()).isBetween(200, 201);
        return UUID.fromString((String) field(r.body(), "id"));
    }

    private record Reply(int status, Object body, String text, String etag, String retryAfter) {
    }
}
