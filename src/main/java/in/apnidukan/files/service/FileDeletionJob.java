package in.apnidukan.files.service;

import in.apnidukan.common.jobs.JobLeases;
import in.apnidukan.files.repo.FileDeletionStore;
import in.apnidukan.files.repo.FileDeletionStore.Pending;
import in.apnidukan.files.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Every 5 minutes: deletes from R2 the objects still queued in file_deletions (the delete right after a file's row went
 * failed, or the server stopped before it). {@code SLICE} keys at a time, at most {@code MAX_PER_RUN} a run, on one
 * server at a time (JobLeases). A key that fails waits longer each time: 1, 2, 4 ... minutes, at most 6 hours, and is
 * tried again for good (R2 may be down for a day). From {@code LOUD_AFTER} failures on, every failure is logged as an
 * ERROR with its key, so an alert can point someone at it.
 */
@Component
public class FileDeletionJob {

    private static final Logger log = LoggerFactory.getLogger(FileDeletionJob.class);
    static final String JOB = "file-deletions";
    static final Duration MAX_RUN = Duration.ofMinutes(10);
    static final int SLICE = 100;
    static final int MAX_PER_RUN = 2_000;
    static final int LOUD_AFTER = 5;
    static final Duration LONGEST_WAIT = Duration.ofHours(6);
    /** Time kept back from MAX_RUN: more than one R2 call can take (R2_CALL_TIMEOUT, 15 s). */
    static final Duration BUDGET_MARGIN = Duration.ofMinutes(1);

    private final FileDeletionStore queue;
    private final FileStorage storage;
    private final JobLeases leases;
    private final Clock clock;
    private final boolean enabled;

    public FileDeletionJob(FileDeletionStore queue, FileStorage storage, JobLeases leases, Clock clock,
                           @Value("${app.jobs.enabled:true}") boolean enabled) {
        this.queue = queue;
        this.storage = storage;
        this.leases = leases;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void scheduled() {
        if (enabled) {
            leases.runExclusively(JOB, MAX_RUN, this::runOnce);
        }
    }

    /** One round; returns how many objects were deleted. Called by tests too. */
    public int runOnce() {
        return runOnce(MAX_RUN.minus(BUDGET_MARGIN));
    }

    /**
     * One round that stops starting new deletes once {@code budget} has passed (one R2 call takes at most 15 s), so it
     * always ends before its lease (MAX_RUN) and another server never runs the same keys alongside it.
     */
    int runOnce(Duration budget) {
        Instant stopAt = clock.instant().plus(budget);
        int deleted = 0;
        int tried = 0;
        rounds:
        while (tried < MAX_PER_RUN) {
            Instant now = clock.instant();
            List<Pending> due = queue.due(now, SLICE);
            for (Pending p : due) {
                if (!clock.instant().isBefore(stopAt)) {
                    log.info("File deletions stopped for this round after {} tries (time is up); the rest next round",
                            tried);
                    break rounds;
                }
                tried++;
                try {
                    storage.delete(p.storageKey());   // deleting a key that is already gone counts as done
                    queue.done(p.storageKey());
                    deleted++;
                } catch (RuntimeException e) {
                    int failures = p.attempts() + 1;
                    queue.failed(p.storageKey(), now.plus(wait(failures)));
                    if (failures >= LOUD_AFTER) {
                        log.error("R2 still refuses to delete {} after {} tries: {}", p.storageKey(), failures,
                                e.getClass().getSimpleName());
                    } else {
                        log.warn("Could not delete {} from R2 (try {}), trying again later: {}", p.storageKey(),
                                failures, e.getClass().getSimpleName());
                    }
                }
            }
            if (due.size() < SLICE) {
                break;   // a failed key was moved later, so a full slice always means more may be due
            }
        }
        if (deleted > 0) {
            log.info("Deleted {} queued objects from R2", deleted);
        }
        return deleted;
    }

    /** How long to wait after this many failures: 1, 2, 4 ... minutes, at most LONGEST_WAIT. */
    static Duration wait(int failures) {
        Duration wait = Duration.ofMinutes(1L << Math.min(failures - 1, 20));
        return wait.compareTo(LONGEST_WAIT) > 0 ? LONGEST_WAIT : wait;
    }
}
