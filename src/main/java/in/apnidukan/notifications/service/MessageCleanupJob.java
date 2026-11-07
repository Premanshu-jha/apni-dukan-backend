package in.apnidukan.notifications.service;

import in.apnidukan.common.jobs.JobLeases;
import in.apnidukan.common.jobs.LongStatements;
import in.apnidukan.notifications.repo.NotificationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Once a day (03:40 in India): deletes messages that finished (sent, failed, cancelled, no device):
 * <ul>
 *   <li>SMS rows {@code smsKeepDays} (7) after they finished: they only exist to be sent, the app's list shows the
 *       Web Push row of the same message;</li>
 *   <li>every other row {@code keepDays} (90) after: the app's list shows them that long.</li>
 * </ul>
 * Bookings and their history (appointment_events) are kept; a message is only a copy of what was said about them.
 *
 * At about 10 million rows a day, one DELETE of a whole day would hold a huge transaction and lock for minutes; so
 * rows go in slices of {@code batch} (10,000), each its own short statement, until a slice comes back short.
 * The round runs under a job lease (JobLeases), so only one copy of the backend does it.
 */
@Component
public class MessageCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(MessageCleanupJob.class);
    static final String JOB = "message-cleanup";
    /** Longest a round may hold the lease; another copy can take over after this if this one died mid-round. */
    static final Duration MAX_RUN = Duration.ofHours(2);

    private final NotificationStore jobs;
    private final JobLeases leases;
    private final LongStatements statements;
    private final Clock clock;
    private final Duration keep;
    private final Duration smsKeep;
    private final int batch;
    private final boolean enabled;

    public MessageCleanupJob(NotificationStore jobs, JobLeases leases, LongStatements statements, Clock clock,
                             @Value("${app.notifications.keep-days:90}") int keepDays,
                             @Value("${app.notifications.sms-keep-days:7}") int smsKeepDays,
                             @Value("${app.cleanup.batch:10000}") int batch,
                             @Value("${app.jobs.enabled:true}") boolean enabled) {
        if (keepDays < 1 || smsKeepDays < 1 || batch < 1) {
            throw new IllegalStateException("app.notifications.keep-days, sms-keep-days and app.cleanup.batch must "
                    + "be at least 1");
        }
        this.jobs = jobs;
        this.leases = leases;
        this.statements = statements;
        this.clock = clock;
        this.keep = Duration.ofDays(keepDays);
        this.smsKeep = Duration.ofDays(smsKeepDays);
        this.batch = batch;
        this.enabled = enabled;
    }

    @Scheduled(cron = "0 40 3 * * *", zone = "Asia/Kolkata")
    public void scheduled() {
        if (enabled) {
            runUnderLease();
        }
    }

    /** One round if no other copy is running one; false if it was skipped. */
    public boolean runUnderLease() {
        return leases.runExclusively(JOB, MAX_RUN, this::runOnce);
    }

    /** Called by tests too; returns how many rows went. */
    public int runOnce() {
        Instant now = Instant.now(clock);
        int sms = 0;
        int n;
        do {   // a full slice means there may be more
            n = statements.run(() -> jobs.deleteFinishedSmsBefore(now.minus(smsKeep), batch));   // up to 60 s
            sms += n;
        } while (n == batch);
        int other = 0;
        do {
            n = statements.run(() -> jobs.deleteFinishedBefore(now.minus(keep), batch));
            other += n;
        } while (n == batch);
        log.info("Deleted {} SMS rows finished more than {} days ago and {} messages finished more than {} days ago",
                sms, smsKeep.toDays(), other, keep.toDays());
        return sms + other;
    }
}
