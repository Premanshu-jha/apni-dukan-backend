package in.apnidukan.analytics.service;

import in.apnidukan.analytics.repo.DailyTotals;
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
import java.time.LocalDate;

/**
 * Every hour: works out the daily totals the admin Analytics page reads (analytics_daily) and every salon's use
 * (salon_usage), so the page reads a few hundred small rows instead of counting the live tables on every visit.
 *
 * The first run works out every day since the first account (once, marked in analytics_jobs); after that each run
 * works out the last three days of India's calendar again (today so far, and the two before, which late changes such
 * as a booking marked no-show the next morning still move). Each day is its own transaction with the job statement
 * limit, so a long history never runs as one huge statement. Activity older than 35 days is deleted.
 *
 * One copy of the backend at a time (job lease); working a day out twice gives the same rows.
 */
@Component
public class AnalyticsJob {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsJob.class);

    static final String JOB = "analytics";
    static final String BACKFILL = "analytics-backfill";
    private static final Duration MAX_RUN = Duration.ofMinutes(30);
    /** Days worked out again every hour: today and the two before. */
    static final int RECENT_DAYS = 3;

    private final DailyTotals totals;
    private final JobLeases leases;
    private final LongStatements statements;
    private final Clock clock;
    private final boolean enabled;

    public AnalyticsJob(DailyTotals totals, JobLeases leases, LongStatements statements, Clock clock,
                        @Value("${app.jobs.enabled:true}") boolean enabled) {
        this.totals = totals;
        this.leases = leases;
        this.statements = statements;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    public void scheduled() {
        if (enabled) {
            leases.runExclusively(JOB, MAX_RUN, this::runOnce);
        }
    }

    /** One round; returns how many days it worked out. Called by tests too. */
    public int runOnce() {
        Instant now = clock.instant();
        LocalDate today = now.atZone(DailyTotals.INDIA).toLocalDate();
        statements.run(() -> totals.forgetOldActivity(today));

        LocalDate from = today.minusDays(RECENT_DAYS - 1L);
        boolean backfill = !totals.done(BACKFILL);
        if (backfill) {
            LocalDate first = totals.firstDay();
            if (first != null && first.isBefore(from)) {
                from = first;
            }
        }
        int days = 0;
        for (LocalDate day = from; !day.isAfter(today); day = day.plusDays(1)) {
            LocalDate d = day;
            statements.run(() -> {
                totals.workOut(d, today);
                return d;
            });
            days++;
        }
        if (backfill) {
            totals.markDone(BACKFILL, now);
            log.info("Analytics: worked out {} days of history", days);
        }
        statements.run(() -> totals.refreshSalonUsage(now));
        return days;
    }
}
