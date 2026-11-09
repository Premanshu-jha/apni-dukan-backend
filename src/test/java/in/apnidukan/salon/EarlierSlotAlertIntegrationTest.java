package in.apnidukan.salon;

import in.apnidukan.common.security.AuthPrincipal;
import in.apnidukan.common.security.JwtService;
import in.apnidukan.common.security.SessionRevocations;
import in.apnidukan.salon.repo.EarlierSlotAlertStore;
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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Tell me if an earlier slot opens" end to end (EarlierSlotAlerts), against real Postgres and Redis in Docker, and
 * the 10-minute late allowance's rewrite of waiting reminders (V33).
 *
 * The salon: Raju does Haircut (30) and Beard (15); Sunil does Haircut and Facial (45). Both work every day
 * 10:00-21:00; online bookings need 30 minutes' notice. "Now" starts at Tuesday 10 Nov 2026, 10:00 in India. A booking
 * goes to the least busy barber who is free (Raju on a tie: he was added first).
 */
@EnabledIf("in.apnidukan.support.TestContainers#dockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({CapturingSenders.class, EarlierSlotAlertIntegrationTest.Fakes.class})
class EarlierSlotAlertIntegrationTest {

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
        // Batches of one: the automatic-end round's cleanup goes through its batch loop
        registry.add("app.jobs.auto-end-batch", () -> "1");
    }

    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired JwtService jwt;
    @Autowired SessionRevocations sessions;
    @Autowired AutoEndJob autoEnd;
    @Autowired EarlierSlotAlertStore alerts;
    @Autowired in.apnidukan.salon.service.EarlierSlotAlerts earlierSlots;

    RestClient http;
    UUID owner;
    UUID salon;
    UUID raju;
    UUID sunil;
    UUID haircut;
    UUID beard;
    UUID facial;
    int phones;

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
        phones = 0;
        owner = person();
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

    // ---------- offers ----------

    @Test
    void aCancelOffersTheFreedTimeOnlyToLaterBookingsThatAskedAndTheFirstToMoveGetsIt() {
        UUID cancelled = id(book(person(), at("12:00"), haircut, false));   // Raju
        id(book(person(), at("12:00"), haircut, false));                     // Sunil: 12:00 is full
        UUID asked = id(book(person(), at("15:00"), haircut, true));         // Raju (30 each: Raju first)
        UUID notAsked = id(book(person(), at("15:30"), haircut, false));     // Sunil
        UUID asked2 = id(book(person(), at("16:00"), haircut, true));        // Raju
        assertThat(staffOf(cancelled)).isEqualTo(raju);
        assertThat(staffOf(asked2)).isEqualTo(raju);

        assertThat(post("/bookings/" + cancelled + "/cancel", Map.of(), customerOf(cancelled)).status())
                .isEqualTo(200);

        for (UUID b : List.of(asked, asked2)) {
            Map<String, Object> job = offer(b);
            assertThat(job.get("url")).isEqualTo("/bookings/" + b + "?earlier=" + at("12:00"));
            assertThat((String) job.get("title")).isEqualTo("Earlier time at Test Shop");
            assertThat((String) job.get("body")).contains("Tue 10 Nov, 12:00 PM with Raju just opened up");
        }
        assertThat((String) offer(asked).get("body")).contains("You're booked for 3:00 PM");
        assertThat(offers(notAsked)).isZero();
        assertThat(offers(cancelled)).isZero();
        // In the app and by push only: no SMS row
        assertThat(count("notification_jobs WHERE type = 'BOOKING_EARLIER_SLOT' AND channel = 'SMS'")).isZero();
        assertThat(offeredStart(asked)).isEqualTo(at("12:00"));

        // The first to move gets it; the other is told it's taken, with the nearest free times
        Reply first = post("/bookings/" + asked + "/reschedule", Map.of("startAt", at("12:00").toString()),
                customerOf(asked));
        assertThat(first.status()).as(first.text()).isEqualTo(200);
        Reply second = post("/bookings/" + asked2 + "/reschedule", Map.of("startAt", at("12:00").toString()),
                customerOf(asked2));
        assertThat(field(second.body(), "code")).as(second.text()).isEqualTo("SLOT_TAKEN");

        // The move freed 15:00 on Raju; Sunil was free 15:00-15:30 (no news) but not from 15:15. The one who lost the
        // race is offered that, a different time from the one before
        assertThat(offers(asked2)).isEqualTo(2);
        assertThat(offeredStart(asked2)).isEqualTo(at("15:15"));
        // The mover's own alert follows its booking, with nothing offered from its new time
        assertThat(jdbc.queryForObject("SELECT starts_at FROM earlier_slot_alerts WHERE appointment_id = ?",
                java.time.OffsetDateTime.class, asked).toInstant()).isEqualTo(at("12:00"));
        assertThat(offeredStart(asked)).isNull();
        assertThat(offers(notAsked)).isZero();
    }

    @Test
    void aTimeAnotherBarberAlreadyHadFreeIsNoNews() {
        UUID cancelled = id(book(person(), at("12:00"), haircut, false));   // Raju; Sunil stays free at 12:00
        UUID asked = id(book(person(), at("15:00"), haircut, true));
        assertThat(post("/bookings/" + cancelled + "/cancel", Map.of(), customerOf(cancelled)).status())
                .isEqualTo(200);
        assertThat(offers(asked)).isZero();
    }

    @Test
    void bookingsBeforeTheFreedTimeOnOtherDaysOrWithAServiceTheBarberDoesntDoAreNotOffered() {
        UUID cancelled = id(book(person(), at("12:00"), haircut, false));            // Raju
        id(book(person(), at("12:00"), haircut, false));                              // Sunil
        UUID before = id(book(person(), at("11:00"), haircut, true));                // earlier than the freed time
        UUID tomorrow = id(book(person(), at("15:00").plus(Duration.ofDays(1)), haircut, true));
        UUID facialLater = id(book(person(), at("15:00"), facial, true));            // Sunil only: Raju doesn't
        assertThat(post("/bookings/" + cancelled + "/cancel", Map.of(), customerOf(cancelled)).status())
                .isEqualTo(200);
        assertThat(offers(before)).isZero();
        assertThat(offers(tomorrow)).isZero();
        assertThat(offers(facialLater)).isZero();
    }

    @Test
    void aBookingLessThanAnHourAwayCantBeMovedSoIsNotOffered() {
        UUID cancelled = id(book(person(), at("12:00"), beard, false));   // Raju 12:00-12:15 (only he does beards)
        UUID soon = id(book(person(), at("12:15"), beard, true));         // Raju 12:15
        CLOCK.set(at("11:20"));   // 12:15 is 55 minutes away; 12:00 is still bookable (30 minutes' notice)
        assertThat(post("/bookings/" + cancelled + "/cancel", Map.of(), customerOf(cancelled)).status())
                .isEqualTo(200);
        assertThat(offers(soon)).isZero();
    }

    @Test
    void anOfferNeverClashesWithTheCustomersOtherBooking() {
        UUID asif = barber("Asif", List.of(beard));   // beards only: he never makes a haircut time old news
        UUID cancelled = id(book(person(), at("12:00"), haircut, false));   // Raju 12:00-12:30
        id(book(person(), at("12:00"), haircut, false));                     // Sunil
        UUID busyCustomer = person();
        UUID asked = id(book(busyCustomer, at("15:00"), haircut, true));    // Raju
        // Their other booking: a beard trim 12:00-12:15, with Asif (Raju is taken then)
        UUID other = id(book(busyCustomer, at("12:00"), beard, false));
        assertThat(staffOf(other)).isEqualTo(asif);
        assertThat(post("/bookings/" + cancelled + "/cancel", Map.of(), customerOf(cancelled)).status())
                .isEqualTo(200);
        // 12:00 would put them in two chairs at once; 12:15-12:45 is after the beard trim
        assertThat(offeredStart(asked)).isEqualTo(at("12:15"));
        assertThat((String) offer(asked).get("body")).contains("12:15 PM with Raju");
    }

    @Test
    void aPausedSalonOffersNothing() {
        UUID cancelled = id(book(person(), at("12:00"), haircut, false));
        id(book(person(), at("12:00"), haircut, false));
        UUID asked = id(book(person(), at("15:00"), haircut, true));
        jdbc.update("INSERT INTO salon_pauses (shop_id, paused_until, updated_by, updated_at) VALUES (?, ?, ?, ?)",
                salon, java.sql.Timestamp.from(at("18:00")), owner, java.sql.Timestamp.from(TUESDAY_10_00));
        assertThat(post("/bookings/" + cancelled + "/cancel", Map.of(), customerOf(cancelled)).status())
                .isEqualTo(200);
        assertThat(offers(asked)).isZero();
    }

    @Test
    void atMostTenBookingsAreOfferedOneFreedTimeTheSoonestFirst() {
        UUID cancelled = id(book(person(), at("12:00"), haircut, false));   // Raju
        id(book(person(), at("12:00"), haircut, false));                     // Sunil
        List<UUID> asked = new ArrayList<>();
        for (int i = 0; i < 12; i++) {   // 13:00, 13:00, 13:30, 13:30, ...: two at a time, one per barber
            String time = LocalTime.of(13, 0).plusMinutes(30L * (i / 2)).toString();
            asked.add(id(book(person(), at(time), haircut, true)));
        }
        assertThat(post("/bookings/" + cancelled + "/cancel", Map.of(), customerOf(cancelled)).status())
                .isEqualTo(200);
        assertThat(asked.subList(0, 10)).allSatisfy(b -> assertThat(offers(b)).isEqualTo(1));
        assertThat(asked.subList(10, 12)).allSatisfy(b -> assertThat(offers(b)).isZero());

        // Someone takes 12:00 and cancels again: the first ten heard of it already (no place used up), the other two do
        UUID again = id(book(person(), at("12:00"), haircut, false));
        assertThat(post("/bookings/" + again + "/cancel", Map.of(), customerOf(again)).status()).isEqualTo(200);
        assertThat(asked.subList(0, 10)).allSatisfy(b -> assertThat(offers(b)).isEqualTo(1));
        assertThat(asked.subList(10, 12)).allSatisfy(b -> assertThat(offers(b)).isEqualTo(1));
    }

    @Test
    void twoCancelsAtOnceBothGoThroughAndTheTimeIsOfferedAtMostOnce() throws Exception {
        UUID first = id(book(person(), at("12:00"), haircut, false));    // Raju
        UUID second = id(book(person(), at("12:00"), haircut, false));   // Sunil
        UUID asked = id(book(person(), at("15:00"), haircut, true));
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Reply> a = pool.submit(() -> {
                go.await();
                return post("/bookings/" + first + "/cancel", Map.of(), customerOf(first));
            });
            Future<Reply> b = pool.submit(() -> {
                go.await();
                return post("/bookings/" + second + "/cancel", Map.of(), customerOf(second));
            });
            go.countDown();
            assertThat(a.get(30, TimeUnit.SECONDS).status()).isEqualTo(200);
            assertThat(b.get(30, TimeUnit.SECONDS).status()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        // Never twice: whichever offer ran second found 12:00 free with the other barber already (no news). If both
        // looked after both cancels, neither offers it: 12:00 was then free with two barbers
        assertThat(offers(asked)).isLessThanOrEqualTo(1);
    }

    @Test
    void theSameTimeIsNeverRecordedTwiceInARowButAnotherOneIsAndNeverForABookingThatChanged() {
        UUID asked = id(book(person(), at("15:00"), haircut, true));
        assertThat(alerts.recordOffers(List.of(offerOf(asked, "15:00", "12:00")))).containsExactly(asked);
        assertThat(alerts.recordOffers(List.of(offerOf(asked, "15:00", "12:00")))).isEmpty();
        assertThat(alerts.recordOffers(List.of(offerOf(asked, "15:00", "13:00")))).containsExactly(asked);
        assertThat(alerts.recordOffers(List.of(offerOf(UUID.randomUUID(), "15:00", "13:00")))).isEmpty();   // no row
        // Read at 15:00, but moved since (or cancelled): nothing is recorded, so nothing is sent
        assertThat(alerts.recordOffers(List.of(offerOf(asked, "16:00", "11:00")))).isEmpty();
        post("/bookings/" + asked + "/cancel", Map.of(), customerOf(asked));
        assertThat(alerts.recordOffers(List.of(offerOf(asked, "15:00", "11:00")))).isEmpty();
    }

    private static EarlierSlotAlertStore.Offer offerOf(UUID booking, String bookedAt, String offered) {
        return new EarlierSlotAlertStore.Offer(booking, at(bookedAt), at(offered));
    }

    @Test
    void aCustomersOwnOtherBookingIsNotOfferedTheTimeTheyGaveUp() {
        UUID customer = person();
        UUID given = id(book(customer, at("12:00"), haircut, false));       // Raju
        id(book(person(), at("12:00"), haircut, false));                     // Sunil
        UUID theirOther = id(book(customer, at("15:00"), haircut, true));
        UUID someoneElse = id(book(person(), at("16:00"), haircut, true));
        assertThat(post("/bookings/" + given + "/cancel", Map.of(), customer).status()).isEqualTo(200);
        assertThat(offers(theirOther)).isZero();
        assertThat(offers(someoneElse)).isEqualTo(1);
    }

    @Test
    void anOfferNeverClashesWithTheCustomersOtherBookingEvenOneThatAlsoAsked() {
        barber("Asif", List.of(beard));
        UUID cancelled = id(book(person(), at("12:00"), haircut, false));   // Raju 12:00-12:30
        id(book(person(), at("12:00"), haircut, false));                     // Sunil
        UUID busyCustomer = person();
        UUID beardAt1215 = id(book(busyCustomer, at("12:15"), beard, true));   // Asif (Raju is taken), switch on
        UUID haircutAt3 = id(book(busyCustomer, at("15:00"), haircut, true));
        assertThat(post("/bookings/" + cancelled + "/cancel", Map.of(), customerOf(cancelled)).status())
                .isEqualTo(200);
        // 12:00 and 12:15 would both overlap their 12:15 beard trim; no other start uses the freed time
        assertThat(offers(haircutAt3)).isZero();
        assertThat(offers(beardAt1215)).isZero();   // 12:00 was free with Asif already: no news
    }

    @Test
    void switchingTheAlertOffWithdrawsAnOfferNotSentYet() {
        UUID cancelled = id(book(person(), at("12:00"), haircut, false));
        id(book(person(), at("12:00"), haircut, false));
        UUID asked = id(book(person(), at("15:00"), haircut, true));
        post("/bookings/" + cancelled + "/cancel", Map.of(), customerOf(cancelled));
        assertThat(offers(asked)).isEqualTo(1);
        put("/bookings/" + asked + "/earlier-alert", Map.of("on", false), customerOf(asked));
        assertThat(jdbc.queryForList("SELECT status FROM notification_jobs WHERE ref_id = ? "
                + "AND type = 'BOOKING_EARLIER_SLOT'", String.class, asked)).containsOnly("CANCELLED");
        // Its reminder is another message: it stays
        assertThat(jdbc.queryForList("SELECT status FROM notification_jobs WHERE ref_id = ? "
                + "AND type = 'BOOKING_REMINDER'", String.class, asked)).containsOnly("PENDING");
    }

    @Test
    void switchingOnAgainRepairsAnAlertWhoseBookingMovedOnAnOldCopy() {
        UUID asked = id(book(person(), at("15:00"), haircut, true));
        // As an old copy of the backend moves it: the appointment moves, the alert's copy of its start doesn't
        jdbc.update("UPDATE appointments SET starts_at = starts_at + interval '1 hour', "
                + "ends_at = ends_at + interval '1 hour' WHERE id = ?", asked);
        jdbc.update("UPDATE earlier_slot_alerts SET offered_start = ? WHERE appointment_id = ?",
                java.sql.Timestamp.from(at("11:00")), asked);
        // Its row no longer matches: it is never offered anything, so it shows off (the customer can switch it on)
        assertThat(field(get("/bookings/" + asked, customerOf(asked)).body(), "earlierAlert")).isEqualTo(false);
        Reply on = put("/bookings/" + asked + "/earlier-alert", Map.of("on", true), customerOf(asked));
        assertThat(field(on.body(), "earlierAlert")).as(on.text()).isEqualTo(true);
        assertThat(jdbc.queryForObject("SELECT starts_at FROM earlier_slot_alerts WHERE appointment_id = ?",
                java.time.OffsetDateTime.class, asked).toInstant()).isEqualTo(at("16:00"));
        assertThat(offeredStart(asked)).isNull();
    }

    // ---------- the switch ----------

    @Test
    void theSwitchIsSetWhenBookingAndCanBeTurnedOnAndOffLater() {
        UUID customer = person();
        Reply booked = book(customer, at("15:00"), haircut, true);
        assertThat(field(booked.body(), "earlierAlert")).isEqualTo(true);
        UUID b = id(booked);
        assertThat(field(get("/bookings/" + b, customer).body(), "earlierAlert")).isEqualTo(true);
        assertThat(field(((List<?>) get("/me/bookings", customer).body()).get(0), "earlierAlert")).isEqualTo(true);

        Reply off = put("/bookings/" + b + "/earlier-alert", Map.of("on", false), customer);
        assertThat(field(off.body(), "earlierAlert")).as(off.text()).isEqualTo(false);
        assertThat(count("earlier_slot_alerts")).isZero();
        assertThat(field(put("/bookings/" + b + "/earlier-alert", Map.of("on", false), customer).body(),
                "earlierAlert")).isEqualTo(false);   // already off: fine
        Reply on = put("/bookings/" + b + "/earlier-alert", Map.of("on", true), customer);
        assertThat(field(on.body(), "earlierAlert")).as(on.text()).isEqualTo(true);
        assertThat(field(put("/bookings/" + b + "/earlier-alert", Map.of("on", true), customer).body(),
                "earlierAlert")).isEqualTo(true);   // already on: one row
        assertThat(count("earlier_slot_alerts")).isEqualTo(1);

        // Booked without it: off
        UUID plain = id(book(person(), at("16:00"), haircut, false));
        assertThat(field(get("/bookings/" + plain, customerOf(plain)).body(), "earlierAlert")).isEqualTo(false);
        // Someone else's booking: as if it didn't exist; no body: refused
        assertThat(put("/bookings/" + b + "/earlier-alert", Map.of("on", true), person()).status()).isEqualTo(404);
        assertThat(put("/bookings/" + b + "/earlier-alert", Map.of(), customer).status()).isEqualTo(400);

        // Less than an hour to go: it can't be moved, so it can't be switched on; off still works
        put("/bookings/" + b + "/earlier-alert", Map.of("on", false), customer);
        CLOCK.set(at("14:01"));
        Reply late = put("/bookings/" + b + "/earlier-alert", Map.of("on", true), customer);
        assertThat(field(late.body(), "code")).as(late.text()).isEqualTo("TOO_LATE_TO_MOVE");
        assertThat(put("/bookings/" + b + "/earlier-alert", Map.of("on", false), customer).status()).isEqualTo(200);
        // A cancelled booking can't be switched on either
        CLOCK.set(TUESDAY_10_00);
        put("/bookings/" + b + "/earlier-alert", Map.of("on", true), customer);
        Reply cancelled = post("/bookings/" + b + "/cancel", Map.of(), customer);
        assertThat(field(field(cancelled.body(), "booking"), "earlierAlert")).isEqualTo(false);   // only while waiting
        assertThat(field(put("/bookings/" + b + "/earlier-alert", Map.of("on", true), customer).body(), "code"))
                .isEqualTo("WRONG_STATUS");

        // Booked for less than an hour ahead with the switch on: it can't move, so it stays off
        Reply soon = book(person(), at("10:45"), haircut, true);
        assertThat(field(soon.body(), "earlierAlert")).as(soon.text()).isEqualTo(false);
        assertThat(count("earlier_slot_alerts WHERE appointment_id = '" + id(soon) + "'")).isZero();
    }

    @Test
    void aRetriedBookingKeepsItsSwitch() {
        UUID customer = person();
        UUID key = UUID.randomUUID();
        UUID b = id(call(HttpMethod.POST, "/bookings", bookBody(at("15:00"), haircut, true), customer, key));
        Reply again = call(HttpMethod.POST, "/bookings", bookBody(at("15:00"), haircut, false), customer, key);
        assertThat(id(again)).isEqualTo(b);   // the same booking, not a new one
        assertThat(field(again.body(), "earlierAlert")).isEqualTo(true);
        assertThat(count("earlier_slot_alerts")).isEqualTo(1);
    }

    @Test
    void alertRowsAreDeletedADayAfterTheirStart() {
        UUID b = id(book(person(), at("15:00"), haircut, true));
        CLOCK.set(at("15:00").plus(Duration.ofDays(1)));
        autoEnd.runOnce();
        assertThat(count("earlier_slot_alerts")).isEqualTo(1);   // exactly a day: kept
        CLOCK.set(at("15:01").plus(Duration.ofDays(1)));
        autoEnd.runOnce();
        assertThat(count("earlier_slot_alerts WHERE appointment_id = '" + b + "'")).isZero();
    }

    // ---------- the late allowance ----------

    @Test
    void theMigrationPutsWaitingRemindersOnTheTenMinuteAllowance() throws Exception {
        UUID b = id(book(person(), at("15:00"), haircut, false));
        // As a reminder written before V33 reads (start + 15), in the app's text and the SMS variables
        jdbc.update("UPDATE notification_jobs SET body = replace(body, 'Held until 3:10 PM', 'Held until 3:15 PM') "
                + "WHERE ref_id = ? AND type = 'BOOKING_REMINDER'", b);
        jdbc.update("INSERT INTO notification_jobs (id, user_id, channel, type, ref_id, title, body, url, "
                        + "sms_variables, send_at, status, attempts, next_try_at, created_at) SELECT gen_random_uuid(), "
                        + "user_id, 'SMS', type, ref_id, title, body, url, "
                        + "'SALON=Test&TIME=3%3A00+PM&CODE=1234&UNTIL=3%3A15+PM', send_at, status, attempts, "
                        + "next_try_at, created_at FROM notification_jobs WHERE ref_id = ? AND type = 'BOOKING_REMINDER'",
                b);
        String sql;
        try (var in = getClass().getResourceAsStream("/db/migration/V33__earlier_slot_alerts.sql")) {
            String file = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            sql = file.substring(file.indexOf("WITH rewritten AS")).strip();
            sql = sql.substring(0, sql.length() - 1);   // without its closing semicolon
        }
        jdbc.update(sql);
        assertThat(jdbc.queryForList("SELECT body FROM notification_jobs WHERE ref_id = ? "
                + "AND type = 'BOOKING_REMINDER'", String.class, b))
                .hasSize(2).allSatisfy(body -> assertThat(body).contains("Held until 3:10 PM"));
        assertThat(jdbc.queryForObject("SELECT sms_variables FROM notification_jobs WHERE ref_id = ? "
                + "AND channel = 'SMS'", String.class, b))
                .isEqualTo("SALON=Test&TIME=3%3A00+PM&CODE=1234&UNTIL=3%3A10+PM");

        // A 12:45 start: "1:00 PM" becomes "12:55 PM", one character more. A text already at the 500 the column
        // holds (a long name and address) is cut back to 500, ending in "…", instead of failing the migration
        UUID late = id(book(person(), at("12:45"), haircut, false));
        String head = "12:45 PM at Test Shop. Start code 1234. Held until 1:00 PM. ";
        jdbc.update("UPDATE notification_jobs SET body = ? WHERE ref_id = ? AND type = 'BOOKING_REMINDER'",
                head + "x".repeat(500 - head.length() - 1) + "…", late);
        jdbc.update(sql);
        String cut = jdbc.queryForObject("SELECT body FROM notification_jobs WHERE ref_id = ? "
                + "AND type = 'BOOKING_REMINDER'", String.class, late);
        assertThat(cut).hasSize(500).contains("Held until 12:55 PM").endsWith("…");
    }

    // ---------- helpers ----------

    /** Offers are made on a worker after the customer's answer: wait for it before looking. */
    private void idle() {
        try {
            assertThat(earlierSlots.awaitIdle(Duration.ofSeconds(20))).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> offer(UUID booking) {
        idle();
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT url, title, body FROM notification_jobs "
                + "WHERE ref_id = ? AND type = 'BOOKING_EARLIER_SLOT' AND channel = 'PUSH' ORDER BY created_at",
                booking);
        assertThat(rows).isNotEmpty();
        return rows.get(0);
    }

    private int offers(UUID booking) {
        idle();
        return count("notification_jobs WHERE ref_id = '" + booking + "' AND type = 'BOOKING_EARLIER_SLOT' "
                + "AND channel = 'PUSH'");
    }

    private Instant offeredStart(UUID booking) {
        idle();
        java.time.OffsetDateTime t = jdbc.queryForObject("SELECT offered_start FROM earlier_slot_alerts "
                + "WHERE appointment_id = ?", java.time.OffsetDateTime.class, booking);
        return t == null ? null : t.toInstant();
    }

    private UUID person() {
        UUID id = UUID.randomUUID();
        TestData.user(jdbc, id, "91" + String.format("%08d", ++phones));
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

    private Map<String, Object> bookBody(Instant start, UUID service, boolean earlierAlert) {
        Map<String, Object> body = new HashMap<>();
        body.put("salonId", salon.toString());
        body.put("serviceIds", List.of(service));
        body.put("startAt", start.toString());
        if (earlierAlert) {
            body.put("earlierAlert", true);
        }
        return body;
    }

    private Reply book(UUID who, Instant start, UUID service, boolean earlierAlert) {
        return call(HttpMethod.POST, "/bookings", bookBody(start, service, earlierAlert), who, UUID.randomUUID());
    }

    private UUID customerOf(UUID booking) {
        return jdbc.queryForObject("SELECT customer_id FROM appointments WHERE id = ?", UUID.class, booking);
    }

    private UUID staffOf(UUID appointment) {
        return jdbc.queryForObject("SELECT staff_id FROM appointments WHERE id = ?", UUID.class, appointment);
    }

    private static Map<String, Object> service(String name, int minutes, long paise) {
        return Map.of("name", name, "durationMinutes", minutes, "pricePaise", paise);
    }

    /** A moment on Tuesday 10 Nov 2026, India time. */
    private static Instant at(String hhmm) {
        return LocalDate.of(2026, 11, 10).atTime(LocalTime.parse(hhmm)).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
    }

    private int count(String tableAndWhere) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + tableAndWhere, Integer.class);
        return n == null ? 0 : n;
    }

    private String token(UUID who) {
        return jwt.issue(new AuthPrincipal(who, null, null, false), sessions.currentGeneration(who)).token();
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
        String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
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
