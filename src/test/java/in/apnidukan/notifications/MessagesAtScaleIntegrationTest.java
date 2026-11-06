package in.apnidukan.notifications;

import in.apnidukan.auth.service.UserDirectoryService;
import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.jobs.JobLeases;
import in.apnidukan.common.notify.TextMessages;
import in.apnidukan.common.security.AuthPrincipal;
import in.apnidukan.common.security.JwtService;
import in.apnidukan.notifications.domain.PushSubscription;
import in.apnidukan.notifications.repo.NotificationStore;
import in.apnidukan.notifications.repo.PushSubscriptionStore;
import in.apnidukan.notifications.service.MessageCleanupJob;
import in.apnidukan.notifications.service.NotificationSendJob;
import in.apnidukan.notifications.service.PushSender;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The messaging jobs at scale, against real Postgres and Redis in Docker, with small batches so a handful of rows
 * shows the behaviour of hundreds: the sender drains several batches in one round, sends a batch in parallel with
 * one device lookup and one phone lookup per batch; the cleanup deletes in slices with its retention; the unread
 * count stops at 100; jobs run under a lease, one copy at a time.
 */
@EnabledIf("in.apnidukan.support.TestContainers#dockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({CapturingSenders.class, MessagesAtScaleIntegrationTest.Fakes.class})
class MessagesAtScaleIntegrationTest {

    static final Instant NOW = Instant.parse("2026-11-10T04:30:00Z");
    static final TestClock CLOCK = new TestClock(NOW);
    static final List<String> PUSHED = new CopyOnWriteArrayList<>();
    static final List<String> TEXTED = new CopyOnWriteArrayList<>();
    static volatile PushSender.Result pushResult = PushSender.Result.SENT;
    static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    static final AtomicInteger MOST_IN_FLIGHT = new AtomicInteger();
    static final AtomicInteger DEVICE_LOOKUPS = new AtomicInteger();
    static final AtomicInteger PHONE_LOOKUPS = new AtomicInteger();

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        TestClock testClock() {
            return CLOCK;
        }

        /** A push service that takes a moment per message, recording how many it is handed at once. */
        @Bean
        @Primary
        PushSender slowPushSender() {
            return (PushSubscription device, String payload) -> {
                MOST_IN_FLIGHT.accumulateAndGet(IN_FLIGHT.incrementAndGet(), Math::max);
                try {
                    Thread.sleep(40);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    IN_FLIGHT.decrementAndGet();
                }
                PUSHED.add(payload);
                return pushResult;
            };
        }

        @Bean
        @Primary
        TextMessages recordingTextMessages() {
            return (phone, templateId, variables) -> {
                TEXTED.add(phone);
                return true;
            };
        }

        @Bean
        @Primary
        PushSubscriptionStore countingDevices(JdbcTemplate jdbc) {
            return new PushSubscriptionStore(jdbc) {
                @Override
                public Map<UUID, List<PushSubscription>> forUsers(Collection<UUID> userIds) {
                    DEVICE_LOOKUPS.incrementAndGet();
                    return super.forUsers(userIds);
                }
            };
        }

        /** The real directory, counting the phone lookups. */
        @Bean
        @Primary
        UserDirectory countingUsers(UserDirectoryService real) {
            return new UserDirectory() {
                @Override
                public UUID findOrCreateByPhone(String phone) {
                    return real.findOrCreateByPhone(phone);
                }

                @Override
                public Optional<UUID> findByPhone(String phone) {
                    return real.findByPhone(phone);
                }

                @Override
                public Map<UUID, Person> find(Collection<UUID> userIds) {
                    PHONE_LOOKUPS.incrementAndGet();
                    return real.find(userIds);
                }

                @Override
                public boolean isActive(UUID userId) {
                    return real.isActive(userId);
                }

                @Override
                public boolean lockIfActive(UUID userId) {
                    return real.lockIfActive(userId);
                }

                @Override
                public List<String> missingProfile(UUID userId) {
                    return real.missingProfile(userId);
                }

                @Override
                public boolean isAdmin(UUID userId) {
                    return real.isAdmin(userId);
                }
            };
        }
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        TestContainers.register(registry);
        registry.add("app.notifications.batch-size", () -> "5");
        registry.add("app.notifications.send-concurrency", () -> "4");
        registry.add("app.cleanup.batch", () -> "3");
        registry.add("app.sms.booking-daily-limit", () -> "4");   // configurable (default 100,000)
        registry.add("app.sms.template.booking-cancelled-by-salon", () -> "tpl-salon-cancel");
    }

    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired JwtService jwt;
    @Autowired NotificationSendJob sender;
    @Autowired MessageCleanupJob cleanup;
    @Autowired NotificationStore store;
    @Autowired JobLeases leases;

    @BeforeEach
    void setUp() {
        TestData.clean(jdbc);
        redis.execute((RedisCallback<Object>) (RedisConnection c) -> {
            c.serverCommands().flushAll();
            return null;
        });
        CLOCK.set(NOW);
        PUSHED.clear();
        TEXTED.clear();
        pushResult = PushSender.Result.SENT;
        MOST_IN_FLIGHT.set(0);
        DEVICE_LOOKUPS.set(0);
        PHONE_LOOKUPS.set(0);
    }

    // ---------- sending ----------

    @Test
    void oneRoundDrainsSeveralBatchesAndSendsEachInParallelWithOneDeviceLookup() {
        List<UUID> jobs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            UUID person = person(i);
            device(person, i);
            jobs.add(due(person, "PUSH", "BOOKING_CONFIRMED"));
        }

        // Batches of 5: 5 + 5 + 2, all in one round
        assertThat(sender.runOnce()).isEqualTo(12);
        assertThat(PUSHED).hasSize(12);
        assertThat(jdbc.queryForList("SELECT DISTINCT status FROM notification_jobs", String.class))
                .containsExactly("SENT");
        assertThat(DEVICE_LOOKUPS.get()).isEqualTo(3);   // once per batch, not once per message
        assertThat(MOST_IN_FLIGHT.get()).isBetween(2, 4);   // in parallel, never more than send-concurrency
        assertThat(sender.runOnce()).isZero();
        assertThat(jobs).hasSize(12);
    }

    @Test
    void aClaimNeverTakesMoreThanItsBatchEvenWithMoreDue() {
        UUID person = person(1);
        for (int i = 0; i < 7; i++) {
            due(person, "PUSH", "BOOKING_CONFIRMED");
        }
        assertThat(store.claimDue(CLOCK.instant(), 1, Duration.ofMinutes(5), 8)).hasSize(1);
        assertThat(store.claimDue(CLOCK.instant(), 3, Duration.ofMinutes(5), 8)).hasSize(3);
        // Exactly those 4 were leased and counted; the other 3 are untouched
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_jobs WHERE attempts = 1", Integer.class))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_jobs WHERE attempts = 0", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void smsOfABatchShareOnePhoneLookupAndStopAtTheConfiguredDailyLimit() {
        for (int i = 0; i < 6; i++) {
            due(person(i), "SMS", "BOOKING_CANCELLED_BY_SALON");
        }
        assertThat(sender.runOnce()).isEqualTo(6);
        assertThat(PHONE_LOOKUPS.get()).isEqualTo(2);   // batches of 5 and 1
        assertThat(TEXTED).hasSize(4);   // the booking-SMS limit here is 4 a day
        assertThat(jdbc.queryForList("SELECT status FROM notification_jobs ORDER BY status", String.class))
                .containsExactly("FAILED", "FAILED", "SENT", "SENT", "SENT", "SENT");
    }

    @Test
    void failuresAreRetriedWithBackoffAndGoneDevicesAreForgottenTogether() {
        UUID failing = person(1);
        device(failing, 1);
        UUID job = due(failing, "PUSH", "BOOKING_CONFIRMED");
        pushResult = PushSender.Result.FAILED;
        assertThat(sender.runOnce()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status || ':' || attempts FROM notification_jobs WHERE id = ?",
                String.class, job)).isEqualTo("PENDING:1");
        assertThat(jdbc.queryForObject("SELECT next_try_at FROM notification_jobs WHERE id = ?", Timestamp.class, job)
                .toInstant()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));

        CLOCK.advance(Duration.ofMinutes(1));
        UUID other = person(2);
        device(other, 2);
        device(other, 3);
        due(other, "PUSH", "BOOKING_CONFIRMED");
        pushResult = PushSender.Result.GONE;   // every device unsubscribed
        assertThat(sender.runOnce()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM push_subscriptions", Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT DISTINCT status FROM notification_jobs", String.class))
                .containsExactly("NO_DEVICE");
    }

    @Test
    void aMessageCancelledAfterItWasClaimedIsNotSent() {
        UUID person = person(1);
        device(person, 1);
        UUID job = due(person, "PUSH", "BOOKING_CONFIRMED");
        List<?> claimed = store.claimDue(CLOCK.instant(), 10, Duration.ofMinutes(5), 8);
        assertThat(claimed).hasSize(1);
        jdbc.update("UPDATE notification_jobs SET status = 'CANCELLED', finished_at = now() WHERE id = ?", job);
        // Another copy's round: the lease isn't over, so nothing is due, and the cancel stands
        assertThat(sender.runOnce()).isZero();
        assertThat(PUSHED).isEmpty();
    }

    // ---------- cleanup ----------

    @Test
    void cleanupDeletesInSlicesSmsAfterAWeekAndOtherMessagesAfter90Days() {
        UUID person = person(1);
        for (int i = 0; i < 7; i++) {
            finished(person, "SMS", Duration.ofDays(8));
        }
        finished(person, "SMS", Duration.ofDays(6));
        finished(person, "SMS", Duration.ofDays(6));
        for (int i = 0; i < 4; i++) {
            finished(person, "PUSH", Duration.ofDays(91));
        }
        for (int i = 0; i < 3; i++) {
            finished(person, "PUSH", Duration.ofDays(30));   // still in the app's list
        }
        due(person, "PUSH", "BOOKING_REMINDER");   // waiting: never deleted
        jdbc.update("UPDATE notification_jobs SET created_at = ? WHERE status = 'PENDING'",
                Timestamp.from(NOW.minus(Duration.ofDays(200))));

        // A slice is never more than asked for, though more are due
        assertThat(store.deleteFinishedSmsBefore(NOW.minus(Duration.ofDays(7)), 1)).isEqualTo(1);
        assertThat(store.deleteFinishedSmsBefore(NOW.minus(Duration.ofDays(7)), 3)).isEqualTo(3);
        assertThat(store.deleteFinishedBefore(NOW.minus(Duration.ofDays(90)), 1)).isEqualTo(1);
        // The job (slices of 3) deletes the rest
        assertThat(cleanup.runOnce()).isEqualTo(3 + 3);
        assertThat(jdbc.queryForList("SELECT channel || ':' || status FROM notification_jobs ORDER BY 1",
                String.class)).containsExactly("PUSH:PENDING", "PUSH:SENT", "PUSH:SENT", "PUSH:SENT", "SMS:SENT",
                "SMS:SENT");
    }

    @Test
    void cleanupRunsOnOneCopyAtATime() {
        finished(person(1), "PUSH", Duration.ofDays(91));
        // Another copy holds the lease
        jdbc.update("INSERT INTO job_leases (name, locked_until, locked_by, locked_at) VALUES "
                + "('message-cleanup', ?, 'other-copy', ?)", Timestamp.from(NOW.plus(Duration.ofMinutes(30))),
                Timestamp.from(NOW));
        assertThat(cleanup.runUnderLease()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_jobs", Integer.class)).isEqualTo(1);

        // Its lease ran out (it died mid-round): this copy takes over, and gives the lease back afterwards
        CLOCK.advance(Duration.ofMinutes(31));
        assertThat(cleanup.runUnderLease()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_jobs", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT locked_until <= ? FROM job_leases WHERE name = 'message-cleanup'",
                Boolean.class, Timestamp.from(CLOCK.instant()))).isTrue();
    }

    @Test
    void aLeaseRefusesASecondTakeWhileHeldAndIsGivenBackAfterTheRound() {
        AtomicBoolean secondRan = new AtomicBoolean();
        boolean first = leases.runExclusively("test-job", Duration.ofMinutes(5),
                () -> secondRan.set(leases.runExclusively("test-job", Duration.ofMinutes(5), () -> { })));
        assertThat(first).isTrue();
        assertThat(secondRan).isFalse();   // refused while the first held it
        assertThat(leases.runExclusively("test-job", Duration.ofMinutes(5), () -> { })).isTrue();   // given back

        // Given back even when the round fails
        assertThatThrownBy(() -> leases.runExclusively("test-job", Duration.ofMinutes(5), () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(leases.runExclusively("test-job", Duration.ofMinutes(5), () -> { })).isTrue();
    }

    // ---------- unread count ----------

    @Test
    void theUnreadCountStopsAt100AndIsStillANumber() {
        UUID person = person(1);
        for (int i = 0; i < 7; i++) {
            finished(person, "PUSH", Duration.ofMinutes(5));
        }
        assertThat(unread(person)).isEqualTo("{\"unread\":7}");
        for (int i = 0; i < 143; i++) {
            finished(person, "PUSH", Duration.ofMinutes(5));
        }
        assertThat(unread(person)).isEqualTo("{\"unread\":100}");   // the apps show "99+"
    }

    // ---------- helpers ----------

    private String unread(UUID person) {
        String token = jwt.issue(new AuthPrincipal(person, null, null, false), 0).token();
        return RestClient.builder()
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + environment.getProperty("local.server.port") + "/api/v1")
                .build().get().uri("/me/notifications/unread-count")
                .headers(h -> h.setBearerAuth(token))
                .retrieve().body(String.class);
    }

    private UUID person(int n) {
        UUID id = UUID.randomUUID();
        TestData.user(jdbc, id, "90000003" + String.format("%02d", n));
        return id;
    }

    private void device(UUID person, int n) {
        jdbc.update("INSERT INTO push_subscriptions (id, user_id, endpoint, p256dh, auth, created_at) "
                        + "VALUES (?, ?, ?, 'key', 'auth', ?)", UUID.randomUUID(), person,
                "https://fcm.googleapis.com/fcm/send/device-" + n, Timestamp.from(NOW));
    }

    /** A message due now. */
    private UUID due(UUID person, String channel, String type) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO notification_jobs (id, user_id, channel, type, title, body, sms_variables, send_at, "
                        + "status, attempts, next_try_at, created_at) VALUES (?, ?, ?, ?, 'Title', 'Body', ?, ?, "
                        + "'PENDING', 0, ?, ?)", id, person, channel, type,
                "SMS".equals(channel) ? "REASON=Closed&SALON=Test&WHEN=Tue" : null, Timestamp.from(NOW),
                Timestamp.from(NOW), Timestamp.from(NOW));
        return id;
    }

    /** A message sent this long ago (unread, in the customer app's list if it is the PUSH row). */
    private void finished(UUID person, String channel, Duration ago) {
        Timestamp at = Timestamp.from(NOW.minus(ago));
        jdbc.update("INSERT INTO notification_jobs (id, user_id, channel, type, title, body, send_at, status, "
                        + "attempts, next_try_at, created_at, finished_at) VALUES (?, ?, ?, 'BOOKING_CONFIRMED', "
                        + "'Title', 'Body', ?, 'SENT', 1, ?, ?, ?)", UUID.randomUUID(), person, channel, at, at, at,
                at);
    }
}
