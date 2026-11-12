package in.apnidukan.shops.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Which field agent visits each business (shops.assigned_admin_id, V30) and the log of every assignment
 * (shop_assignments). Plain SQL: the Shop entity doesn't map these columns, so an owner's edit or a decision saved
 * through it never writes an old assignment back over a new one.
 */
@Repository
public class ShopAssignmentStore {

    /**
     * Waiting for a visit: its first, a reinstated shop's, or a live shop's re-check (V41). The same words as the
     * predicates of V42's indexes, so those are used.
     */
    static final String WAITING = "(status = 'PENDING_VISIT' OR recheck_since IS NOT NULL)";

    private final JdbcTemplate jdbc;

    public ShopAssignmentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The business's row, locked until the transaction ends: an assignment and a decision on the same business wait
     * for each other. Empty if there's no such business.
     */
    public Optional<Locked> lock(UUID shopId) {
        return jdbc.query("SELECT id, status, recheck_since, pincode, assigned_admin_id, name, area FROM shops "
                        + "WHERE id = ? FOR UPDATE", (rs, i) -> locked(rs), shopId).stream().findFirst();
    }

    /** Who it is assigned to (no lock); empty when unassigned or there's no such business. */
    public Optional<UUID> assignee(UUID shopId) {
        return jdbc.queryForList("SELECT assigned_admin_id FROM shops WHERE id = ? AND assigned_admin_id IS NOT NULL",
                UUID.class, shopId).stream().findFirst();
    }

    /**
     * The field agent who should visit a business in this pincode: among the field agents covering it (not deleted,
     * not working in this business, not {@code except}), the one with the fewest businesses waiting for a visit, the
     * longest on the team first on a tie. This business itself isn't counted (its old agent isn't held back by it).
     * Empty when nobody covers it.
     */
    public Optional<UUID> pick(UUID shopId, String pincode, UUID except) {
        return jdbc.queryForList("SELECT p.user_id FROM admin_pincodes p JOIN users u ON u.id = p.user_id "
                + "WHERE p.pincode = ? AND u.admin_role = 'ADMIN' AND u.admin_designation = 'FIELD_AGENT' "
                + "AND u.deleted_at IS NULL AND (?::uuid IS NULL OR p.user_id <> ?::uuid) "
                + "AND NOT EXISTS (SELECT 1 FROM shop_members m WHERE m.shop_id = ? AND m.user_id = p.user_id) "
                + "ORDER BY (SELECT count(*) FROM shops s WHERE s.assigned_admin_id = p.user_id "
                + "AND (s.status = 'PENDING_VISIT' OR s.recheck_since IS NOT NULL) AND s.id <> ?), "
                + "u.admin_since NULLS FIRST, u.id LIMIT 1",
                UUID.class, pincode, except, except, shopId, shopId).stream().findFirst();
    }

