package in.apnidukan.common.jobs;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Runs a background job on one copy of the backend at a time (table job_leases). Before a round, a job takes its
 * lease for at most `maxRun`; another copy that tries meanwhile skips its round. The lease is given back as soon as
 * the round ends, and a copy that dies mid-round frees it when `maxRun` passes. Jobs stay safe to run twice anyway
 * (their updates decide what each row becomes); the lease only stops several copies doing the same scan.
 *
 * Each call runs in its own short statement (no surrounding transaction), so the lease is visible to others at once.
 */
@Component
public class JobLeases {

    /** Who holds a lease: this copy of the backend (random per start, plus the host name for reading the table). */
    private static final String ME = hostName() + "/" + UUID.randomUUID().toString().substring(0, 8);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JobLeases(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Runs `round` if this copy gets the lease; returns whether it ran. */
    public boolean runExclusively(String job, Duration maxRun, Runnable round) {
        if (!take(job, maxRun)) {
            return false;
        }
        try {
            round.run();
            return true;
        } finally {
            release(job);
        }
    }

    boolean take(String job, Duration maxRun) {
        Instant now = Instant.now(clock);
        int got = jdbc.update("INSERT INTO job_leases (name, locked_until, locked_by, locked_at) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (name) DO UPDATE SET locked_until = EXCLUDED.locked_until, "
                        + "locked_by = EXCLUDED.locked_by, locked_at = EXCLUDED.locked_at "
                        + "WHERE job_leases.locked_until <= EXCLUDED.locked_at",
                job, Timestamp.from(now.plus(maxRun)), ME, Timestamp.from(now));
        return got == 1;
    }

    void release(String job) {
        jdbc.update("UPDATE job_leases SET locked_until = locked_at WHERE name = ? AND locked_by = ?", job, ME);
    }

    private static String hostName() {
        String h = System.getenv("HOSTNAME");
        if (h == null || h.isBlank()) {
            h = System.getenv("RAILWAY_REPLICA_ID");
        }
        h = h == null || h.isBlank() ? "app" : h;
        return h.length() > 60 ? h.substring(0, 60) : h;
    }
}
