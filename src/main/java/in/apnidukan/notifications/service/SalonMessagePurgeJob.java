package in.apnidukan.notifications.service;

import in.apnidukan.common.jobs.JobLeases;
import in.apnidukan.common.jobs.LongStatements;
import in.apnidukan.notifications.repo.NotificationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Every minute: deletes the salon messages of people who left a salon (queued in salon_message_purges by
 * NotificationService.forgetSalonMessages; their lists stopped showing them at that moment), walking each person's
 * messages in id order, {@code slice} (1,000) rows per statement. A salon owner closing a salon with lakhs of messages
 * is done over a few short statements instead of one huge delete inside the close. At most {@code perRun} (100,000)
 * rows a minute; whoever isn't finished continues next minute from where it stopped (after_id). Like AccountWipeJob,
 * and under a job lease, so one copy of the backend does it.
 */
@Component
public class SalonMessagePurgeJob {

    private static final Logger log = LoggerFactory.getLogger(SalonMessagePurgeJob.class);
    static final String JOB = "salon-message-purges";
    static final Duration MAX_RUN = Duration.ofMinutes(5);

    private final NotificationStore jobs;
    private final JobLeases leases;
    private final LongStatements statements;
    private final int slice;
    private final int perRun;
    private final boolean enabled;

    public SalonMessagePurgeJob(NotificationStore jobs, JobLeases leases, LongStatements statements,
                                @Value("${app.jobs.account-wipe-slice:1000}") int slice,
                                @Value("${app.jobs.account-wipe-per-run:100000}") int perRun,
                                @Value("${app.jobs.enabled:true}") boolean enabled) {
        if (slice < 1 || perRun < slice) {
            throw new IllegalStateException("app.jobs.account-wipe-slice must be at least 1, and per-run at least "
                    + "one slice");
        }
        this.jobs = jobs;
        this.leases = leases;
        this.statements = statements;
        this.slice = slice;
        this.perRun = perRun;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 50_000)
    public void scheduled() {
        if (enabled) {
            leases.runExclusively(JOB, MAX_RUN, this::runOnce);
        }
    }

    /** Called by tests too; returns how many people were finished. */
    public int runOnce() {
        int rows = 0;
        int finished = 0;
        for (NotificationStore.Purge purge : jobs.purgesDue(20)) {
            UUID person = purge.userId();
            UUID after = purge.afterId();
            boolean done = false;
            while (rows < perRun) {
                UUID from = after;
                Optional<UUID> last = statements.run(() -> jobs.purgeSlice(person, purge.upTo(), from, slice));
                rows += slice;
                if (last.isEmpty()) {
                    done = true;
                    break;
                }
                after = last.get();
                jobs.purgeProgress(person, purge.upTo(), after);   // a run that stops here carries on from this id
            }
            if (!done) {
                break;   // this minute's share is used; this person continues next minute, from `after`
            }
            if (statements.run(() -> jobs.purgeDone(person, purge.upTo()))) {   // reads their rows once
                finished++;
            }
        }
        if (finished > 0) {
            log.info("Deleted the salon messages of {} people who left a salon", finished);
        }
        return finished;
    }
}
