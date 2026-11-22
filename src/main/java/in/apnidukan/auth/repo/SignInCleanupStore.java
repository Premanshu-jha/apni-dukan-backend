package in.apnidukan.auth.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Deletes ended sign-ins a slice at a time, from refresh_tokens' default part only (refresh_tokens_default: rows of
 * before V88, or of a week whose part wasn't made in time). The weekly parts go a whole week at once instead
 * (WeeklyParts), so their rows are never deleted one by one.
 *
 * Deleted with plain SQL, because Postgres has no DELETE … LIMIT: each
 * statement deletes at most {@code max} rows chosen by a MATERIALIZED CTE (picked once; a plain IN (sub-select)
 * may be run again by Postgres and reach more rows), and runs as its own short transaction (no surrounding one), so
 * a night with millions of rows never holds one huge transaction or lock.
 */
@Repository
public class SignInCleanupStore {

    private final JdbcTemplate jdbc;

    public SignInCleanupStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Up to {@code max} tokens that expired before {@code before} (index ix_refresh_tokens_expires). */
    public int deleteExpired(Instant before, int max) {
        return jdbc.update("WITH slice AS MATERIALIZED (SELECT id FROM refresh_tokens_default WHERE expires_at < ? LIMIT ?) "
                + "DELETE FROM refresh_tokens_default WHERE id IN (SELECT id FROM slice)", at(before), max);
    }

    /**
     * Up to {@code max} tokens that ended before {@code before}, whatever the reason (signed out, rotated, detected
     * copy). Index ix_refresh_tokens_revoked.
     */
    public int deleteRevoked(Instant before, int max) {
        return jdbc.update("WITH slice AS MATERIALIZED (SELECT id FROM refresh_tokens_default WHERE revoked_at < ? LIMIT ?) "
                + "DELETE FROM refresh_tokens_default WHERE id IN (SELECT id FROM slice)", at(before), max);
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
