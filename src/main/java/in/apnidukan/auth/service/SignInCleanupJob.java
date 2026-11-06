package in.apnidukan.auth.service;

import in.apnidukan.auth.repo.SignInCleanupStore;
import in.apnidukan.common.jobs.JobLeases;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Once a day (03:30 in India): deletes ended sign-ins. Every refresh ends one token (ROTATED) and adds a new one, so
 * this table grows fastest of all: with lakhs of people refreshing several times a day, millions of rows a day.
 *
 * <ul>
 *   <li>Expired, or signed out (one device, everywhere, a detected copy): deleted {@code keepDays} (30) after.</li>
 *   <li>Rotated: deleted {@code rotatedKeepDays} (2) after rotation, once the token that replaced it is no longer in
 *       use. What a rotated token is kept for: coming back within AuthService.RETRY_GRACE (60 s) it is the app
 *       retrying; later, it means it was copied, and every session ends. That alarm matters while the copy's
 *       session can still be alive, which is exactly while the successor is in use (the thief refreshed first, so
 *       the owner's app later presents the old token), so such a token stays until then, however old. Once the
 *       successor has ended, the old token can no longer reveal a session to end and goes after the short wait.</li>
 * </ul>
 * Deleted in slices of {@code batch} rows (SignInCleanupStore), each its own transaction, under a job lease
 * (JobLeases) so only one copy of the backend runs it.
 */
@Component
public class SignInCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(SignInCleanupJob.class);
    static final String JOB = "sign-in-cleanup";
    /** Longest a round may hold the lease; another copy can take over after this if this one died mid-round. */
    static final Duration MAX_RUN = Duration.ofHours(2);

    private final SignInCleanupStore store;
    private final JobLeases leases;
    private final Clock clock;
    private final Duration keep;
    private final Duration rotatedKeep;
    private final int batch;
    private final boolean enabled;

    public SignInCleanupJob(SignInCleanupStore store, JobLeases leases, Clock clock,
                            @Value("${app.auth.ended-sign-ins-keep-days:30}") int keepDays,
                            @Value("${app.auth.rotated-keep-days:2}") int rotatedKeepDays,
                            @Value("${app.cleanup.batch:10000}") int batch,
                            @Value("${app.jobs.enabled:true}") boolean enabled) {
        if (rotatedKeepDays < 1 || keepDays < 1 || batch < 1) {
            // Under a day would come close to RETRY_GRACE and to tokens just being swapped
            throw new IllegalStateException("app.auth.rotated-keep-days, app.auth.ended-sign-ins-keep-days and "
                    + "app.cleanup.batch must be at least 1");
        }
        this.store = store;
        this.leases = leases;
        this.clock = clock;
        this.keep = Duration.ofDays(keepDays);
        this.rotatedKeep = Duration.ofDays(rotatedKeepDays);
        this.batch = batch;
        this.enabled = enabled;
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Kolkata")
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
        Instant before = now.minus(keep);
        int expired = 0;
        int n;
        do {   // a full slice means there may be more
            n = store.deleteExpired(before, batch);
            expired += n;
        } while (n == batch);
        int revoked = 0;
        do {
            n = store.deleteRevoked(before, batch);
            revoked += n;
        } while (n == batch);
        int rotated = 0;
        Instant after = Instant.EPOCH;
        SignInCleanupStore.RotatedSlice slice;
        do {
            slice = store.deleteRotated(after, now.minus(rotatedKeep), now, batch);
            rotated += slice.deleted();
            after = slice.lastAt();
        } while (slice.read() == batch);
        log.info("Deleted {} expired, {} signed-out and {} rotated sign-ins", expired, revoked, rotated);
        return expired + revoked + rotated;
    }
}
