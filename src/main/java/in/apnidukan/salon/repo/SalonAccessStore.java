package in.apnidukan.salon.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** salon_pauses (online booking paused) and walk_in_access (who besides the owner may add walk-ins). */
@Repository
public class SalonAccessStore {

    private final JdbcTemplate jdbc;

    public SalonAccessStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Until when online booking is paused, if a pause was set (it may have run out already). */
    public Optional<Instant> pausedUntil(UUID shopId) {
        return jdbc.query("SELECT paused_until FROM salon_pauses WHERE shop_id = ?",
                (rs, i) -> rs.getObject(1, OffsetDateTime.class).toInstant(), shopId).stream().findFirst();
    }

    /** pausedUntil for many salons in one query (salons that never paused are left out). */
    public java.util.Map<UUID, Instant> pausedUntil(java.util.Collection<UUID> shopIds) {
        java.util.Map<UUID, Instant> out = new java.util.HashMap<>();
        if (shopIds.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT shop_id, paused_until FROM salon_pauses WHERE shop_id = ANY (?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", shopIds.toArray())),
                rs -> {
                    out.put(rs.getObject(1, UUID.class), rs.getObject(2, OffsetDateTime.class).toInstant());
                });
        return out;
    }

    /** Sets or replaces the pause (one row per salon; two taps at once just leave the last one). */
    public void pause(UUID shopId, Instant until, UUID by, Instant now) {
        jdbc.update("INSERT INTO salon_pauses (shop_id, paused_until, updated_by, updated_at) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (shop_id) DO UPDATE SET paused_until = EXCLUDED.paused_until, "
                        + "updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at",
                shopId, AppointmentStore.at(until), by, AppointmentStore.at(now));
    }

    public void resume(UUID shopId) {
        jdbc.update("DELETE FROM salon_pauses WHERE shop_id = ?", shopId);
    }

    public Set<UUID> walkInAccess(UUID shopId) {
        return new HashSet<>(jdbc.query("SELECT user_id FROM walk_in_access WHERE shop_id = ?",
                (rs, i) -> rs.getObject(1, UUID.class), shopId));
    }

    public boolean hasWalkInAccess(UUID shopId, UUID userId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM walk_in_access WHERE shop_id = ? AND user_id = ?",
                Integer.class, shopId, userId);
        return n != null && n > 0;
    }

    public void grantWalkIns(UUID shopId, UUID userId, UUID by, Instant now) {
        jdbc.update("INSERT INTO walk_in_access (shop_id, user_id, granted_by, granted_at) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT (shop_id, user_id) DO NOTHING", shopId, userId, by, AppointmentStore.at(now));
    }

    public void revokeWalkIns(UUID shopId, UUID userId) {
        jdbc.update("DELETE FROM walk_in_access WHERE shop_id = ? AND user_id = ?", shopId, userId);
    }

    /** Takes walk-in access away from this person in every salon (their account was deleted). */
    public void revokeAllWalkIns(UUID userId) {
        jdbc.update("DELETE FROM walk_in_access WHERE user_id = ?", userId);
    }
}
