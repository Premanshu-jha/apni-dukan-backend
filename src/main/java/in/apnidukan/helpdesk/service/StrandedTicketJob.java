package in.apnidukan.helpdesk.service;

import in.apnidukan.common.jobs.JobLeases;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;

/**
 * Every 5 minutes, on one server at a time: open tickets held by someone away or out of support go to whoever is here
 * ({@link TicketAssigner#reassignStranded}). Writing on one's own ticket no longer waits on the helpdesk-wide lock (so
 * thousands can write at once); one written at the very moment its person went away is caught here.
 */
@Component
public class StrandedTicketJob {

    static final String JOB = "ticket-stranded";
    private static final Logger log = LoggerFactory.getLogger(StrandedTicketJob.class);

    private final TicketAssigner assigner;
    private final JobLeases leases;
    private final TransactionOperations transactions;
    private final boolean enabled;

    public StrandedTicketJob(TicketAssigner assigner, JobLeases leases, TransactionOperations transactions,
                             @Value("${app.jobs.enabled:true}") boolean enabled) {
        this.assigner = assigner;
        this.leases = leases;
        this.transactions = transactions;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 240_000)
    public void scheduled() {
        if (enabled) {
            leases.runExclusively(JOB, Duration.ofMinutes(4), this::runOnce);
        }
    }

    /** One round; returns how many tickets moved. Called by tests too. */
    public int runOnce() {
        Integer moved = transactions.execute(status -> {
            assigner.lock();
            return assigner.reassignStranded();
        });
        if (moved != null && moved > 0) {
            log.info("Gave {} tickets held by someone away or out of support to others", moved);
        }
        return moved == null ? 0 : moved;
    }
}
