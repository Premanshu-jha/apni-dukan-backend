package in.apnidukan.shops.repo;

import in.apnidukan.shops.domain.ReviewGroup;
import in.apnidukan.shops.domain.ShopEventType;
import in.apnidukan.shops.domain.ShopType;
import in.apnidukan.shops.domain.VisitIssue;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * SQL for the field team's pages: counts per group, the lists, a shop's last approved version (from the Envers
 * history in shops_aud) and its timeline (shop_events).
 */
@Repository
public class ShopReviewStore {

    /** Most rows a list returns (the field team searches beyond that). */
    public static final int LIST_LIMIT = 100;

    /** "Was this shop ever approved?" from its history. */
    private static final String EVER_APPROVED =
            "EXISTS (SELECT 1 FROM shops_aud a WHERE a.id = s.id AND a.status = 'APPROVED')";

    private static final String GROUP = "CASE "
            + "WHEN s.status = 'PENDING_VISIT' AND " + EVER_APPROVED + " THEN 'RECHECK' "
            + "WHEN s.status = 'PENDING_VISIT' THEN 'FIRST_VISIT' "
            + "WHEN s.status = 'DRAFT' AND s.status_reason IS NOT NULL THEN 'SENT_BACK' "
            + "WHEN s.status = 'APPROVED' THEN 'LIVE' "
            + "WHEN s.status = 'SUSPENDED' THEN 'SUSPENDED' END";

    private final JdbcTemplate jdbc;

    public ShopReviewStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** How many shops of each type are in each group (every group present, zeros included). */
    public Map<ShopType, Map<ReviewGroup, Long>> counts() {
        Map<ShopType, Map<ReviewGroup, Long>> out = new EnumMap<>(ShopType.class);
        for (ShopType t : ShopType.values()) {
            Map<ReviewGroup, Long> zero = new EnumMap<>(ReviewGroup.class);
            for (ReviewGroup g : ReviewGroup.values()) {
                zero.put(g, 0L);
            }
            out.put(t, zero);
        }
        jdbc.query("SELECT s.type, " + GROUP + " AS grp, count(*) AS n FROM shops s GROUP BY 1, 2", rs -> {
            String grp = rs.getString("grp");
            if (grp != null) {
                out.get(ShopType.valueOf(rs.getString("type"))).put(ReviewGroup.valueOf(grp), rs.getLong("n"));
            }
        });
        return out;
    }

    /**
     * Shop ids in a group, longest waiting first (by when the shop last changed), at most LIST_LIMIT. `q` (optional)
     * matches the shop number exactly, or the name, owner's name or owner's phone in part.
     */
    public List<UUID> search(ShopType type, ReviewGroup group, String q) {
        StringBuilder sql = new StringBuilder("SELECT s.id FROM shops s "
                + "LEFT JOIN shop_members m ON m.shop_id = s.id AND m.role = 'OWNER' "
                + "LEFT JOIN users u ON u.id = m.user_id "
                + "WHERE s.type = ? AND " + GROUP + " = ?");
        List<Object> args = new ArrayList<>(List.of(type.name(), group.name()));
        if (q != null && !q.isBlank()) {
            q = q.length() > 60 ? q.substring(0, 60) : q;
            String like = "%" + q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                    + "%";
            sql.append(" AND (CAST(s.shop_code AS text) = ? OR lower(s.name) LIKE ? OR lower(s.owner_name) LIKE ?"
                    + " OR u.phone LIKE ?)");
            args.addAll(List.of(q.trim(), like, like, like));
        }
        sql.append(" ORDER BY s.updated_at, s.id LIMIT ").append(LIST_LIMIT);
        return jdbc.queryForList(sql.toString(), UUID.class, args.toArray());
    }

