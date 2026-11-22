package in.apnidukan.common.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The weekly parts of refresh_tokens and notification_jobs (V88, README "Weekly parts"): makes the coming weeks'
 * parts and drops old weeks whose rows the nightly cleanup would all have deleted anyway. The database does both
 * (partitions_prepare, partitions_drop_old, run as the owner), because the backend's own user may not create or drop
 * tables. Called by the nightly cleanups (SignInCleanupJob, MessageCleanupJob), which run under a job lease.
 */
@Component
public class WeeklyParts {

    private static final Logger log = LoggerFactory.getLogger(WeeklyParts.class);
    /** Parts made ahead: a month of nights the job may miss before rows start landing in the default part. */
    static final int WEEKS_AHEAD = 4;
    /** A week still IN_USE this long after it could first have gone is worth a look (a message stuck waiting?). */
    static final int STUCK_AFTER_DAYS = 60;
    /** Weeks dropped in one night at most (a backlog after months without the job goes over a few nights). */
    static final int MAX_DROPS = 20;

    /** The tables that have weekly parts. */
    public enum Table {
        REFRESH_TOKENS("refresh_tokens"),
        NOTIFICATION_JOBS("notification_jobs");

        private final String sql;

        Table(String sql) {
            this.sql = sql;
        }

        /** The table's name in the database. */
        public String sql() {
            return sql;
        }
    }

    /** What happened to an old week. */
    public record Old(String part, LocalDate week, String outcome) {
    }

    private final JdbcTemplate jdbc;
    private final LongStatements statements;
    private final Clock clock;

    public WeeklyParts(JdbcTemplate jdbc, LongStatements statements, Clock clock) {
        this.jdbc = jdbc;
        this.statements = statements;
        this.clock = clock;
    }

    /**
     * Makes sure this week and the next 4 have parts, then drops the old weeks that can go. Never throws: a night
     * when this fails only means old rows stay a day longer (and, after a month, new rows go to the default part),
     * so the row cleanup after it still runs. Returns how many weeks were dropped.
     */
    public int maintain(Table table, int keepDays) {
        try {
            Integer made = statements.run(() -> jdbc.queryForObject("SELECT partitions_prepare(?, ?)",
                    Integer.class, table.sql(), WEEKS_AHEAD));
            if (made > 0) {
                log.info("Made {} weekly part(s) of {}", made, table.sql());
            }
        } catch (RuntimeException e) {
            log.warn("Could not make the coming weeks' parts of {}: {}", table.sql(), e.getMessage());
        }
        // One drop per call, each in its own transaction (the table's lock ends with it): again while one went
        List<Old> old = new java.util.ArrayList<>();
        for (int call = 0; call < MAX_DROPS; call++) {
            List<Old> seen;
            try {
                seen = statements.run(() -> jdbc.query("SELECT part, week, outcome FROM partitions_drop_old(?, ?)",
                        (rs, i) -> new Old(rs.getString(1), rs.getObject(2, LocalDate.class), rs.getString(3)),
                        table.sql(), keepDays));
            } catch (RuntimeException e) {
                log.warn("Could not drop the old weeks of {}: {}", table.sql(), e.getMessage());
                break;
            }
            boolean dropped = !seen.isEmpty() && "DROPPED".equals(seen.get(seen.size() - 1).outcome());
            if (!dropped) {
                old.addAll(seen);   // IN_USE and BUSY weeks: reported once, below
                break;
            }
            old.add(seen.get(seen.size() - 1));
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate stuckBefore = today.minusDays(keepDays + STUCK_AFTER_DAYS);
        int dropped = 0;
        for (Old week : old) {
            switch (week.outcome()) {
                case "DROPPED" -> dropped++;
                case "BUSY" -> log.warn("{} was busy; its week is left for tomorrow", week.part());
                default -> {
                    if (week.week().isBefore(stuckBefore)) {
                        log.warn("{} still holds rows the cleanup keeps, {} days after its week began", week.part(),
                                ChronoUnit.DAYS.between(week.week(), today));
                    }
                }
            }
        }
        if (dropped > 0) {
            log.info("Dropped {} old week(s) of {}", dropped, table.sql());
        }
        return dropped;
    }
}
