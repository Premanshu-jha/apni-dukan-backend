package in.apnidukan.auth.repo;

import in.apnidukan.auth.domain.RevokeReason;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * The reads and writes a refresh makes, each in one round trip (plain SQL): it runs on every app open for every
 * signed-in person, so each statement saved is lakhs of round trips a day. The rest of refresh_tokens is used through
 * RefreshTokenRepository.
 *
 * Times compared with swap times (arrival, now) come from the database's clock, the one all copies of the backend
 * share, read in the same statement as the row they are compared with.
 */
@Repository
public class RefreshTokenStore {

    private static final String DB_NOW = "(extract(epoch FROM clock_timestamp()) * 1000000)::bigint";

    /** Whose token it is, and when the request arrived (the database's clock, before waiting for any lock). */
    public record Arrival(UUID userId, Instant at) {
    }

    /**
     * A token as refresh needs it, with the database's clock when it was read.
     * @param successorUnused it was swapped for a token that has never been used (nor ended otherwise)
     */
    /** @param sessionId the device's sign-in it belongs to (kept through every swap); null for a token from before V45 */
    public record Stored(UUID id, UUID userId, String device, Instant expiresAt, Instant revokedAt,
                         RevokeReason revokeReason, UUID replacedBy, boolean successorUnused, Instant now,
                         UUID sessionId) {

        /** The sign-in this token belongs to: its own id for a token from before V45 (its successors keep it). */
        public UUID session() {
            return sessionId != null ? sessionId : id;
        }
    }

    private final JdbcTemplate jdbc;

    public RefreshTokenStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The token's owner and the database's clock, in one query; empty for an unknown token. */
    public Optional<Arrival> arrival(String tokenHash) {
        return jdbc.query("SELECT user_id, " + DB_NOW + " FROM refresh_tokens WHERE token_hash = ?",
                (rs, i) -> new Arrival(rs.getObject(1, UUID.class), micros(rs.getLong(2))), tokenHash)
                .stream().findFirst();
    }

    /**
     * The token read fresh (call it under the person's lock: a refresh of the same token that held the lock first is
     * seen), whether its successor is still unused, and the database's clock, in one query.
     */
    public Optional<Stored> read(String tokenHash) {
        return jdbc.query("SELECT t.id, t.user_id, t.device, t.expires_at, t.revoked_at, t.revoke_reason, "
                        + "t.replaced_by, (s.id IS NOT NULL AND s.revoked_at IS NULL) AS successor_unused, " + DB_NOW
                        + ", t.session_id FROM refresh_tokens t LEFT JOIN refresh_tokens s ON s.id = t.replaced_by "
                        + "WHERE t.token_hash = ?", RefreshTokenStore::stored, tokenHash)
                .stream().findFirst();
    }

    /**
     * Swaps the token: ended as ROTATED at {@code now}, recording the token it was swapped for. Only while it is still
     * active; returns false if something ended it first.
     */
    public boolean rotate(UUID id, Instant now, UUID next) {
        return jdbc.update("UPDATE refresh_tokens SET revoked_at = ?, revoke_reason = 'ROTATED', replaced_by = ? "
                + "WHERE id = ? AND revoked_at IS NULL", at(now), next, id) == 1;
    }

    /** A new token (a plain INSERT: the id is new, so nothing needs reading first). */
    public void insert(UUID id, UUID userId, String tokenHash, String device, Instant createdAt, Instant expiresAt,
                       UUID sessionId) {
        jdbc.update("INSERT INTO refresh_tokens (id, user_id, token_hash, device, created_at, expires_at, session_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)", id, userId, tokenHash, device, at(createdAt), at(expiresAt),
                sessionId);
    }

    private static Stored stored(ResultSet rs, int row) throws SQLException {
        OffsetDateTime revoked = rs.getObject("revoked_at", OffsetDateTime.class);
        String reason = rs.getString("revoke_reason");
        return new Stored(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("device"),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant(), revoked == null ? null : revoked.toInstant(),
                reason == null ? null : RevokeReason.valueOf(reason), rs.getObject("replaced_by", UUID.class),
                rs.getBoolean("successor_unused"), micros(rs.getLong(9)), rs.getObject("session_id", UUID.class));
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant micros(long micros) {
        return Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1000L);
    }
}
