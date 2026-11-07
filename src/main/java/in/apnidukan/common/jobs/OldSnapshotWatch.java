package in.apnidukan.common.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Watches for a transaction that has been open a long time while holding back the database's oldest snapshot (it
 * wrote something, or still reads from an old snapshot). While it runs, every salon's day board and upcoming deltas
 * start from it (their cursor is the oldest running transaction, V21), so they carry more rows each poll (bounded:
 * past 300 the whole answer comes instead), and Postgres can't clean up old row versions. Requests can't hold one
 * that long (statement limit 5 s, short transactions); a stuck session in Neon's SQL editor or a broken job can.
 *
 * Once a minute: if one is older than app.db.old-transaction-warn (OLD_TRANSACTION_WARN, default 10 minutes), a WARN
 * "A transaction has been open for ..." with its process id, so someone can end it (pg_terminate_backend). Point an
 * alert at it.
 */
@Component
public class OldSnapshotWatch {

    private static final Logger log = LoggerFactory.getLogger(OldSnapshotWatch.class);

    /** The oldest such transaction: its process id, how long it has been open, and its state. */
    public record Oldest(int pid, Duration open, String state) {
    }

    private final JdbcTemplate jdbc;
    private final Duration warnAfter;
    private final boolean enabled;

    public OldSnapshotWatch(JdbcTemplate jdbc, @Value("${app.db.old-transaction-warn:10m}") Duration warnAfter,
                            @Value("${app.jobs.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.warnAfter = warnAfter;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void scheduled() {
        if (enabled) {
            check();
        }
    }

    /** The oldest transaction holding back the snapshot for longer than the warning time, logged; empty if none. */
    public Optional<Oldest> check() {
        List<Oldest> found = jdbc.query("SELECT pid, EXTRACT(EPOCH FROM now() - xact_start)::bigint, state "
                        + "FROM pg_stat_activity WHERE pid <> pg_backend_pid() "
                        + "AND (backend_xid IS NOT NULL OR backend_xmin IS NOT NULL) "
                        + "AND xact_start < now() - make_interval(secs => ?) ORDER BY xact_start LIMIT 1",
                (rs, i) -> new Oldest(rs.getInt(1), Duration.ofSeconds(rs.getLong(2)), rs.getString(3)),
                (double) warnAfter.toMillis() / 1000);
        found.forEach(o -> log.warn("A transaction has been open for {} (process {}, {}) and holds back the oldest "
                + "snapshot: day board deltas grow and old rows can't be cleaned up. End it if it is stuck "
                + "(SELECT pg_terminate_backend({})).", o.open(), o.pid(), o.state(), o.pid()));
        return found.stream().findFirst();
    }
}