    /** Which of these shops were ever approved. */
    public Set<UUID> everApproved(Collection<UUID> shopIds) {
        if (shopIds.isEmpty()) {
            return Set.of();
        }
        Set<UUID> out = new HashSet<>();
        jdbc.query("SELECT DISTINCT a.id FROM shops_aud a WHERE a.status = 'APPROVED' AND a.id = ANY (?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", shopIds.toArray())),
                rs -> {
                    out.add(rs.getObject("id", UUID.class));
                });
        return out;
    }

    /** Each shop's owner's phone (null once the owner deleted their account). */
    public Map<UUID, String> ownerPhones(Collection<UUID> shopIds) {
        Map<UUID, String> out = new HashMap<>();
        if (shopIds.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT m.shop_id, u.phone FROM shop_members m JOIN users u ON u.id = m.user_id "
                        + "WHERE m.role = 'OWNER' AND m.shop_id = ANY (?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", shopIds.toArray())),
                rs -> {
                    out.put(rs.getObject("shop_id", UUID.class), rs.getString("phone"));
                });
        return out;
    }

    /** The shop as it was when last approved (from its history), if it ever was. */
    public Optional<Snapshot> lastApproved(UUID shopId) {
        return jdbc.query("SELECT * FROM shops_aud WHERE id = ? AND status = 'APPROVED' ORDER BY rev DESC LIMIT 1",
                rs -> rs.next() ? Optional.of(snapshot(rs)) : Optional.<Snapshot>empty(), shopId);
    }

    /** Whether a file was a photo of some shop's last approved version (kept so the field team can compare). */
    public boolean isLastApprovedPhoto(UUID fileId) {
        Boolean yes = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM shops_aud a WHERE a.status = 'APPROVED' "
                + "AND (a.owner_photo_file_id = ? OR a.shop_photo_file_id = ?) AND a.rev = (SELECT max(b.rev) "
                + "FROM shops_aud b WHERE b.id = a.id AND b.status = 'APPROVED'))", Boolean.class, fileId, fileId);
        return Boolean.TRUE.equals(yes);
    }

    public void addEvent(UUID shopId, Instant at, UUID by, boolean byAdmin, ShopEventType event, String reason,
                         String note, Collection<VisitIssue> checklist) {
        jdbc.update("INSERT INTO shop_events (id, shop_id, at, by_user, by_admin, event, reason, note, checklist) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), shopId, Timestamp.from(at), by, byAdmin, event.name(), reason, note,
                checklist == null || checklist.isEmpty() ? null
                        : String.join(",", checklist.stream().map(Enum::name).toList()));
    }

    /** Newest first. */
    public List<Event> timeline(UUID shopId) {
        return jdbc.query("SELECT e.*, u.name AS by_name FROM shop_events e LEFT JOIN users u ON u.id = e.by_user "
                + "WHERE e.shop_id = ? ORDER BY e.at DESC, e.seq DESC", (rs, i) -> {
                    String list = rs.getString("checklist");
                    return new Event(rs.getTimestamp("at").toInstant(), ShopEventType.valueOf(rs.getString("event")),
                            rs.getString("by_name"), rs.getBoolean("by_admin"), rs.getString("reason"),
                            rs.getString("note"), list == null ? List.of()
                                    : Arrays.stream(list.split(",")).map(VisitIssue::valueOf).toList());
                }, shopId);
    }

    private static Snapshot snapshot(ResultSet rs) throws SQLException {
        return new Snapshot(rs.getString("name"), rs.getString("owner_name"), rs.getString("address_line"),
                rs.getString("area"), rs.getString("city"), rs.getString("pincode"), rs.getBigDecimal("latitude"),
                rs.getBigDecimal("longitude"), rs.getString("gstin"), rs.getString("udyam_number"),
                rs.getObject("owner_photo_file_id", UUID.class), rs.getObject("shop_photo_file_id", UUID.class));
    }

    /** A shop's details at one point in its history. */
    public record Snapshot(String name, String ownerName, String addressLine, String area, String city,
                           String pincode, BigDecimal latitude, BigDecimal longitude, String gstin,
                           String udyamNumber, UUID ownerPhotoFileId, UUID shopPhotoFileId) {
    }

    /** @param byName who did it (null for the system, or an account deleted since) */
    public record Event(Instant at, ShopEventType event, String byName, boolean byAdmin, String reason, String note,
                        List<VisitIssue> checklist) {

        public Event {
            checklist = List.copyOf(checklist);
        }
    }
}
