package in.apnidukan.shops.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * shop_invites (V25): an owner's invitation to the person with a phone number to join as a helper, or to sign in as
 * one of the salon's barbers. At most one waiting (PENDING) per shop and phone, and one per barber profile (unique
 * indexes); one past its expires_at counts as expired wherever it is read.
 */
@Repository
public class ShopInviteStore {

    /** One invitation. @param staffId the barber profile (BARBER only) */
    public record Invite(UUID id, UUID shopId, String role, UUID staffId, String toPhone, UUID invitedBy, String status,
                         Instant createdAt, Instant expiresAt) {

        /** Still waiting for an answer at this moment. */
        public boolean waitingAt(Instant now) {
            return "PENDING".equals(status) && expiresAt.isAfter(now);
        }
    }

    private static final String COLUMNS = "id, shop_id, role, staff_id, to_phone, invited_by, status, created_at, "
            + "expires_at";
    private static final RowMapper<Invite> ROW = (rs, i) -> new Invite(rs.getObject("id", UUID.class),
            rs.getObject("shop_id", UUID.class), rs.getString("role"), rs.getObject("staff_id", UUID.class),
            rs.getString("to_phone"), rs.getObject("invited_by", UUID.class), rs.getString("status"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("expires_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    public ShopInviteStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Saves a new waiting invitation; false if one for the same shop and phone (or the same barber) is already waiting
     * (the unique indexes: two taps at the same moment).
     */
    public boolean insert(Invite v) {
        return jdbc.update("INSERT INTO shop_invites (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?) "
                        + "ON CONFLICT DO NOTHING", v.id(), v.shopId(), v.role(), v.staffId(), v.toPhone(),
                v.invitedBy(), at(v.createdAt()), at(v.expiresAt())) == 1;
    }

    /** Marks the shop's waiting invitations that ran out EXPIRED, so new ones can be made (the unique indexes). */
    public void expire(UUID shopId, Instant now) {
        jdbc.update("UPDATE shop_invites SET status = 'EXPIRED', decided_at = expires_at WHERE shop_id = ? "
                + "AND status = 'PENDING' AND expires_at <= ?", shopId, at(now));
    }

    /** The shop's invitations still waiting, oldest first. */
    public List<Invite> waitingFor(UUID shopId, Instant now) {
        return jdbc.query("SELECT " + COLUMNS + " FROM shop_invites WHERE shop_id = ? AND status = 'PENDING' "
                + "AND expires_at > ? ORDER BY created_at, id", ROW, shopId, at(now));
    }

    /** The barbers' invitations still waiting in this shop: barber profile → the phone the owner typed. */
    public Map<UUID, String> waitingBarbers(UUID shopId, Instant now) {
        Map<UUID, String> out = new HashMap<>();
        jdbc.query("SELECT staff_id, to_phone FROM shop_invites WHERE shop_id = ? AND status = 'PENDING' "
                + "AND expires_at > ? AND staff_id IS NOT NULL", rs -> {
                    out.put(rs.getObject(1, UUID.class), rs.getString(2));
                }, shopId, at(now));
        return out;
    }

    /** Invitations still waiting for this phone, newest first (through ix_shop_invites_to_phone). */
    public List<Invite> waitingForPhone(String phone, Instant now) {
        return jdbc.query("SELECT " + COLUMNS + " FROM shop_invites WHERE to_phone = ? AND status = 'PENDING' "
                + "AND expires_at > ? ORDER BY created_at DESC LIMIT 20", ROW, phone, at(now));
    }

    /**
     * The person moved from oldPhone to newPhone: invitations waiting for the old number are cancelled (moving them
     * would show the inviting shop the new number; it can invite again), and so is one from a shop the person already
     * works in to the new number. Returns how many were cancelled.
     */
    public int cancelForPhoneChange(UUID userId, String oldPhone, String newPhone, Instant now) {
        return jdbc.update("UPDATE shop_invites i SET status = 'CANCELLED', decided_at = ? WHERE status = 'PENDING' "
                        + "AND (to_phone = ? OR (to_phone = ? AND EXISTS (SELECT 1 FROM shop_members m "
                        + "WHERE m.user_id = ? AND m.shop_id = i.shop_id)))",
                at(now), oldPhone, newPhone, userId);
    }

    public Optional<Invite> find(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM shop_invites WHERE id = ?", ROW, id).stream().findFirst();
    }

    /**
     * PENDING → ACCEPTED, DECLINED or CANCELLED, only while still waiting; false if someone decided first or it ran
     * out. The row is locked from here until the transaction ends.
     */
    public boolean decide(UUID id, String status, UUID by, Instant now) {
        return jdbc.update("UPDATE shop_invites SET status = ?, decided_at = ?, decided_by = ? WHERE id = ? "
                + "AND status = 'PENDING' AND expires_at > ?", status, at(now), by, id, at(now)) == 1;
    }

    /** Every invitation of the shop still waiting is cancelled (the shop closed, or its owner changed). */
    public void cancelAllFor(UUID shopId, UUID by, Instant now) {
        jdbc.update("UPDATE shop_invites SET status = 'CANCELLED', decided_at = ?, decided_by = ? WHERE shop_id = ? "
                + "AND status = 'PENDING'", at(now), by, shopId);
    }

    /** The barber profile's waiting invitation, if any, is cancelled; returns whether there was one. */
    public boolean cancelForBarber(UUID shopId, UUID staffId, UUID by, Instant now) {
        return jdbc.update("UPDATE shop_invites SET status = 'CANCELLED', decided_at = ?, decided_by = ? "
                + "WHERE shop_id = ? AND staff_id = ? AND status = 'PENDING'", at(now), by, shopId, staffId) > 0;
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
