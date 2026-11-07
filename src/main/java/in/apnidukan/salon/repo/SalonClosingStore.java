package in.apnidukan.salon.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * salon_closings (V25): closed salons whose upcoming online bookings SalonClosingJob is still cancelling. One row per
 * salon; it goes once nothing is left to cancel.
 */
@Repository
public class SalonClosingStore {

    /** A closed salon still to finish. @param cancelFrom bookings starting after this are cancelled */
    public record Closing(UUID shopId, String reason, String detail, Instant cancelFrom) {
    }

    private final JdbcTemplate jdbc;

    public SalonClosingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Queues the salon (inside the closing's transaction: it exists only if the closing is saved). Once only. */
    public void queue(UUID shopId, String reason, String detail, Instant cancelFrom, Instant now) {
        jdbc.update("INSERT INTO salon_closings (shop_id, reason, detail, cancel_from, queued_at) VALUES (?, ?, ?, ?, ?) "
                + "ON CONFLICT (shop_id) DO NOTHING", shopId, reason, detail, at(cancelFrom), at(now));
    }

    /** The oldest salons still to finish. */
    public List<UUID> due(int max) {
        return jdbc.queryForList("SELECT shop_id FROM salon_closings ORDER BY queued_at, shop_id LIMIT ?", UUID.class,
                max);
    }

    /**
     * The salon's row, locked until the transaction ends; empty if another server is on it right now (SKIP LOCKED)
     * or it is finished.
     */
    public Optional<Closing> lock(UUID shopId) {
        return jdbc.query("SELECT shop_id, reason, detail, cancel_from FROM salon_closings WHERE shop_id = ? "
                        + "FOR UPDATE SKIP LOCKED", (rs, i) -> new Closing(rs.getObject(1, UUID.class),
                        rs.getString(2), rs.getString(3), rs.getObject(4, OffsetDateTime.class).toInstant()), shopId)
                .stream().findFirst();
    }

    public void done(UUID shopId) {
        jdbc.update("DELETE FROM salon_closings WHERE shop_id = ?", shopId);
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
