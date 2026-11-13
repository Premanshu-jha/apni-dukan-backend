package in.apnidukan.files.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** video_uploads (V26): the one-time upload links given for salon videos, until each upload is completed. */
@Repository
public class VideoUploadStore {

    /**
     * One upload link.
     * @param stagingKey      where the phone PUTs the file (the link's key); never the kept video's key
     * @param durationSeconds as the phone read it; null when it couldn't
     * @param fileId          the recorded file once the upload completed; null before
     */
    public record Upload(UUID id, UUID shopId, String stagingKey, String contentType, long sizeBytes,
                         Integer durationSeconds, UUID createdBy, Instant createdAt, UUID fileId) {
    }

    private static final RowMapper<Upload> ROW = (rs, i) -> new Upload(rs.getObject("id", UUID.class),
            rs.getObject("shop_id", UUID.class), rs.getString("storage_key"), rs.getString("content_type"),
            rs.getLong("size_bytes"), rs.getObject("duration_seconds", Integer.class),
            rs.getObject("created_by", UUID.class), rs.getTimestamp("created_at").toInstant(),
            rs.getObject("file_id", UUID.class));

    private final JdbcTemplate jdbc;

    public VideoUploadStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void add(Upload u) {
        jdbc.update("INSERT INTO video_uploads (id, shop_id, storage_key, content_type, size_bytes, duration_seconds, "
                        + "created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", u.id(), u.shopId(), u.stagingKey(),
                u.contentType(), u.sizeBytes(), u.durationSeconds(), u.createdBy(), Timestamp.from(u.createdAt()));
    }

    /** This shop's upload, if any. */
    public Optional<Upload> find(UUID id, UUID shopId) {
        return jdbc.query("SELECT * FROM video_uploads WHERE id = ? AND shop_id = ?", ROW, id, shopId).stream()
                .findFirst();
    }

    /** The same, locked until the transaction ends (two "complete" calls at once: the second waits, then sees it). */
    public Optional<Upload> lock(UUID id, UUID shopId) {
        return jdbc.query("SELECT * FROM video_uploads WHERE id = ? AND shop_id = ? FOR UPDATE", ROW, id, shopId)
                .stream().findFirst();
    }

    /**
     * Holds this shop's upload starts until the transaction ends (a transaction-level advisory lock, so no table row
     * is locked): two starts at the same moment count one after the other, never both under the daily limit.
     */
    public void lockShopStarts(UUID shopId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null, "video-uploads:" + shopId);
    }

    /** Upload links this shop got since a moment (ix_video_uploads_shop_created). */
    public long countSince(UUID shopId, Instant since) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM video_uploads WHERE shop_id = ? AND created_at >= ?",
                Long.class, shopId, Timestamp.from(since));
        return n == null ? 0 : n;
    }

    /** A person's ticket video upload (no shop), if any. */
    public Optional<Upload> findPersonal(UUID id, UUID userId) {
        return jdbc.query("SELECT * FROM video_uploads WHERE id = ? AND shop_id IS NULL AND created_by = ?", ROW, id,
                userId).stream().findFirst();
    }

    /** The same, locked until the transaction ends. */
    public Optional<Upload> lockPersonal(UUID id, UUID userId) {
        return jdbc.query("SELECT * FROM video_uploads WHERE id = ? AND shop_id IS NULL AND created_by = ? FOR UPDATE",
                ROW, id, userId).stream().findFirst();
    }

    /** Holds this person's ticket uploads (files and video links) until the transaction ends: one after the other. */
    public void lockPersonStarts(UUID userId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null, "ticket-uploads:" + userId);
    }

    /** Ticket video links this person got since a moment (ix_video_uploads_person_created). */
    public long countPersonalSince(UUID userId, Instant since) {
        // A completed one is counted as its file (files.purpose TICKET_FILE), not twice
        Long n = jdbc.queryForObject("SELECT count(*) FROM video_uploads WHERE shop_id IS NULL AND created_by = ? "
                + "AND created_at >= ? AND file_id IS NULL", Long.class, userId, Timestamp.from(since));
        return n == null ? 0 : n;
    }

    /** Whether some upload completed with this file (it may have been purged or replaced since). */
    public boolean completedWith(UUID fileId) {
        return !jdbc.queryForList("SELECT 1 FROM video_uploads WHERE file_id = ? LIMIT 1", Integer.class, fileId)
                .isEmpty();
    }

    public void completed(UUID id, UUID fileId) {
        jdbc.update("UPDATE video_uploads SET file_id = ? WHERE id = ?", fileId, id);
    }

    /** Forgets links older than this (long expired; the daily count only looks at today). Returns how many. */
    public int pruneOlderThan(Instant before) {
        return jdbc.update("DELETE FROM video_uploads WHERE created_at < ?", Timestamp.from(before));
    }
}
