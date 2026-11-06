package in.apnidukan.notifications.service;

import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.notify.TextMessages;
import in.apnidukan.common.security.RateLimiter;
import in.apnidukan.notifications.domain.Audience;
import in.apnidukan.notifications.domain.Channel;
import in.apnidukan.notifications.domain.JobStatus;
import in.apnidukan.notifications.domain.NotificationJob;
import in.apnidukan.notifications.domain.PushSubscription;
import in.apnidukan.notifications.repo.NotificationStore;
import in.apnidukan.notifications.repo.NotificationStore.Outcome;
import in.apnidukan.notifications.repo.PushSubscriptionStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Sends the messages that are due, as Web Push or SMS (each its own row), every couple of seconds.
 *
 * <p>One round <b>drains</b> the queue: it claims a batch (app.notifications.batch-size, 500), sends it, and claims
 * the next while batches come back full, so a burst (every 6 PM reminder at once) goes out in one round instead of
 * 100 messages every 30 seconds. Claiming is one short statement (NotificationStore.claimDue, FOR UPDATE SKIP
 * LOCKED); the sending happens after it, so no database connection waits on a push service.
 *
 * <p>A batch is sent <b>in parallel</b> on virtual threads, at most app.notifications.send-concurrency (8) at a
 * time: a send mostly waits on the push service or MSG91, so the waits overlap instead of queueing. Everything a
 * batch needs from the database is read once for the whole batch (every device in one query, every phone in one),
 * and each message's outcome is written the moment it is known (one short statement), so a crash or redeploy
 * mid-batch never forgets what was already sent. The database work of a send is that and one lease renewal, each
 * a single statement outside any transaction: a send holds a connection for milliseconds, never while it waits on
 * the network. The concurrency is still kept at most
 * half the connection pool (clamped with a warning), so web requests always find connections.
 *
 * <p><b>Several copies of the backend</b>: this job does not take a JobLeases lease. Every copy runs it, and SKIP
 * LOCKED gives each claim different rows, so more copies simply send more in parallel; giving up (giveUp) is one
 * idempotent UPDATE. A message is sent at most once in normal operation: just before sending, its lease is renewed
 * only if it is still this copy's (NotificationStore.renew), and its outcome is recorded only under that lease.
 * A send that has started is never interrupted: each one is bounded (at most {@link #MAX_DEVICES} devices, no new
 * device after {@link #MESSAGE_BUDGET}, each device call with its own HTTP timeouts), well inside its lease, and
 * every outcome is recorded. A round claims only about what can be sent before app.notifications.batch-deadline-ms
 * (3 minutes) at the expected pace (app.notifications.expected-send-ms); messages of a batch not started by the
 * deadline, or when the server is stopping, are given back untried (their attempt is not counted) and the round
 * ends. A server that dies in the moment between a provider accepting a message and its outcome being written can
 * still send that one message twice.
 *
 * <p>A failed message is tried again after 1, 5 and 15 minutes, then marked FAILED.
 */
@Component
public class NotificationSendJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationSendJob.class);
    /**
     * How long a claimed message is this server's. Renewed for each message just before it is sent, so it only
     * has to cover the wait for its turn in the batch (at most the batch deadline) and one message (at most
     * MESSAGE_BUDGET plus one device's 15 s).
     */
    static final Duration LEASE = Duration.ofMinutes(5);
    /** No further device of one message is tried after this; the rest count as failed (tried again later). */
    static final Duration MESSAGE_BUDGET = Duration.ofSeconds(60);
    /** Devices one message goes to at most (NotificationService keeps no more per person). */
    static final int MAX_DEVICES = NotificationService.MAX_DEVICES;
    /** Claimed this often without any outcome recorded (the server kept failing): given up as FAILED. */
    static final int MAX_ATTEMPTS = 8;
    /** Booking SMS one customer can get in a day (a few bookings, moves and cancels); more is someone looping. */
    static final int SMS_PER_PERSON_PER_DAY = 10;
    private static final String DAY_KEY = "sms-booking-day";
    private static final String PERSON_KEY = "sms-booking-person:";
    private static final String STAFF_KEY = "sms-staff-person:";
    /** Wait before the 2nd, 3rd and 4th try. */
    static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5),
            Duration.ofMinutes(15));

    private final NotificationStore jobs;
    private final PushSubscriptionStore devices;
    private final PushSender sender;
    private final TextMessages sms;
    private final SmsTemplates smsTemplates;
    private final UserDirectory users;
    private final RateLimiter limits;
    private final int smsBookingDailyLimit;
    private final int staffSmsPerPerson;
    private final int batchSize;
    private final int claimSize;
    private final Duration batchDeadline;
    /** Set when the server is stopping: no more claims, no new sends; running ones finish and record. */
    private volatile boolean stopping;
    private final ExecutorService senders;
    private final Clock clock;
    private final boolean enabled;

    public NotificationSendJob(NotificationStore jobs, PushSubscriptionStore devices, PushSender sender,
                               TextMessages sms, SmsTemplates smsTemplates, UserDirectory users, RateLimiter limits,
                               @Value("${app.sms.booking-daily-limit:100000}") int smsBookingDailyLimit,
                               @Value("${app.sms.staff-per-person-daily-limit:1000}") int staffSmsPerPerson,
                               @Value("${app.notifications.batch-size:500}") int batchSize,
                               @Value("${app.notifications.send-concurrency:8}") int concurrency,
                               @Value("${spring.datasource.hikari.maximum-pool-size:10}") int dbPoolSize,
                               @Value("${app.notifications.batch-deadline-ms:180000}") long batchDeadlineMs,
                               @Value("${app.notifications.expected-send-ms:1000}") long expectedSendMs,
                               Clock clock, @Value("${app.jobs.enabled:true}") boolean enabled) {
        if (batchSize < 1 || concurrency < 1 || batchDeadlineMs < 1 || expectedSendMs < 1) {
            throw new IllegalStateException("app.notifications.batch-size, send-concurrency, batch-deadline-ms and "
                    + "expected-send-ms must be at least 1");
        }
        this.jobs = jobs;
        this.devices = devices;
        this.sender = sender;
        this.sms = sms;
        this.smsTemplates = smsTemplates;
        this.users = users;
        this.limits = limits;
        this.smsBookingDailyLimit = smsBookingDailyLimit;
        this.staffSmsPerPerson = staffSmsPerPerson;
        this.batchSize = batchSize;
        this.batchDeadline = Duration.ofMillis(batchDeadlineMs);
        int parallel = concurrency(concurrency, dbPoolSize);
        this.claimSize = claimSize(batchSize, parallel, batchDeadlineMs, expectedSendMs);
        // A fixed number of virtual threads: cheap to wait on the network, and never more sends at once than this
        this.senders = Executors.newFixedThreadPool(parallel, Thread.ofVirtual().name("notify-send-", 0).factory());
        this.clock = clock;
        this.enabled = enabled;
    }

    /**
     * The sends allowed at once: as asked, but at most half the database pool (at least 1), so the sender can never
     * take the connections web requests need. Clamped with a warning rather than refusing to start.
     */
    static int concurrency(int requested, int dbPoolSize) {
        int most = Math.max(1, dbPoolSize / 2);
        if (requested > most) {
            log.warn("NOTIFY_SEND_CONCURRENCY {} is more than half of DB_POOL_SIZE {}: using {}", requested,
                    dbPoolSize, most);
            return most;
        }
        return requested;
    }

    /**
     * How many messages one claim takes: the batch size, but no more than {@code parallel} sends can get through
     * in 80% of the deadline at the expected pace (at least {@code parallel}), so a batch normally finishes in time
     * and few messages are claimed only to be given back.
     */
    static int claimSize(int batchSize, int parallel, long deadlineMs, long expectedSendMs) {
        long fits = parallel * (deadlineMs * 8 / 10) / expectedSendMs;
        return (int) Math.min(batchSize, Math.max(parallel, fits));
    }

    @Scheduled(fixedDelayString = "${app.notifications.send-delay-ms:2000}", initialDelay = 10_000)
    public void scheduled() {
        if (enabled) {
            runOnce();
        }
    }

    @PreDestroy
    void stop() throws InterruptedException {
        // No more claims; sends already running finish and record (never interrupted: the provider may have
        // accepted them); waiting ones are given back untried
        stopping = true;
        senders.shutdown();
        if (!senders.awaitTermination(MESSAGE_BUDGET.toSeconds() + 20, TimeUnit.SECONDS)) {
            log.warn("Notification sends still running at shutdown");
        }
    }

    /** One round, draining every due message; returns how many it handled. Called directly by tests. */
    public int runOnce() {
        jobs.giveUp(MAX_ATTEMPTS, now());
        int handled = 0;
        while (!stopping) {
            Instant now = now();
            List<NotificationJob> due = jobs.claimDue(now, claimSize, LEASE, MAX_ATTEMPTS);
            int givenBack = sendBatch(due, now.plus(LEASE));
            handled += due.size() - givenBack;
            // A full batch means there may be more waiting; one that ran out of time means we're behind: next round
            if (due.size() < claimSize || givenBack > 0) {
                break;
            }
        }
        return handled;
    }

    /** What sending one message came to: its outcome (null if not sent by us) and devices that are gone. */
    private record Sent(Outcome outcome, List<UUID> goneDevices) {
    }

    /** A message handed back untried (deadline or stopping). */
    private static final Sent GIVEN_BACK = new Sent(null, List.of());

    /** Sends one batch; returns how many of its messages were given back untried. */
    private int sendBatch(List<NotificationJob> batch, Instant lease) {
        if (batch.isEmpty()) {
            return 0;
        }
        long deadline = System.nanoTime() + batchDeadline.toNanos();
        // Everything the batch needs, one query each
        Set<UUID> pushTo = new HashSet<>();
        Set<UUID> smsTo = new HashSet<>();
        for (NotificationJob job : batch) {
            (job.channel() == Channel.SMS ? smsTo : pushTo).add(job.userId());
        }
        Map<UUID, List<PushSubscription>> devicesByUser = devices.forUsers(pushTo);
        Map<UUID, UserDirectory.Person> people = smsTo.isEmpty() ? Map.of() : users.find(smsTo);
        Map<UUID, Outcome> overBudget = overSmsBudget(batch, lease);
        jobs.record(List.copyOf(overBudget.values()));   // dropped SMS: recorded at once, never sent

        int givenBack = 0;
        List<Future<Sent>> running = new ArrayList<>(batch.size());
        for (NotificationJob job : batch) {
            if (overBudget.containsKey(job.id())) {
                continue;
            }
            try {
                running.add(senders.submit(() -> sendOne(job, lease, deadline, devicesByUser, people)));
            } catch (RejectedExecutionException stopped) {
                giveBack(job, lease);
                givenBack++;
            }
        }
        for (Future<Sent> f : running) {
            try {
                // Each send is bounded (see the class comment) and records its own outcome, so this can't hang
                // and, if this thread is stopped, nothing already sent is forgotten
                if (f.get() == GIVEN_BACK) {
                    givenBack++;
                }
            } catch (ExecutionException e) {
                log.warn("Sending a notification failed: {}", e.getCause().getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return givenBack;
    }

    /** Hands a claimed message back untried: due again now, its attempt not counted. */
    private void giveBack(NotificationJob job, Instant lease) {
        jobs.release(job.id(), lease, now());
    }

    /**
     * Sends one message if it is still ours and records its outcome at once. GIVEN_BACK if the batch's deadline
     * passed before its turn or the server is stopping; null if it is no longer ours or failed unexpectedly (due
     * again when its lease runs out).
     */
    private Sent sendOne(NotificationJob job, Instant lease, long deadline,
                         Map<UUID, List<PushSubscription>> devicesByUser, Map<UUID, UserDirectory.Person> people) {
        try {
            if (stopping || System.nanoTime() - deadline > 0) {
                giveBack(job, lease);
                return GIVEN_BACK;
            }
            Instant mine = now().plus(LEASE);
            if (!jobs.renew(job.id(), lease, mine)) {   // no longer ours (cancelled, or taken after our lease)
                return null;
            }
            Sent sent = job.channel() == Channel.SMS
                    ? new Sent(deliverSms(job, mine, people.get(job.userId())), List.of())
                    : deliverPush(job, mine, devicesByUser.getOrDefault(job.userId(), List.of()));
            jobs.record(List.of(sent.outcome()));
            devices.delete(sent.goneDevices());
            return sent;
        } catch (RuntimeException e) {
            // One bad message must not stop the rest; it becomes due again when its lease runs out
            log.warn("Sending notification {} failed: {}", job.id(), e.getClass().getSimpleName());
            return null;
        }
    }

    private Sent deliverPush(NotificationJob job, Instant lease, List<PushSubscription> targets) {
        String payload = payload(job);
        long giveUpAt = System.nanoTime() + MESSAGE_BUDGET.toNanos();
        int sent = 0;
        int failed = 0;
        List<UUID> gone = new ArrayList<>();
        for (PushSubscription device : targets.subList(0, Math.min(targets.size(), MAX_DEVICES))) {
            if (System.nanoTime() - giveUpAt > 0) {
                failed++;   // out of time for this message: the remaining devices are tried on the next try
                break;
            }
            switch (sender.send(device, payload)) {
                case SENT -> sent++;
                case GONE -> gone.add(device.id());
                case FAILED -> failed++;
            }
        }
        Instant now = now();
        Outcome outcome;
        if (sent > 0) {
            outcome = Outcome.finished(job.id(), JobStatus.SENT, now, lease);
        } else if (failed == 0) {
            // no devices, or every one had unsubscribed
            outcome = Outcome.finished(job.id(), JobStatus.NO_DEVICE, now, lease);
        } else {
            outcome = failedTry(job, now, lease);
        }
        return new Sent(outcome, gone);
    }

    /** Tried and failed: try again after the next backoff, or give up after the last. */
    private static Outcome failedTry(NotificationJob job, Instant now, Instant lease) {
        if (job.attempts() > BACKOFF.size()) {
            return Outcome.finished(job.id(), JobStatus.FAILED, now, lease);
        }
        return Outcome.retry(job.id(), now.plus(BACKOFF.get(job.attempts() - 1)), lease);
    }

    /**
     * One SMS from the type's template to the person's phone (its budget was checked for the whole batch). The
     * template is looked up again now: if it was removed from the settings in the meantime, the SMS is dropped
     * (NO_DEVICE).
     */
    private Outcome deliverSms(NotificationJob job, Instant lease, UserDirectory.Person person) {
        Optional<String> template = smsTemplates.forType(job.type());
        if (template.isEmpty() || person == null || person.phone() == null) {   // no phone: account deleted
            return Outcome.finished(job.id(), JobStatus.NO_DEVICE, now(), lease);
        }
        if (sms.send(person.phone(), template.get(), job.smsVariables())) {
            countSms(job);   // only SMS really sent count; refused ones and retries don't
            return Outcome.finished(job.id(), JobStatus.SENT, now(), lease);
        }
        return failedTry(job, now(), lease);
    }

    /**
     * Booking SMS cost money, so they have their own limits (separate from the sign-in codes' limit, so they can
     * never use up the budget people need to sign in):
     * <ul>
     *   <li>per customer: 10 a day ({@link #SMS_PER_PERSON_PER_DAY}) for the messages they get (BOOKING_*);</li>
     *   <li>per salon owner or barber: SMS_STAFF_PER_PERSON_DAILY_LIMIT (1000) a day for the partner messages
     *       (SALON_*, SHOP_*): a busy salon gets one per booking, hundreds a day;</li>
     *   <li>in total: SMS_BOOKING_DAILY_LIMIT a day.</li>
     * </ul>
     * Checked for the whole batch before any is sent, counting the batch's own SMS as it goes, so parallel sends of
     * one batch can never pass a limit together. Over a limit the SMS is dropped (FAILED, under the claim's lease);
     * the Web Push message still goes out. Only SMS actually sent are counted in Redis (countSms). Other copies of
     * the backend sending at the same moment can still pass a limit by a few: these are spending guards.
     */
    private Map<UUID, Outcome> overSmsBudget(List<NotificationJob> batch, Instant lease) {
        Map<UUID, Outcome> dropped = new HashMap<>();
        Map<String, Long> used = new HashMap<>();
        long dayUsed = -1;
        for (NotificationJob job : batch) {
            if (job.channel() != Channel.SMS) {
                continue;
            }
            String personKey = personKey(job);
            int personCap = isStaffMessage(job) ? staffSmsPerPerson : SMS_PER_PERSON_PER_DAY;
            long person = used.computeIfAbsent(personKey, limits::current);
            if (person >= personCap) {
                log.warn("Booking SMS for user {} over {} today: not sent", job.userId(), personCap);
                dropped.put(job.id(), Outcome.finished(job.id(), JobStatus.FAILED, now(), lease));
                continue;
            }
            if (dayUsed < 0) {
                dayUsed = limits.current(DAY_KEY);
            }
            if (dayUsed >= smsBookingDailyLimit) {
                if (limits.tryHit("sms-booking-day-alert", 1, Duration.ofHours(24))) {   // one alert a day
                    log.error("Daily booking-SMS limit ({}) reached: booking SMS paused. Check for abuse before "
                            + "raising SMS_BOOKING_DAILY_LIMIT.", smsBookingDailyLimit);
                }
                dropped.put(job.id(), Outcome.finished(job.id(), JobStatus.FAILED, now(), lease));
                continue;
            }
            used.put(personKey, person + 1);
            dayUsed++;
        }
        return dropped;
    }

    /** Messages to the salon's people (the partner app's types), not to customers. */
    static boolean isStaffMessage(NotificationJob job) {
        return Audience.PARTNER.typeGroups().contains(job.type().split("_", 2)[0]);
    }

    private static String personKey(NotificationJob job) {
        return (isStaffMessage(job) ? STAFF_KEY : PERSON_KEY) + job.userId();
    }

    private void countSms(NotificationJob job) {
        long today = limits.hit(DAY_KEY, Duration.ofHours(24));
        // The counter passes each number once per 24 hours, so this logs once a day; point an alert at it
        if (today == Math.max(1, smsBookingDailyLimit * 8L / 10)) {
            log.warn("80% of the daily booking-SMS limit used ({} of {}). At {} booking SMS pause until the day "
                    + "is over; raise SMS_BOOKING_DAILY_LIMIT if this is real traffic.", today, smsBookingDailyLimit,
                    smsBookingDailyLimit);
        }
        limits.hit(personKey(job), Duration.ofHours(24));
    }

    /** Cut to microseconds, what the database stores, so a lease read back compares equal. */
    private Instant now() {
        return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    /** What the app's service worker receives: {"type","title","body","url"}. */
    static String payload(NotificationJob job) {
        return "{\"type\":\"" + WebPushCrypto.json(job.type()) + "\",\"title\":\"" + WebPushCrypto.json(job.title())
                + "\",\"body\":\"" + WebPushCrypto.json(job.body()) + "\",\"url\":"
                + (job.url() == null ? "null" : "\"" + WebPushCrypto.json(job.url()) + "\"") + "}";
    }
}
