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
 * <p>A batch is sent <b>in parallel</b> on virtual threads, at most app.notifications.send-concurrency (64) at a
 * time, and at most app.notifications.per-host-concurrency (32) to one push service or to MSG91, so one slow
 * provider can't take every slot and none is flooded: a send mostly waits on the network, so the waits overlap
 * instead of queueing. Everything a batch needs from the database is read once for the whole batch (every device in
 * one query, every phone in one). The sends themselves never touch the database: the batch's own thread renews the
 * leases of the next few messages in one statement just before handing them out (a quarter of the concurrency at a
 * time), and outcomes are written together, one statement per app.notifications.record-every (50) outcomes or every
 * quarter second, by one thread at a time. So the sender uses at most two connections however high the
 * concurrency, and web requests always find theirs. Outcomes are written within a quarter second of being known, and
 * every one is written before the round ends or the server stops: a crash loses at most that quarter second's.
 *
 * <p><b>Several copies of the backend</b>: this job does not take a JobLeases lease. Every copy runs it, and SKIP
 * LOCKED gives each claim different rows, so more copies simply send more in parallel; giving up (giveUp) is one
 * idempotent UPDATE. A message is sent at most once in normal operation: just before sending, its lease is renewed
 * only if it is still this copy's (NotificationStore.renewAll), and its outcome is recorded only under that lease.
 * A send that has started is never interrupted: each one is bounded (at most {@link #MAX_DEVICES} devices, no new
 * device after {@link #MESSAGE_BUDGET}, each device call with its own HTTP timeouts), well inside its lease, and
 * every outcome is recorded. A round claims only about what can be sent before app.notifications.batch-deadline-ms
 * (3 minutes) at the expected pace (app.notifications.expected-send-ms); messages of a batch not started by the
 * deadline, or when the server is stopping, are given back untried (their attempt is not counted) and the round
 * ends. A server that dies in the moment between a provider accepting a message and its outcome being written (at
 * most a quarter second, see above) can still send those messages twice.
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
    private final int parallel;
    /** Leases renewed in one statement, just before those messages are handed to the senders. */
    private final int renewGroup;
    private final int perHost;
    private final int recordEvery;
    /** The outcomes of batches being sent now (stop() writes what they still hold). */
    private final Set<Outcomes> open = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** At most perHost sends at once to each push service (by host) and to the SMS provider ("sms"). */
    private final Map<String, java.util.concurrent.Semaphore> hosts = new java.util.concurrent.ConcurrentHashMap<>();
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
                               @Value("${app.notifications.send-concurrency:64}") int concurrency,
                               @Value("${app.notifications.per-host-concurrency:32}") int perHost,
                               @Value("${app.notifications.record-every:50}") int recordEvery,
                               @Value("${app.notifications.batch-deadline-ms:180000}") long batchDeadlineMs,
                               @Value("${app.notifications.expected-send-ms:1000}") long expectedSendMs,
                               Clock clock, @Value("${app.jobs.enabled:true}") boolean enabled) {
        if (batchSize < 1 || concurrency < 1 || perHost < 1 || recordEvery < 1 || batchDeadlineMs < 1
                || expectedSendMs < 1) {
            throw new IllegalStateException("app.notifications.batch-size, send-concurrency, per-host-concurrency, "
                    + "record-every, batch-deadline-ms and expected-send-ms must be at least 1");
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
        this.parallel = concurrency;
        this.renewGroup = Math.max(1, concurrency / 4);
        this.perHost = perHost;
        this.recordEvery = recordEvery;
        this.claimSize = claimSize(batchSize, parallel, batchDeadlineMs, expectedSendMs);
        // A fixed number of virtual threads: cheap to wait on the network, and never more sends at once than this
        this.senders = Executors.newFixedThreadPool(parallel, Thread.ofVirtual().name("notify-send-", 0).factory());
        this.clock = clock;
        this.enabled = enabled;
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
    public void stop() throws InterruptedException {
        // No more claims; sends already running finish and record (never interrupted: the provider may have
        // accepted them); waiting ones are given back untried
        stopping = true;
        senders.shutdown();
        if (!senders.awaitTermination(MESSAGE_BUDGET.toSeconds() + 20, TimeUnit.SECONDS)) {
            log.warn("Notification sends still running at shutdown");
        }
        // Then every outcome still waiting is written here, whatever the batch's own thread is doing (the scheduler
        // may be stopping it). This bean is destroyed before the store and the DataSource it uses, so they still work
        for (Outcomes o : open) {
            o.writeAll();
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

    /** What sending one message came to: its outcome and devices that are gone. */
    private record Sent(Outcome outcome, List<UUID> goneDevices) {
    }

    /** How often outcomes waiting to be written are written anyway (fewer than record-every of them). */
    static final Duration RECORD_AT_LEAST_EVERY = Duration.ofMillis(250);

    /**
     * Outcomes waiting to be written, from every sender thread. Written in one statement each time record-every of
     * them are waiting (by the thread that added the last one, unless another is already writing: it picks them up)
     * and by the batch's thread at least every RECORD_AT_LEAST_EVERY and at its end. One writer at a time.
     */
    private final class Outcomes {
        /** When outcomes were last written (System.nanoTime). */
        private volatile long lastWrite = System.nanoTime();
        private final java.util.concurrent.locks.ReentrantLock adding = new java.util.concurrent.locks.ReentrantLock();
        private final java.util.concurrent.locks.ReentrantLock writing = new java.util.concurrent.locks.ReentrantLock();
        private List<Outcome> waiting = new ArrayList<>();
        private List<UUID> gone = new ArrayList<>();

        void add(Sent sent) {
            int count;
            adding.lock();
            try {
                waiting.add(sent.outcome());
                gone.addAll(sent.goneDevices());
                count = waiting.size();
            } finally {
                adding.unlock();
            }
            if (count >= recordEvery && writing.tryLock()) {
                try {
                    write();
                } finally {
                    writing.unlock();
                }
            }
        }

        /** writeAll() if the last write was RECORD_AT_LEAST_EVERY ago or more. */
        void writeIfDue() {
            if (System.nanoTime() - lastWrite >= RECORD_AT_LEAST_EVERY.toNanos()) {
                writeAll();
            }
        }

        /** Writes everything waiting now (waits for a write in progress first). */
        void writeAll() {
            writing.lock();
            try {
                write();
            } finally {
                writing.unlock();
            }
        }

        private void write() {
            lastWrite = System.nanoTime();
            while (true) {
                List<Outcome> outcomes;
                List<UUID> devicesGone;
                adding.lock();
                try {
                    outcomes = waiting;
                    devicesGone = gone;
                    waiting = new ArrayList<>();
                    gone = new ArrayList<>();
                } finally {
                    adding.unlock();
                }
                if (outcomes.isEmpty() && devicesGone.isEmpty()) {
                    return;
                }
                try {
                    for (int i = 0; i < outcomes.size(); i += recordEvery) {
                        jobs.record(outcomes.subList(i, Math.min(outcomes.size(), i + recordEvery)));
                    }
                    devices.delete(devicesGone);
                } catch (RuntimeException e) {
                    // Not written: those messages become due again when their lease runs out (sent again then)
                    log.warn("Recording {} notification outcomes failed: {}", outcomes.size(),
                            e.getClass().getSimpleName());
                }
            }
        }
    }

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

        List<NotificationJob> toSend = batch.stream().filter(job -> !overBudget.containsKey(job.id())).toList();
        Outcomes outcomes = new Outcomes();
        open.add(outcomes);
        java.util.concurrent.Semaphore slots = new java.util.concurrent.Semaphore(parallel);
        List<Future<?>> running = new ArrayList<>(toSend.size());
        int next = 0;
        int givenBack = 0;
        try {
            while (next < toSend.size()) {
                // A free slot (writing what is waiting while none is), then as many more as are free, up to a group
                int free = waitForSlot(slots, deadline, outcomes);
                if (free == 0) {
                    break;   // the deadline passed, or the server is stopping: the rest go back untried
                }
                int more = Math.min(renewGroup, toSend.size() - next) - 1;
                while (more > 0 && slots.tryAcquire()) {
                    free++;
                    more--;
                }
                List<NotificationJob> group = toSend.subList(next, Math.min(toSend.size(), next + free));
                next += group.size();
                slots.release(free - group.size());
                // Still ours (not cancelled, not taken after our lease ran out): one statement for the group
                Instant mine = now().plus(LEASE);
                Set<UUID> ours = jobs.renewAll(group.stream().map(NotificationJob::id).toList(), lease, mine);
                for (NotificationJob job : group) {
                    if (!ours.contains(job.id())) {
                        slots.release();
                        continue;
                    }
                    try {
                        running.add(senders.submit(() -> {
                            try {
                                Sent sent = sendOne(job, mine, devicesByUser, people);
                                if (sent != null) {
                                    outcomes.add(sent);
                                }
                            } finally {
                                slots.release();
                            }
                        }));
                    } catch (RejectedExecutionException stopped) {
                        slots.release();
                        jobs.releaseAll(List.of(job.id()), mine, now());
                        givenBack++;
                    }
                }
            }
            if (next < toSend.size()) {
                List<UUID> rest = toSend.subList(next, toSend.size()).stream().map(NotificationJob::id).toList();
                jobs.releaseAll(rest, lease, now());   // their attempt isn't counted; due again now
                givenBack += rest.size();
            }
        } catch (RuntimeException e) {
            // The database didn't answer a renewal or a release: the messages not handed out stay leased and are due
            // again when their lease runs out; the sends already running finish and are written below
            log.warn("Handing out a notification batch failed: {}", e.getClass().getSimpleName());
            givenBack = Math.max(1, toSend.size() - next);   // not handled; at least 1, so the round stops here
        } finally {
            // Each send is bounded (see the class comment), so this can't hang; meanwhile what came back is written
            // at least every RECORD_AT_LEAST_EVERY
            waiting:
            for (Future<?> f : running) {
                while (true) {
                    try {
                        f.get(RECORD_AT_LEAST_EVERY.toMillis(), TimeUnit.MILLISECONDS);
                        break;
                    } catch (java.util.concurrent.TimeoutException stillSending) {
                        outcomes.writeIfDue();
                    } catch (ExecutionException e) {
                        log.warn("Sending a notification failed: {}", e.getCause().getClass().getSimpleName());
                        break;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break waiting;
                    }
                }
            }
            boolean allBack = running.stream().allMatch(Future::isDone);
            outcomes.writeAll();   // every outcome is written before the round goes on (or the server stops)
            if (allBack) {
                open.remove(outcomes);
            }
            // Otherwise this thread was stopped while sends still run: stop() writes what they bring back
        }
        return givenBack;
    }

    /**
     * Waits for one free send slot, writing the waiting outcomes every RECORD_AT_LEAST_EVERY meanwhile; returns 1, or
     * 0 if the batch's deadline passed or the server is stopping first.
     */
    private int waitForSlot(java.util.concurrent.Semaphore slots, long deadline, Outcomes outcomes) {
        while (!stopping && System.nanoTime() - deadline < 0) {
            try {
                boolean got = slots.tryAcquire(RECORD_AT_LEAST_EVERY.toMillis(), TimeUnit.MILLISECONDS);
                outcomes.writeIfDue();   // also while slots keep coming free at once
                if (got) {
                    return stopping || System.nanoTime() - deadline >= 0 ? releaseAndStop(slots) : 1;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 0;
            }
            outcomes.writeAll();
        }
        return 0;
    }

    private static int releaseAndStop(java.util.concurrent.Semaphore slots) {
        slots.release();
        return 0;
    }

    /**
     * Sends one message whose lease was just renewed ({@code lease}); its outcome is written by the batch (Outcomes).
     * Null if it failed unexpectedly (due again when its lease runs out).
     */
    private Sent sendOne(NotificationJob job, Instant lease, Map<UUID, List<PushSubscription>> devicesByUser,
                         Map<UUID, UserDirectory.Person> people) {
        try {
            return job.channel() == Channel.SMS
                    ? new Sent(deliverSms(job, lease, people.get(job.userId())), List.of())
                    : deliverPush(job, lease, devicesByUser.getOrDefault(job.userId(), List.of()));
        } catch (RuntimeException e) {
            // One bad message must not stop the rest; it becomes due again when its lease runs out
            log.warn("Sending notification {} failed: {}", job.id(), e.getClass().getSimpleName());
            return null;
        }
    }

    /** Runs a call to one provider while holding one of its perHost slots (waiting for one if all are taken). */
    private <T> T atHost(String host, java.util.function.Supplier<T> call) {
        java.util.concurrent.Semaphore slot = hosts.computeIfAbsent(host,
                h -> new java.util.concurrent.Semaphore(perHost));
        slot.acquireUninterruptibly();
        try {
            return call.get();
        } finally {
            slot.release();
        }
    }

    /** The push service a device's address points at (its host), for the per-host limit. */
    static String hostOf(String endpoint) {
        try {
            String host = java.net.URI.create(endpoint).getHost();
            return host == null ? "push" : host;
        } catch (IllegalArgumentException e) {
            return "push";
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
            switch (atHost(hostOf(device.endpoint()), () -> sender.send(device, payload))) {
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
        if (atHost("sms", () -> sms.send(person.phone(), template.get(), job.smsVariables()))) {
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
