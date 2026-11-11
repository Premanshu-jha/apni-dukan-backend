package in.apnidukan.shops.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * shop_handovers (V22): an owner's request that the person with a phone number take the shop over. At most one
 * waiting (PENDING) per shop (a unique index); one past its expires_at counts as expired wherever it is read.
 */
@Repository
public class ShopHandoverStore {

    /** One hand-over request. */
    public record Handover(UUID id, UUID shopId, UUID fromUser, String toPhone, String status, Instant createdAt,
                           Instant expiresAt) {

        /** Still waiting for an answer at this moment. */
        public boolean waitingAt(Instant now) {
            return "PENDING".equals(status) && expiresAt.isAfter(now);
        }
    }

    private static final String COLUMNS = "id, shop_id, from_user, to_phone, status, created_at, expires_at";
    private static final RowMapper<Handover> ROW = (rs, i) -> new Handover(rs.getObject("id", UUID.class),
            rs.getObject("shop_id", UUID.class), rs.getObject("from_user", UUID.class), rs.getString("to_phone"),
            rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("expires_at").toInstant());

    private final JdbcTemplate jdbc;

    public ShopHandoverStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Marks the shop's request EXPIRED if it ran out, so a new one can be made (the one-waiting index). */
    public void expire(UUID shopId, Instant now) {
        jdbc.update("UPDATE shop_handovers SET status = 'EXPIRED', decided_at = expires_at WHERE shop_id = ? "
                + "AND status = 'PENDING' AND expires_at <= ?", shopId, Timestamp.from(now));
    }

    public void insert(Handover h) {
        jdbc.update("INSERT INTO shop_handovers (" + COLUMNS + ") VALUES (?, ?, ?, ?, 'PENDING', ?, ?)",
                h.id(), h.shopId(), h.fromUser(), h.toPhone(), Timestamp.from(h.createdAt()),
                Timestamp.from(h.expiresAt()));
    }

    /** The shop's request still waiting (PENDING and not run out), if any. */
    public Optional<Handover> waitingFor(UUID shopId, Instant now) {
        return jdbc.query("SELECT " + COLUMNS + " FROM shop_handovers WHERE shop_id = ? AND status = 'PENDING' "
                + "AND expires_at > ?", ROW, shopId, Timestamp.from(now)).stream().findFirst();
    }

    /** Requests still waiting for this phone, newest first (through ix_shop_handovers_to_phone). */
    public List<Handover> waitingForPhone(String phone, Instant now) {
        return jdbc.query("SELECT " + COLUMNS + " FROM shop_handovers WHERE to_phone = ? AND status = 'PENDING' "
                + "AND expires_at > ? ORDER BY created_at DESC LIMIT 10", ROW, phone, Timestamp.from(now));
    }

    /**
     * The person moved from oldPhone to newPhone: requests to take over a shop waiting for the old number are cancelled
     * (moving them would show the asking owner the new number; they can ask again), and so is one from the person's
     * own shop to the new number. Returns how many were cancelled.
     */
    public int cancelForPhoneChange(UUID userId, String oldPhone, String newPhone, Instant now) {
        return jdbc.update("UPDATE shop_handovers SET status = 'CANCELLED', decided_at = ?, decided_by = ? "
                + "WHERE status = 'PENDING' AND (to_phone = ? OR (to_phone = ? AND from_user = ?))",
                Timestamp.from(now), userId, oldPhone, newPhone, userId);
    }

    /** The request, its row locked (FOR UPDATE) until the transaction ends. */
    public Optional<Handover> lock(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM shop_handovers WHERE id = ? FOR UPDATE", ROW, id)
                .stream().findFirst();
    }

    public Optional<Handover> find(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM shop_handovers WHERE id = ?", ROW, id).stream().findFirst();
    }

    /**
     * PENDING → the given status (ACCEPTED, DECLINED, CANCELLED), only while still waiting; false if someone decided
     * first or it ran out.
     */
    public boolean decide(UUID id, String status, UUID by, Instant now) {
        return jdbc.update("UPDATE shop_handovers SET status = ?, decided_at = ?, decided_by = ? WHERE id = ? "
                + "AND status = 'PENDING' AND expires_at > ?", status, Timestamp.from(now), by, id,
                Timestamp.from(now)) == 1;
    }

    /** The shop's waiting request, if any, is cancelled (the shop closed, or its owner changed). */
    public void cancelFor(UUID shopId, UUID by, Instant now) {
        jdbc.update("UPDATE shop_handovers SET status = 'CANCELLED', decided_at = ?, decided_by = ? WHERE shop_id = ? "
                + "AND status = 'PENDING'", Timestamp.from(now), by, shopId);
    }
}
