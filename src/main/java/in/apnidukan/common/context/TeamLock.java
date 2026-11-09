package in.apnidukan.common.context;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One database lock (a transaction-level advisory lock) between the admin team and assignments. Team changes (a
 * member added, edited or removed, test numbers) take it exclusively; anything that assigns a business to a field
 * agent takes it shared, before locking the business's row. So a business is never given to an agent whose pincode
 * or role is being taken away at that moment, and the two can only wait for each other, never deadlock (both take
 * this lock first, then rows). Held until the transaction ends; must run inside one.
 */
public final class TeamLock {

    /** The advisory lock's key (was AdminTeamService.LOCK). */
    public static final int KEY = 7201;

    private TeamLock() {
    }

    /** Team changes: waits for every assignment in progress, and they wait for it. */
    public static void exclusive(JdbcTemplate jdbc) {
        jdbc.queryForObject("SELECT count(*) FROM (SELECT pg_advisory_xact_lock(?)) l", Long.class, KEY);
    }

    /** Assignments: many at once, but none while a team change is in progress. */
    public static void shared(JdbcTemplate jdbc) {
        jdbc.queryForObject("SELECT count(*) FROM (SELECT pg_advisory_xact_lock_shared(?)) l", Long.class, KEY);
    }
}
