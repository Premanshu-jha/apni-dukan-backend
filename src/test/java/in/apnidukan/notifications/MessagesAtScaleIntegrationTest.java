package in.apnidukan.notifications;

import in.apnidukan.auth.service.UserDirectoryService;
import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.jobs.JobLeases;
import in.apnidukan.common.notify.TextMessages;
import in.apnidukan.common.security.AuthPrincipal;
import in.apnidukan.common.security.JwtService;
import in.apnidukan.notifications.domain.PushSubscription;
import in.apnidukan.notifications.domain.JobStatus;
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
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
@org.junit.jupiter.api.extension.ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({CapturingSenders.class, MessagesAtScaleIntegrationTest.Fakes.class})
class MessagesAtScaleIntegrationTest {

    static final Instant NOW = Instant.parse("2026-11-10T04:30:00Z");
    static final TestClock CLOCK = new TestClock(NOW);
    static final List<String> PUSHED = new CopyOnWriteArrayList<>();
    static final List<String> TEXTED = new CopyOnWriteArrayList<>();
    static volatile PushSender.Result pushResult = PushSender.Result.SENT;
    static volatile long pushMillis = 40;
    /** How long a device whose address contains "/slow-" takes. */
    static volatile long slowMillis = 2000;
    static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    static final AtomicInteger MOST_IN_FLIGHT = new AtomicInteger();
    /** Sends running at once and the most seen, per push service host. */
    static final Map<String, AtomicInteger> HOST_IN_FLIGHT = new java.util.concurrent.ConcurrentHashMap<>();
    static final Map<String, AtomicInteger> HOST_MOST = new java.util.concurrent.ConcurrentHashMap<>();
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
                String host = java.net.URI.create(device.endpoint()).getHost();
                AtomicInteger onHost = HOST_IN_FLIGHT.computeIfAbsent(host, h -> new AtomicInteger());
                HOST_MOST.computeIfAbsent(host, h -> new AtomicInteger()).accumulateAndGet(onHost.incrementAndGet(),
                        Math::max);
                try {
                    Thread.sleep(device.endpoint().contains("/slow-") ? slowMillis : pushMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    IN_FLIGHT.decrementAndGet();
                    onHost.decrementAndGet();
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
                public Optional<UUID> findByPhone(String phone) {
                    return real.findByPhone(phone);
                }

                @Override
                public String normalizePhone(String phone) {
                    return real.normalizePhone(phone);
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
                public in.apnidukan.common.context.TeamRole teamRole(UUID userId) {
                    return real.teamRole(userId);
                }

                @Override
                public boolean isAdmin(UUID userId) {
                    return real.isAdmin(userId);
                }

                @Override
                public Optional<UUID> findByVerifiedEmail(String email) {
                    return real.findByVerifiedEmail(email);
                }

                @Override
                public Optional<String> verifiedEmail(UUID userId) {
                    return real.verifiedEmail(userId);
                }

                @Override
                public java.util.List<UUID> supportStaff() {
                    return real.supportStaff();
                }
            };
        }
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        TestContainers.register(registry);
        registry.add("app.notifications.batch-size", () -> "5");
        registry.add("app.notifications.send-concurrency", () -> "4");
        registry.add("app.notifications.per-host-concurrency", () -> "2");   // at most 2 at once to one service
        registry.add("app.notifications.record-every", () -> "3");   // outcomes written three at a time
        registry.add("app.cleanup.batch", () -> "3");
        registry.add("app.sms.booking-daily-limit", () -> "4");   // configurable (default 100,000)
        registry.add("app.sms.template.booking-cancelled-by-salon", () -> "tpl-salon-cancel");
        registry.add("app.sms.template.salon-new-booking", () -> "tpl-salon-new");
        registry.add("app.sms.staff-per-person-daily-limit", () -> "12");
        registry.add("app.notifications.batch-deadline-ms", () -> "1000");
        registry.add("app.notifications.expected-send-ms", () -> "50");
    }

    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired JwtService jwt;
    @Autowired NotificationSendJob sender;
    @Autowired MessageCleanupJob cleanup;
    @Autowired NotificationStore store;
    @Autowired JobLeases leases;
    @Autowired PushSubscriptionStore deviceStore;
    @Autowired PushSender pushSender;
    @Autowired TextMessages textMessages;
    @Autowired in.apnidukan.notifications.service.SmsTemplates smsTemplates;
    @Autowired UserDirectory userDirectory;
    @Autowired in.apnidukan.common.security.RateLimiter rateLimiter;

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
        pushMillis = 40;
        MOST_IN_FLIGHT.set(0);
        HOST_IN_FLIGHT.clear();
        HOST_MOST.clear();
        slowMillis = 2000;
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
    void sendsToOnePushServiceStayUnderItsOwnLimitWhileOthersUseTheRestAndEveryOutcomeIsWritten() {
        for (int i = 0; i < 10; i++) {
            UUID person = person(i);
            jdbc.update("INSERT INTO push_subscriptions (id, user_id, endpoint, p256dh, auth, created_at) "
                    + "VALUES (?, ?, ?, 'key', 'auth', ?)", UUID.randomUUID(), person, (i % 2 == 0
                    ? "https://fcm.googleapis.com/fcm/send/d-" : "https://updates.push.services.mozilla.com/wpush/v2/d-")
                    + i, Timestamp.from(NOW));
            due(person, "PUSH", "BOOKING_CONFIRMED");
        }
        pushMillis = 150;
        assertThat(sender.runOnce()).isEqualTo(10);
        assertThat(HOST_MOST.get("fcm.googleapis.com").get()).isBetween(1, 2);
        assertThat(HOST_MOST.get("updates.push.services.mozilla.com").get()).isBetween(1, 2);
        assertThat(MOST_IN_FLIGHT.get()).isBetween(3, 4);   // both services at once, never above send-concurrency
        // Written in threes and the rest at the end: all of them before the round returned
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_jobs WHERE status = 'SENT' "
                + "AND finished_at IS NOT NULL", Integer.class)).isEqualTo(10);
    }

    /** A sender of its own (writing outcomes only by time: 50 a statement), so stopping it leaves the others alone. */
    private NotificationSendJob ownSender() {
        return new NotificationSendJob(store, deviceStore, pushSender, textMessages, smsTemplates, userDirectory,
                rateLimiter, 100_000, 1000, 5, 4, 2, 50, 180_000, 50, CLOCK, false);
    }

    @Test
    void anOutcomeIsWrittenWithinHalfASecondWhileOtherSendsOfTheBatchStillRun() throws Exception {
        UUID quick = person(1);
        device(quick, 1);
        UUID quickJob = due(quick, "PUSH", "BOOKING_CONFIRMED");
        UUID slow = person(2);
        jdbc.update("INSERT INTO push_subscriptions (id, user_id, endpoint, p256dh, auth, created_at) VALUES (?, ?, "
                + "'https://fcm.googleapis.com/fcm/send/slow-2', 'key', 'auth', ?)", UUID.randomUUID(), slow,
                Timestamp.from(NOW));
        UUID slowJob = due(slow, "PUSH", "BOOKING_CONFIRMED");
        NotificationSendJob own = ownSender();
        ExecutorService thread = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> round = thread.submit(own::runOnce);
            // Waits for the condition (polling), not a fixed time: the quick one's outcome is written while the slow
            // one (2 s) is still being sent
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofMillis(1500))
                    .pollInterval(java.time.Duration.ofMillis(20)).until(() -> "SENT".equals(status(quickJob)));
            assertThat(round.isDone()).isFalse();   // the slow one is still being sent
            assertThat(status(slowJob)).isEqualTo("PENDING");
            assertThat(round.get(10, TimeUnit.SECONDS)).isEqualTo(2);
            assertThat(status(slowJob)).isEqualTo("SENT");
        } finally {
            thread.shutdownNow();
            own.stop();
        }
    }

    @Test
    void stoppingTheServerWritesEveryOutcomeOfSendsThatWereRunning() throws Exception {
        UUID slow = person(2);
        jdbc.update("INSERT INTO push_subscriptions (id, user_id, endpoint, p256dh, auth, created_at) VALUES (?, ?, "
                + "'https://fcm.googleapis.com/fcm/send/slow-2', 'key', 'auth', ?)", UUID.randomUUID(), slow,
                Timestamp.from(NOW));
        UUID slowJob = due(slow, "PUSH", "BOOKING_CONFIRMED");
        slowMillis = 1000;
        NotificationSendJob own = ownSender();
        ExecutorService thread = Executors.newSingleThreadExecutor();
        try {
            IN_FLIGHT.set(0);
            thread.submit(own::runOnce);
            // Being sent now: waits until the push service has it (polling), not a fixed time
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                    .pollInterval(java.time.Duration.ofMillis(10)).until(() -> IN_FLIGHT.get() == 1);
            thread.shutdownNow();   // the scheduler stopping the round's own thread
            own.stop();   // the server stopping: the running send finishes, its outcome is written before this returns
            assertThat(status(slowJob)).isEqualTo("SENT");
        } finally {
            thread.shutdownNow();
        }
    }

    private String status(UUID job) {
        return jdbc.queryForObject("SELECT status FROM notification_jobs WHERE id = ?", String.class, job);
    }

    @Test
    void outcomesOfAnyKindAreWrittenInOneStatementOnlyWhileTheMessageIsStillOurs() {
        UUID a = due(person(1), "PUSH", "BOOKING_CONFIRMED");
        UUID b = due(person(2), "PUSH", "BOOKING_CONFIRMED");
        UUID c = due(person(3), "PUSH", "BOOKING_CONFIRMED");
        Instant lease = NOW.plus(Duration.ofMinutes(5));
        assertThat(store.claimDue(NOW, 10, Duration.ofMinutes(5), 8)).hasSize(3);
        jdbc.update("UPDATE notification_jobs SET status = 'CANCELLED', finished_at = now() WHERE id = ?", c);
        store.record(List.of(NotificationStore.Outcome.finished(a, JobStatus.SENT, NOW, lease),
                NotificationStore.Outcome.retry(b, NOW.plus(Duration.ofMinutes(1)), lease),
                NotificationStore.Outcome.finished(c, JobStatus.SENT, NOW, lease)));
        assertThat(jdbc.queryForObject("SELECT status FROM notification_jobs WHERE id = ?", String.class, a))
                .isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT status || ' ' || (next_try_at = ?) FROM notification_jobs "
                + "WHERE id = ?", String.class, Timestamp.from(NOW.plus(Duration.ofMinutes(1))), b))
                .isEqualTo("PENDING true");
        assertThat(jdbc.queryForObject("SELECT status FROM notification_jobs WHERE id = ?", String.class, c))
                .isEqualTo("CANCELLED");   // the cancel in between wins
        // A lease that isn't ours any more: nothing changes
        store.record(List.of(NotificationStore.Outcome.finished(b, JobStatus.FAILED, NOW, lease)));
        assertThat(jdbc.queryForObject("SELECT status FROM notification_jobs WHERE id = ?", String.class, b))
                .isEqualTo("PENDING");
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
    void sendsStillRunningAtTheDeadlineFinishAndRecordAndTheRestAreGivenBackUntried() {
        for (int i = 0; i < 5; i++) {
            UUID person = person(i);
            device(person, i);
            due(person, "PUSH", "BOOKING_CONFIRMED");
        }
        pushMillis = 1200;   // longer than the 1 s batch deadline here
        // 4 sends start at once (concurrency 4) and are never interrupted; the 5th would start after the deadline
        assertThat(sender.runOnce()).isEqualTo(4);
        assertThat(PUSHED).hasSize(4);
        assertThat(jdbc.queryForList("SELECT status || ':' || attempts FROM notification_jobs ORDER BY 1",
                String.class)).containsExactly("PENDING:0", "SENT:1", "SENT:1", "SENT:1", "SENT:1");

        // Given back due now, its attempt not counted: the next round sends it, once
        pushMillis = 40;
        assertThat(sender.runOnce()).isEqualTo(1);
        assertThat(PUSHED).hasSize(5);
        assertThat(jdbc.queryForList("SELECT DISTINCT status || ':' || attempts FROM notification_jobs",
                String.class)).containsExactly("SENT:1");
    }

    @Test
    void oneBatchNeverPassesACustomersDailySmsCapEvenWhenSentInParallel() {
        UUID customer = person(1);
        redis.opsForValue().set("rl:sms-booking-person:" + customer, "8", Duration.ofHours(1));   // cap is 10
        for (int i = 0; i < 3; i++) {
            due(customer, "SMS", "BOOKING_CANCELLED_BY_SALON");
        }
        sender.runOnce();
        assertThat(TEXTED).hasSize(2);
        assertThat(jdbc.queryForList("SELECT status FROM notification_jobs ORDER BY status", String.class))
                .containsExactly("FAILED", "SENT", "SENT");
    }

    @Test
    void salonStaffHaveTheirOwnHigherSmsCap() {
        UUID owner = person(1);
        // Over the customers' 10 (as a customer), but salon messages count against the staff cap (12 here)
        redis.opsForValue().set("rl:sms-booking-person:" + owner, "10", Duration.ofHours(1));
        redis.opsForValue().set("rl:sms-staff-person:" + owner, "11", Duration.ofHours(1));
        due(owner, "SMS", "SALON_NEW_BOOKING");
        due(owner, "SMS", "SALON_NEW_BOOKING");
        sender.runOnce();
        assertThat(TEXTED).hasSize(1);
        assertThat(redis.opsForValue().get("rl:sms-staff-person:" + owner)).isEqualTo("12");
        assertThat(jdbc.queryForList("SELECT status FROM notification_jobs ORDER BY status", String.class))
                .containsExactly("FAILED", "SENT");
    }

    @Test
    void smsOfABatchShareOnePhoneLookupAndStopAtTheConfiguredDailyLimit(CapturedOutput output) {
        for (int i = 0; i < 6; i++) {
            due(person(i), "SMS", "BOOKING_CANCELLED_BY_SALON");
        }
        assertThat(sender.runOnce()).isEqualTo(6);
        assertThat(PHONE_LOOKUPS.get()).isEqualTo(2);   // batches of 5 and 1
        assertThat(TEXTED).hasSize(4);   // the booking-SMS limit here is 4 a day
        assertThat(jdbc.queryForList("SELECT status FROM notification_jobs ORDER BY status", String.class))
                .containsExactly("FAILED", "FAILED", "SENT", "SENT", "SENT", "SENT");
        // At 80% of the day's 4 (the 3rd SMS), one warning; the 4th doesn't repeat it
        assertThat(output.getAll().split("80% of the daily booking-SMS limit used", -1)).hasSize(2);
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
        // Another copy holds the lease (leases run on the database's clock)
        jdbc.update("INSERT INTO job_leases (name, locked_until, locked_by, locked_at) VALUES "
                + "('message-cleanup', now() + interval '30 minutes', 'other-copy', now())");
        assertThat(cleanup.runUnderLease()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_jobs", Integer.class)).isEqualTo(1);

        // Its lease ran out (it died mid-round): this copy takes over, and gives the lease back afterwards
        jdbc.update("UPDATE job_leases SET locked_until = now() - interval '1 second' WHERE name = 'message-cleanup'");
        assertThat(cleanup.runUnderLease()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_jobs", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT locked_until <= now() FROM job_leases WHERE name = 'message-cleanup'",
                Boolean.class)).isTrue();
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