    /** True if this person is a field agent (not deleted) who covers this pincode. */
    public boolean covers(UUID adminId, String pincode) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM admin_pincodes p "
                + "JOIN users u ON u.id = p.user_id WHERE p.user_id = ? AND p.pincode = ? AND u.admin_role = 'ADMIN' "
                + "AND u.admin_designation = 'FIELD_AGENT' AND u.deleted_at IS NULL)", Boolean.class, adminId,
                pincode));
    }

    /** True if this person is a field agent (not deleted). */
    public boolean isFieldAgent(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM users WHERE id = ? "
                + "AND admin_role = 'ADMIN' AND admin_designation = 'FIELD_AGENT' AND deleted_at IS NULL)",
                Boolean.class, userId));
    }

    /** True if this person works in this business (owner, helper or barber). */
    public boolean isMember(UUID shopId, UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM shop_members "
                + "WHERE shop_id = ? AND user_id = ?)", Boolean.class, shopId, userId));
    }

    /** Assigns (or, with null, unassigns) a business whose row the caller locked. */
    public void set(UUID shopId, UUID adminId, Instant now) {
        jdbc.update("UPDATE shops SET assigned_admin_id = ?, assigned_at = ? WHERE id = ?", adminId,
                adminId == null ? null : Timestamp.from(now), shopId);
    }

    /** One line of the assignment log, in the caller's transaction. */
    public void log(UUID shopId, Instant at, UUID from, UUID to, UUID by, String reason) {
        jdbc.update("INSERT INTO shop_assignments (id, shop_id, at, from_admin, to_admin, by_user, reason) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), shopId, Timestamp.from(at), from, to, by, reason);
    }

    /**
     * The next businesses waiting for this admin's visit after {@code afterId} (null: from the start), locked, in id
     * order (so two moves never lock the same rows in a different order), at most {@code max}. A page at a time, so
     * an agent with many is handled in slices of one statement each.
     */
    public List<Locked> lockPendingOf(UUID adminId, UUID afterId, int max) {
        return jdbc.query("SELECT id, status, recheck_since, pincode, assigned_admin_id, name, area FROM shops "
                        + "WHERE assigned_admin_id = ? AND " + WAITING + " "
                        + "AND (?::uuid IS NULL OR id > ?::uuid) "
                        + "ORDER BY id LIMIT ? FOR UPDATE",
                (rs, i) -> locked(rs), adminId, afterId, afterId, max);
    }

    /**
     * Like lockPendingOf, for the businesses waiting with nobody assigned in these pincodes (ix_shops_unassigned_waiting is
     * by type and time; at the size of one agent's pincodes the pincode filter reads a small set).
     */
    public List<Locked> lockUnassignedIn(java.util.Collection<String> pincodes, UUID afterId, int max) {
        if (pincodes.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT id, status, recheck_since, pincode, assigned_admin_id, name, area FROM shops "
                        + "WHERE " + WAITING + " AND assigned_admin_id IS NULL AND pincode = ANY (?) "
                        + "AND (?::uuid IS NULL OR id > ?::uuid) ORDER BY id LIMIT ? FOR UPDATE",
                ps -> {
                    ps.setArray(1, ps.getConnection().createArrayOf("varchar", pincodes.toArray()));
                    ps.setObject(2, afterId);
                    ps.setObject(3, afterId);
                    ps.setInt(4, max);
                },
                (rs, i) -> locked(rs));
    }

    private static Locked locked(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Locked(rs.getObject("id", UUID.class), rs.getString("status"),
                rs.getTimestamp("recheck_since") != null, rs.getString("pincode"),
                rs.getObject("assigned_admin_id", UUID.class), rs.getString("name"), rs.getString("area"));
    }

    /** How many businesses waiting for a visit each admin has (only those with any). */
    public Map<UUID, Long> pendingCounts(Collection<UUID> adminIds) {
        Map<UUID, Long> out = new HashMap<>();
        if (adminIds.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT assigned_admin_id, count(*) AS n FROM shops WHERE " + WAITING + " "
                        + "AND assigned_admin_id = ANY (?) GROUP BY assigned_admin_id",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", adminIds.toArray())),
                rs -> {
                    out.put(rs.getObject("assigned_admin_id", UUID.class), rs.getLong("n"));
                });
        return out;
    }

    /** Each business's assignee (only the assigned ones). */
    public Map<UUID, UUID> assignees(Collection<UUID> shopIds) {
        Map<UUID, UUID> out = new HashMap<>();
        if (shopIds.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT id, assigned_admin_id FROM shops WHERE id = ANY (?) AND assigned_admin_id IS NOT NULL",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", shopIds.toArray())),
                rs -> {
                    out.put(rs.getObject("id", UUID.class), rs.getObject("assigned_admin_id", UUID.class));
                });
        return out;
    }

    /**
     * @param status     the business's status
     * @param rechecking a live business waiting for its re-check (it stays APPROVED meanwhile)
     * @param assignee   null when unassigned
     */
    public record Locked(UUID shopId, String status, boolean rechecking, String pincode, UUID assignee, String name,
                         String area) {

        /** Waiting for a visit (first, reinstated, or a live business's re-check). */
        public boolean pending() {
            return "PENDING_VISIT".equals(status) || rechecking;
        }
    }
}
