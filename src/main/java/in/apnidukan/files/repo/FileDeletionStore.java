package in.apnidukan.files.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * Queues the key to be deleted only from {@code dueAt} on: an upload link's object (VideoUploadService), deleted
     * if the upload is never completed, taken off the queue ({@link #done}) when it is.
     */
    public void queueFrom(String storageKey, Instant now, Instant dueAt) {
        jdbc.update("INSERT INTO file_deletions (storage_key, queued_at, next_try_at) VALUES (?, ?, ?) "
                + "ON CONFLICT (storage_key) DO NOTHING", storageKey, at(now), at(dueAt));
    }

    /** Queues the key due now, or moves it forward to now if it was queued for later (a file let go of). */
    public void queueNow(String storageKey, Instant now) {
        jdbc.update("INSERT INTO file_deletions (storage_key, queued_at, next_try_at) VALUES (?, ?, ?) "
                + "ON CONFLICT (storage_key) DO UPDATE SET next_try_at = LEAST(file_deletions.next_try_at, "
                + "EXCLUDED.next_try_at)", storageKey, at(now), at(now));
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

    /**
     * Claims up to {@code max} keys due by now, oldest due first, in one transaction: the rows are locked (FOR UPDATE
     * SKIP LOCKED: a row another transaction holds, e.g. a shop setting that video right now, is left for later) and
     * pushed to {@code claimedUntil}, so no other server takes them meanwhile. A salon video completed but never set
     * on a shop, or a ticket file never sent, loses its files row here, in the same transaction, so nothing can attach
     * it once its bytes go. A key
     * whose files row still exists otherwise (something uses it) is taken off the queue and never deleted.
     */
    @Transactional
    public List<Pending> claimDue(Instant now, int max, Instant claimedUntil) {
        List<Pending> due = jdbc.query("SELECT storage_key, attempts FROM file_deletions WHERE next_try_at <= ? "
                        + "ORDER BY next_try_at, storage_key LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs, i) -> new Pending(rs.getString(1), rs.getInt(2)), at(now), max);
        if (due.isEmpty()) {
            return due;
        }
        String[] keys = due.stream().map(Pending::storageKey).toArray(String[]::new);
        jdbc.update("DELETE FROM files f WHERE f.storage_key = ANY (?) AND f.purpose = 'SHOP_VIDEO' "
                + "AND NOT EXISTS (SELECT 1 FROM shops s WHERE s.shop_video_file_id = f.id)", (Object) keys);
        // A ticket file still queued was never sent (sending it takes it off the queue): it goes, row and bytes
        jdbc.update("DELETE FROM files f WHERE f.storage_key = ANY (?) AND f.purpose = 'TICKET_FILE'", (Object) keys);
        List<String> inUse = jdbc.queryForList("SELECT storage_key FROM files WHERE storage_key = ANY (?)",
                String.class, (Object) keys);
        if (!inUse.isEmpty()) {
            jdbc.update("DELETE FROM file_deletions WHERE storage_key = ANY (?)", (Object) inUse.toArray(String[]::new));
        }
        jdbc.update("UPDATE file_deletions SET next_try_at = ? WHERE storage_key = ANY (?)", at(claimedUntil),
                (Object) keys);
        return due.stream().filter(p -> !inUse.contains(p.storageKey())).toList();
    }

    /**
     * Locks the key's queue row until the transaction ends (waiting for a claim under way); true if it was queued.
     * Then a claim can't take it until this transaction ends.
     */
    public boolean lock(String storageKey) {
        return !jdbc.queryForList("SELECT 1 FROM file_deletions WHERE storage_key = ? FOR UPDATE", Integer.class,
                storageKey).isEmpty();
    }

    /** Whether the files row still exists (read now, not from Hibernate's cache), held against deletion meanwhile. */
    public boolean fileExists(java.util.UUID fileId) {
        return !jdbc.queryForList("SELECT 1 FROM files WHERE id = ? FOR KEY SHARE", Integer.class, fileId).isEmpty();
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
