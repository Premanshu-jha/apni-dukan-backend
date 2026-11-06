package in.apnidukan.auth.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Deletes ended sign-ins (refresh_tokens) a slice at a time. Plain SQL, because Postgres has no DELETE … LIMIT: each
 * statement deletes at most {@code max} rows chosen by a MATERIALIZED CTE (picked once; a plain IN (sub-select)
 * may be run again by Postgres and reach more rows), and runs as its own short transaction (no
 * surrounding one), so a night with millions of rows never holds one huge transaction or lock.
 */
@Repository
public class SignInCleanupStore {

    private final JdbcTemplate jdbc;

    public SignInCleanupStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Up to {@code max} tokens that expired before {@code before} (index ix_refresh_tokens_expires). */
    public int deleteExpired(Instant before, int max) {
        return jdbc.update("WITH slice AS MATERIALIZED (SELECT id FROM refresh_tokens WHERE expires_at < ? LIMIT ?) "
                + "DELETE FROM refresh_tokens WHERE id IN (SELECT id FROM slice)", at(before), max);
    }

    /**
     * Up to {@code max} tokens signed out (any way but rotation) before {@code before}, and tokens rotated before
     * replaced_by existed (they keep this longer rule, since nobody knows their successor). Index
     * ix_refresh_tokens_revoked.
     */
    public int deleteRevoked(Instant before, int max) {
        return jdbc.update("WITH slice AS MATERIALIZED (SELECT id FROM refresh_tokens "
                + "WHERE revoked_at < ? AND (revoke_reason <> 'ROTATED' OR replaced_by IS NULL) LIMIT ?) "
                + "DELETE FROM refresh_tokens WHERE id IN (SELECT id FROM slice)", at(before), max);
    }

    /**
     * One slice of rotated tokens, oldest first, rotated after {@code after} and before {@code before}: deletes those
     * whose successor is no longer in use (rotated in turn, signed out or expired). One whose successor is still in
     * use stays: if it comes back, it is the "copied token" alarm that ends the copy's session (AuthService.refresh).
     * The slice moves on by rotation time, so the tokens that stay are not read again on the next slice.
     */
    public RotatedSlice deleteRotated(Instant after, Instant before, Instant now, int max) {
        return java.util.Objects.requireNonNull(jdbc.queryForObject("""
                        WITH slice AS MATERIALIZED (
                            SELECT id, revoked_at, replaced_by FROM refresh_tokens
                            WHERE revoked_at > ? AND revoked_at < ? AND revoke_reason = 'ROTATED'
                              AND replaced_by IS NOT NULL
                            ORDER BY revoked_at LIMIT ?),
                        gone AS (
                            DELETE FROM refresh_tokens d USING slice
                            WHERE d.id = slice.id AND NOT EXISTS (
                                SELECT 1 FROM refresh_tokens s WHERE s.id = slice.replaced_by
                                AND s.revoked_at IS NULL AND s.expires_at > ?)
                            RETURNING d.id)
                        SELECT (SELECT count(*) FROM slice) AS seen, (SELECT count(*) FROM gone) AS deleted,
                               (SELECT max(revoked_at) FROM slice) AS last_at""",
                (rs, i) -> {
                    OffsetDateTime last = rs.getObject("last_at", OffsetDateTime.class);
                    return new RotatedSlice(rs.getInt("seen"), rs.getInt("deleted"),
                            last == null ? after : last.toInstant());
                },
                at(after), at(before), max, at(now)));
    }

    /**
     * @param read    rotated tokens looked at (fewer than asked for: this was the last slice)
     * @param deleted of those, how many were deleted
     * @param lastAt  the latest rotation time looked at: the next slice starts after it
     */
    public record RotatedSlice(int read, int deleted, Instant lastAt) {
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
