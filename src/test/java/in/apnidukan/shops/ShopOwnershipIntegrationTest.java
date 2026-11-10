package in.apnidukan.shops;

import in.apnidukan.common.security.AuthPrincipal;
import in.apnidukan.common.security.JwtService;
import in.apnidukan.common.security.SessionRevocations;
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
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static in.apnidukan.support.CapturingSenders.LAST_SMS;
import static in.apnidukan.support.CapturingSenders.codeSentTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * An owner leaving their shop, end to end against real Postgres and Redis: closing it for good (bookings cancelled,
 * everyone's membership ended, the account can then be deleted) and handing it over (asked by phone, the same answer
 * for any number, accepted or declined, running out after 7 days), and the races between them.
 *
 * The salon: Raju (with his own sign-in) does Haircut; a helper; the owner; a customer with a booking at 12:00.
 * "Now" is Tuesday 10 Nov 2026, 10:00 in India.
 */
@EnabledIf("in.apnidukan.support.TestContainers#dockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({CapturingSenders.class, ShopOwnershipIntegrationTest.Fakes.class})
class ShopOwnershipIntegrationTest {

    static final Instant TUESDAY_10_00 = Instant.parse("2026-11-10T04:30:00Z");
    static final TestClock CLOCK = new TestClock(TUESDAY_10_00);

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
        TestContainers.register(registry);
        registry.add("app.jobs.salon-closing-batch", () -> "50");   // the closing job goes through its slices
        registry.add("app.jobs.account-wipe-slice", () -> "100");   // and so does the salon-message purge
    }

    @Autowired in.apnidukan.salon.service.SalonClosingJob closings;
    @Autowired in.apnidukan.notifications.service.SalonMessagePurgeJob purges;
    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired JwtService jwt;
    @Autowired SessionRevocations sessions;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    RestClient http;
    UUID owner;
    UUID helper;
    UUID rajuUser;
    UUID customer;
    UUID salon;
    UUID raju;
    UUID haircut;
    UUID booking;
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
        LAST_SMS.clear();
        CLOCK.set(TUESDAY_10_00);
        owner = person("9000000001");
        customer = person("9000000101");
        salon = UUID.randomUUID();
        TestData.salon(jdbc, salon, owner);
        jdbc.update("UPDATE shops SET status = 'APPROVED' WHERE id = ?", salon);
        haircut = id(post("/salon/services", Map.of("name", "Haircut", "durationMinutes", 30, "pricePaise", 15000),
                owner));
        raju = id(post("/salon/staff", Map.of("name", "Raju"), owner));
        Reply s = put("/salon/staff/" + raju + "/services", Map.of("version", 0, "serviceIds", List.of(haircut)),
                owner);
        List<Map<String, Object>> week = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            week.add(Map.of("weekday", day, "start", "09:00", "end", "21:00"));
        }
        put("/salon/staff/" + raju + "/hours", Map.of("version", field(s.body(), "version"), "hours", week), owner);
        rajuUser = linkBarber(raju, "9000000201");
        TestData.completeProfile(jdbc, "9000000201");
        helper = addHelper("9000000301");
        TestData.completeProfile(jdbc, "9000000301");
        put("/salon/members/" + helper + "/walk-in-access", Map.of("allowed", true), owner);
        booking = id(call(HttpMethod.POST, "/bookings", Map.of("salonId", salon.toString(), "serviceIds",
                List.of(haircut), "startAt", at("12:00").toString()), token(customer), UUID.randomUUID()));
    }

    // ---------- closing ----------

    @Test
    void closingCancelsUpcomingBookingsEndsEveryMembershipAndThenTheOwnerCanDeleteTheirAccount() {
        assertThat(field(post("/auth/me/delete-code", Map.of(), owner).body(), "code")).isEqualTo("SHOP_OWNER");
        String helperToken = token(helper);   // issued before: must stop working
        // The owner's token names the shop (as sign-in makes it); it stays valid after closing (they stay signed in)
        String ownerShopToken = jwt.issue(new AuthPrincipal(owner, salon, "OWNER", false),
                sessions.currentGeneration(owner)).token();
        String fileLink = "/files/" + UUID.randomUUID() + "/link";
        assertThat(call(HttpMethod.GET, fileLink, null, ownerShopToken, null).status()).isEqualTo(404);

        assertThat(field(post("/shops/me/close", Map.of("version", shopVersion() + 1), owner).body(), "code"))
                .isEqualTo("SHOP_CHANGED");
        assertThat(field(post("/shops/me/close", Map.of("version", shopVersion()), helper).body(), "code"))
                .isEqualTo("OWNER_ONLY");
        Reply closed = post("/shops/me/close", Map.of("version", shopVersion(), "reason", "Moving to Pune"), owner);
        assertThat(closed.status()).as(closed.text()).isEqualTo(200);
        assertThat(field(closed.body(), "status")).isEqualTo("CLOSED");

        // At once: no booking can be made any more (the salon is gone from customers' pages), and its bookings are
        // queued for the background job
        assertThat(get("/salons/" + salon, customer).status()).isEqualTo(404);
        assertThat(count("salon_closings WHERE shop_id = '" + salon + "'")).isEqualTo(1);
        // Customers: the job cancels the booking and they are told
        assertThat(closings.runOnce()).isEqualTo(1);
        assertThat(count("salon_closings")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, booking))
                .isEqualTo("CANCELLED_BY_SALON");
        assertThat(jdbc.queryForList("SELECT body FROM notification_jobs WHERE user_id = ? AND type = "
                + "'BOOKING_CANCELLED_BY_SALON' AND channel = 'PUSH'", String.class, customer))
                .singleElement().asString().contains("The salon has closed.");
        assertThat(get("/salons/" + salon, customer).status()).isEqualTo(404);
        // Everyone left: no members, Raju's profile stays without his sign-in, no walk-in access
        assertThat(count("shop_members WHERE shop_id = '" + salon + "'")).isZero();
        assertThat(jdbc.queryForObject("SELECT user_id FROM salon_staff WHERE id = ?", UUID.class, raju)).isNull();
        assertThat(count("walk_in_access")).isZero();
        assertThat(call(HttpMethod.GET, "/auth/me", null, helperToken, null).status()).isEqualTo(401);
        assertThat(get("/shops/me", owner).status()).isEqualTo(404);
        // Files go by the shop in the database, not the token's: the closed shop's are no longer theirs
        Reply files = call(HttpMethod.GET, fileLink, null, ownerShopToken, null);
        assertThat(files.status()).as(files.text()).isEqualTo(403);
        assertThat(field(files.body(), "code")).isEqualTo("SHOP_REQUIRED");
        // The timeline says so, with the owner's reason
        assertThat(jdbc.queryForObject("SELECT event || ':' || reason FROM shop_events WHERE shop_id = ?",
                String.class, salon)).isEqualTo("CLOSED:Moving to Pune");

        // Asking again (a lost reply) answers the same; nothing changes
        Reply again = post("/shops/me/close", Map.of("version", 0), owner);
        assertThat(again.status()).as(again.text()).isEqualTo(200);
        assertThat(field(again.body(), "id")).isEqualTo(salon.toString());
        assertThat(count("shop_events WHERE shop_id = '" + salon + "'")).isEqualTo(1);

        // The field team sees it as CLOSED and can't decide on it
        UUID admin = person("9000000901");
        jdbc.update("UPDATE users SET admin_role = 'SUPER_ADMIN', admin = true WHERE id = ?", admin);
        List<?> listed = (List<?>) get("/admin/shops?type=SALON&group=CLOSED", admin).body();
        assertThat(listed).extracting(x -> field(x, "id")).containsExactly(salon.toString());
        assertThat(((Number) field(field(get("/admin/dashboard", admin).body(), "SALON"), "CLOSED")).intValue())
                .isEqualTo(1);
        assertThat(field(post("/admin/shops/" + salon + "/suspend", Map.of("version", shopVersion(), "reason",
                "Closed anyway"), admin).body(), "code")).isEqualTo("WRONG_STATUS");

        // Never opened again: the owner can register a new shop, or delete their account
        assertThat(post("/auth/me/delete-code", Map.of(), owner).status()).isEqualTo(202);
        assertThat(post("/auth/me/delete", Map.of("code", codeSentTo(LAST_SMS, "9000000001")), owner).status())
                .isEqualTo(204);
    }

    // ---------- handing over ----------

    @Test
    void aHandOverToSomeoneWithoutAShopAnswersTheSameForAnyNumberAndSwapsTheOwnerOnAccept() {
        UUID taker = person("9000000401");
        Reply asked = post("/shops/me/handover", Map.of("phone", "+91 90000 00401", "version", shopVersion()),
                owner);
        assertThat(asked.status()).as(asked.text()).isEqualTo(202);
        assertThat(field(asked.body(), "message"))
                .isEqualTo("If that number has a Clozest account, they'll see your request.");
        assertThat(field(get("/shops/me/handover", owner).body(), "phone")).isEqualTo("9000000401");
        assertThat(field(post("/shops/me/handover", Map.of("phone", "9000000402", "version", shopVersion()),
                owner).body(), "code")).isEqualTo("HANDOVER_PENDING");
        assertThat(count("notification_jobs WHERE user_id = '" + taker + "' AND type = 'SHOP_HANDOVER_REQUEST'"))
                .isEqualTo(1);

        List<?> waiting = (List<?>) get("/me/handover", taker).body();
        assertThat(waiting).singleElement().satisfies(h -> {
            assertThat(field(h, "shopId")).isEqualTo(salon.toString());
            assertThat(field(h, "shopName")).isEqualTo("Test Shop");
        });
        UUID handover = UUID.fromString((String) field(waiting.get(0), "id"));
        assertThat((List<?>) get("/me/handover", customer).body()).isEmpty();   // not their phone
        assertThat(post("/me/handover/" + handover + "/accept", null, customer).status()).isEqualTo(404);

        String ownerToken = token(owner);
        Reply accepted = post("/me/handover/" + handover + "/accept", null, taker);
        assertThat(accepted.status()).as(accepted.text()).isEqualTo(200);
        assertThat(field(accepted.body(), "myRole")).isEqualTo("OWNER");
        assertThat(jdbc.queryForList("SELECT user_id::text || ':' || role FROM shop_members WHERE shop_id = ? "
                        + "AND role = 'OWNER'", String.class, salon)).containsExactly(taker + ":OWNER");
        assertThat(count("shop_members WHERE user_id = '" + owner + "'")).isZero();   // the old owner left
        assertThat(call(HttpMethod.GET, "/auth/me", null, ownerToken, null).status()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, booking))
                .isEqualTo("CONFIRMED");   // the shop goes on
        assertThat(jdbc.queryForList("SELECT event FROM shop_events WHERE shop_id = ?", String.class, salon))
                .containsExactly("HANDED_OVER");
        assertThat(count("notification_jobs WHERE user_id = '" + owner + "' AND type = 'SHOP_HANDOVER_ACCEPTED'"))
                .isEqualTo(1);
        assertThat(count("notification_jobs WHERE user_id = '" + taker + "' AND type = 'SHOP_HANDOVER_DONE'"))
                .isEqualTo(1);
        assertThat(field(post("/me/handover/" + handover + "/accept", null, taker).body(), "code"))
                .isEqualTo("HANDOVER_GONE");
        // The old owner can now delete their account; the new one runs the salon
        assertThat(post("/auth/me/delete-code", Map.of(), owner).status()).isEqualTo(202);
        assertThat(get("/salon/staff", taker).status()).isEqualTo(200);
    }

    @Test
    void aNumberWithoutAnAccountGetsTheSameAnswerAndAShopMayAskFiveTimesADay() {
        Reply unknown = post("/shops/me/handover", Map.of("phone", "9000000499", "version", shopVersion()), owner);
        assertThat(unknown.status()).isEqualTo(202);
        assertThat(field(unknown.body(), "message"))
                .isEqualTo("If that number has a Clozest account, they'll see your request.");
        assertThat(count("notification_jobs WHERE type = 'SHOP_HANDOVER_REQUEST'")).isZero();
        // Someone signing up with that number later finds it
        UUID later = person("9000000499");
        assertThat((List<?>) get("/me/handover", later).body()).hasSize(1);
        assertThat(field(post("/shops/me/handover", Map.of("phone", "9000000496", "version", shopVersion()),
                owner).body(), "code")).isEqualTo("HANDOVER_PENDING");   // still one waiting
        for (int i = 0; i < 3; i++) {
            assertThat(delete("/shops/me/handover", owner).status()).isEqualTo(204);
            assertThat(post("/shops/me/handover", Map.of("phone", "9000000498", "version", shopVersion()), owner)
                    .status()).isEqualTo(202);
        }
        assertThat(delete("/shops/me/handover", owner).status()).isEqualTo(204);
        Reply sixth = post("/shops/me/handover", Map.of("phone", "9000000497", "version", shopVersion()), owner);
        assertThat(sixth.status()).isEqualTo(429);
        assertThat(field(sixth.body(), "code")).isEqualTo("TOO_MANY_REQUESTS");
        assertThat(get("/shops/me/handover", owner).status()).isEqualTo(404);
        // Their own number is no hand-over
        redis.execute((RedisCallback<Object>) c -> {
            c.serverCommands().flushAll();
            return null;
        });
        assertThat(field(post("/shops/me/handover", Map.of("phone", "9000000001", "version", shopVersion()),
                owner).body(), "code")).isEqualTo("VALIDATION_FAILED");
        assertThat(field(post("/shops/me/handover", Map.of("phone", "12345", "version", shopVersion()), owner)
                .body(), "code")).isEqualTo("INVALID_PHONE");
    }

    @Test
    void aHelperDeclinesThenARequestRunsOutThenTheBarberTakesOverAndStaysABarber() {
        UUID first = handOverTo("9000000301");
        assertThat(post("/me/handover/" + first + "/decline", null, helper).status()).isEqualTo(204);
        assertThat(count("notification_jobs WHERE user_id = '" + owner + "' AND type = 'SHOP_HANDOVER_DECLINED'"))
                .isEqualTo(1);
        Reply late = post("/me/handover/" + first + "/accept", null, helper);
        assertThat(field(late.body(), "code")).isEqualTo("HANDOVER_GONE");
        assertThat(field(field(late.body(), "details"), "status")).isEqualTo("DECLINED");

        // Seven days without an answer: it ran out, and the owner may ask again
        UUID second = handOverTo("9000000301");
        CLOCK.advance(Duration.ofDays(7).plusMinutes(1));
        Reply expired = post("/me/handover/" + second + "/accept", null, helper);
        assertThat(field(field(expired.body(), "details"), "status")).as(expired.text()).isEqualTo("EXPIRED");
        assertThat((List<?>) get("/me/handover", helper).body()).isEmpty();

        // Someone working in another shop must leave it first
        UUID otherOwner = person("9000000601");
        TestData.shop(jdbc, UUID.randomUUID(), otherOwner);
        UUID toOther = handOverTo("9000000601");
        assertThat(field(post("/me/handover/" + toOther + "/accept", null, otherOwner).body(), "code"))
                .isEqualTo("ALREADY_IN_A_SHOP");
        assertThat(delete("/shops/me/handover", owner).status()).isEqualTo(204);

        // The owner cuts hair too; when he hands over and leaves, his profile lets go of his sign-in (like a barber who
        // leaves: the new owner switches it off or removes it)
        Reply me = post("/salon/staff/me", Map.of(), owner);
        assertThat(me.status()).as(me.text()).isEqualTo(201);
        UUID ownersChair = UUID.fromString((String) field(me.body(), "id"));

        // The barber with his own sign-in takes over: the same membership becomes OWNER, and he stays a barber (an
        // owner may cut hair too)
        UUID membership = jdbc.queryForObject("SELECT id FROM shop_members WHERE user_id = ?", UUID.class, rajuUser);
        UUID third = handOverTo("9000000201");
        Reply took = post("/me/handover/" + third + "/accept", null, rajuUser);
        assertThat(took.status()).as(took.text()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT role FROM shop_members WHERE id = ?", String.class, membership))
                .isEqualTo("OWNER");
        assertThat(jdbc.queryForObject("SELECT user_id FROM salon_staff WHERE id = ?", UUID.class, raju))
                .isEqualTo(rajuUser);
        assertThat(jdbc.queryForObject("SELECT user_id FROM salon_staff WHERE id = ?", UUID.class, ownersChair))
                .isNull();
        assertThat(count("shop_members WHERE shop_id = '" + salon + "'")).isEqualTo(2);   // he and the helper
        assertThat(count("shop_members_aud WHERE id = '" + membership + "'")).isEqualTo(2);   // its history
    }

    // ---------- closing a busy salon ----------

    @Test
    void closingABusySalonIsOneShortRequestAndTheJobCancelsItsBookingsInSlicesAndTellsEveryCustomer() {
        // 300 more bookings over the next two weeks (written straight in) and 2,000 salon messages of the owner's
        Instant first = at("09:00").plus(Duration.ofDays(1));
        for (int i = 0; i < 300; i++) {
            Instant start = first.plus(Duration.ofDays(i / 24)).plus(Duration.ofMinutes(30L * (i % 24)));
            insertBooking(customer, raju, start);
        }
        java.sql.Timestamp sent = java.sql.Timestamp.from(CLOCK.instant().minusSeconds(60));
        jdbc.update("INSERT INTO notification_jobs (id, user_id, channel, type, title, body, status, attempts, send_at, "
                + "next_try_at, created_at) SELECT gen_random_uuid(), ?, 'PUSH', 'SALON_NEW_BOOKING', 't', 'b', 'SENT', "
                + "1, ?, ?, ? FROM generate_series(1, 2000)", owner, sent, sent, sent);

        Reply closed = post("/shops/me/close", Map.of("version", shopVersion()), owner);
        assertThat(closed.status()).as(closed.text()).isEqualTo(200);
        // At once: CLOSED (nobody can book), the bookings queued, the owner's salon messages hidden, nothing deleted
        assertThat(call(HttpMethod.POST, "/bookings", Map.of("salonId", salon.toString(), "serviceIds",
                List.of(haircut), "startAt", at("15:00").toString()), token(person("9000000102")), UUID.randomUUID())
                .status()).isEqualTo(404);
        assertThat(count("appointments WHERE status = 'CONFIRMED'")).isEqualTo(301);
        assertThat(count("notification_jobs WHERE user_id = '" + owner + "' AND type LIKE 'SALON%'"))
                .isEqualTo(2001);   // + today's booking
        // Only the shop's own messages (setUp's accepted invitations) are still listed, none of the salon's
        assertThat((List<?>) field(get("/me/notifications?app=partner", owner).body(), "items"))
                .extracting(x -> field(x, "type")).containsOnly("SHOP_INVITE_ACCEPTED");

        // The job: 50 a slice, every customer told once; running it again finds nothing
        assertThat(closings.runOnce()).isEqualTo(301);
        assertThat(count("appointments WHERE status = 'CONFIRMED'")).isZero();
        assertThat(count("appointment_events WHERE event = 'CANCELLED_BY_SALON'")).isEqualTo(301);
        assertThat(count("notification_jobs WHERE type = 'BOOKING_CANCELLED_BY_SALON' AND channel = 'PUSH'"))
                .isEqualTo(301);
        assertThat(count("salon_closings")).isZero();
        assertThat(closings.runOnce()).isZero();
        // The owner's messages go in slices of 100 too
        assertThat(purges.runOnce()).isEqualTo(3);   // the owner, the helper and the barber
        assertThat(count("notification_jobs WHERE type LIKE 'SALON%'")).isZero();
        assertThat(count("salon_message_purges")).isZero();
    }

    @Test
    void suspendingABusySalonIsOneShortRequestAndTheJobCancelsItsBookingsOnceButNotAfterAReinstate() {
        UUID admin = person("9000000901");
        jdbc.update("UPDATE users SET admin_role = 'SUPER_ADMIN', admin = true WHERE id = ?", admin);
        Instant first = at("09:00").plus(Duration.ofDays(1));
        for (int i = 0; i < 300; i++) {
            insertBooking(customer, raju, first.plus(Duration.ofDays(i / 24)).plus(Duration.ofMinutes(30L * (i % 24))));
        }
        assertThat(post("/admin/shops/" + salon + "/suspend", Map.of("version", shopVersion(), "reason", "Fake"),
                admin).status()).isEqualTo(200);
        // At once: SUSPENDED (no booking can be made), nothing cancelled in the request
        assertThat(count("appointments WHERE status = 'CONFIRMED'")).isEqualTo(301);
        assertThat(get("/salons/" + salon, customer).status()).isEqualTo(404);
        // The job: 50 a slice, every customer told once ("isn't taking online bookings"); again: nothing
        assertThat(closings.runOnce()).isEqualTo(301);
        assertThat(count("notification_jobs WHERE type = 'BOOKING_CANCELLED_BY_SALON' AND channel = 'PUSH' "
                + "AND body LIKE '%taking online bookings%'")).isEqualTo(301);
        assertThat(closings.runOnce()).isZero();
        assertThat(count("salon_closings")).isZero();

        // Suspended, then reinstated before the job ran: the job stops, the bookings stay
        assertThat(post("/admin/shops/" + salon + "/reinstate", Map.of("version", shopVersion()), admin).status())
                .isEqualTo(200);
        insertBooking(customer, raju, first.plus(Duration.ofDays(20)));
        assertThat(post("/admin/shops/" + salon + "/suspend", Map.of("version", shopVersion(), "reason", "Check"),
                admin).status()).isEqualTo(200);
        assertThat(post("/admin/shops/" + salon + "/reinstate", Map.of("version", shopVersion()), admin).status())
                .isEqualTo(200);
        assertThat(closings.runOnce()).isZero();
        assertThat(count("appointments WHERE status = 'CONFIRMED'")).isEqualTo(1);
        assertThat(count("salon_closings")).isZero();
    }

    @Test
    void aBookingHeldByACustomerWhileTheJobRunsIsSkippedAndCancelledInTheNextRound() throws Exception {
        assertThat(post("/shops/me/close", Map.of("version", shopVersion()), owner).status()).isEqualTo(200);
        org.springframework.transaction.support.TransactionTemplate tx =
                new org.springframework.transaction.support.TransactionTemplate(transactions);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM appointments WHERE id = ? FOR UPDATE", UUID.class, booking);
                held.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(closings.runOnce()).isZero();   // skipped, not waited for
            assertThat(count("salon_closings")).isEqualTo(1);   // so the salon stays queued
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(closings.runOnce()).isEqualTo(1);
        assertThat(count("salon_closings")).isZero();
    }

    @Test
    void aReinstateCommittingWhileTheJobWaitsForTheShopsRowStopsIt() throws Exception {
        UUID admin = person("9000000901");
        jdbc.update("UPDATE users SET admin_role = 'SUPER_ADMIN', admin = true WHERE id = ?", admin);
        assertThat(post("/admin/shops/" + salon + "/suspend", Map.of("version", shopVersion(), "reason", "Fake"),
                admin).status()).isEqualTo(200);
        org.springframework.transaction.support.TransactionTemplate tx =
                new org.springframework.transaction.support.TransactionTemplate(transactions);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // A reinstate in progress: the shop's row written, not committed yet
            Future<?> reinstate = pool.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.update("UPDATE shops SET status = 'PENDING_VISIT', version = version + 1 WHERE id = ?", salon);
                held.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Integer> job = pool.submit(closings::runOnce);
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(20))
                    .until(() -> count("pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE '%for share%'")
                            == 1);
            release.countDown();
            reinstate.get(10, TimeUnit.SECONDS);
            assertThat(job.get(10, TimeUnit.SECONDS)).isZero();   // it saw the reinstate: nothing cancelled
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, booking))
                .isEqualTo("CONFIRMED");
        assertThat(count("salon_closings")).isZero();
    }

    @Test
    void aHelperInvitationToAPhoneInvitedAsABarberIsRefusedNotAnsweredWithTheBarbersOne() {
        assertThat(post("/salon/staff", Map.of("name", "Sunil", "phone", "9000000811"), owner).status())
                .isEqualTo(201);
        Reply helper = post("/shops/me/helpers", Map.of("phone", "9000000811"), owner);
        assertThat(helper.status()).as(helper.text()).isEqualTo(409);
        assertThat(helper.text()).contains("INVITE_PENDING");
        assertThat(count("shop_invites WHERE to_phone = '9000000811'")).isEqualTo(1);
    }

    // ---------- invitations ----------

    @Test
    void anInvitationAnswersTheSameForAnyNumberShowsNoNameAndJoinsOnlyWhenAccepted() {
        Reply stranger = post("/shops/me/helpers", Map.of("phone", "9000000501"), owner);   // nobody has it
        Reply known = post("/shops/me/helpers", Map.of("phone", "+91 90000 00101"), owner);   // the customer's
        assertThat(stranger.status()).as(stranger.text()).isEqualTo(202);
        assertThat(known.status()).isEqualTo(202);
        assertThat(field(stranger.body(), "message")).isEqualTo(field(known.body(), "message"));
        assertThat(((Map<?, ?>) field(known.body(), "invite")).keySet())
                .isEqualTo(((Map<?, ?>) field(stranger.body(), "invite")).keySet());
        assertThat(known.text()).doesNotContain("Test person");
        assertThat(count("users WHERE phone = '9000000501'")).isZero();   // no account is made
        // Asking again answers the invitation already waiting
        Reply again = post("/shops/me/helpers", Map.of("phone", "9000000501"), owner);
        assertThat(field(field(again.body(), "invite"), "id")).isEqualTo(field(field(stranger.body(), "invite"), "id"));
        // Nobody joined; the owner sees both by the phone they typed, never a name
        assertThat(count("shop_members WHERE shop_id = '" + salon + "'")).isEqualTo(3);
        List<?> waiting = (List<?>) get("/shops/me/invites", owner).body();
        assertThat(waiting).extracting(x -> field(x, "phone")).containsExactlyInAnyOrder("9000000501", "9000000101");
        assertThat(get("/shops/me/invites", owner).text()).doesNotContain("Test person");
        assertThat(get("/shops/me/members", owner).text()).doesNotContain("9000000101");
        // Only the account that has the number is told; it sees the shop and says yes
        assertThat(count("notification_jobs WHERE type = 'SHOP_INVITE'")).isEqualTo(1);
        List<?> mine = (List<?>) get("/me/invites", customer).body();
        assertThat(mine).singleElement().satisfies(x -> {
            assertThat(field(x, "shopName")).isEqualTo("Test Shop");
            assertThat(field(x, "role")).isEqualTo("HELPER");
        });
        UUID invite = UUID.fromString((String) field(mine.get(0), "id"));
        assertThat(post("/me/invites/" + invite + "/accept", Map.of(), owner).status()).isEqualTo(404);   // not theirs
        Reply joined = post("/me/invites/" + invite + "/accept", Map.of(), customer);
        assertThat(joined.status()).as(joined.text()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT role FROM shop_members WHERE user_id = ?", String.class, customer))
                .isEqualTo("HELPER");
        assertThat(count("notification_jobs WHERE user_id = '" + owner + "' AND type = 'SHOP_INVITE_ACCEPTED'"))
                .isEqualTo(3);   // Raju and the helper accepted theirs in setUp
        assertThat(get("/shops/me/members", owner).text()).contains("9000000101");   // now they agreed
        assertThat(field(post("/me/invites/" + invite + "/accept", Map.of(), customer).body(), "code"))
                .isEqualTo("INVITE_GONE");

        // Someone who works in another shop: the owner's answer is the same; only they learn it, on accept
        UUID other = person("9000000601");
        TestData.shop(jdbc, UUID.randomUUID(), other);
        Reply busy = post("/shops/me/helpers", Map.of("phone", "9000000601"), owner);
        assertThat(busy.status()).isEqualTo(202);
        assertThat(field(busy.body(), "message")).isEqualTo(field(known.body(), "message"));
        UUID theirs = UUID.fromString((String) field(((List<?>) get("/me/invites", other).body()).get(0), "id"));
        assertThat(field(post("/me/invites/" + theirs + "/accept", Map.of(), other).body(), "code"))
                .isEqualTo("ALREADY_IN_A_SHOP");
    }

    @Test
    void anInvitationCanBeDeclinedCancelledOrRunOutAndAShopInvitesAtMost20Times() {
        UUID asked = person("9000000701");
        UUID declined = inviteId(post("/shops/me/helpers", Map.of("phone", "9000000701"), owner));
        assertThat(post("/me/invites/" + declined + "/decline", Map.of(), asked).status()).isEqualTo(204);
        assertThat(count("notification_jobs WHERE user_id = '" + owner + "' AND type = 'SHOP_INVITE_DECLINED'"))
                .isEqualTo(1);
        assertThat(field(field(post("/me/invites/" + declined + "/accept", Map.of(), asked).body(), "details"),
                "status")).isEqualTo("DECLINED");

        UUID cancelled = inviteId(post("/shops/me/helpers", Map.of("phone", "9000000701"), owner));
        assertThat(delete("/shops/me/invites/" + cancelled, owner).status()).isEqualTo(204);
        assertThat(field(field(post("/me/invites/" + cancelled + "/accept", Map.of(), asked).body(), "details"),
                "status")).isEqualTo("CANCELLED");

        UUID late = inviteId(post("/shops/me/helpers", Map.of("phone", "9000000701"), owner));
        CLOCK.advance(Duration.ofDays(7).plusMinutes(1));
        assertThat((List<?>) get("/me/invites", asked).body()).isEmpty();
        assertThat((List<?>) get("/shops/me/invites", owner).body()).isEmpty();
        assertThat(field(field(post("/me/invites/" + late + "/accept", Map.of(), asked).body(), "details"),
                "status")).isEqualTo("EXPIRED");

        // 5 so far (setUp invited Raju and the helper); 15 more fit in the day (each cancelled, or the helper limit
        // would stop them first), then 429
        for (int i = 0; i < 15; i++) {
            UUID v = inviteId(post("/shops/me/helpers", Map.of("phone", "90000008" + String.format("%02d", i)), owner));
            assertThat(delete("/shops/me/invites/" + v, owner).status()).isEqualTo(204);
        }
        Reply over = post("/shops/me/helpers", Map.of("phone", "9000000899"), owner);
        assertThat(over.status()).as(over.text()).isEqualTo(429);
        assertThat(over.text()).contains("TOO_MANY_REQUESTS");
    }

    @Test
    void aBarbersInvitationShowsOnHisProfileAndGoesWhenHeIsSwitchedOffOrTheSalonCloses() {
        UUID sunil = id(post("/salon/staff", Map.of("name", "Sunil", "phone", "9000000802"), owner));
        Object profile = ((List<?>) get("/salon/staff", owner).body()).stream()
                .filter(x -> sunil.toString().equals(field(x, "id"))).findFirst().orElseThrow();
        assertThat(field(profile, "invitedPhone")).isEqualTo("9000000802");
        assertThat(field(profile, "loginPhone")).isNull();
        UUID sunilUser = person("9000000802");
        UUID invite = UUID.fromString((String) field(((List<?>) get("/me/invites", sunilUser).body()).get(0), "id"));
        assertThat(field(((List<?>) get("/me/invites", sunilUser).body()).get(0), "role")).isEqualTo("BARBER");
        // Switched off before he answered: the invitation goes with it
        long version = ((Number) field(profile, "version")).longValue();
        assertThat(call(HttpMethod.PATCH, "/salon/staff/" + sunil, Map.of("version", version, "active", false),
                token(owner), null).status()).isEqualTo(200);
        assertThat(field(field(post("/me/invites/" + invite + "/accept", Map.of(), sunilUser).body(), "details"),
                "status")).isEqualTo("CANCELLED");
        assertThat(count("shop_members WHERE user_id = '" + sunilUser + "'")).isZero();

        // A waiting helper invitation goes when the salon closes
        UUID waiting = inviteId(post("/shops/me/helpers", Map.of("phone", "9000000803"), owner));
        assertThat(post("/shops/me/close", Map.of("version", shopVersion()), owner).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM shop_invites WHERE id = ?", String.class, waiting))
                .isEqualTo("CANCELLED");
    }

    @Test
    void acceptingABarbersInvitationWhileTheOwnerSwitchesHimOffNeverLeavesASignInWithoutAnActiveProfile()
            throws Exception {
        UUID arif = id(post("/salon/staff", Map.of("name", "Arif", "phone", "9000000804"), owner));
        UUID arifUser = person("9000000804");
        UUID invite = UUID.fromString((String) field(((List<?>) get("/me/invites", arifUser).body()).get(0), "id"));
        long version = jdbc.queryForObject("SELECT version FROM salon_staff WHERE id = ?", Long.class, arif);
        List<Reply> replies = race(() -> post("/me/invites/" + invite + "/accept", Map.of(), arifUser),
                () -> call(HttpMethod.PATCH, "/salon/staff/" + arif, Map.of("version", version, "active", false),
                        token(owner), null));
        assertThat(replies.get(0).status()).as(replies.get(0).text()).isIn(200, 409);
        assertThat(replies.get(1).status()).as(replies.get(1).text()).isIn(200, 409);
        Boolean active = jdbc.queryForObject("SELECT active FROM salon_staff WHERE id = ?", Boolean.class, arif);
        UUID linked = jdbc.queryForObject("SELECT user_id FROM salon_staff WHERE id = ?", UUID.class, arif);
        int member = count("shop_members WHERE user_id = '" + arifUser + "'");
        // Either he joined and is linked to an active profile, or he is not in the salon at all
        if (member == 1) {
            assertThat(active).isTrue();
            assertThat(linked).isEqualTo(arifUser);
        } else {
            assertThat(linked).isNull();
        }
    }

    // ---------- races ----------

    @Test
    void acceptingAndClosingAtTheSameMomentNeverBothHappen() throws Exception {
        UUID taker = person("9000000401");
        UUID handover = handOverTo("9000000401");
        long version = shopVersion();
        List<Reply> replies = race(() -> post("/me/handover/" + handover + "/accept", null, taker),
                () -> post("/shops/me/close", Map.of("version", version), owner));
        String status = jdbc.queryForObject("SELECT status FROM shops WHERE id = ?", String.class, salon);
        if (replies.get(0).status() == 200) {
            assertThat(replies.get(1).status()).as(replies.get(1).text()).isEqualTo(404);
            assertThat(status).isEqualTo("APPROVED");
            assertThat(owners()).containsExactly(taker);
        } else {
            assertThat(replies.get(1).status()).as(replies.get(1).text()).isEqualTo(200);
            assertThat(field(replies.get(0).body(), "code")).isEqualTo("HANDOVER_GONE");
            assertThat(status).isEqualTo("CLOSED");
            assertThat(count("shop_members WHERE shop_id = '" + salon + "'")).isZero();
        }
    }

    @Test
    void acceptingWhileTheOwnerDeletesTheirAccountLeavesExactlyOneLiveOwner() throws Exception {
        UUID taker = person("9000000401");
        UUID handover = handOverTo("9000000401");
        // The owner's deletion code was asked for before (it is refused while they own the shop, so ask after a
        // first accept would be too late to race): the deletion refuses or runs after the accept, never both
        jdbc.update("UPDATE shop_members SET role = 'HELPER' WHERE user_id = ?", owner);
        assertThat(post("/auth/me/delete-code", Map.of(), owner).status()).isEqualTo(202);
        jdbc.update("UPDATE shop_members SET role = 'OWNER' WHERE user_id = ?", owner);
        String code = codeSentTo(LAST_SMS, "9000000001");
        List<Reply> replies = race(() -> post("/me/handover/" + handover + "/accept", null, taker),
                () -> post("/auth/me/delete", Map.of("code", code), owner));
        assertThat(replies.get(0).status()).as(replies.get(0).text()).isEqualTo(200);   // never in its way
        assertThat(replies.get(1).status()).as(replies.get(1).text()).isIn(204, 409);
        assertThat(owners()).containsExactly(taker);
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM users WHERE id = ?", Boolean.class, taker))
                .isTrue();
    }

    @Test
    void aHelperAcceptingWhileDeletingTheirOwnAccountNeverLeavesTheShopWithoutAnOwner() throws Exception {
        UUID handover = handOverTo("9000000301");
        assertThat(post("/auth/me/delete-code", Map.of(), helper).status()).isEqualTo(202);
        String code = codeSentTo(LAST_SMS, "9000000301");
        List<Reply> replies = race(() -> post("/me/handover/" + handover + "/accept", null, helper),
                () -> post("/auth/me/delete", Map.of("code", code), helper));
        List<UUID> owners = owners();
        assertThat(owners).hasSize(1);
        Boolean live = jdbc.queryForObject("SELECT deleted_at IS NULL FROM users WHERE id = ?", Boolean.class,
                owners.get(0));
        assertThat(live).isTrue();
        if (owners.get(0).equals(helper)) {
            assertThat(replies.get(0).status()).isEqualTo(200);
            assertThat(replies.get(1).status()).as(replies.get(1).text()).isEqualTo(409);   // SHOP_OWNER now
        } else {
            assertThat(owners.get(0)).isEqualTo(owner);
            assertThat(replies.get(1).status()).as(replies.get(1).text()).isEqualTo(204);
            // Refused (the account went first), or the database broke a tie between the two (409 TRY_AGAIN)
            assertThat(replies.get(0).status()).as(replies.get(0).text()).isIn(401, 404, 409);
        }
    }

    @Test
    void acceptingWhileTheOwnerSwapsTheRequestForAnotherLeavesOneOutcome() throws Exception {
        UUID taker = person("9000000401");
        UUID handover = handOverTo("9000000401");
        List<Reply> replies = race(() -> post("/me/handover/" + handover + "/accept", null, taker),
                () -> delete("/shops/me/handover", owner));
        if (replies.get(0).status() == 200) {
            assertThat(replies.get(1).status()).isEqualTo(404);   // no shop of theirs any more
            assertThat(owners()).containsExactly(taker);
        } else {
            assertThat(field(field(replies.get(0).body(), "details"), "status")).isEqualTo("CANCELLED");
            assertThat(owners()).containsExactly(owner);
            assertThat(post("/shops/me/handover", Map.of("phone", "9000000402", "version", shopVersion()), owner)
                    .status()).isEqualTo(202);
        }
        assertThat(count("shop_handovers WHERE shop_id = '" + salon + "' AND status = 'PENDING'"))
                .isLessThanOrEqualTo(1);
    }

    // ---------- helpers ----------

    private UUID handOverTo(String phone) {
        Reply asked = post("/shops/me/handover", Map.of("phone", phone, "version", shopVersion()), owner);
        assertThat(asked.status()).as(asked.text()).isEqualTo(202);
        return UUID.fromString((String) field(field(asked.body(), "handover"), "id"));
    }

    private static UUID inviteId(Reply r) {
        assertThat(r.status()).as(r.text()).isEqualTo(202);
        return UUID.fromString((String) field(field(r.body(), "invite"), "id"));
    }

    /** A booking row written straight into the table (as many as a test needs, without the 2-per-customer rule). */
    private void insertBooking(UUID who, UUID barber, Instant start) {
        bookingNo++;
        jdbc.update("INSERT INTO appointments (id, shop_id, staff_id, kind, customer_id, booking_no, start_code, "
                        + "starts_at, ends_at, status, created_by, created_at, updated_at) VALUES (?, ?, ?, 'BOOKING', "
                        + "?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, ?)", UUID.randomUUID(), salon, barber, who,
                "T" + bookingNo, String.format("%04d", bookingNo % 10000), java.sql.Timestamp.from(start),
                java.sql.Timestamp.from(start.plus(Duration.ofMinutes(30))), who,
                java.sql.Timestamp.from(TUESDAY_10_00), java.sql.Timestamp.from(TUESDAY_10_00));
    }

    private List<UUID> owners() {
        return jdbc.queryForList("SELECT user_id FROM shop_members WHERE shop_id = ? AND role = 'OWNER'", UUID.class,
                salon);
    }

    private long shopVersion() {
        return jdbc.queryForObject("SELECT version FROM shops WHERE id = ?", Long.class, salon);
    }

    /**
     * The owner gives this barber profile a sign-in: an invitation to the phone, which its holder (made here, as a
     * bare sign-in, if nobody signed in with it yet) accepts. Returns the holder.
     */
    private UUID linkBarber(UUID staffId, String phone) {
        Reply invited = put("/salon/staff/" + staffId + "/login", Map.of("phone", phone), owner);
        assertThat(invited.status()).as(invited.text()).isEqualTo(200);
        return acceptInvite(phone);
    }

    /** The owner invites a helper by phone; its holder (made if needed) accepts. Returns the holder. */
    private UUID addHelper(String phone) {
        Reply invited = post("/shops/me/helpers", Map.of("phone", phone), owner);
        assertThat(invited.status()).as(invited.text()).isEqualTo(202);
        return acceptInvite(phone);
    }

    /** The person with this phone (a bare sign-in if nobody has one) accepts the invitation waiting for it. */
    private UUID acceptInvite(String phone) {
        UUID who = jdbc.query("SELECT id FROM users WHERE phone = ?", (rs, i) -> rs.getObject(1, UUID.class), phone)
                .stream().findFirst().orElseGet(() -> {
                    UUID id = UUID.randomUUID();
                    TestData.bareUser(jdbc, id, phone);
                    return id;
                });
        UUID invite = jdbc.queryForObject("SELECT id FROM shop_invites WHERE to_phone = ? AND status = 'PENDING'",
                UUID.class, phone);
        Reply accepted = post("/me/invites/" + invite + "/accept", Map.of(), who);
        assertThat(accepted.status()).as(accepted.text()).isEqualTo(200);
        return who;
    }

    private UUID person(String phone) {
        UUID id = UUID.randomUUID();
        TestData.user(jdbc, id, phone);
        return id;
    }

    private int count(String tableAndWhere) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + tableAndWhere, Integer.class);
        return n == null ? 0 : n;
    }

    private static Instant at(String hhmm) {
        return java.time.LocalDate.of(2026, 11, 10).atTime(java.time.LocalTime.parse(hhmm))
                .atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
    }

    /** Runs two requests at the same moment and returns both replies. */
    private static List<Reply> race(Supplier<Reply> a, Supplier<Reply> b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            Future<Reply> fa = pool.submit(() -> {
                go.await();
                return a.get();
            });
            Future<Reply> fb = pool.submit(() -> {
                go.await();
                return b.get();
            });
            go.countDown();
            return List.of(fa.get(30, TimeUnit.SECONDS), fb.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    /** A token as sign-in makes it (the shop endpoints read the membership from the database). */
    private String token(UUID who) {
        Boolean admin = jdbc.queryForObject("SELECT admin_role <> 'NONE' FROM users WHERE id = ?", Boolean.class,
                who);
        return jwt.issue(new AuthPrincipal(who, null, null, Boolean.TRUE.equals(admin)),
                sessions.currentGeneration(who)).token();
    }

    private Reply get(String path, UUID who) {
        return call(HttpMethod.GET, path, null, token(who), null);
    }

    private Reply post(String path, Object body, UUID who) {
        return call(HttpMethod.POST, path, body, token(who), null);
    }

    private Reply put(String path, Object body, UUID who) {
        return call(HttpMethod.PUT, path, body, token(who), null);
    }

    private Reply delete(String path, UUID who) {
        return call(HttpMethod.DELETE, path, null, token(who), null);
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
        return new Reply(status, parsed, text);
    }

    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

    private static Object field(Object json, String name) {
        return ((Map<?, ?>) json).get(name);
    }

    private static UUID id(Reply r) {
        assertThat(r.status()).as(r.text()).isBetween(200, 201);
        return UUID.fromString((String) field(r.body(), "id"));
    }

    private record Reply(int status, Object body, String text) {
    }
}
