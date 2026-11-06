package in.apnidukan.notifications.service;

import in.apnidukan.common.jobs.JobLeases;
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
 * Every minute: empties the messages of deleted accounts (queued in account_wipes by NotificationAccountDeletion),
 * {@code slice} (1,000) rows per statement, walking each person's rows in id order. A salon owner with lakhs of
 * messages is emptied over a few short statements instead of one huge update inside the delete request. At most
 * {@code perRun} (100,000) rows a minute, so a run stays short; whoever isn't finished is continued next minute
 * (from the start of their rows: the already-empty ones are only read, not written again).
 * Under a job lease, so one copy of the backend does it.
 */
@Component
public class AccountWipeJob {

    private static final Logger log = LoggerFactory.getLogger(AccountWipeJob.class);
    static final String JOB = "account-wipes";
    static final Duration MAX_RUN = Duration.ofMinutes(5);

    private final NotificationStore jobs;
    private final JobLeases leases;
    private final int slice;
    private final int perRun;
    private final boolean enabled;

    public AccountWipeJob(NotificationStore jobs, JobLeases leases,
                          @Value("${app.jobs.account-wipe-slice:1000}") int slice,
                          @Value("${app.jobs.account-wipe-per-run:100000}") int perRun,
                          @Value("${app.jobs.enabled:true}") boolean enabled) {
        if (slice < 1 || perRun < slice) {
            throw new IllegalStateException("app.jobs.account-wipe-slice must be at least 1, and per-run at least "
                    + "one slice");
        }
        this.jobs = jobs;
        this.leases = leases;
        this.slice = slice;
        this.perRun = perRun;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
    public void scheduled() {
        if (enabled) {
            leases.runExclusively(JOB, MAX_RUN, this::runOnce);
        }
    }

    /** Called by tests too; returns how many people were finished. */
    public int runOnce() {
        int rows = 0;
        int finished = 0;
        for (UUID person : jobs.wipesDue(20)) {
            UUID after = NotificationStore.FIRST;
            boolean done = false;
            while (rows < perRun) {
                Optional<UUID> last = jobs.wipeSlice(person, after, slice);
                rows += slice;
                if (last.isEmpty()) {
                    done = true;
                    break;
                }
                after = last.get();
            }
            if (!done) {
                break;   // this minute's share is used; this person continues next minute
            }
            jobs.wipeDone(person);
            finished++;
        }
        if (finished > 0) {
            log.info("Emptied the messages of {} deleted accounts", finished);
        }
        return finished;
    }
}
