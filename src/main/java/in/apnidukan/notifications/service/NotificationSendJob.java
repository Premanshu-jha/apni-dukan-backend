package in.apnidukan.notifications.service;

import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.notify.TextMessages;
import in.apnidukan.common.security.RateLimiter;
import in.apnidukan.notifications.domain.Channel;
import in.apnidukan.notifications.domain.JobStatus;
import in.apnidukan.notifications.domain.NotificationJob;
import in.apnidukan.notifications.domain.PushSubscription;
import in.apnidukan.notifications.repo.NotificationStore;
import in.apnidukan.notifications.repo.PushSubscriptionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every 30 seconds: sends the messages that are due, as Web Push or SMS (each its own row). Claiming them is one short transaction (see
 * NotificationStore.claimDue); the sending happens after it, so no database connection waits on a push service.
 * A failed message is tried again after 1, 5 and 15 minutes, then marked FAILED.
 */
@Component
public class NotificationSendJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationSendJob.class);
    static final int BATCH = 100;
    /**
     * How long a claimed message is this server's. Renewed for each message just before it is sent, so it only
     * has to cover one message (at most 10 devices × 15 s); if this server stops, others pick it up after this.
     */
    static final Duration LEASE = Duration.ofMinutes(5);
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
    private final TransactionTemplate tx;
    private final Clock clock;
    private final boolean enabled;

    public NotificationSendJob(NotificationStore jobs, PushSubscriptionStore devices, PushSender sender,
                               TextMessages sms, SmsTemplates smsTemplates, UserDirectory users, RateLimiter limits,
                               @Value("${app.sms.booking-daily-limit:300}") int smsBookingDailyLimit,
                               PlatformTransactionManager transactions, Clock clock,
                               @Value("${app.jobs.enabled:true}") boolean enabled) {
        this.jobs = jobs;
        this.devices = devices;
        this.sender = sender;
        this.sms = sms;
        this.smsTemplates = smsTemplates;
        this.users = users;
        this.limits = limits;
        this.smsBookingDailyLimit = smsBookingDailyLimit;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void scheduled() {
        if (enabled) {
            runOnce();
        }
    }

    /** One round; returns how many messages it handled. Called directly by tests. */
    public int runOnce() {
        Instant now = now();
        Instant lease = now.plus(LEASE);
        List<NotificationJob> due = tx.execute(status -> {
            jobs.giveUp(MAX_ATTEMPTS, now);
            return jobs.claimDue(now, BATCH, LEASE, MAX_ATTEMPTS);
        });
        if (due == null) {
            return 0;
        }
        for (NotificationJob job : due) {
            try {
                Instant mine = now().plus(LEASE);
                if (jobs.renew(job.id(), lease, mine)) {   // still ours: nobody else will send it now
                    deliver(job, mine);
                }
            } catch (RuntimeException e) {
                // One bad message must not stop the rest; it becomes due again when its lease runs out
                log.warn("Sending notification {} failed: {}", job.id(), e.getClass().getSimpleName());
            }
        }
        return due.size();
    }

    private void deliver(NotificationJob job, Instant lease) {
        if (job.channel() == Channel.SMS) {
            deliverSms(job, lease);
            return;
        }
        List<PushSubscription> targets = devices.forUser(job.userId());
        String payload = payload(job);
        int sent = 0;
        int failed = 0;
        for (PushSubscription device : targets) {
            switch (sender.send(device, payload)) {
                case SENT -> sent++;
                case GONE -> devices.delete(device.id());
                case FAILED -> failed++;
            }
        }
        Instant now = now();
        if (sent > 0) {
            jobs.finish(job.id(), JobStatus.SENT, now, lease);
        } else if (failed == 0) {
            jobs.finish(job.id(), JobStatus.NO_DEVICE, now, lease);   // no devices, or every one had unsubscribed
        } else if (job.attempts() > BACKOFF.size()) {
            jobs.finish(job.id(), JobStatus.FAILED, now, lease);
        } else {
            jobs.retryAt(job.id(), now.plus(BACKOFF.get(job.attempts() - 1)), lease);
        }
    }

    /**
     * One SMS from the type's template to the person's phone. The template is looked up again now: if it was
     * removed from the settings in the meantime, the SMS is dropped (NO_DEVICE).
     */
    private void deliverSms(NotificationJob job, Instant lease) {
        Optional<String> template = smsTemplates.forType(job.type());
        UserDirectory.Person person = users.find(List.of(job.userId())).get(job.userId());
        Instant now = now();
        if (template.isEmpty() || person == null || person.phone() == null) {   // no phone: account deleted
            jobs.finish(job.id(), JobStatus.NO_DEVICE, now, lease);
        } else if (!withinSmsBudget(job.userId())) {
            jobs.finish(job.id(), JobStatus.FAILED, now, lease);   // over budget: dropped, never sent later
        } else if (sms.send(person.phone(), template.get(), job.smsVariables())) {
            countSms(job.userId());   // only SMS really sent count; refused ones and retries don't
            jobs.finish(job.id(), JobStatus.SENT, now, lease);
        } else if (job.attempts() > BACKOFF.size()) {
            jobs.finish(job.id(), JobStatus.FAILED, now, lease);
        } else {
            jobs.retryAt(job.id(), now.plus(BACKOFF.get(job.attempts() - 1)), lease);
        }
    }

    /**
     * Booking SMS cost money, so they have their own limits (separate from the sign-in codes' limit, so they can
     * never use up the budget people need to sign in): 10 per person per day, and SMS_BOOKING_DAILY_LIMIT in total
     * per day. The person's cap is checked first, so one person can't use up everyone's budget; only SMS actually
     * sent are counted (countSms). Over a limit the SMS is dropped (FAILED); the Web Push message still goes out.
     * Two sends at the very same moment can pass a limit by one or two: these are spending guards, not exact counts.
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
        limits.hit(DAY_KEY, Duration.ofHours(24));
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
