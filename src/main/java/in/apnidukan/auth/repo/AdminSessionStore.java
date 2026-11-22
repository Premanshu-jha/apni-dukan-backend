package in.apnidukan.auth.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.UUID;

/**
 * Which device sign-ins (sessions) of admins passed the email-code step, and when (V86, table admin_sessions).
 * Times are the database's clock, the one every copy of the backend shares.
 *
 * Used on every sign-in and refresh of an admin (AuthService.issueTokens), so each call is one short statement.
 */
@Repository
public class AdminSessionStore {

    private final JdbcTemplate jdbc;

    public AdminSessionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * True when this person's device sign-in passed the email-code step within the last {@code window}, and after they
     * last joined the team (users.admin_since): someone removed and added again passes the step again.
     */
    public boolean verifiedWithin(UUID sessionId, UUID userId, Duration window) {
        Boolean ok = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM admin_sessions a JOIN users u ON u.id = a.user_id
                               WHERE a.session_id = ? AND a.user_id = ?
                                 AND a.verified_at > clock_timestamp() - make_interval(secs => ?)
                                 AND (u.admin_since IS NULL OR a.verified_at >= u.admin_since))""",
                Boolean.class, sessionId, userId, window.toSeconds());
        return Boolean.TRUE.equals(ok);
    }

    /**
     * True while this device sign-in is still on: one of its refresh tokens not ended and not expired (a device that
     * signed out can't turn a leftover access token into admin access).
     */
    public boolean sessionLive(UUID sessionId, UUID userId) {
        Boolean ok = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM refresh_tokens
                               WHERE user_id = ? AND COALESCE(session_id, id) = ?
                                 AND revoked_at IS NULL AND expires_at > clock_timestamp())""",
                Boolean.class, userId, sessionId);
        return Boolean.TRUE.equals(ok);
    }

    /**
     * Records that this device sign-in passed the step now, and deletes this person's rows older than {@code window}
     * (no longer worth anything), so the table never grows past a few rows per admin. A session id is only ever this
     * person's (it is their first refresh token's id), so a row of someone else's is never moved.
     */
    public void record(UUID sessionId, UUID userId, Duration window) {
        jdbc.update("""
                INSERT INTO admin_sessions (session_id, user_id, verified_at) VALUES (?, ?, clock_timestamp())
                ON CONFLICT (session_id) DO UPDATE SET verified_at = clock_timestamp()
                WHERE admin_sessions.user_id = EXCLUDED.user_id""", sessionId, userId);
        jdbc.update("""
                DELETE FROM admin_sessions
                WHERE user_id = ? AND session_id <> ? AND verified_at <= clock_timestamp() - make_interval(secs => ?)""",
                userId, sessionId, window.toSeconds());
    }
}
