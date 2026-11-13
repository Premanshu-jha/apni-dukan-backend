package in.apnidukan.helpdesk.service;

import in.apnidukan.common.files.FileRefs;
import in.apnidukan.common.jobs.JobLeases;
import in.apnidukan.helpdesk.repo.TicketStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Every hour: the photos, videos and PDFs of tickets resolved more than {@link TicketService#FILES_KEPT} ago are
 * deleted (their words stay; "removed" shows in their place). They may show faces, bills and addresses, and nobody
 * needs them by then. {@value #SLICE} at a time, each slice in its own transaction, on one server at a time. A ticket
 * opened again before then keeps its files (it is no longer resolved).
 */
@Component
public class TicketFileRetentionJob {

    static final String JOB = "ticket-file-retention";
    static final int SLICE = 200;
    static final int MAX_PER_RUN = 5_000;
    private static final Logger log = LoggerFactory.getLogger(TicketFileRetentionJob.class);

    private final TicketStore store;
    private final FileRefs files;
    private final JobLeases leases;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final boolean enabled;

    public TicketFileRetentionJob(TicketStore store, FileRefs files, JobLeases leases,
                                  TransactionOperations transactions, Clock clock,
                                  @Value("${app.jobs.enabled:true}") boolean enabled) {
        this.store = store;
        this.files = files;
        this.leases = leases;
        this.transactions = transactions;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 300_000)
    public void scheduled() {
        if (enabled) {
            leases.runExclusively(JOB, Duration.ofMinutes(20), this::runOnce);
        }
    }

    /** One round; returns how many files were deleted. Called by tests too. */
    public int runOnce() {
        int removed = 0;
        while (removed < MAX_PER_RUN) {
            Integer done = transactions.execute(status -> {
                Instant now = clock.instant();
                List<UUID> due = store.filesResolvedBefore(now.minus(TicketService.FILES_KEPT), SLICE);
                for (UUID id : due) {
                    files.release(id);   // the row now, the bytes queued (FileDeletionJob)
                    store.fileRemoved(id, now);
                }
                return due.size();
            });
            if (done == null || done == 0) {
                break;
            }
            removed += done;
        }
        if (removed > 0) {
            log.info("Removed {} ticket files 6 months after their tickets were resolved", removed);
        }
        return removed;
    }
}
