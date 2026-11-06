package in.apnidukan.notifications.service;

import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.notify.TextMessages;
import in.apnidukan.common.security.RateLimiter;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sends the messages that are due, as Web Push or SMS (each its own row), every couple of seconds.
 *
 * <p>One round <b>drains</b> the queue: it claims a batch (app.notifications.batch-size, 500), sends it, and claims
 * the next while batches come back full, so a burst (every 6 PM reminder at once) goes out in one round instead of
 * 100 messages every 30 seconds. Claiming is one short statement (NotificationStore.claimDue, FOR UPDATE SKIP
 * LOCKED); the sending happens after it, so no database connection waits on a push service.
 *
 * <p>A batch is sent <b>in parallel</b> on virtual threads, at most app.notifications.send-concurrency (32) at a
 * time: a send mostly waits on the push service or MSG91, so 32 waits overlap instead of queueing. Everything a
 * batch needs from the database is read once for the whole batch (every device in one query, every phone in one),
 * and the outcomes are written together at the end (NotificationStore.record, one JDBC batch), so the database sees
 * a handful of statements per batch plus one short lease renewal per message.
 *
 * <p><b>Several copies of the backend</b>: this job does not take a JobLeases lease. Every copy runs it, and SKIP
 * LOCKED gives each claim different rows, so more copies simply send more in parallel; giving up (giveUp) is one
 * idempotent UPDATE. Each message is still sent at most once: just before sending, its lease is renewed only if
 * it is still this copy's (NotificationStore.renew), and its outcome is recorded only under that lease.
 *
 * <p>A failed message is tried again after 1, 5 and 15 minutes, then marked FAILED. A batch that takes longer than
 * {@link #BATCH_DEADLINE} stops waiting: messages not sent by then become due again when their lease runs out.
 */
@Component
public class NotificationSendJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationSendJob.class);
    /**
     * How long a claimed message is this server's. Renewed for each message just before it is sent, so it only
     * has to cover the wait for its turn in the batch and one message (at most 10 devices × 15 s).
     */
    static final Duration LEASE = Duration.ofMinutes(5);
    /** Longest a round waits for one batch's sends; well inside LEASE, so outcomes are recorded under it. */
    static final Duration BATCH_DEADLINE = Duration.ofMinutes(3);
    /** Claimed this often without any outcome recorded (the server kept failing): given up as FAILED. */
    static final int MAX_ATTEMPTS = 8;
    /** Booking SMS one person can get in a day (a few bookings, moves and cancels); more is someone looping. */
    static final int SMS_PER_PERSON_PER_DAY = 10;
    private static final String DAY_KEY = "sms-booking-day";
    private static final String PERSON_KEY = "sms-booking-person:";
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
    private final int batchSize;
    private final ExecutorService senders;
    private final Clock clock;
    private final boolean enabled;

    public NotificationSendJob(NotificationStore jobs, PushSubscriptionStore devices, PushSender sender,
                               TextMessages sms, SmsTemplates smsTemplates, UserDirectory users, RateLimiter limits,
                               @Value("${app.sms.booking-daily-limit:100000}") int smsBookingDailyLimit,
                               @Value("${app.notifications.batch-size:500}") int batchSize,
                               @Value("${app.notifications.send-concurrency:32}") int concurrency,
                               Clock clock, @Value("${app.jobs.enabled:true}") boolean enabled) {
        if (batchSize < 1 || concurrency < 1) {
            throw new IllegalStateException("app.notifications.batch-size and send-concurrency must be at least 1");
        }
        this.jobs = jobs;
        this.devices = devices;
        this.sender = sender;
        this.sms = sms;
        this.smsTemplates = smsTemplates;
        this.users = users;
        this.limits = limits;
        this.smsBookingDailyLimit = smsBookingDailyLimit;
        this.batchSize = batchSize;
        // A fixed number of virtual threads: cheap to wait on the network, and never more sends at once than this
        this.senders = Executors.newFixedThreadPool(concurrency, Thread.ofVirtual().name("notify-send-", 0).factory());
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.notifications.send-delay-ms:2000}", initialDelay = 10_000)
    public void scheduled() {
        if (enabled) {
            runOnce();
        }
    }

    @PreDestroy
    void stop() {
        senders.shutdownNow();   // messages in flight become due again when their lease runs out
    }

    /** One round, draining every due message; returns how many it handled. Called directly by tests. */
    public int runOnce() {
        jobs.giveUp(MAX_ATTEMPTS, now());
        int handled = 0;
        while (true) {
            Instant now = now();
            List<NotificationJob> due = jobs.claimDue(now, batchSize, LEASE, MAX_ATTEMPTS);
            sendBatch(due, now.plus(LEASE));
            handled += due.size();
            if (due.size() < batchSize) {   // a full batch means there may be more waiting
                return handled;
            }
        }
    }

    /** What sending one message came to: its outcome (null if it was no longer ours) and devices that are gone. */
    private record Sent(Outcome outcome, List<UUID> goneDevices) {
    }

    private void sendBatch(List<NotificationJob> batch, Instant lease) {
        if (batch.isEmpty()) {
            return;
        }
        // Everything the batch needs, one query each
        Set<UUID> pushTo = new HashSet<>();
        Set<UUID> smsTo = new HashSet<>();
        for (NotificationJob job : batch) {
            (job.channel() == Channel.SMS ? smsTo : pushTo).add(job.userId());
        }
        Map<UUID, List<PushSubscription>> devicesByUser = devices.forUsers(pushTo);
        Map<UUID, UserDirectory.Person> people = smsTo.isEmpty() ? Map.of() : users.find(smsTo);

        List<Future<Sent>> running = new ArrayList<>(batch.size());
        for (NotificationJob job : batch) {
            running.add(senders.submit(() -> sendOne(job, lease, devicesByUser, people)));
        }
        long deadline = System.nanoTime() + BATCH_DEADLINE.toNanos();
        List<Outcome> outcomes = new ArrayList<>(batch.size());
        List<UUID> gone = new ArrayList<>();
        for (Future<Sent> f : running) {
            try {
                Sent sent = f.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (sent != null && sent.outcome() != null) {
                    outcomes.add(sent.outcome());
                }
                if (sent != null) {
                    gone.addAll(sent.goneDevices());
                }
            } catch (TimeoutException e) {
                f.cancel(true);   // not recorded: due again when its lease runs out
            } catch (ExecutionException e) {
                log.warn("Sending a notification failed: {}", e.getCause().getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        jobs.record(outcomes);
        devices.delete(gone);
    }

    /** Sends one message if it is still ours; null if not, or if it failed unexpectedly (due again later). */
    private Sent sendOne(NotificationJob job, Instant lease, Map<UUID, List<PushSubscription>> devicesByUser,
                         Map<UUID, UserDirectory.Person> people) {
        try {
            Instant mine = now().plus(LEASE);
            if (!jobs.renew(job.id(), lease, mine)) {   // no longer ours (cancelled, or taken after our lease)
                return null;
            }
            if (job.channel() == Channel.SMS) {
                return new Sent(deliverSms(job, mine, people.get(job.userId())), List.of());
            }
            return deliverPush(job, mine, devicesByUser.getOrDefault(job.userId(), List.of()));
        } catch (RuntimeException e) {
            // One bad message must not stop the rest; it becomes due again when its lease runs out
            log.warn("Sending notification {} failed: {}", job.id(), e.getClass().getSimpleName());
            return null;
        }
    }

    private Sent deliverPush(NotificationJob job, Instant lease, List<PushSubscription> targets) {
        String payload = payload(job);
        int sent = 0;
        int failed = 0;
        List<UUID> gone = new ArrayList<>();
        for (PushSubscription device : targets) {
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
     * One SMS from the type's template to the person's phone. The template is looked up again now: if it was
     * removed from the settings in the meantime, the SMS is dropped (NO_DEVICE).
     */
    private Outcome deliverSms(NotificationJob job, Instant lease, UserDirectory.Person person) {
        Optional<String> template = smsTemplates.forType(job.type());
        if (template.isEmpty() || person == null || person.phone() == null) {   // no phone: account deleted
            return Outcome.finished(job.id(), JobStatus.NO_DEVICE, now(), lease);
        }
        if (!withinSmsBudget(job.userId())) {
            return Outcome.finished(job.id(), JobStatus.FAILED, now(), lease);   // over budget: dropped for good
        }
        if (sms.send(person.phone(), template.get(), job.smsVariables())) {
            countSms(job.userId());   // only SMS really sent count; refused ones and retries don't
            return Outcome.finished(job.id(), JobStatus.SENT, now(), lease);
        }
        return failedTry(job, now(), lease);
    }

    /**
     * Booking SMS cost money, so they have their own limits (separate from the sign-in codes' limit, so they can
     * never use up the budget people need to sign in): 10 per person per day, and SMS_BOOKING_DAILY_LIMIT in total
     * per day. The person's cap is checked first, so one person can't use up everyone's budget; only SMS actually
     * sent are counted (countSms). Over a limit the SMS is dropped (FAILED); the Web Push message still goes out.
     * Sends running at the very same moment (up to send-concurrency of them) can pass a limit by that many: these
     * are spending guards, not exact counts. At 80% of the daily total a warning is logged once (see countSms).
     */
    private boolean withinSmsBudget(UUID userId) {
        if (limits.current(PERSON_KEY + userId) >= SMS_PER_PERSON_PER_DAY) {
            log.warn("Booking SMS for user {} over {} today: not sent", userId, SMS_PER_PERSON_PER_DAY);
            return false;
        }
        if (limits.current(DAY_KEY) >= smsBookingDailyLimit) {
            if (limits.tryHit("sms-booking-day-alert", 1, Duration.ofHours(24))) {   // one alert a day, not one per SMS
                log.error("Daily booking-SMS limit ({}) reached: booking SMS paused. Check for abuse before raising "
                        + "SMS_BOOKING_DAILY_LIMIT.", smsBookingDailyLimit);
            }
            return false;
        }
        return true;
    }

    private void countSms(UUID userId) {
        long today = limits.hit(DAY_KEY, Duration.ofHours(24));
        // The counter passes each number once per 24 hours, so this logs once a day; point an alert at it
        if (today == Math.max(1, smsBookingDailyLimit * 8L / 10)) {
            log.warn("80% of the daily booking-SMS limit used ({} of {}). At {} booking SMS pause until the day "
                    + "is over; raise SMS_BOOKING_DAILY_LIMIT if this is real traffic.", today, smsBookingDailyLimit,
                    smsBookingDailyLimit);
        }
        limits.hit(PERSON_KEY + userId, Duration.ofHours(24));
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
