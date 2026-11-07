package in.apnidukan.files.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * file_deletions (V19): storage keys whose objects must still be deleted from R2. Written in the same transaction as
 * the file's row goes, so a key is never lost between the row and the object; cleared once R2 confirms.
 */
@Repository
public class FileDeletionStore {

    /** A key still to delete, and how often deleting it failed so far. */
    public record Pending(String storageKey, int attempts) {
    }

    private final JdbcTemplate jdbc;

    public FileDeletionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Queues the key (once: queuing it again changes nothing). Due at once for the job. */
    public void queue(String storageKey, Instant now) {
        jdbc.update("INSERT INTO file_deletions (storage_key, queued_at, next_try_at) VALUES (?, ?, ?) "
                + "ON CONFLICT (storage_key) DO NOTHING", storageKey, at(now), at(now));
    }

    /** R2 confirmed the object is gone. */
    public void done(String storageKey) {
        jdbc.update("DELETE FROM file_deletions WHERE storage_key = ?", storageKey);
    }

    /** Up to {@code max} keys due by now, oldest due first (ix_file_deletions_due). */
    public List<Pending> due(Instant now, int max) {
        return jdbc.query("SELECT storage_key, attempts FROM file_deletions WHERE next_try_at <= ? "
                        + "ORDER BY next_try_at, storage_key LIMIT ?",
                (rs, i) -> new Pending(rs.getString(1), rs.getInt(2)), at(now), max);
    }

    /** One more failed try; the next one not before {@code nextTry}. */
    public void failed(String storageKey, Instant nextTry) {
        jdbc.update("UPDATE file_deletions SET attempts = attempts + 1, next_try_at = ? WHERE storage_key = ?",
                at(nextTry), storageKey);
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
