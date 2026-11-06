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

    /** Shops a list page returns; the next page starts after the last one (see {@link Cursor}). */
    public static final int PAGE = 50;

    private static final String GROUP = "CASE "
            + "WHEN s.status = 'PENDING_VISIT' AND s.ever_approved THEN 'RECHECK' "
            + "WHEN s.status = 'PENDING_VISIT' THEN 'FIRST_VISIT' "
            + "WHEN s.status = 'DRAFT' AND s.status_reason IS NOT NULL THEN 'SENT_BACK' "
            + "WHEN s.status = 'APPROVED' THEN 'LIVE' "
            + "WHEN s.status = 'SUSPENDED' THEN 'SUSPENDED' END";

    /**
     * Where a list page ends: the last shop's status_since and id. Lists are ordered by both (longest in the group
     * first), so the next page is everything after this pair: each page is one index range, however deep.
     */
    public record Cursor(Instant statusSince, UUID id) {
    }

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
     * One page of shop ids in a group, longest in it first (status_since, then id), at most PAGE, after the cursor
     * (null: the first page). `q` (optional) matches the shop number exactly ("1042" or "#1042"), the owner's phone in
     * part (spaces, "+91" and dashes ignored), or the name or owner's name in part.
     */
    public List<UUID> search(ShopType type, ReviewGroup group, String q, Cursor after) {
        // The group as plain conditions on the shop's columns, so a page is one range of ix_shops_review_list
        StringBuilder sql = new StringBuilder("SELECT s.id FROM shops s WHERE s.type = ? AND ");
        sql.append(switch (group) {
            case FIRST_VISIT -> "s.status = 'PENDING_VISIT' AND NOT s.ever_approved";
            case RECHECK -> "s.status = 'PENDING_VISIT' AND s.ever_approved";
            case SENT_BACK -> "s.status = 'DRAFT' AND s.status_reason IS NOT NULL";
            case LIVE -> "s.status = 'APPROVED'";
            case SUSPENDED -> "s.status = 'SUSPENDED'";
        });
        List<Object> args = new ArrayList<>(List.of(type.name()));
        String text = q == null ? "" : q.strip();
        if (!text.isEmpty()) {
            text = text.codePoints().limit(60).collect(StringBuilder::new, StringBuilder::appendCodePoint,
                    StringBuilder::append).toString();
            String number = text.startsWith("#") ? text.substring(1) : text;
            String compact = text.replaceAll("[\\s()-]", "");
            String digits = compact.startsWith("+91") ? compact.substring(3)   // +91 98480 12345, +91 98480
                    : compact.matches("91\\d{10}") ? compact.substring(2)
                    : compact.matches("0\\d{10}") ? compact.substring(1)      // 09848012345
                    : compact;
            // Each way of matching is its own indexed lookup (trigram on names and phones, the unique shop number),
            // joined with UNION: an OR across them would make the database read every shop instead
            sql.append(" AND s.id IN (SELECT id FROM shops WHERE lower(name) LIKE ? "
                    + "UNION SELECT id FROM shops WHERE lower(owner_name) LIKE ?");
            args.addAll(List.of(like(text), like(text)));
            if (number.matches("\\d{1,12}")) {
                sql.append(" UNION SELECT id FROM shops WHERE shop_code = ?");
                args.add(Long.parseLong(number));
            }
            if (digits.matches("\\d{3,10}")) {
                sql.append(" UNION SELECT m.shop_id FROM shop_members m JOIN users u ON u.id = m.user_id "
                        + "WHERE m.role = 'OWNER' AND u.phone LIKE ?");
                args.add(like(digits));
            }
            sql.append(")");
        }
        if (after != null) {
            sql.append(" AND (s.status_since, s.id) > (?, ?)");
            args.add(Timestamp.from(after.statusSince()));
            args.add(after.id());
        }
        // Only constants and bound values go into this SQL (never the search text itself)
        sql.append(" ORDER BY s.status_since, s.id LIMIT ").append(PAGE);
        return jdbc.queryForList(sql.toString(), UUID.class, args.toArray());
    }

    /** "Contains", with LIKE's own characters (%, _ and the backslash) taken literally. */
    private static String like(String text) {
        return "%" + text.toLowerCase(java.util.Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%")
                .replace("_", "\\_") + "%";
    }

    /** When each of these shops entered its current status (its latest timeline entry other than a note). */
    public Map<UUID, Instant> statusSince(Collection<UUID> shopIds) {
        Map<UUID, Instant> out = new HashMap<>();
        if (shopIds.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT s.id, s.status_since AS since FROM shops s WHERE s.id = ANY (?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", shopIds.toArray())),
                rs -> {
                    out.put(rs.getObject("id", UUID.class), rs.getTimestamp("since").toInstant());
                });
        return out;
    }

    /** Which of these shops were ever approved. */
    public Set<UUID> everApproved(Collection<UUID> shopIds) {
        if (shopIds.isEmpty()) {
            return Set.of();
        }
        Set<UUID> out = new HashSet<>();
        jdbc.query("SELECT s.id FROM shops s WHERE s.ever_approved AND s.id = ANY (?)",
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

    /**
     * The shop as it was at the moment it was last approved (the history row where it became APPROVED), if it ever
     * was. Not a later row saved while it stayed live: edits that didn't need a visit (a new name, GSTIN) were never
     * checked, so they must show up as changes too.
     */
    public Optional<Snapshot> lastApproved(UUID shopId) {
        return jdbc.query("SELECT a.* FROM shops_aud a WHERE a.id = ? AND a.status = 'APPROVED' "
                        + "AND COALESCE((SELECT p.status FROM shops_aud p WHERE p.id = a.id AND p.rev < a.rev "
                        + "ORDER BY p.rev DESC LIMIT 1), '') <> 'APPROVED' ORDER BY a.rev DESC LIMIT 1",
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

        if (event != ShopEventType.NOTE) {
            // Every status change writes an entry here, so this is where the list's columns are kept (V16): when the
            // shop entered its current status, and whether it was ever approved (a later visit is a re-check)
            // (reinstating sends a shop back for a visit, PENDING_VISIT: only APPROVED makes it approved)
            boolean approved = event == ShopEventType.APPROVED;
            jdbc.update("UPDATE shops SET status_since = GREATEST(status_since, ?), "
                    + "ever_approved = ever_approved OR ? WHERE id = ?", Timestamp.from(at), approved, shopId);
        }
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
