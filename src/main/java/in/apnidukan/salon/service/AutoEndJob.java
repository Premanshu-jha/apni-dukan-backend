package in.apnidukan.salon.service;

import in.apnidukan.salon.repo.AppointmentStore;
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
import java.time.temporal.ChronoUnit;

/**
 * Every minute:
 * <ul>
 *   <li>an appointment in service ends by itself at its planned end (there is no Done button). If a service runs
 *       long, the app doesn't know; the next customer may wait;</li>
 *   <li>a booking not started 15 minutes after its start becomes a no-show (see
 *       SalonDayService.markNoShows).</li>
 * </ul>
 */
@Component
public class AutoEndJob {

    private static final Logger log = LoggerFactory.getLogger(AutoEndJob.class);

    /** Rows per batch (app.jobs.auto-end-batch, default 500): each batch is its own short transaction. */
    private final int batchSize;
    /** A round never runs longer than this (another server takes over after it). */
    private static final Duration MAX_ROUND = Duration.ofMinutes(5);

    private final AppointmentStore appointments;
    private final SalonDayService day;
    private final JobLeases leases;
    private final LongStatements statements;
    private final Clock clock;
    private final boolean enabled;

    public AutoEndJob(AppointmentStore appointments, SalonDayService day, JobLeases leases, LongStatements statements,
                      Clock clock, @Value("${app.jobs.enabled:true}") boolean enabled,
                      @Value("${app.jobs.auto-end-batch:500}") int batchSize) {
        if (batchSize < 1) {   // 0 would loop forever (every batch "full"), below 0 fail every minute
            throw new IllegalStateException("app.jobs.auto-end-batch must be at least 1");
        }
        this.batchSize = batchSize;
        this.leases = leases;
        this.statements = statements;
        this.appointments = appointments;
        this.day = day;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    public void scheduled() {
        if (enabled) {
            // One server at a time does the round; the others skip it (they would only find nothing left)
            leases.runExclusively("auto-end", MAX_ROUND, this::runOnce);
        }
    }

    /**
     * One round, in batches until nothing is left; returns how many appointments it closed (completed plus
     * no-shows). Called by tests too.
     */
    public int runOnce() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        int done = 0;
        int batch;
        do {
            // Each batch its own transaction, allowed the jobs' longer statement limit (LongStatements)
            batch = statements.run(() -> appointments.completeEnded(now, batchSize));
            done += batch;
        } while (batch == batchSize);
        int missed = 0;
        do {
            batch = statements.run(() -> day.markNoShows(now, batchSize));
            missed += batch;
        } while (batch == batchSize);
        if (done + missed > 0) {
            log.info("Completed {} appointments at their planned end; {} not started became no-shows", done, missed);
        }
        return done + missed;
    }
}
