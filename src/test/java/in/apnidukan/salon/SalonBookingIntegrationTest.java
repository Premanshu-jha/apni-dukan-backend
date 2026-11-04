package in.apnidukan.salon;

import in.apnidukan.common.security.AuthPrincipal;
import in.apnidukan.common.security.JwtService;
import in.apnidukan.common.security.SessionRevocations;
import in.apnidukan.notifications.domain.NotificationJob;
import in.apnidukan.notifications.domain.PushSubscription;
import in.apnidukan.notifications.service.NotificationSendJob;
import in.apnidukan.notifications.service.PushSender;
import in.apnidukan.salon.service.AutoEndJob;
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Online booking, walk-ins and the salon's daily work end to end, against real Postgres and Redis in Docker, with a
 * clock the test moves by hand. Races are fired at the same moment from two threads: the database's own rules
 * must let exactly the right one through.
 *
 * The salon: Raju does Haircut (30) and Beard (15); Sunil does Haircut and Facial (45). Both work every day
 * 10:00-21:00. "Now" starts at Tuesday 10 Nov 2026, 10:00 in India.
 */
@EnabledIf("in.apnidukan.support.TestContainers#dockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({CapturingSenders.class, SalonBookingIntegrationTest.Fakes.class})
class SalonBookingIntegrationTest {

    static final Instant TUESDAY_10_00 = Instant.parse("2026-11-10T04:30:00Z");
    static final TestClock CLOCK = new TestClock(TUESDAY_10_00);
    static final List<String> PUSHED = new CopyOnWriteArrayList<>();
    static volatile PushSender.Result pushResult = PushSender.Result.SENT;
    static final List<String> TEXTED = new CopyOnWriteArrayList<>();

    private static final String FCM = "https://fcm.googleapis.com/fcm/send/device-1";
    private static final String P256DH =
            "BFFcPW6545a5BNP-yn9U_c0MwemXvzddylFa0KbDtANfRTa-OlDzGPv5pUdZAqIhUCvvDVfgjFOyzApW8X2fk1Q";
    /** The device's 16-byte auth value: bytes 100..115, base64url. */
    private static final String DEVICE_A = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(new byte[] {100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 114, 115});

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        TestClock testClock() {
            return CLOCK;
        }

        @Bean
        @Primary
        in.apnidukan.common.notify.TextMessages recordingTextMessages() {
            return (phone, templateId, variables) -> {
                TEXTED.add(phone + " " + templateId + " " + new java.util.TreeMap<>(variables));
                return true;
            };
        }

        @Bean
        @Primary
        PushSender recordingPushSender() {
            return (PushSubscription device, String payload) -> {
                PUSHED.add(payload);
                return pushResult;
            };
        }
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        TestContainers.register(registry);
        // Only this type has an SMS template in the tests: it goes out as SMS too, the others don't
        registry.add("app.sms.template.booking-cancelled-by-salon", () -> "tpl-salon-cancel");
    }

    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired JwtService jwt;
    @Autowired SessionRevocations sessions;
    @Autowired AutoEndJob autoEnd;
    @Autowired NotificationSendJob sender;
    @Autowired in.apnidukan.notifications.service.MessageCleanupJob messageCleanup;
    @Autowired in.apnidukan.notifications.repo.NotificationStore store;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    RestClient http;
    UUID owner;
    UUID salon;
    UUID raju;
    UUID sunil;
    UUID haircut;
    UUID beard;
    UUID facial;
    UUID customer;
    UUID customer2;

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
        PUSHED.clear();
        TEXTED.clear();
        pushResult = PushSender.Result.SENT;

        owner = person("9000000001");
        customer = person("9000000101");
        customer2 = person("9000000102");
        salon = UUID.randomUUID();
        TestData.salon(jdbc, salon, owner);
        jdbc.update("UPDATE shops SET status = 'APPROVED' WHERE id = ?", salon);
        haircut = id(post("/salon/services", service("Haircut", 30, 15000), owner));
        beard = id(post("/salon/services", service("Beard trim", 15, 8000), owner));
        facial = id(post("/salon/services", service("Facial", 45, 40000), owner));
        raju = barber("Raju", List.of(haircut, beard));
        CLOCK.advance(Duration.ofSeconds(1));   // Sunil is added after Raju: with equal load, Raju is tried first
        sunil = barber("Sunil", List.of(haircut, facial));
        CLOCK.set(TUESDAY_10_00);
    }

    // ---------- booking ----------

    @Test
    void aCustomerFindsTheSalonSeesFreeTimesAndBooks() {
        List<?> near = (List<?>) get("/salons?lat=17.466&lng=78.365", customer).body();
        assertThat(near).hasSize(1);
        assertThat(field(near.get(0), "id")).isEqualTo(salon.toString());
        assertThat((List<?>) get("/salons/" + salon + "/services", customer).body()).hasSize(3);

        Reply slots = get("/salons/" + salon + "/availability?services=" + haircut + "," + beard
                + "&date=2026-11-10", customer);
        List<?> starts = (List<?>) field(slots.body(), "starts");
        assertThat(field(starts.get(0), "time")).isEqualTo("10:30");   // 30 minutes' notice, on a quarter hour
        assertThat(field(slots.body(), "durationMinutes")).isEqualTo(45);

        Reply booked = book(customer, at("12:00"), List.of(haircut, beard), UUID.randomUUID());
        assertThat(booked.status()).as(booked.text()).isEqualTo(201);
        assertThat((String) field(booked.body(), "bookingNo")).matches("S[A-HJ-NP-Z2-9]{7}");
        assertThat((String) field(booked.body(), "startCode")).matches("[0-9]{4}");
        assertThat(field(booked.body(), "totalPricePaise")).isEqualTo(23000);
        assertThat(field(booked.body(), "heldUntil")).isEqualTo(at("12:15").toString());
        UUID bookingId = id(booked);
        // Only Raju does both services
        assertThat(jdbc.queryForObject("SELECT staff_id FROM appointments WHERE id = ?", UUID.class, bookingId))
                .isEqualTo(raju);
        // Confirmation now, reminder an hour before
        assertThat(jobs(bookingId)).containsExactly("BOOKING_CONFIRMED:PENDING", "BOOKING_REMINDER:PENDING");
        assertThat(jdbc.queryForObject("SELECT send_at FROM notification_jobs WHERE type = 'BOOKING_REMINDER'",
                java.time.OffsetDateTime.class).toInstant()).isEqualTo(at("11:00"));

        // The salon sees it with the customer's name, never the start code
        Reply day = get("/salon/day?date=2026-11-10", owner);
        assertThat(day.text()).contains(bookingId.toString()).doesNotContain("startCode");
        // The customer sees it with the code
        Reply mine = get("/me/bookings", customer);
        assertThat(field(((List<?>) mine.body()).get(0), "startCode")).isEqualTo(field(booked.body(), "startCode"));
        // 12:00 is now gone for haircut + beard (Raju was the only one)
        assertThat(times(haircut + "," + beard)).doesNotContain("12:00", "12:15", "12:30").contains("12:45");
    }

    @Test
    void theSameIdempotencyKeyGivesBackTheSameBooking() {
        UUID key = UUID.randomUUID();
        Reply first = book(customer, at("12:00"), List.of(haircut), key);
        Reply again = book(customer, at("12:00"), List.of(haircut), key);
        assertThat(again.status()).isEqualTo(201);
        assertThat(field(again.body(), "id")).isEqualTo(field(first.body(), "id"));
        assertThat(count("appointments")).isEqualTo(1);
        assertThat(book(customer, at("12:00"), List.of(haircut), null).status()).isEqualTo(400);
    }

    @Test
    void twoCustomersRacingForTheLastFreeBarberCantBothGetHim() throws Exception {
        List<Reply> replies = race(() -> book(customer, at("12:00"), List.of(haircut, beard), UUID.randomUUID()),
                () -> book(customer2, at("12:00"), List.of(haircut, beard), UUID.randomUUID()));
        assertThat(replies.stream().map(Reply::status).sorted().toList()).containsExactly(201, 409);
        Reply lost = replies.stream().filter(r -> r.status() == 409).findFirst().orElseThrow();
        assertThat(field(lost.body(), "code")).isEqualTo("SLOT_TAKEN");
        assertThat((List<?>) field(field(lost.body(), "details"), "nearest")).hasSize(3);
        assertThat(count("appointments")).isEqualTo(1);
    }

    @Test
    void withTwoBarbersFreeBothBookingsLandOnDifferentBarbers() throws Exception {
        List<Reply> replies = race(() -> book(customer, at("12:00"), List.of(haircut), UUID.randomUUID()),
                () -> book(customer2, at("12:00"), List.of(haircut), UUID.randomUUID()));
        assertThat(replies).allMatch(r -> r.status() == 201);
        assertThat(jdbc.queryForList("SELECT DISTINCT staff_id FROM appointments", UUID.class))
                .containsExactlyInAnyOrder(raju, sunil);
    }

    @Test
    void aCustomerCanHoldTwoBookingsAtOnce() throws Exception {
        assertThat(book(customer, at("12:00"), List.of(haircut), UUID.randomUUID()).status()).isEqualTo(201);
        // The second and third at the same moment: the customer lock lets only one through
        List<Reply> replies = race(() -> book(customer, at("13:00"), List.of(haircut), UUID.randomUUID()),
                () -> book(customer, at("14:00"), List.of(haircut), UUID.randomUUID()));
        assertThat(replies.stream().map(Reply::status).sorted().toList()).containsExactly(201, 409);
        assertThat(replies.stream().filter(r -> r.status() == 409).findFirst().orElseThrow().text())
                .contains("TOO_MANY_BOOKINGS");
    }

    @Test
    void timesOffTheGridOrOutsideTheWindowAreRefused() {
        assertThat(book(customer, at("12:07"), List.of(haircut), UUID.randomUUID()).text())
                .contains("OUTSIDE_WINDOW");
        assertThat(book(customer, at("10:15"), List.of(haircut), UUID.randomUUID()).text())
                .contains("OUTSIDE_WINDOW");   // less than 30 minutes' notice
        assertThat(book(customer, TUESDAY_10_00.plus(Duration.ofDays(8)), List.of(haircut), UUID.randomUUID())
                .text()).contains("OUTSIDE_WINDOW");   // more than 7 days ahead
        assertThat(book(customer, at("12:00"), List.of(beard, facial), UUID.randomUUID()).text())
                .contains("NO_BARBER_FOR_SERVICES");
    }

    // ---------- cancel, strikes, reschedule ----------

    @Test
    void lateCancelsAreStrikesAndThreeBlockOnlineBooking() {
        UUID early = id(book(customer, at("18:00"), List.of(haircut), UUID.randomUUID()));
        Reply onTime = post("/bookings/" + early + "/cancel", Map.of(), customer);
        assertThat(field(onTime.body(), "countedAsLate")).isEqualTo(false);

        for (String time : List.of("11:00", "11:30", "12:00")) {
            UUID b = id(book(customer, at(time), List.of(haircut), UUID.randomUUID()));
            CLOCK.set(at(time).minus(Duration.ofMinutes(20)));
            Reply late = post("/bookings/" + b + "/cancel", Map.of(), customer);
            assertThat(field(late.body(), "countedAsLate")).as(late.text()).isEqualTo(true);
            assertThat(jobs(b)).contains("BOOKING_CANCELLED:PENDING");
        }
        assertThat(count("customer_strikes")).isEqualTo(3);
        Reply blocked = book(customer, at("16:00"), List.of(haircut), UUID.randomUUID());
        assertThat(blocked.status()).isEqualTo(403);
        assertThat(field(blocked.body(), "code")).isEqualTo("BOOKING_BLOCKED");
        // Somebody else can still book
        assertThat(book(customer2, at("16:00"), List.of(haircut), UUID.randomUUID()).status()).isEqualTo(201);
        // Deleting the account (and signing up again) is no way round the block
        Reply delete = post("/auth/me/delete-code", Map.of(), customer);
        assertThat(delete.status()).as(delete.text()).isEqualTo(409);
        assertThat(field(delete.body(), "code")).isEqualTo("BOOKING_BLOCKED");
    }

    @Test
    void anAccountCantBeDeletedWithABookingStartingWithinTheHour() {
        UUID b = id(book(customer, at("11:00"), List.of(haircut), UUID.randomUUID()));
        CLOCK.set(at("10:30"));
        Reply soon = post("/auth/me/delete-code", Map.of(), customer);
        assertThat(soon.status()).as(soon.text()).isEqualTo(409);
        assertThat(field(soon.body(), "code")).isEqualTo("BOOKING_SOON");
        assertThat(status(b)).isEqualTo("CONFIRMED");
        // Cancelling it first is the way (a late cancel, a strike), then the account can go
        assertThat(post("/bookings/" + b + "/cancel", Map.of(), customer).status()).isEqualTo(200);
        assertThat(post("/auth/me/delete-code", Map.of(), customer).status()).isEqualTo(202);
    }

    @Test
    void refusedBookingAttemptsDontUseUpTheDailyChanges() {
        for (int i = 0; i < 12; i++) {   // outside the booking window: refused, not counted
            assertThat(field(book(customer, at("10:00").plus(Duration.ofDays(30)), List.of(haircut),
                    UUID.randomUUID()).body(), "code")).isEqualTo("OUTSIDE_WINDOW");
        }
        assertThat(book(customer, at("18:00"), List.of(haircut), UUID.randomUUID()).status()).isEqualTo(201);
    }

    @Test
    void oneCustomerCantHoldTwoBookingsAtTheSameTime() {
        UUID first = id(book(customer, at("12:00"), List.of(haircut), UUID.randomUUID()));
        Reply overlap = book(customer, at("12:15"), List.of(haircut), UUID.randomUUID());   // another barber is free
        assertThat(overlap.status()).as(overlap.text()).isEqualTo(409);
        assertThat(field(overlap.body(), "code")).isEqualTo("OVERLAPS_YOUR_BOOKING");
        UUID second = id(book(customer, at("15:00"), List.of(haircut), UUID.randomUUID()));
        Reply move = post("/bookings/" + second + "/reschedule", Map.of("startAt", at("12:15").toString()), customer);
        assertThat(field(move.body(), "code")).isEqualTo("OVERLAPS_YOUR_BOOKING");
        // Moving a booking a little onto its own time is fine
        assertThat(post("/bookings/" + first + "/reschedule", Map.of("startAt", at("12:15").toString()), customer)
                .status()).isEqualTo(200);
    }

    @Test
    void freeTimesForAMoveCountTheBookingsOwnTimeAsFree() {
        // Haircut + beard: only Raju does both. Booked 12:00-12:45
        UUID b = id(book(customer, at("12:00"), List.of(haircut, beard), UUID.randomUUID()));
        String path = "/salons/" + salon + "/availability?services=" + haircut + "&services=" + beard
                + "&date=2026-11-10";
        assertThat(get(path, customer).text()).doesNotContain(at("12:15").toString());
        assertThat(get(path + "&moving=" + b, customer).text()).contains(at("12:15").toString());
        // Someone else's booking can't be used to see through it
        assertThat(get(path + "&moving=" + b, customer2).status()).isEqualTo(404);
    }

    @Test
    void aCustomerCanBookOrMoveAtMostTenTimesADay() {
        for (int i = 0; i < 10; i++) {
            UUID b = id(book(customer, at("18:00"), List.of(haircut), UUID.randomUUID()));
            assertThat(post("/bookings/" + b + "/cancel", Map.of(), customer).status()).isEqualTo(200);
        }
        Reply more = book(customer, at("18:00"), List.of(haircut), UUID.randomUUID());
        assertThat(more.status()).as(more.text()).isEqualTo(429);
        assertThat(field(more.body(), "code")).isEqualTo("TOO_MANY_CHANGES");
        assertThat(book(customer2, at("18:00"), List.of(haircut), UUID.randomUUID()).status()).isEqualTo(201);
    }

    @Test
    void signingOutEverywhereStopsMessagesToEveryDevice() {
        assertThat(post("/me/push-subscriptions", subscription(FCM), customer).status()).isEqualTo(204);
        assertThat(count("push_subscriptions")).isEqualTo(1);
        assertThat(post("/auth/logout-all", Map.of(), customer).status()).isEqualTo(204);
        assertThat(count("push_subscriptions")).isZero();
    }

    @Test
    void reschedulingToAnOverlappingTimeMovesTheSameRowAndTheReminder() {
        UUID b = id(book(customer, at("12:00"), List.of(haircut, beard), UUID.randomUUID()));
        Reply moved = post("/bookings/" + b + "/reschedule", Map.of("startAt", at("12:15").toString()), customer);
        assertThat(moved.status()).as(moved.text()).isEqualTo(200);
        assertThat(field(moved.body(), "startsAt")).isEqualTo(at("12:15").toString());
        assertThat(jdbc.queryForObject("SELECT staff_id FROM appointments WHERE id = ?", UUID.class, b))
                .isEqualTo(raju);
        assertThat(jobs(b)).containsExactlyInAnyOrder("BOOKING_CONFIRMED:CANCELLED", "BOOKING_REMINDER:CANCELLED",
                "BOOKING_RESCHEDULED:PENDING", "BOOKING_REMINDER:PENDING");
    }

    @Test
    void reschedulingMovesToAnotherBarberWhenTheFirstIsBusy() {
        UUID mine = id(book(customer, at("12:00"), List.of(haircut), UUID.randomUUID()));   // Raju (added first)
        assertThat(staffOf(mine)).isEqualTo(raju);
        book(customer2, at("13:00"), List.of(haircut, beard), UUID.randomUUID());   // Raju busy at 13:00
        Reply moved = post("/bookings/" + mine + "/reschedule", Map.of("startAt", at("13:00").toString()), customer);
        assertThat(moved.status()).as(moved.text()).isEqualTo(200);
        assertThat(staffOf(mine)).isEqualTo(sunil);
        // Now both barbers are busy at 13:00: a third customer can't get it
        UUID third = person("9000000103");
        assertThat(book(third, at("13:00"), List.of(haircut), UUID.randomUUID()).text()).contains("SLOT_TAKEN");
    }

    @Test
    void aBookingRacingAWalkInForTheSameBarberMovesOnToTheNextBarber() throws Exception {
        // A walk-in for Raju at 12:00 is being saved but not committed yet when the booking arrives
        try (Held walkIn = new Held(() -> insertWalkIn(raju, "12:00", 30))) {
            Future<Reply> booking = background(() -> book(customer, at("12:00"), List.of(haircut), UUID.randomUUID()));
            waitUntilBlocked("insert into appointments");
            walkIn.commit();
            Reply r = booking.get(10, TimeUnit.SECONDS);
            assertThat(r.status()).as(r.text()).isEqualTo(201);
            assertThat(staffOf(id(r))).isEqualTo(sunil);   // Raju was tried first, the database said busy
        }
    }

    @Test
    void reassigningOntoTimeTakenAtTheSameMomentIsRefused() throws Exception {
        UUID b = id(book(customer, at("12:00"), List.of(haircut), UUID.randomUUID()));   // Raju
        try (Held walkIn = new Held(() -> insertWalkIn(sunil, "12:00", 30))) {
            Future<Reply> reassign = background(() -> post("/salon/appointments/" + b + "/reassign",
                    Map.of("staffId", sunil.toString()), owner));
            waitUntilBlocked("update appointments");
            walkIn.commit();
            Reply r = reassign.get(10, TimeUnit.SECONDS);
            assertThat(field(r.body(), "code")).as(r.text()).isEqualTo("BARBER_BUSY");
            assertThat(staffOf(b)).isEqualTo(raju);
        }
    }

    @Test
    void aBookingMeetingABarberBeingSwitchedOffGoesToTheNextBarber() throws Exception {
        // A switch-off holds Raju's row locked (as PATCH active=false does) and isn't committed yet when the
        // booking arrives
        try (Held switchOff = new Held(() -> {
            jdbc.queryForList("SELECT id FROM salon_staff WHERE id = ? FOR UPDATE", raju);
            jdbc.update("UPDATE salon_staff SET active = false WHERE id = ?", raju);
        })) {
            Future<Reply> booking = background(() -> book(customer, at("12:00"), List.of(haircut), UUID.randomUUID()));
            waitUntilBlocked("select active from salon_staff");
            switchOff.commit();
            Reply r = booking.get(10, TimeUnit.SECONDS);
            assertThat(r.status()).as(r.text()).isEqualTo(201);
            assertThat(staffOf(id(r))).isEqualTo(sunil);   // never on the barber who was just switched off
        }
    }

    @Test
    void theSameKeyForADifferentBookingIsRefused() {
        UUID key = UUID.randomUUID();
        book(customer, at("12:00"), List.of(haircut), key);
        Reply other = book(customer, at("13:00"), List.of(haircut), key);
        assertThat(other.status()).isEqualTo(409);
        assertThat(field(other.body(), "code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(count("appointments")).isEqualTo(1);
    }

    @Test
    void noReminderForABookingMadeLessThan75MinutesAhead() {
        UUID soon = id(book(customer, at("11:15"), List.of(haircut), UUID.randomUUID()));
        assertThat(jobs(soon)).containsExactly("BOOKING_CONFIRMED:PENDING");
    }

    @Test
    void cancellingExactlyOneHourBeforeIsNotLate() {
        UUID b = id(book(customer, at("12:00"), List.of(haircut), UUID.randomUUID()));
        CLOCK.set(at("11:00"));
        assertThat(field(post("/bookings/" + b + "/cancel", Map.of(), customer).body(), "countedAsLate"))
                .isEqualTo(false);
        assertThat(count("customer_strikes")).isZero();
    }

    @Test
    void aBookingNobodyStartedMovesToPastOnceItsTimeIsOver() {
        UUID b = id(book(customer, at("12:00"), List.of(haircut), UUID.randomUUID()));
        CLOCK.set(at("12:31"));
        assertThat((List<?>) get("/me/bookings", customer).body()).isEmpty();
        assertThat(get("/me/bookings?when=past", customer).text()).contains(b.toString());
        assertThat(start(b, Map.of("withoutCode", true), owner).text()).contains("WRONG_STATUS");   // too late
    }

    @Test
    void someoneElsesBookingIsNotFound() {
        UUID b = id(book(customer, at("12:00"), List.of(haircut), UUID.randomUUID()));
        assertThat(get("/bookings/" + b, customer2).status()).isEqualTo(404);
        assertThat(post("/bookings/" + b + "/cancel", Map.of(), customer2).status()).isEqualTo(404);
    }

    // ---------- start code, no-show, automatic end ----------

    @Test
    void startNeedsTheCustomersCodeAndLocksAfterFiveWrongTries() {
        Reply booked = book(customer, at("12:00"), List.of(haircut, beard), UUID.randomUUID());
        UUID b = id(booked);
        String code = (String) field(booked.body(), "startCode");
        String wrong = code.equals("0000") ? "1111" : "0000";
        UUID other = id(book(customer2, at("12:00"), List.of(haircut), UUID.randomUUID()));   // Sunil

        CLOCK.set(at("11:40"));
        assertThat(start(b, Map.of("code", code), owner).text()).contains("WRONG_STATUS");   // too early
        CLOCK.set(at("11:45"));
        Reply first = start(b, Map.of("code", wrong), owner);
        assertThat(first.status()).isEqualTo(400);
        assertThat(field(field(first.body(), "details"), "triesLeft")).isEqualTo(4);
        for (int i = 0; i < 3; i++) {
            start(b, Map.of("code", wrong), owner);
        }
        assertThat(start(b, Map.of("code", wrong), owner).status()).isEqualTo(429);
        assertThat(start(b, Map.of("code", code), owner).status()).isEqualTo(429);   // even the right one now

        Reply without = start(b, Map.of("withoutCode", true), owner);
        assertThat(without.status()).as(without.text()).isEqualTo(200);
        assertThat(field(without.body(), "status")).isEqualTo("IN_SERVICE");
        assertThat(field(without.body(), "startedWithCode")).isEqualTo(false);
        assertThat(jobs(b)).contains("BOOKING_REMINDER:CANCELLED");

        String otherCode = (String) field(get("/bookings/" + other, customer2).body(), "startCode");
        Reply ok = start(other, Map.of("code", otherCode), owner);
        assertThat(field(ok.body(), "startedWithCode")).isEqualTo(true);
    }

    @Test
    void noShowIsAllowedFifteenMinutesAfterTheStartAndFreesTheTime() {
        UUID b = id(book(customer, at("12:00"), List.of(haircut, beard), UUID.randomUUID()));
        CLOCK.set(at("12:14"));
        assertThat(post("/salon/appointments/" + b + "/no-show", Map.of(), owner).text()).contains("WRONG_STATUS");
        CLOCK.set(at("12:15"));
        Reply r = post("/salon/appointments/" + b + "/no-show", Map.of(), owner);
        assertThat(field(r.body(), "status")).as(r.text()).isEqualTo("NO_SHOW");
        assertThat(count("customer_strikes")).isEqualTo(1);
        // Raju is free again: a walk-in can start right now
        Reply walkIn = walkIn(owner, List.of(haircut, beard), raju, UUID.randomUUID());
        assertThat(walkIn.status()).as(walkIn.text()).isEqualTo(201);
    }

    @Test
    void appointmentsInServiceEndByThemselvesAndUnstartedOnesBecomeNoShowsAfter15Minutes() {
        UUID walk = id(walkIn(owner, List.of(haircut), raju, UUID.randomUUID()));   // 10:00-10:30
        UUID b = id(book(customer, at("11:00"), List.of(haircut), UUID.randomUUID()));   // Raju
        UUID held = id(book(customer2, at("11:00"), List.of(haircut), UUID.randomUUID()));   // Sunil (Raju is taken)
        CLOCK.set(at("10:29"));
        assertThat(autoEnd.runOnce()).isZero();
        CLOCK.set(at("11:14"));
        assertThat(autoEnd.runOnce()).isEqualTo(1);   // the walk-in in service is done; the others are still held
        assertThat(status(walk)).isEqualTo("COMPLETED");
        assertThat(get("/salon/day", owner).text()).contains("\"notStarted\":true");
        CLOCK.set(at("11:15"));
        assertThat(start(b, Map.of("withoutCode", true), owner).text()).contains("WRONG_STATUS");   // held until 11:15
        assertThat(autoEnd.runOnce()).isEqualTo(2);
        assertThat(autoEnd.runOnce()).isZero();   // running again changes nothing
        assertThat(status(b)).isEqualTo("NO_SHOW");
        assertThat(status(held)).isEqualTo("NO_SHOW");
        assertThat(count("customer_strikes")).isEqualTo(2);
        assertThat(count("appointment_events WHERE event = 'NO_SHOW' AND detail = 'automatic' AND by_user IS NULL"))
                .isEqualTo(2);
        assertThat(jobs(b)).contains("BOOKING_NO_SHOW:PENDING");
        // The barbers are free again at once
        assertThat(walkIn(owner, List.of(haircut), raju, UUID.randomUUID()).status()).isEqualTo(201);
    }

    @Test
    void walkInRetriesReturnTheSameWalkInAndADifferentOneWithTheSameKeyIsRefused() {
        UUID key = UUID.randomUUID();
        Reply first = walkIn(owner, List.of(haircut), raju, key);
        Reply again = walkIn(owner, List.of(haircut), raju, key);
        assertThat(field(again.body(), "id")).isEqualTo(field(first.body(), "id"));
        assertThat(field(walkIn(owner, List.of(haircut), sunil, key).body(), "code"))
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(count("appointments")).isEqualTo(1);
    }

    // ---------- walk-ins and who may add them ----------

    @Test
    void onlyTheOwnerAndPeopleHeGaveAccessToCanAddWalkIns() {
        UUID helper = person("9000000002");
        TestData.member(jdbc, salon, helper, "HELPER");
        Reply refused = walkIn(helper, List.of(haircut), sunil, UUID.randomUUID());
        assertThat(refused.status()).isEqualTo(403);
        assertThat(field(refused.body(), "code")).isEqualTo("WALK_INS_NOT_ALLOWED");

        Reply granted = put("/salon/members/" + helper + "/walk-in-access", Map.of("allowed", true), owner);
        assertThat(granted.status()).as(granted.text()).isEqualTo(200);
        assertThat(walkIn(helper, List.of(haircut), sunil, UUID.randomUUID()).status()).isEqualTo(201);
        assertThat(put("/salon/members/" + helper + "/walk-in-access", Map.of("allowed", true), helper).status())
                .isEqualTo(403);   // only the owner gives access

        put("/salon/members/" + helper + "/walk-in-access", Map.of("allowed", false), owner);
        assertThat(walkIn(helper, List.of(haircut), raju, UUID.randomUUID()).status()).isEqualTo(403);
    }

    @Test
    void walkInSuggestionsAndWalkInsOnlyStartNow() {
        walkIn(owner, List.of(haircut, beard), raju, UUID.randomUUID());   // Raju busy 10:00-10:45
        Reply options = get("/salon/walk-ins/options?services=" + haircut, owner);
        List<?> list = (List<?>) options.body();
        assertThat(field(list.get(0), "staffId")).isEqualTo(sunil.toString());   // free now first
        assertThat(field(list.get(0), "freeNow")).isEqualTo(true);
        assertThat(field(list.get(1), "freeNow")).isEqualTo(false);
        assertThat(field(list.get(1), "time")).isEqualTo("10:45");

        // A walk-in always starts now: a time for later is refused, and so is a barber who isn't free now
        Map<String, Object> later = new HashMap<>(Map.of("serviceIds", List.of(haircut), "staffId", sunil.toString(),
                "startAt", at("12:00").toString()));
        assertThat(field(call(HttpMethod.POST, "/salon/walk-ins", later, owner, UUID.randomUUID()).body(), "code"))
                .isEqualTo("UNKNOWN_FIELD");
        Reply busy = walkIn(owner, List.of(beard), raju, UUID.randomUUID());
        assertThat(field(busy.body(), "code")).as(busy.text()).isEqualTo("BARBER_BUSY");
        assertThat(field(field(busy.body(), "details"), "freeAt")).isEqualTo(at("10:45").toString());
    }

    // ---------- barbers with their own sign-in ----------

    @Test
    void aBarberWithASignInSeesAndWorksOnlyHisOwnDay() {
        Reply linked = put("/salon/staff/" + raju + "/login", Map.of("phone", "9000000201"), owner);
        assertThat(linked.status()).as(linked.text()).isEqualTo(200);
        assertThat(field(linked.body(), "loginPhone")).isEqualTo("9000000201");
        UUID rajuUser = jdbc.queryForObject("SELECT id FROM users WHERE phone = '9000000201'", UUID.class);
        assertThat(jdbc.queryForObject("SELECT role FROM shop_members WHERE user_id = ?", String.class, rajuUser))
                .isEqualTo("BARBER");

        UUID onSunil = id(book(customer, at("12:00"), List.of(haircut, facial).subList(0, 1),
                UUID.randomUUID()));   // Raju added first → Raju
        UUID onSunil2 = id(book(customer2, at("12:00"), List.of(haircut), UUID.randomUUID()));   // then Sunil
        UUID sunilsBooking = staffOf(onSunil).equals(sunil) ? onSunil : onSunil2;
        UUID rajusBooking = sunilsBooking.equals(onSunil) ? onSunil2 : onSunil;

        Reply day = get("/salon/day?date=2026-11-10", rajuUser);
        assertThat((List<?>) field(day.body(), "barbers")).hasSize(1);
        assertThat(day.text()).contains(rajusBooking.toString()).doesNotContain(sunilsBooking.toString());
        CLOCK.set(at("11:50"));
        assertThat(start(sunilsBooking, Map.of("withoutCode", true), rajuUser).status()).isEqualTo(404);
        assertThat(start(rajusBooking, Map.of("withoutCode", true), rajuUser).status()).isEqualTo(403);
        // His own break yes; leave and walk-ins no
        assertThat(post("/salon/staff/" + raju + "/blocks", block("BREAK", "15:00", "15:30"), rajuUser).status())
                .isEqualTo(201);
        assertThat(field(post("/salon/staff/" + raju + "/blocks", block("LEAVE", "17:00", "21:00"), rajuUser)
                .body(), "code")).isEqualTo("OWNER_ONLY");
        assertThat(walkIn(rajuUser, List.of(haircut), raju, UUID.randomUUID()).status()).isEqualTo(403);

        // The same phone can't be a barber in a second place
        Reply twice = put("/salon/staff/" + sunil + "/login", Map.of("phone", "9000000201"), owner);
        assertThat(field(twice.body(), "code")).isEqualTo("ALREADY_IN_A_SHOP");

        // Unlinked: membership gone, his old sign-in stops working at once
        String oldToken = token(rajuUser);
        assertThat(delete("/salon/staff/" + raju + "/login", owner).status()).isEqualTo(200);
        assertThat(count("shop_members WHERE user_id = '" + rajuUser + "'")).isZero();
        Integer oldTokenStatus = http.get().uri("/salon/day").headers(h -> h.setBearerAuth(oldToken))
                .exchange((rq, res) -> Integer.valueOf(res.getStatusCode().value()));
        assertThat(oldTokenStatus).isEqualTo(401);
    }

    @Test
    void aBarberWithWalkInAccessSeesEveryBarberAndStartsHisOwnBookingWithTheCode() {
        put("/salon/staff/" + raju + "/login", Map.of("phone", "9000000201"), owner);
        UUID rajuUser = jdbc.queryForObject("SELECT id FROM users WHERE phone = '9000000201'", UUID.class);
        put("/salon/members/" + rajuUser + "/walk-in-access", Map.of("allowed", true), owner);
        assertThat((List<?>) field(get("/salon/day?date=2026-11-10", rajuUser).body(), "barbers")).hasSize(2);
        assertThat(walkIn(rajuUser, List.of(haircut), sunil, UUID.randomUUID()).status()).isEqualTo(201);
        // Other members don't see barbers' sign-in phones
        assertThat(get("/salon/staff", rajuUser).text()).doesNotContain("9000000201");
        assertThat(get("/salon/staff", owner).text()).contains("9000000201");

        Reply booked = book(customer, at("12:00"), List.of(haircut, beard), UUID.randomUUID());   // Raju
        CLOCK.set(at("11:50"));
        Reply started = start(id(booked), Map.of("code", field(booked.body(), "startCode")), rajuUser);
        assertThat(field(started.body(), "status")).as(started.text()).isEqualTo("IN_SERVICE");
    }

    @Test
    void aBarberCantUseTheShopsFiles() {
        put("/salon/staff/" + raju + "/login", Map.of("phone", "9000000201"), owner);
        UUID rajuUser = jdbc.queryForObject("SELECT id FROM users WHERE phone = '9000000201'", UUID.class);
        UUID bill = id(upload(owner, salon, "BILL", 7));
        Integer status = http.get().uri("/files/" + bill + "/link")
                .headers(h -> h.setBearerAuth(jwt.issue(new AuthPrincipal(rajuUser, salon, "BARBER", false),
                        sessions.currentGeneration(rajuUser)).token()))
                .exchange((rq, res) -> Integer.valueOf(res.getStatusCode().value()));
        assertThat(status).isEqualTo(403);
    }

    @Test
    void aBarberWithUpcomingBookingsCantBeSwitchedOff() {
        book(customer, at("12:00"), List.of(haircut, beard), UUID.randomUUID());   // Raju
        long version = ((Number) field(((List<?>) get("/salon/staff", owner).body()).get(0), "version")).longValue();
        Reply r = patch("/salon/staff/" + raju, Map.of("version", version, "active", false), owner);
        assertThat(field(r.body(), "code")).as(r.text()).isEqualTo("STAFF_HAS_BOOKINGS");
    }

    @Test
    void aBarberSwitchedBackOnGetsTheClosedDayAndHisOldBreakThatDayGoes() {
        Reply brk = post("/salon/staff/" + raju + "/blocks", Map.of("type", "BREAK",
                "startAt", at("13:00").plus(Duration.ofDays(2)).toString(),
                "endAt", at("14:00").plus(Duration.ofDays(2)).toString()), owner);
        assertThat(brk.status()).as(brk.text()).isEqualTo(201);
        long version = rajuVersion();
        assertThat(patch("/salon/staff/" + raju, Map.of("version", version, "active", false), owner).status())
                .isEqualTo(200);
        post("/salon/closures", Map.of("date", "2026-11-12"), owner);   // only Sunil is active now
        Reply on = patch("/salon/staff/" + raju, Map.of("version", rajuVersion(), "active", true), owner);
        assertThat(on.status()).as(on.text()).isEqualTo(200);
        assertThat(count("appointments WHERE staff_id = '" + raju + "' AND block_type = 'CLOSED' "
                + "AND status = 'BLOCKED'")).isEqualTo(1);
        assertThat(status(id(brk))).isEqualTo("REMOVED");
        assertThat(slots(haircut.toString(), "2026-11-12")).isEmpty();
    }

    @Test
    void aLeaveReachingOutsideAClosedDayStopsTheBarberBeingSwitchedBackOnUntilItIsShortened() {
        Instant day12 = at("00:00").plus(Duration.ofDays(2));
        Reply leave = post("/salon/staff/" + raju + "/blocks", Map.of("type", "LEAVE",
                "startAt", day12.minus(Duration.ofDays(1)).toString(),
                "endAt", day12.plus(Duration.ofDays(2)).toString()), owner);   // 11 to 13 Nov
        assertThat(leave.status()).as(leave.text()).isEqualTo(201);
        patch("/salon/staff/" + raju, Map.of("version", rajuVersion(), "active", false), owner);
        post("/salon/closures", Map.of("date", "2026-11-12"), owner);   // only Sunil is active now
        Reply refused = patch("/salon/staff/" + raju, Map.of("version", rajuVersion(), "active", true), owner);
        assertThat(field(refused.body(), "code")).as(refused.text()).isEqualTo("BLOCK_CONFLICTS");
        assertThat(status(id(leave))).isEqualTo("BLOCKED");   // nothing of his leave removed

        // The owner removes the leave; now Raju comes back and the closed day is closed for him too
        assertThat(delete("/salon/staff/" + raju + "/blocks/" + id(leave), owner).status()).isEqualTo(204);
        Reply on = patch("/salon/staff/" + raju, Map.of("version", rajuVersion(), "active", true), owner);
        assertThat(on.status()).as(on.text()).isEqualTo(200);
        assertThat(count("appointments WHERE staff_id = '" + raju + "' AND block_type = 'CLOSED' "
                + "AND status = 'BLOCKED'")).isEqualTo(1);
        assertThat(slots(haircut.toString(), "2026-11-12")).isEmpty();
    }

    @Test
    void aBarberAddedAfterADayWasClosedIsClosedThatDayToo() {
        post("/salon/closures", Map.of("date", "2026-11-12"), owner);
        UUID amit = barber("Amit", List.of(haircut));
        assertThat(count("appointments WHERE staff_id = '" + amit + "' AND block_type = 'CLOSED'")).isEqualTo(1);
        assertThat(slots(haircut.toString(), "2026-11-12")).isEmpty();
    }

    // ---------- breaks, leave, closed days, reassign, salon cancel ----------

    @Test
    void aBlockOverABookingIsRefusedUntilTheBookingIsMoved() {
        UUID b = id(book(customer, at("15:00"), List.of(haircut, beard), UUID.randomUUID()));   // Raju
        Reply conflicts = get("/salon/staff/" + raju + "/blocks/conflicts?from=" + at("14:30") + "&to="
                + at("16:00"), owner);
        assertThat((List<?>) conflicts.body()).hasSize(1);
        Reply refused = post("/salon/staff/" + raju + "/blocks", block("BREAK", "14:30", "16:00"), owner);
        assertThat(refused.status()).isEqualTo(409);
        assertThat(field(refused.body(), "code")).isEqualTo("BLOCK_CONFLICTS");
        assertThat(count("appointments WHERE kind = 'BLOCK'")).isZero();

        // Sunil doesn't do beard: can't take it
        assertThat(post("/salon/appointments/" + b + "/reassign", Map.of("staffId", sunil.toString()), owner)
                .status()).isEqualTo(400);
        Reply cancelled = post("/salon/appointments/" + b + "/cancel", Map.of("reason", "Raju is unwell"), owner);
        assertThat(field(cancelled.body(), "status")).isEqualTo("CANCELLED_BY_SALON");
        assertThat(jobs(b)).contains("BOOKING_REMINDER:CANCELLED", "BOOKING_CANCELLED_BY_SALON:PENDING");
        assertThat(count("customer_strikes")).isZero();

        Reply saved = post("/salon/staff/" + raju + "/blocks", block("BREAK", "14:30", "16:00"), owner);
        assertThat(saved.status()).as(saved.text()).isEqualTo(201);
        assertThat(times(haircut + "," + beard)).doesNotContain("14:30", "15:00").contains("16:00");
        assertThat(delete("/salon/staff/" + raju + "/blocks/" + id(saved), owner).status()).isEqualTo(204);
        assertThat(times(haircut + "," + beard)).contains("15:00");
    }

    @Test
    void reassignMovesABookingToAnotherFreeBarber() {
        UUID b = id(book(customer, at("12:00"), List.of(haircut), UUID.randomUUID()));
        UUID other = staffOf(b).equals(raju) ? sunil : raju;
        Reply moved = post("/salon/appointments/" + b + "/reassign", Map.of("staffId", other.toString()), owner);
        assertThat(moved.status()).as(moved.text()).isEqualTo(200);
        assertThat(staffOf(b)).isEqualTo(other);
        // The first barber now has another booking at 12:00: moving it back on top of that is refused
        book(customer2, at("12:00"), List.of(haircut), UUID.randomUUID());
        Reply busy = post("/salon/appointments/" + b + "/reassign",
                Map.of("staffId", (staffOf(b).equals(raju) ? sunil : raju).toString()), owner);
        assertThat(field(busy.body(), "code")).isEqualTo("BARBER_BUSY");
    }

    @Test
    void aClosedDayIsAllOrNothingAndRemovesEveryOnlineSlot() {
        book(customer, at("12:00").plus(Duration.ofDays(1)), List.of(haircut), UUID.randomUUID());
        Reply refused = post("/salon/closures", Map.of("date", "2026-11-11"), owner);
        assertThat(field(refused.body(), "code")).isEqualTo("BLOCK_CONFLICTS");
        assertThat(count("appointments WHERE kind = 'BLOCK'")).isZero();

        Reply closed = post("/salon/closures", Map.of("date", "2026-11-12"), owner);
        assertThat(closed.status()).as(closed.text()).isEqualTo(201);
        assertThat(field(closed.body(), "barbers")).isEqualTo(2);
        assertThat(slots(haircut.toString(), "2026-11-12")).isEmpty();
        assertThat(post("/salon/closures", Map.of("date", "2026-11-12"), customer).status()).isEqualTo(404);
        assertThat(delete("/salon/closures/" + field(closed.body(), "closureId"), owner).status()).isEqualTo(204);
        assertThat(slots(haircut.toString(), "2026-11-12")).isNotEmpty();
    }

    @Test
    void pausingHidesTheSalonAndStopsBookingsUntilResumed() {
        assertThat(post("/salon/pause", Map.of(), owner).status()).isEqualTo(200);
        assertThat((List<?>) get("/salons?lat=17.466&lng=78.365", customer).body()).isEmpty();
        assertThat(book(customer, at("12:00"), List.of(haircut), UUID.randomUUID()).text())
                .contains("SALON_NOT_BOOKABLE");
        CLOCK.set(at("23:59").plus(Duration.ofMinutes(1)));   // midnight: the pause ran out by itself
        assertThat((List<?>) get("/salons?lat=17.466&lng=78.365", customer).body()).hasSize(1);
        post("/salon/pause", Map.of(), owner);
        post("/salon/resume", Map.of(), owner);
        assertThat((List<?>) get("/salons?lat=17.466&lng=78.365", customer).body()).hasSize(1);
    }

    @Test
    void appointmentsOfAnotherSalonAreNotFound() {
        UUID b = id(book(customer, at("12:00"), List.of(haircut), UUID.randomUUID()));
        UUID otherOwner = person("9000000009");
        TestData.salon(jdbc, UUID.randomUUID(), otherOwner);
        CLOCK.set(at("12:20"));
        assertThat(post("/salon/appointments/" + b + "/no-show", Map.of(), otherOwner).status()).isEqualTo(404);
        assertThat(status(b)).isEqualTo("CONFIRMED");
    }

    // ---------- shops waiting for approval ----------

    @Test
    void registrationNeedsBothPhotosThenTheFieldTeamApprovesAfterTheVisit() {
        UUID newOwner = person("9000000010");
        UUID newSalon = UUID.randomUUID();
        TestData.salon(jdbc, newSalon, newOwner);
        assertThat(get("/salons/" + newSalon, customer).status()).isEqualTo(404);   // not approved: invisible

        Reply early = post("/shops/me/submit", Map.of("version", 0), newOwner);
        assertThat(field(early.body(), "code")).as(early.text()).isEqualTo("PHOTOS_REQUIRED");
        UUID ownerPhoto = id(upload(newOwner, newSalon, "OWNER_PHOTO", 1));
        UUID shopPhoto = id(upload(newOwner, newSalon, "SHOP_PHOTO", 2));
        // A photo uploaded for something else doesn't count
        UUID bill = id(upload(newOwner, newSalon, "BILL", 3));
        assertThat(put("/shops/me/photos", Map.of("version", 0, "ownerPhotoFileId", bill.toString()), newOwner)
                .status()).isEqualTo(400);
        Reply photos = put("/shops/me/photos", Map.of("version", 0, "ownerPhotoFileId", ownerPhoto.toString(),
                "shopPhotoFileId", shopPhoto.toString()), newOwner);
        assertThat(photos.status()).as(photos.text()).isEqualTo(200);
        // An attached photo can't be deleted
        assertThat(delete("/files/" + ownerPhoto, newOwner, newSalon).status()).isEqualTo(409);

        UUID admin = person("9000000099");
        jdbc.update("UPDATE users SET admin = true WHERE id = ?", admin);
        assertThat(approve(newSalon, admin).status()).isEqualTo(409);   // DRAFT
        Reply submitted = post("/shops/me/submit", Map.of("version", field(photos.body(), "version")), newOwner);
        assertThat(field(submitted.body(), "status")).as(submitted.text()).isEqualTo("PENDING_VISIT");

        assertThat(get("/admin/shops", customer).status()).isEqualTo(403);   // not the field team
        List<?> waiting = (List<?>) get("/admin/shops", admin).body();
        assertThat(waiting).hasSize(1);
        assertThat((String) field(waiting.get(0), "ownerPhotoUrl")).isNotBlank();
        assertThat((String) field(waiting.get(0), "shopPhotoUrl")).isNotBlank();
        // The owner changes the shop photo after the field team loaded the list: approving the old version fails
        long checked = ((Number) field(waiting.get(0), "version")).longValue();
        UUID otherPhoto = id(upload(newOwner, newSalon, "SHOP_PHOTO", 9));
        put("/shops/me/photos", Map.of("version", checked, "shopPhotoFileId", otherPhoto.toString()), newOwner);
        Reply stale = post("/admin/shops/" + newSalon + "/approve", Map.of("version", checked), admin);
        assertThat(field(stale.body(), "code")).as(stale.text()).isEqualTo("SHOP_CHANGED");
        assertThat(approve(newSalon, admin).status()).isEqualTo(200);
        assertThat(approve(newSalon, admin).status()).isEqualTo(409);
        Reply page = get("/salons/" + newSalon, customer);
        assertThat(page.status()).isEqualTo(200);
        assertThat((String) field(page.body(), "photoUrl")).isNotBlank();
        // A new shop photo after approval: back to the field team, invisible to customers meanwhile
        UUID newPhoto = id(upload(newOwner, newSalon, "SHOP_PHOTO", 8));
        long version = ((Number) field(get("/shops/me", newOwner).body(), "version")).longValue();
        Reply changed = put("/shops/me/photos", Map.of("version", version, "shopPhotoFileId", newPhoto.toString()),
                newOwner);
        assertThat(field(changed.body(), "status")).as(changed.text()).isEqualTo("PENDING_VISIT");
        assertThat(get("/salons/" + newSalon, customer).status()).isEqualTo(404);
        assertThat(approve(newSalon, admin).status()).isEqualTo(200);
        // A new address too; a new name alone doesn't need a visit
        version = ((Number) field(get("/shops/me", newOwner).body(), "version")).longValue();
        Reply renamed = patch("/shops/me", Map.of("version", version, "name", "Sharma Hair Studio"), newOwner);
        assertThat(field(renamed.body(), "status")).as(renamed.text()).isEqualTo("APPROVED");
        Reply moved = patch("/shops/me", Map.of("version", field(renamed.body(), "version"),
                "addressLine", "Shop 9, New Market Road"), newOwner);
        assertThat(field(moved.body(), "status")).as(moved.text()).isEqualTo("PENDING_VISIT");
        assertThat(approve(newSalon, admin).status()).isEqualTo(200);
        // A new owner photo or a moved map pin: checked again too
        UUID newOwnerPhoto = id(upload(newOwner, newSalon, "OWNER_PHOTO", 10));
        version = ((Number) field(get("/shops/me", newOwner).body(), "version")).longValue();
        assertThat(field(put("/shops/me/photos", Map.of("version", version, "ownerPhotoFileId",
                newOwnerPhoto.toString()), newOwner).body(), "status")).isEqualTo("PENDING_VISIT");
        assertThat(approve(newSalon, admin).status()).isEqualTo(200);
        version = ((Number) field(get("/shops/me", newOwner).body(), "version")).longValue();
        assertThat(field(patch("/shops/me", Map.of("version", version, "latitude", 17.4702), newOwner).body(),
                "status")).isEqualTo("PENDING_VISIT");
    }

    @Test
    void aMoveWaitsForBookingsInProgressAndDoesntResendRemindersAlreadySent() throws Exception {
        UUID b = id(book(customer, at("12:00"), List.of(haircut), UUID.randomUUID()));
        jdbc.update("UPDATE notification_jobs SET status = 'SENT' WHERE ref_id = ? AND type = 'BOOKING_REMINDER'", b);
        long version = ((Number) field(get("/shops/me", owner).body(), "version")).longValue();
        // A real booking is in progress: it has taken the shop (FOR SHARE) and now waits for a barber, whose rows a
        // held transaction keeps locked. The owner saves a new address meanwhile: the move must wait for the booking.
        Future<Reply> second;
        Future<Reply> move;
        try (Held barbers = new Held(() -> jdbc.queryForList("SELECT id FROM salon_staff WHERE shop_id = ? "
                + "FOR UPDATE", salon))) {
            second = background(() -> book(customer2, at("13:00"), List.of(haircut), UUID.randomUUID()));
            waitUntilBlocked("select active from salon_staff");
            move = background(() -> patch("/shops/me",
                    Map.of("version", version, "addressLine", "Shop 9, New Market Road"), owner));
            waitUntilBlocked("update shops");   // blocked by the booking's hold on the shop
            barbers.commit();
        }
        Reply booked = second.get(10, TimeUnit.SECONDS);
        assertThat(booked.status()).as(booked.text()).isEqualTo(201);   // booked before the move
        assertThat(move.get(10, TimeUnit.SECONDS).status()).isEqualTo(200);
        // The booking that finished first got its reminder rewritten with the new address
        assertThat(jdbc.queryForObject("SELECT body FROM notification_jobs WHERE ref_id = ? AND channel = 'PUSH' "
                + "AND type = 'BOOKING_REMINDER'", String.class, id(booked))).contains("New Market Road");
        // Its reminder had already gone out: no second one with the new address
        assertThat(jobs(b)).containsExactly("BOOKING_CONFIRMED:PENDING", "BOOKING_REMINDER:SENT");
    }

    @Test
    void aSalonMovingRewritesPendingRemindersAndHoldsMovesUntilCheckedAgain() {
        UUID b = id(book(customer, at("12:00"), List.of(haircut), UUID.randomUUID()));
        long version = ((Number) field(get("/shops/me", owner).body(), "version")).longValue();
        Reply moved = patch("/shops/me", Map.of("version", version, "addressLine", "Shop 9, New Market Road"), owner);
        assertThat(field(moved.body(), "status")).as(moved.text()).isEqualTo("PENDING_VISIT");
        assertThat(jobs(b)).containsExactly("BOOKING_CONFIRMED:PENDING", "BOOKING_REMINDER:PENDING");   // rewritten
        assertThat(jdbc.queryForObject("SELECT body FROM notification_jobs WHERE ref_id = ? AND status = 'PENDING' "
                + "AND type = 'BOOKING_REMINDER' AND channel = 'PUSH'", String.class, b)).contains("New Market Road");

        Reply mine = get("/bookings/" + b, customer);
        assertThat(field(mine.body(), "canChange")).isEqualTo(true);
        assertThat(field(mine.body(), "canMove")).isEqualTo(false);
        Reply move = post("/bookings/" + b + "/reschedule", Map.of("startAt", at("13:00").toString()), customer);
        assertThat(field(move.body(), "code")).as(move.text()).isEqualTo("SALON_NOT_BOOKABLE");
        assertThat(post("/bookings/" + b + "/cancel", Map.of(), customer).status()).isEqualTo(200);
    }

    @Test
    void aBarberPhotoIsOptionalAndMustBeAStaffPhotoOfThisSalon() {
        UUID photo = id(upload(owner, salon, "STAFF_PHOTO", 4));
        UUID shopPhoto = id(upload(owner, salon, "SHOP_PHOTO", 5));
        assertThat(put("/salon/staff/" + raju + "/photo", Map.of("fileId", shopPhoto.toString()), owner).status())
                .isEqualTo(400);
        Reply set = put("/salon/staff/" + raju + "/photo", Map.of("fileId", photo.toString()), owner);
        assertThat(field(set.body(), "photoFileId")).as(set.text()).isEqualTo(photo.toString());
        Map<String, Object> remove = new HashMap<>();
        remove.put("fileId", null);
        assertThat(field(put("/salon/staff/" + raju + "/photo", remove, owner).body(), "photoFileId")).isNull();
    }

    // ---------- notifications ----------

    @Test
    void messagesGoToTheCustomersDevicesWhenDue() {
        assertThat(post("/me/push-subscriptions", subscription("https://evil.example/x"), customer).status())
                .isEqualTo(400);
        assertThat(post("/me/push-subscriptions", subscription(FCM), customer).status()).isEqualTo(204);
        UUID b = id(book(customer, at("12:00"), List.of(haircut), UUID.randomUUID()));
        // Someone with no device
        id(book(customer2, at("12:00"), List.of(haircut), UUID.randomUUID()));

        // The two confirmations and the owner's two "new booking" notices; the reminders aren't due yet
        assertThat(sender.runOnce()).isEqualTo(4);
        assertThat(PUSHED).hasSize(1);
        assertThat(PUSHED.get(0)).contains("\"type\":\"BOOKING_CONFIRMED\"").contains("/bookings/" + b);
        assertThat(jdbc.queryForList("SELECT status FROM notification_jobs WHERE type = 'BOOKING_CONFIRMED' "
                + "ORDER BY status", String.class)).containsExactly("NO_DEVICE", "SENT");

        CLOCK.set(at("11:00"));
        pushResult = PushSender.Result.FAILED;
        sender.runOnce();
        assertThat(jdbc.queryForObject("SELECT status || ':' || attempts FROM notification_jobs WHERE ref_id = ? "
                + "AND type = 'BOOKING_REMINDER'", String.class, b)).isEqualTo("PENDING:1");
        CLOCK.advance(Duration.ofMinutes(1));
        pushResult = PushSender.Result.GONE;   // the device unsubscribed meanwhile
        sender.runOnce();
        assertThat(count("push_subscriptions")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM notification_jobs WHERE ref_id = ? "
                + "AND type = 'BOOKING_REMINDER'", String.class, b)).isEqualTo("NO_DEVICE");
    }

    @Test
    void theInAppListShowsEachAppItsOwnMessagesNewestFirstAndMarksThemRead() {
        UUID b = id(book(customer, at("12:00"), List.of(haircut), UUID.randomUUID()));
        // Not sent yet (the sender hasn't run), but already due: cancelling later must not take it off the list
        CLOCK.advance(Duration.ofMinutes(1));
        assertThat(post("/bookings/" + b + "/cancel", null, customer).status()).isEqualTo(200);

        // The customer app: confirmation and cancellation, newest first; the reminder was cancelled, not shown
        Reply list = get("/me/notifications", customer);
        assertThat(list.status()).as(list.text()).isEqualTo(200);
        List<?> items = (List<?>) field(list.body(), "items");
        assertThat(items).extracting(i -> field(i, "type")).containsExactly("BOOKING_CANCELLED", "BOOKING_CONFIRMED");
        assertThat(field(items.get(1), "url")).isEqualTo("/bookings/" + b);
        assertThat(field(list.body(), "unread")).isEqualTo(2);
        assertThat(field(list.body(), "next")).isNull();

        // The owner's salon messages show only in the partner app, never in their customer list
        assertThat((List<?>) field(get("/me/notifications", owner).body(), "items")).isEmpty();
        assertThat((List<?>) field(get("/me/notifications?app=partner", owner).body(), "items"))
                .extracting(i -> field(i, "type")).containsExactly("SALON_BOOKING_CANCELLED", "SALON_NEW_BOOKING");
        assertThat(get("/me/notifications?app=kirana", owner).status()).isEqualTo(400);

        // Marking one read, then all; someone else's id changes nothing
        String first = (String) field(items.get(0), "id");
        assertThat(post("/me/notifications/read", Map.of("ids", List.of(first)), customer2).status()).isEqualTo(204);
        assertThat(field(get("/me/notifications/unread-count", customer).body(), "unread")).isEqualTo(2);
        assertThat(post("/me/notifications/read", Map.of("ids", List.of(first)), customer).status()).isEqualTo(204);
        assertThat(field(get("/me/notifications/unread-count", customer).body(), "unread")).isEqualTo(1);
        assertThat(post("/me/notifications/read", Map.of(), customer).status()).isEqualTo(204);
        assertThat(field(get("/me/notifications/unread-count", customer).body(), "unread")).isEqualTo(0);
        assertThat(field(get("/me/notifications/unread-count?app=partner", owner).body(), "unread")).isEqualTo(2);
        assertThat(get("/me/notifications?cursor=nonsense", customer).status()).isEqualTo(400);
        java.util.List<Object> withEmpty = new java.util.ArrayList<>();
        withEmpty.add(null);
        assertThat(post("/me/notifications/read", Map.of("ids", withEmpty), customer).status()).isEqualTo(400);
    }

    @Test
    void deletingAnAccountBlanksTheSalonsMessagesThatNameThem() {
        patch("/auth/me", Map.of("name", "Ravi Kumar"), customer);
        id(book(customer, at("12:00"), List.of(haircut), UUID.randomUUID()));
        assertThat(get("/me/notifications?app=partner", owner).text()).contains("Ravi");
        post("/auth/me/delete-code", Map.of(), customer);
        String code = in.apnidukan.support.CapturingSenders.codeSentTo(
                in.apnidukan.support.CapturingSenders.LAST_SMS, "9000000101");
        assertThat(post("/auth/me/delete", Map.of("code", code), customer).status()).isEqualTo(204);
        Reply list = get("/me/notifications?app=partner", owner);
        assertThat(list.text()).doesNotContain("Ravi");
        // The salon still learns the time is free, from the notice that names nobody
        assertThat((List<?>) field(list.body(), "items")).extracting(i -> field(i, "type"))
                .containsExactly("SALON_BOOKING_CANCELLED");
        // A blanked message that was still waiting is never sent empty
        sender.runOnce();
        assertThat(jdbc.queryForList("SELECT status FROM notification_jobs WHERE title = '' AND status <> 'CANCELLED'",
                String.class)).isEmpty();
    }

    @Test
    void theInAppListPagesWithoutSkippingMessagesSentAtTheSameMoment() {
        Instant now = CLOCK.instant();
        for (int i = 0; i < 35; i++) {
            jdbc.update("INSERT INTO notification_jobs (id, user_id, channel, type, title, body, send_at, status, "
                    + "attempts, next_try_at, created_at) VALUES (?, ?, 'PUSH', 'BOOKING_REMINDER', 'Soon', ?, ?, "
                    + "'SENT', 1, ?, ?)", UUID.randomUUID(), customer, "message " + i,
                    java.sql.Timestamp.from(now), java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
        }
        Reply one = get("/me/notifications", customer);
        String next = (String) field(one.body(), "next");
        assertThat((List<?>) field(one.body(), "items")).hasSize(30);
        assertThat(next).isNotNull();
        Reply two = get("/me/notifications?cursor=" + next, customer);   // only letters, digits, - : . _
        assertThat(two.status()).as(two.text()).isEqualTo(200);
        assertThat((List<?>) field(two.body(), "items")).hasSize(5);
        assertThat(field(two.body(), "next")).isNull();
        java.util.Set<Object> seen = new java.util.HashSet<>();
        ((List<?>) field(one.body(), "items")).forEach(i -> seen.add(field(i, "id")));
        ((List<?>) field(two.body(), "items")).forEach(i -> seen.add(field(i, "id")));
        assertThat(seen).hasSize(35);
    }

    @Test
    void aMessageThatKeepsFailingIsGivenUpAfterFourTriesAndALeasedOneIsntTakenTwice() {
        post("/me/push-subscriptions", subscription(FCM), customer);
        UUID b = id(book(customer, at("11:15"), List.of(haircut), UUID.randomUUID()));   // confirmation only
        pushResult = PushSender.Result.FAILED;
        assertThat(sender.runOnce()).isEqualTo(2);   // and the owner's notice, who has no device (done)
        assertThat(sender.runOnce()).isZero();   // waiting for its retry: not taken again
        for (int minutes : new int[] {1, 5, 15}) {
            CLOCK.advance(Duration.ofMinutes(minutes));
            assertThat(sender.runOnce()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT status || ':' || attempts FROM notification_jobs WHERE ref_id = ?",
                String.class, b)).isEqualTo("FAILED:4");
        assertThat(PUSHED).hasSize(4);
    }

    @Test
    void aMessageTypeWithAnSmsTemplateAlsoGoesOutAsSms() {
        UUID b = id(book(customer, at("12:00"), List.of(haircut), UUID.randomUUID()));
        // No template for confirmations here: push only
        assertThat(count("notification_jobs WHERE channel = 'SMS'")).isZero();
        post("/salon/appointments/" + b + "/cancel", Map.of("reason", "Power cut today"), owner);
        assertThat(jdbc.queryForList("SELECT channel || ':' || type FROM notification_jobs WHERE status = 'PENDING' "
                + "AND ref_id IS NOT NULL ORDER BY channel", String.class)).containsExactly("PUSH:BOOKING_CANCELLED_BY_SALON",
                "SMS:BOOKING_CANCELLED_BY_SALON");
        sender.runOnce();
        assertThat(TEXTED).containsExactly("9000000101 tpl-salon-cancel {REASON=Power cut today, SALON=Test Shop, "
                + "WHEN=Tue 10 Nov, 12:00 PM}");
        assertThat(jdbc.queryForObject("SELECT status FROM notification_jobs WHERE channel = 'SMS'", String.class))
                .isEqualTo("SENT");
    }

    @Test
    void aMessageWhoseLeaseRanOutAndWasTakenByAnotherServerIsNotSentByTheFirst() {
        UUID b = id(book(customer, at("11:15"), List.of(haircut), UUID.randomUUID()));
        Instant t0 = CLOCK.instant();
        List<NotificationJob> mine = store.claimDue(t0, 10, Duration.ofMinutes(5), 8).stream()
                .filter(j -> j.type().equals("BOOKING_CONFIRMED")).toList();   // not the owner's notice
        assertThat(mine).hasSize(1);
        Instant myLease = t0.plus(Duration.ofMinutes(5));
        // This server stalls; after its lease another server claims the same message
        Instant later = t0.plus(Duration.ofMinutes(6));
        assertThat(store.claimDue(later, 10, Duration.ofMinutes(5), 8))
                .anyMatch(j -> j.id().equals(mine.get(0).id()));
        assertThat(store.renew(mine.get(0).id(), myLease, later.plus(Duration.ofMinutes(5)))).isFalse();
        store.finish(mine.get(0).id(), in.apnidukan.notifications.domain.JobStatus.SENT, later, myLease);
        assertThat(jdbc.queryForObject("SELECT status FROM notification_jobs WHERE ref_id = ?", String.class, b))
                .isEqualTo("PENDING");   // the first server's late result doesn't count
    }

    @Test
    void aReminderBeingSentWhenTheSalonMovesGoesOutOnceAndIsntQueuedAgain() {
        UUID b = id(book(customer, at("12:00"), List.of(haircut), UUID.randomUUID()));
        CLOCK.set(at("11:00"));
        Instant now = CLOCK.instant();
        List<NotificationJob> claimed = store.claimDue(now, 10, Duration.ofMinutes(5), 8);
        NotificationJob reminder = claimed.stream().filter(j -> j.type().equals("BOOKING_REMINDER")).findFirst()
                .orElseThrow();
        // While this server is sending it, the owner saves a new address
        long version = ((Number) field(get("/shops/me", owner).body(), "version")).longValue();
        assertThat(patch("/shops/me", Map.of("version", version, "addressLine", "Shop 9, New Market Road"), owner)
                .status()).isEqualTo(200);
        // The send finishes with the lease it claimed: still valid, so the reminder counts as sent, once (its row
        // already holds the new text; the copy being sent goes out with the old one)
        store.finish(reminder.id(), in.apnidukan.notifications.domain.JobStatus.SENT, now, now.plus(Duration.ofMinutes(5)));
        assertThat(jdbc.queryForList("SELECT status || ':' || (body LIKE '%New Market Road%') FROM notification_jobs "
                + "WHERE ref_id = ? AND type = 'BOOKING_REMINDER'", String.class, b)).containsExactly("SENT:true");
    }

    @Test
    void aReminderKeepsTheStartCodeAndHeldUntilWhenALongAddressMakesItTooLong() {
        jdbc.update("UPDATE shops SET address_line = ?, area = ? WHERE id = ?", "A".repeat(200), "B".repeat(100), salon);
        Reply booked = book(customer, at("12:00"), List.of(haircut), UUID.randomUUID());
        String body = jdbc.queryForObject("SELECT body FROM notification_jobs WHERE ref_id = ? "
                + "AND type = 'BOOKING_REMINDER'", String.class, id(booked));
        assertThat(body).hasSizeLessThanOrEqualTo(500)
                .contains("Start code " + field(booked.body(), "startCode"))
                .contains("Held until 12:15 PM");
    }

    @Test
    void aMessageClaimedTooOftenWithoutAnOutcomeIsGivenUp() {
        UUID b = id(book(customer, at("11:15"), List.of(haircut), UUID.randomUUID()));
        jdbc.update("UPDATE notification_jobs SET attempts = 8");   // the owner's notice too
        assertThat(sender.runOnce()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM notification_jobs WHERE ref_id = ?", String.class, b))
                .isEqualTo("FAILED");
    }

    @Test
    void bookingSmsStopsAtTheDailyLimitPerPerson() {
        UUID b = id(book(customer, at("12:00"), List.of(haircut), UUID.randomUUID()));
        redis.opsForValue().set("rl:sms-booking-person:" + customer, "10");   // 10 already today
        post("/salon/appointments/" + b + "/cancel", Map.of("reason", "Power cut today"), owner);
        sender.runOnce();
        assertThat(TEXTED).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM notification_jobs WHERE channel = 'SMS'", String.class))
                .isEqualTo("FAILED");
    }

    @Test
    void bookingSmsStopsAtTheDailyLimitForEveryone() {
        UUID b = id(book(customer, at("12:00"), List.of(haircut), UUID.randomUUID()));
        redis.opsForValue().set("rl:sms-booking-day", "300");   // the day's total is used up
        post("/salon/appointments/" + b + "/cancel", Map.of("reason", "Power cut today"), owner);
        sender.runOnce();
        assertThat(TEXTED).isEmpty();
        assertThat(redis.opsForValue().get("rl:sms-booking-person:" + customer)).isNull();   // nothing counted
    }

    @Test
    void customersSetTheirNameAndTheSalonSeesIt() {
        Reply named = patch("/auth/me", Map.of("name", "  Ravi   Kumar "), customer);
        assertThat(field(named.body(), "name")).as(named.text()).isEqualTo("Ravi Kumar");
        book(customer, at("12:00"), List.of(haircut), UUID.randomUUID());
        assertThat(get("/salon/day", owner).text()).contains("\"customerName\":\"Ravi Kumar\"");
    }

    @Test
    void theOwnerSeesTheCustomersPhoneToCallButABarberNeverDoes() {
        book(customer, at("12:00"), List.of(haircut, beard), UUID.randomUUID());   // only Raju does both
        assertThat(get("/salon/day", owner).text()).contains("\"customerPhone\":\"9000000101\"");
        put("/salon/staff/" + raju + "/login", Map.of("phone", "9000000201"), owner);
        UUID rajuUser = jdbc.queryForObject("SELECT id FROM users WHERE phone = '9000000201'", UUID.class);
        put("/salon/members/" + rajuUser + "/walk-in-access", Map.of("allowed", true), owner);
        Reply hisDay = get("/salon/day", rajuUser);
        assertThat(hisDay.text()).contains("\"customerName\":\"Test person\"").doesNotContain("9000000101");
    }

    @Test
    void theSalonIsToldWhenACustomerBooksMovesOrCancels() {
        put("/salon/staff/" + raju + "/login", Map.of("phone", "9000000201"), owner);
        UUID rajuUser = jdbc.queryForObject("SELECT id FROM users WHERE phone = '9000000201'", UUID.class);
        patch("/auth/me", Map.of("name", "Ravi Kumar"), customer);
        UUID b = id(book(customer, at("12:00"), List.of(haircut, beard), UUID.randomUUID()));   // only Raju
        assertThat(salonNotices()).containsExactlyInAnyOrder(
                owner + " SALON_NEW_BOOKING Ravi Kumar booked Haircut + Beard trim with Raju, Tue 10 Nov, 12:00 PM.",
                rajuUser + " SALON_NEW_BOOKING Ravi Kumar booked Haircut + Beard trim with Raju, Tue 10 Nov, 12:00 PM.");

        jdbc.update("DELETE FROM notification_jobs WHERE ref_id IS NULL");
        post("/bookings/" + b + "/reschedule", Map.of("startAt", at("14:00").toString()), customer);
        assertThat(salonNotices()).hasSize(2).allMatch(n -> n.contains("SALON_BOOKING_MOVED Ravi Kumar moved booking")
                && n.contains("from Tue 10 Nov, 12:00 PM to Tue 10 Nov, 2:00 PM, with Raju."));

        jdbc.update("DELETE FROM notification_jobs WHERE ref_id IS NULL");
        post("/bookings/" + b + "/cancel", Map.of(), customer);
        assertThat(salonNotices()).hasSize(2).allMatch(n -> n.contains("SALON_BOOKING_CANCELLED Ravi Kumar cancelled "
                + "Tue 10 Nov, 2:00 PM with Raju."));
        // A later change never cancels what the salon was already told
        assertThat(count("notification_jobs WHERE ref_id IS NULL AND status = 'CANCELLED'")).isZero();
    }

    @Test
    void theUpcomingListShowsWhatsComingAcrossDaysSoonestFirst() {
        UUID tomorrow = id(book(customer, at("12:00").plus(Duration.ofDays(1)), List.of(haircut, beard),
                UUID.randomUUID()));   // Raju
        UUID today = id(book(customer2, at("13:00"), List.of(facial), UUID.randomUUID()));   // Sunil
        UUID later = id(book(customer2, at("15:00"), List.of(haircut, beard), UUID.randomUUID()));   // Raju
        post("/salon/staff/" + raju + "/blocks", block("BREAK", "16:00", "16:30"), owner);   // not listed
        id(walkIn(owner, List.of(haircut), sunil, UUID.randomUUID()));   // in service now: not listed

        List<?> all = (List<?>) get("/salon/upcoming", owner).body();
        assertThat(all.stream().map(x -> field(x, "id"))).containsExactly(today.toString(), later.toString(),
                tomorrow.toString());
        // Still listed while the customer may arrive late, gone once marked
        CLOCK.set(at("13:10"));
        assertThat(get("/salon/upcoming", owner).text()).contains(today.toString());

        put("/salon/staff/" + raju + "/login", Map.of("phone", "9000000201"), owner);
        UUID rajuUser = jdbc.queryForObject("SELECT id FROM users WHERE phone = '9000000201'", UUID.class);
        List<?> his = (List<?>) get("/salon/upcoming", rajuUser).body();
        assertThat(his.stream().map(x -> field(x, "id"))).containsExactly(later.toString(), tomorrow.toString());
    }

    @Test
    void aNoShowIsUndoneByTheOwnerTheStrikeGoesAndTheBookingIsClosed() {
        UUID b = id(book(customer, at("12:00"), List.of(haircut, beard), UUID.randomUUID()));   // Raju, to 12:45
        CLOCK.set(at("12:20"));
        assertThat(post("/salon/appointments/" + b + "/no-show", Map.of(), owner).status()).isEqualTo(200);
        assertThat(count("customer_strikes")).isEqualTo(1);
        put("/salon/staff/" + raju + "/login", Map.of("phone", "9000000201"), owner);
        UUID rajuUser = jdbc.queryForObject("SELECT id FROM users WHERE phone = '9000000201'", UUID.class);
        assertThat(post("/salon/appointments/" + b + "/undo-no-show", Map.of(), rajuUser).status()).isEqualTo(403);

        Reply undone = post("/salon/appointments/" + b + "/undo-no-show", Map.of(), owner);
        assertThat(undone.status()).as(undone.text()).isEqualTo(200);
        assertThat(field(undone.body(), "status")).isEqualTo("CANCELLED_BY_SALON");
        assertThat(field(undone.body(), "cancelReason")).isEqualTo("No-show undone");
        assertThat(count("customer_strikes")).isZero();
        assertThat(count("appointment_events WHERE event = 'NO_SHOW_UNDONE' AND detail = 'strike removed'"))
                .isEqualTo(1);
        assertThat(jobs(b)).noneMatch(j -> j.startsWith("BOOKING_CANCELLED_BY_SALON"));   // the customer isn't told
        assertThat(post("/salon/appointments/" + b + "/undo-no-show", Map.of(), owner).text())
                .contains("WRONG_STATUS");
    }

    @Test
    void afterADaySupportTakesBackTheStrikeInsteadOfTheSalon() {
        UUID b = id(book(customer, at("12:00"), List.of(haircut), UUID.randomUUID()));
        CLOCK.set(at("12:20"));
        post("/salon/appointments/" + b + "/no-show", Map.of(), owner);
        CLOCK.advance(Duration.ofHours(24));
        assertThat(post("/salon/appointments/" + b + "/undo-no-show", Map.of(), owner).text())
                .contains("WRONG_STATUS").contains("support");

        UUID admin = person("9000000999");
        jdbc.update("UPDATE users SET admin = TRUE WHERE id = ?", admin);
        assertThat(post("/admin/salon/strikes/lookup", Map.of("phone", "9000000101"), owner).status()).isEqualTo(403);
        Reply list = post("/admin/salon/strikes/lookup", Map.of("phone", "9000000101"), admin);
        assertThat(list.status()).as(list.text()).isEqualTo(200);
        assertThat(field(list.body(), "blockedUntil")).isNull();
        List<?> strikes = (List<?>) field(list.body(), "strikes");
        assertThat(strikes).hasSize(1);
        assertThat(field(strikes.get(0), "kind")).isEqualTo("NO_SHOW");
        assertThat(field(strikes.get(0), "salonName")).isEqualTo("Test Shop");

        assertThat(post("/admin/salon/strikes/" + b + "/remove", Map.of("reason", "Barber pressed it by mistake"),
                admin).status()).isEqualTo(204);
        assertThat(count("customer_strikes")).isZero();
        assertThat(count("appointment_events WHERE event = 'STRIKE_REMOVED'")).isEqualTo(1);
        assertThat(field(post("/admin/salon/strikes/" + b + "/remove", Map.of("reason", "again"), admin).body(),
                "code")).isEqualTo("STRIKE_NOT_FOUND");
        assertThat(field(post("/admin/salon/strikes/lookup", Map.of("phone", "9000000555"), admin).body(), "code"))
                .isEqualTo("CUSTOMER_NOT_FOUND");
    }

    @Test
    void aSuspendedSalonDisappearsItsUpcomingBookingsAreCancelledAndItCanComeBack() {
        UUID admin = person("9000000999");
        jdbc.update("UPDATE users SET admin = TRUE WHERE id = ?", admin);
        UUID b = id(book(customer, at("12:00"), List.of(haircut), UUID.randomUUID()));
        assertThat(field(post("/admin/shops/" + salon + "/reject", Map.of("version", shopVersion(), "reason",
                "Photo is blurry"), admin).body(), "code")).isEqualTo("WRONG_STATUS");   // only before approval
        assertThat(post("/admin/shops/" + salon + "/suspend", Map.of("version", shopVersion() - 1, "reason",
                "Closed for months"), admin).text()).contains("SHOP_CHANGED");

        Reply suspended = post("/admin/shops/" + salon + "/suspend", Map.of("version", shopVersion(), "reason",
                "Customers report it closed for months."), admin);
        assertThat(suspended.status()).as(suspended.text()).isEqualTo(200);
        assertThat(field(suspended.body(), "status")).isEqualTo("SUSPENDED");
        assertThat(status(b)).isEqualTo("CANCELLED_BY_SALON");
        String toCustomer = jdbc.queryForObject("SELECT body FROM notification_jobs WHERE ref_id = ? "
                + "AND type = 'BOOKING_CANCELLED_BY_SALON' AND channel = 'PUSH'", String.class, b);
        assertThat(toCustomer).contains("isn't taking online bookings").doesNotContain("months");
        assertThat(field(get("/shops/me", owner).body(), "statusReason"))
                .isEqualTo("Customers report it closed for months.");
        assertThat(count("notification_jobs WHERE type = 'SHOP_SUSPENDED' AND user_id = '" + owner + "'"))
                .isEqualTo(1);
        assertThat((List<?>) get("/salons?lat=17.466&lng=78.365", customer).body()).isEmpty();
        assertThat(book(customer, at("13:00"), List.of(haircut), UUID.randomUUID()).status()).isEqualTo(404);
        // The salon still runs its own day (walk-ins)
        assertThat(walkIn(owner, List.of(haircut), raju, UUID.randomUUID()).status()).isEqualTo(201);

        // Ending the suspension puts it back in the visit list: live only after a new check
        Reply back = post("/admin/shops/" + salon + "/reinstate", Map.of("version", shopVersion()), admin);
        assertThat(field(back.body(), "status")).as(back.text()).isEqualTo("PENDING_VISIT");
        assertThat(field(back.body(), "statusReason")).isNull();
        assertThat((List<?>) get("/salons?lat=17.466&lng=78.365", customer).body()).isEmpty();
        assertThat(approve(salon, admin).status()).isEqualTo(200);
        assertThat((List<?>) get("/salons?lat=17.466&lng=78.365", customer).body()).hasSize(1);
        assertThat(status(b)).isEqualTo("CANCELLED_BY_SALON");   // cancelled bookings stay cancelled
    }

    @Test
    void aShopWhoseVisitDidntPassGoesBackToDraftWithTheReason() {
        UUID admin = person("9000000999");
        jdbc.update("UPDATE users SET admin = TRUE WHERE id = ?", admin);
        jdbc.update("UPDATE shops SET status = 'PENDING_VISIT' WHERE id = ?", salon);
        assertThat(post("/admin/shops/" + salon + "/reject", Map.of("version", shopVersion(), "reason", "x"), admin)
                .status()).isEqualTo(400);
        Reply sentBack = post("/admin/shops/" + salon + "/reject", Map.of("version", shopVersion(), "reason",
                "The shop photo doesn't show the name board."), admin);
        assertThat(field(sentBack.body(), "status")).as(sentBack.text()).isEqualTo("DRAFT");
        Reply mine = get("/shops/me", owner);
        assertThat(field(mine.body(), "statusReason")).isEqualTo("The shop photo doesn't show the name board.");
        assertThat(jdbc.queryForObject("SELECT body FROM notification_jobs WHERE type = 'SHOP_NOT_APPROVED'",
                String.class)).contains("name board").contains("submit again");
    }

    @Test
    void aCustomerWhoDeletesTheirAccountLosesUpcomingBookingsTheSalonIsToldAndRecordsStay() {
        patch("/auth/me", Map.of("name", "Ravi Kumar"), customer);
        post("/me/push-subscriptions", subscription(FCM), customer);
        UUID b = id(book(customer, at("12:00"), List.of(haircut, beard), UUID.randomUUID()));   // Raju
        String oldToken = token(customer);

        assertThat(field(post("/auth/me/delete-code", Map.of(), owner).body(), "code")).isEqualTo("SHOP_OWNER");
        assertThat(post("/auth/me/delete-code", Map.of(), customer).status()).isEqualTo(202);
        String code = in.apnidukan.support.CapturingSenders.codeSentTo(
                in.apnidukan.support.CapturingSenders.LAST_SMS, "9000000101");
        Reply wrong = post("/auth/me/delete", Map.of("code", code.equals("000000") ? "111111" : "000000"), customer);
        assertThat(wrong.status()).as(wrong.text()).isEqualTo(400);
        assertThat(count("users WHERE deleted_at IS NOT NULL")).isZero();
        assertThat(post("/auth/me/delete", Map.of("code", code), customer).status()).isEqualTo(204);

        // Who it was is gone; the row and its history stay
        assertThat(jdbc.queryForObject("SELECT (phone IS NULL) AND (name IS NULL) AND (deleted_at IS NOT NULL) "
                + "FROM users WHERE id = ?", Boolean.class, customer)).isTrue();
        assertThat(status(b)).isEqualTo("CANCELLED_BY_CUSTOMER");
        assertThat(count("customer_strikes")).isZero();
        assertThat(count("push_subscriptions")).isZero();
        assertThat(count("notification_jobs WHERE status = 'PENDING' AND user_id = '" + customer + "'")).isZero();
        assertThat(jdbc.queryForObject("SELECT body FROM notification_jobs WHERE type = 'SALON_BOOKING_CANCELLED' "
                + "AND user_id = ?", String.class, owner)).startsWith("A customer cancelled")
                .contains("deleted their account").doesNotContain("Ravi");
        // Their own messages keep no text
        assertThat(count("notification_jobs WHERE user_id = '" + customer + "' AND body <> ''")).isZero();
        // A token from before the deletion can't book, even if the sign-out check let it through (Redis down)
        assertThat(field(book(customer, at("15:00"), List.of(haircut), UUID.randomUUID()).body(), "code"))
                .isEqualTo("NOT_SIGNED_IN");
        String day = get("/salon/day", owner).text();
        assertThat(day).contains(b.toString()).contains("\"customerName\":\"Customer\"").doesNotContain("9000000101");
        // Sessions in use stop at once
        Integer status = http.get().uri("/salon/upcoming").headers(h -> h.setBearerAuth(oldToken))
                .exchange((rq, res) -> Integer.valueOf(res.getStatusCode().value()));
        assertThat(status).isEqualTo(401);
        // The phone is free for a brand-new account
        put("/salon/staff/" + raju + "/login", Map.of("phone", "9000000101"), owner);
        assertThat(jdbc.queryForObject("SELECT id FROM users WHERE phone = '9000000101'", UUID.class))
                .isNotEqualTo(customer);
    }

    @Test
    void aBarberWhoDeletesHisAccountLeavesTheSalonButHisNameAndHistoryStay() {
        put("/salon/staff/" + raju + "/login", Map.of("phone", "9000000201"), owner);
        UUID rajuUser = jdbc.queryForObject("SELECT id FROM users WHERE phone = '9000000201'", UUID.class);
        put("/salon/members/" + rajuUser + "/walk-in-access", Map.of("allowed", true), owner);
        assertThat(post("/auth/me/delete-code", Map.of(), rajuUser).status()).isEqualTo(202);
        String code = in.apnidukan.support.CapturingSenders.codeSentTo(
                in.apnidukan.support.CapturingSenders.LAST_SMS, "9000000201");
        assertThat(post("/auth/me/delete", Map.of("code", code), rajuUser).status()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT user_id FROM salon_staff WHERE id = ?", UUID.class, raju)).isNull();
        assertThat(jdbc.queryForObject("SELECT name FROM salon_staff WHERE id = ?", String.class, raju))
                .isEqualTo("Raju");
        assertThat(count("shop_members WHERE user_id = '" + rajuUser + "'")).isZero();
        assertThat(count("walk_in_access")).isZero();
    }

    @Test
    void bookingNeedsAVerifiedEmailAndANameFirst() {
        UUID fresh = UUID.randomUUID();
        TestData.bareUser(jdbc, fresh, "9000000150");   // signed in with the phone only
        assertThat((List<?>) get("/salons?lat=17.466&lng=78.365", fresh).body()).hasSize(1);   // browsing is fine
        Reply r = book(fresh, at("12:00"), List.of(haircut), UUID.randomUUID());
        assertThat(r.status()).isEqualTo(403);
        assertThat(field(r.body(), "code")).isEqualTo("PROFILE_INCOMPLETE");
        assertThat(field(field(r.body(), "details"), "missing")).isEqualTo(List.of("email", "name"));
        jdbc.update("UPDATE users SET email = 'fresh@test.in', email_verified_at = now() WHERE id = ?", fresh);
        assertThat(field(field(book(fresh, at("12:00"), List.of(haircut), UUID.randomUUID()).body(), "details"),
                "missing")).isEqualTo(List.of("name"));
        patch("/auth/me", Map.of("name", "Meera"), fresh);
        assertThat(book(fresh, at("12:00"), List.of(haircut), UUID.randomUUID()).status()).isEqualTo(201);
    }

    @Test
    void theOwnerAddsAHelperWhoWorksTheSalonAndCanBeRemovedOrLeave() {
        Reply added = post("/shops/me/helpers", Map.of("phone", "9000000301"), owner);
        assertThat(added.status()).as(added.text()).isEqualTo(200);
        UUID helper = jdbc.queryForObject("SELECT id FROM users WHERE phone = '9000000301'", UUID.class);
        assertThat(jdbc.queryForObject("SELECT role FROM shop_members WHERE user_id = ?", String.class, helper))
                .isEqualTo("HELPER");
        assertThat(field(post("/shops/me/helpers", Map.of("phone", "9000000302"), helper).body(), "code"))
                .isEqualTo("OWNER_ONLY");
        assertThat(field(post("/shops/me/helpers", Map.of("phone", "9000000301"), owner).body(), "code"))
                .isEqualTo("ALREADY_IN_A_SHOP");
        assertThat(get("/shops/me/members", owner).text()).contains("9000000301").contains("HELPER");

        // A helper works the whole day: starts a booking without the customer's code
        UUID b = id(book(customer, at("12:00"), List.of(haircut), UUID.randomUUID()));
        put("/salon/members/" + helper + "/walk-in-access", Map.of("allowed", true), owner);
        CLOCK.set(at("11:50"));
        assertThat(start(b, Map.of("withoutCode", true), helper).status()).isEqualTo(200);

        // Removed: membership and walk-in access go, and his session stops at once
        String oldToken = token(helper);
        Reply removed = delete("/shops/me/helpers/" + helper, owner);
        assertThat(removed.status()).as(removed.text()).isEqualTo(200);
        assertThat(count("shop_members WHERE user_id = '" + helper + "'")).isZero();
        assertThat(count("walk_in_access")).isZero();
        Integer status = http.get().uri("/salon/day").headers(h -> h.setBearerAuth(oldToken))
                .exchange((rq, res) -> Integer.valueOf(res.getStatusCode().value()));
        assertThat(status).isEqualTo(401);
        assertThat(field(delete("/shops/me/helpers/" + helper, owner).body(), "code")).isEqualTo("HELPER_NOT_FOUND");
    }

    @Test
    void aBarberAddedWithoutAskingCanLeaveAndHisProfileStays() {
        put("/salon/staff/" + raju + "/login", Map.of("phone", "9000000201"), owner);
        UUID rajuUser = jdbc.queryForObject("SELECT id FROM users WHERE phone = '9000000201'", UUID.class);
        assertThat(field(post("/shops/me/leave", Map.of(), owner).body(), "code")).isEqualTo("OWNER_CANT_LEAVE");
        assertThat(post("/shops/me/leave", Map.of(), rajuUser).status()).isEqualTo(204);
        assertThat(count("shop_members WHERE user_id = '" + rajuUser + "'")).isZero();
        assertThat(jdbc.queryForObject("SELECT user_id FROM salon_staff WHERE id = ?", UUID.class, raju)).isNull();
        assertThat(jdbc.queryForObject("SELECT name FROM salon_staff WHERE id = ?", String.class, raju))
                .isEqualTo("Raju");
    }

    @Test
    void aBookingCantBeMovedInItsLastHourOnlyCancelled() {
        UUID b = id(book(customer, at("12:00"), List.of(haircut), UUID.randomUUID()));
        CLOCK.set(at("11:01"));
        Reply moved = post("/bookings/" + b + "/reschedule", Map.of("startAt", at("14:00").toString()), customer);
        assertThat(field(moved.body(), "code")).as(moved.text()).isEqualTo("TOO_LATE_TO_MOVE");
        assertThat(field(get("/bookings/" + b, customer).body(), "canMove")).isEqualTo(false);
        assertThat(field(get("/bookings/" + b, customer).body(), "canChange")).isEqualTo(true);
    }

    @Test
    void newHoursOrServicesCantStrandABarbersUpcomingBookings() {
        UUID b = id(book(customer, at("12:00"), List.of(haircut, beard), UUID.randomUUID()));   // Raju
        List<Map<String, Object>> noTuesday = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            if (day != 2) {
                noTuesday.add(Map.of("weekday", day, "start", "10:00", "end", "21:00"));
            }
        }
        Reply hours = put("/salon/staff/" + raju + "/hours", Map.of("version", rajuVersion(), "hours", noTuesday),
                owner);
        assertThat(field(hours.body(), "code")).as(hours.text()).isEqualTo("BOOKINGS_IN_THE_WAY");
        assertThat(hours.text()).contains(b.toString());
        Reply services = put("/salon/staff/" + raju + "/services", Map.of("version", rajuVersion(),
                "serviceIds", List.of(haircut)), owner);
        assertThat(field(services.body(), "code")).as(services.text()).isEqualTo("BOOKINGS_IN_THE_WAY");
        // Hours that still cover it are fine
        List<Map<String, Object>> shorter = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            shorter.add(Map.of("weekday", day, "start", "11:00", "end", "20:00"));
        }
        assertThat(put("/salon/staff/" + raju + "/hours", Map.of("version", rajuVersion(), "hours", shorter), owner)
                .status()).isEqualTo(200);
    }

    @Test
    void finishedMessagesAreDeletedAfter90DaysButWaitingOnesStay() {
        book(customer, at("12:00"), List.of(haircut), UUID.randomUUID());
        jdbc.update("UPDATE notification_jobs SET status = 'SENT', finished_at = ? WHERE type = 'BOOKING_CONFIRMED'",
                java.sql.Timestamp.from(TUESDAY_10_00.minus(Duration.ofDays(91))));
        int before = count("notification_jobs");
        assertThat(messageCleanup.runOnce()).isEqualTo(1);
        assertThat(count("notification_jobs")).isEqualTo(before - 1);
        assertThat(count("notification_jobs WHERE status = 'PENDING'")).isEqualTo(before - 1);
    }

    @Test
    void theOwnersHistoryCountsTheDayAndWhatWasDone() {
        id(walkIn(owner, List.of(haircut), raju, UUID.randomUUID()));   // 10:00-10:30, ₹150
        UUID missed = id(book(customer, at("12:00"), List.of(haircut), UUID.randomUUID()));
        CLOCK.set(at("12:15"));
        autoEnd.runOnce();   // the walk-in completes, the booking becomes a no-show
        Reply h = get("/salon/history?from=2026-11-10&to=2026-11-10", owner);
        assertThat(h.status()).as(h.text()).isEqualTo(200);
        assertThat(field(h.body(), "counts")).isEqualTo(Map.of("COMPLETED", 1, "NO_SHOW", 1));
        assertThat(((Number) field(h.body(), "completedPaise")).longValue()).isEqualTo(15000);
        List<?> list = (List<?>) field(h.body(), "appointments");
        assertThat(field(list.get(0), "id")).isEqualTo(missed.toString());   // latest first
        assertThat(get("/salon/history?from=2026-10-01&to=2026-11-10", owner).status()).isEqualTo(400);
        put("/salon/staff/" + raju + "/login", Map.of("phone", "9000000201"), owner);
        UUID rajuUser = jdbc.queryForObject("SELECT id FROM users WHERE phone = '9000000201'", UUID.class);
        assertThat(get("/salon/history?from=2026-11-10&to=2026-11-10", rajuUser).status()).isEqualTo(403);
    }

    // ---------- helpers ----------

    private long shopVersion() {
        Long v = jdbc.queryForObject("SELECT version FROM shops WHERE id = ?", Long.class, salon);
        return v == null ? 0 : v;
    }

    /** The salon-side messages queued: "user TYPE body", without the booking number at the end. */
    private List<String> salonNotices() {
        return jdbc.queryForList("SELECT user_id || ' ' || type || ' ' || body FROM notification_jobs "
                + "WHERE ref_id IS NULL AND channel = 'PUSH'", String.class).stream()
                .map(s -> s.replaceAll(" Booking [A-Z0-9]+\\..*$", "")).toList();
    }

    private UUID person(String phone) {
        UUID id = UUID.randomUUID();
        TestData.user(jdbc, id, phone);
        return id;
    }

    private UUID barber(String name, List<UUID> services) {
        Reply added = post("/salon/staff", Map.of("name", name), owner);
        UUID id = id(added);
        Reply s = put("/salon/staff/" + id + "/services", Map.of("version", 0, "serviceIds", services), owner);
        List<Map<String, Object>> week = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            week.add(Map.of("weekday", day, "start", "10:00", "end", "21:00"));
        }
        put("/salon/staff/" + id + "/hours", Map.of("version", field(s.body(), "version"), "hours", week), owner);
        return id;
    }

    /** Uploads a small real JPEG (its colour depends on seed, so each one is a different file) as the owner. */
    private Reply upload(UUID who, UUID shopId, String purpose, int seed) {
        byte[] bytes = jpeg(64, 48, new java.awt.Color(seed * 37 % 256, seed * 91 % 256, seed * 53 % 256));
        org.springframework.util.MultiValueMap<String, Object> form = new org.springframework.util.LinkedMultiValueMap<>();
        form.add("purpose", purpose);
        form.add("file", new org.springframework.core.io.ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "photo.jpg";
            }
        });
        return http.post().uri("/files").headers(h -> h.setBearerAuth(shopToken(who, shopId)))
                .contentType(MediaType.MULTIPART_FORM_DATA).body(form)
                .exchange((rq, res) -> read(res));
    }

    private static byte[] jpeg(int width, int height, java.awt.Color colour) {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(width, height,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setColor(colour);
        g.fillRect(0, 0, width, height);
        g.dispose();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            javax.imageio.ImageIO.write(image, "jpeg", out);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private Reply delete(String path, UUID who, UUID shopId) {
        return http.delete().uri(path).headers(h -> h.setBearerAuth(shopToken(who, shopId)))
                .exchange((rq, res) -> read(res));
    }

    /** A token that carries the shop (the files module takes the shop from the token). */
    private String shopToken(UUID who, UUID shopId) {
        return jwt.issue(new AuthPrincipal(who, shopId, "OWNER", false), sessions.currentGeneration(who)).token();
    }

    private Reply book(UUID who, Instant start, List<UUID> services, UUID key) {
        Map<String, Object> body = Map.of("salonId", salon.toString(), "serviceIds", services,
                "startAt", start.toString());
        return call(HttpMethod.POST, "/bookings", body, who, key);
    }

    private Reply walkIn(UUID who, List<UUID> services, UUID staffId, UUID key) {
        Map<String, Object> body = new HashMap<>();
        body.put("serviceIds", services);
        body.put("staffId", staffId.toString());
        return call(HttpMethod.POST, "/salon/walk-ins", body, who, key);
    }

    private Reply start(UUID appointment, Map<String, Object> body, UUID who) {
        return post("/salon/appointments/" + appointment + "/start", body, who);
    }

    private List<String> times(String services) {
        return slots(services, "2026-11-10");
    }

    private List<String> slots(String services, String date) {
        Reply r = get("/salons/" + salon + "/availability?services=" + services + "&date=" + date, customer);
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        return ((List<?>) field(r.body(), "starts")).stream().map(s -> (String) field(s, "time")).toList();
    }

    private static Map<String, Object> block(String type, String from, String to) {
        return Map.of("type", type, "startAt", at(from).toString(), "endAt", at(to).toString());
    }

    private static Map<String, Object> service(String name, int minutes, long paise) {
        return Map.of("name", name, "durationMinutes", minutes, "pricePaise", paise);
    }

    private static Map<String, Object> subscription(String endpoint) {
        return Map.of("endpoint", endpoint, "keys", Map.of("p256dh", P256DH, "auth", DEVICE_A));
    }

    /** A moment on Tuesday 10 Nov 2026, India time. */
    private static Instant at(String hhmm) {
        return java.time.LocalDate.of(2026, 11, 10).atTime(java.time.LocalTime.parse(hhmm))
                .atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
    }

    private List<String> jobs(UUID ref) {
        return jdbc.queryForList("SELECT type || ':' || status FROM notification_jobs WHERE ref_id = ? "
                + "AND channel = 'PUSH' ORDER BY created_at, send_at", String.class, ref);
    }

    private String status(UUID appointment) {
        return jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, appointment);
    }

    private UUID staffOf(UUID appointment) {
        return jdbc.queryForObject("SELECT staff_id FROM appointments WHERE id = ?", UUID.class, appointment);
    }

    /** The field team approves with the version it sees in its list. */
    private Reply approve(UUID shopId, UUID admin) {
        Object version = jdbc.queryForObject("SELECT version FROM shops WHERE id = ?", Long.class, shopId);
        return post("/admin/shops/" + shopId + "/approve", Map.of("version", version), admin);
    }

    private long rajuVersion() {
        return ((Number) field(((List<?>) get("/salon/staff", owner).body()).stream()
                .filter(x -> raju.toString().equals(field(x, "id"))).findFirst().orElseThrow(), "version")).longValue();
    }

    private int count(String tableAndWhere) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + tableAndWhere, Integer.class);
        return n == null ? 0 : n;
    }

    /** Inserts a walk-in row directly (inside a held transaction, to line up a race). */
    private void insertWalkIn(UUID staffId, String time, int minutes) {
        Instant start = at(time);
        jdbc.update("INSERT INTO appointments (id, shop_id, staff_id, kind, starts_at, ends_at, status, created_by, "
                        + "created_at, updated_at) VALUES (?, ?, ?, 'WALK_IN', ?, ?, 'CONFIRMED', ?, ?, ?)",
                UUID.randomUUID(), salon, staffId, java.sql.Timestamp.from(start),
                java.sql.Timestamp.from(start.plus(Duration.ofMinutes(minutes))), owner,
                java.sql.Timestamp.from(TUESDAY_10_00), java.sql.Timestamp.from(TUESDAY_10_00));
    }

    private static Future<Reply> background(java.util.concurrent.Callable<Reply> call) {
        ExecutorService one = Executors.newSingleThreadExecutor();
        Future<Reply> f = one.submit(call);
        one.shutdown();
        return f;
    }

    /** Waits (up to 10 s) until some session is waiting for a lock while running this kind of statement. */
    private void waitUntilBlocked(String statement) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Integer waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = "
                    + "'Lock' AND query ILIKE ?", Integer.class, statement + "%");
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Nothing waited on the held transaction in a " + statement);
    }

    /** Runs SQL in a transaction on its own thread and keeps it open until commit(); close() rolls it back. */
    private final class Held implements AutoCloseable {
        private final CountDownLatch ready = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final ExecutorService thread = Executors.newSingleThreadExecutor();
        private final Future<?> done;

        Held(Runnable sql) throws Exception {
            done = thread.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactions)
                    .executeWithoutResult(status -> {
                        sql.run();
                        ready.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException e) {
                            status.setRollbackOnly();   // closed without commit()
                            Thread.currentThread().interrupt();
                        }
                    }));
            if (!ready.await(10, TimeUnit.SECONDS)) {
                thread.shutdownNow();
                throw new AssertionError("The held transaction never started");
            }
        }

        void commit() throws Exception {
            release.countDown();
            done.get(10, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws InterruptedException {
            thread.shutdownNow();   // interrupts the wait: rolled back unless commit() ran
            thread.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    /** Runs two requests at the same moment and returns both replies. */
    private static List<Reply> race(java.util.function.Supplier<Reply> a, java.util.function.Supplier<Reply> b)
            throws Exception {
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

    /**
     * A fresh access token for this person at the test clock's time, like sign-in makes (the shop in it doesn't
     * matter here: the salon module reads the membership from the database).
     */
    private String token(UUID who) {
        Boolean admin = jdbc.queryForObject("SELECT admin FROM users WHERE id = ?", Boolean.class, who);
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

    private Reply patch(String path, Object body, UUID who) {
        return call(HttpMethod.PATCH, path, body, who, null);
    }

    private Reply delete(String path, UUID who) {
        return call(HttpMethod.DELETE, path, null, who, null);
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
