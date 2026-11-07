package in.apnidukan.auth.service;

import in.apnidukan.auth.repo.SignInCleanupStore;
import in.apnidukan.common.jobs.JobLeases;
import in.apnidukan.common.jobs.LongStatements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Once a day (03:30 in India): deletes sign-ins that ended (expired, signed out, rotated) more than {@code keepDays}
 * (30) days ago. Every refresh ends one token (ROTATED) and adds a new one, so this table grows fastest of all: with
 * lakhs of people refreshing several times a day, millions of rows a day.
 *
 * Rotated tokens are kept the whole 30 days (the refresh lifetime) on purpose: one coming back later than
 * AuthService.RETRY_GRACE means it was copied, and every session ends. A thief who refreshed first can keep
 * refreshing along a chain of tokens (r1 → r2 → r3 …); the owner's phone then presents r1, possibly days later, and
 * only finding r1 raises the alarm. 30 days covers every chain a phone can still present (its token expires then).
 *
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
    private final LongStatements statements;
    private final Clock clock;
    private final Duration keep;
    private final int batch;
    private final boolean enabled;

    public SignInCleanupJob(SignInCleanupStore store, JobLeases leases, LongStatements statements, Clock clock,
                            @Value("${app.auth.ended-sign-ins-keep-days:30}") int keepDays,
                            @Value("${app.cleanup.batch:10000}") int batch,
                            @Value("${app.jobs.enabled:true}") boolean enabled) {
        if (keepDays < 1 || batch < 1) {
            throw new IllegalStateException("app.auth.ended-sign-ins-keep-days and app.cleanup.batch must be at "
                    + "least 1");
        }
        this.store = store;
        this.leases = leases;
        this.statements = statements;
        this.clock = clock;
        this.keep = Duration.ofDays(keepDays);
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
        Instant before = Instant.now(clock).minus(keep);
        int expired = 0;
        int n;
        do {   // a full slice means there may be more
            n = statements.run(() -> store.deleteExpired(before, batch));   // up to 60 s, not a request's 5
            expired += n;
        } while (n == batch);
        int revoked = 0;
        do {
            n = statements.run(() -> store.deleteRevoked(before, batch));
            revoked += n;
        } while (n == batch);
        log.info("Deleted {} expired and {} ended sign-ins (more than {} days ago)", expired, revoked, keep.toDays());
        return expired + revoked;
    }
}
