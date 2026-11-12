package in.apnidukan.shops.repo;

import in.apnidukan.shops.domain.ReviewGroup;
import in.apnidukan.shops.domain.ShopEventType;
import in.apnidukan.shops.domain.ShopType;
import in.apnidukan.shops.domain.TimelineCursor;
import in.apnidukan.shops.domain.VisitIssue;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
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

    /** Re-check: approved before and waiting again (the predicate of ix_shops_review_recheck, V42). */
    static final String RECHECK_SQL = "((s.status = 'PENDING_VISIT' AND s.ever_approved) OR s.recheck_since IS NOT NULL)";

    private static final String GROUP = "CASE "
            + "WHEN " + RECHECK_SQL + " THEN 'RECHECK' "
            + "WHEN s.status = 'PENDING_VISIT' THEN 'FIRST_VISIT' "
            + "WHEN s.status = 'DRAFT' AND s.status_reason IS NOT NULL THEN 'SENT_BACK' "
            + "WHEN s.status = 'APPROVED' THEN 'LIVE' "
            + "WHEN s.status = 'SUSPENDED' THEN 'SUSPENDED' "
            + "WHEN s.status = 'CLOSED' THEN 'CLOSED' END";

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
        return counts(null);
    }

    /**
     * Like counts(), only the shops assigned to this field agent (index ix_shops_assignee_list: one agent's shops,
     * a small range, so never kept in Redis). Null: every shop.
     */
    public Map<ShopType, Map<ReviewGroup, Long>> counts(UUID assignee) {
        Map<ShopType, Map<ReviewGroup, Long>> out = new EnumMap<>(ShopType.class);
        for (ShopType t : ShopType.values()) {
            Map<ReviewGroup, Long> zero = new EnumMap<>(ReviewGroup.class);
            for (ReviewGroup g : ReviewGroup.values()) {
                zero.put(g, 0L);
            }
            out.put(t, zero);
        }
        org.springframework.jdbc.core.RowCallbackHandler add = rs -> {
            String grp = rs.getString("grp");
            if (grp != null) {
                out.get(ShopType.valueOf(rs.getString("type"))).put(ReviewGroup.valueOf(grp), rs.getLong("n"));
            }
        };
        if (assignee == null) {
            jdbc.query("SELECT s.type, " + GROUP + " AS grp, count(*) AS n FROM shops s GROUP BY 1, 2", add);
        } else {
            jdbc.query("SELECT s.type, " + GROUP + " AS grp, count(*) AS n FROM shops s "
                    + "WHERE s.assigned_admin_id = ? GROUP BY 1, 2", add, assignee);
        }
        return out;
    }

    /**
     * Live salons with a registration document not checked yet, oldest change first
     * (ix_shops_registration_doc_to_check).
     */
    public List<UUID> documentsToCheck(int limit) {
        return jdbc.queryForList("SELECT id FROM shops WHERE registration_doc_file_id IS NOT NULL "
                + "AND registration_doc_checked_at IS NULL AND status = 'APPROVED' ORDER BY updated_at, id LIMIT ?",
                UUID.class, limit);
    }

    /**
     * One page of shop ids in a group, longest in it first (status_since, then id), at most PAGE, after the cursor
     * (null: the first page). `q` (optional) matches the shop number exactly ("1042" or "#1042"), the owner's phone in
     * part (spaces, "+91" and dashes ignored), or the name or owner's name in part. {@code assignee}: only the shops
     * assigned to this field agent (ix_shops_assignee_list); {@code unassigned}: only those waiting for a visit with no
     * agent (ix_shops_unassigned_waiting); neither: every shop.
     */
    public List<UUID> search(ShopType type, ReviewGroup group, String q, Cursor after, UUID assignee,
                             boolean unassigned) {
        // The group as plain conditions on the shop's columns, so a page is one range of ix_shops_review_list
        StringBuilder sql = new StringBuilder("SELECT s.id FROM shops s WHERE s.type = ? AND ");
        sql.append(switch (group) {
            case FIRST_VISIT -> "s.status = 'PENDING_VISIT' AND NOT s.ever_approved";
            case RECHECK -> RECHECK_SQL;
            case SENT_BACK -> "s.status = 'DRAFT' AND s.status_reason IS NOT NULL";
            case LIVE -> "s.status = 'APPROVED' AND s.recheck_since IS NULL";
            case SUSPENDED -> "s.status = 'SUSPENDED'";
            case CLOSED -> "s.status = 'CLOSED'";
        });
        List<Object> args = new ArrayList<>(List.of(type.name()));
        if (assignee != null) {
            sql.append(" AND s.assigned_admin_id = ?");
            args.add(assignee);
        }
        if (unassigned) {
            sql.append(" AND s.assigned_admin_id IS NULL");
        }
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
     * The shop as it was at the moment it was last checked by the field team (the history row where it became
     * APPROVED, or where a live shop's re-check was done: approved with nothing waiting after a row that was waiting),
     * if it ever was. Not a later row saved while it stayed live: edits that didn't need a visit (a new name) were
     * never checked, and a live shop waiting for its re-check stays APPROVED, so they must show up as changes too.
     * Found by the check count (V41); for a shop checked before that column, by the status change.
     */
    public Optional<Snapshot> lastApproved(UUID shopId) {
        // The first history row with the newest check count: the version the field team checked (a re-check that
        // ended without a check, ShopService.endRecheckIfNothingLeft, keeps the count, so it doesn't count)
        Optional<Snapshot> checked = jdbc.query("SELECT a.* FROM shops_aud a WHERE a.id = ? AND a.checks > 0 "
                        + "AND a.checks = (SELECT max(b.checks) FROM shops_aud b WHERE b.id = a.id) "
                        + "ORDER BY a.rev LIMIT 1",
                rs -> rs.next() ? Optional.of(snapshot(rs)) : Optional.<Snapshot>empty(), shopId);
        if (checked.isPresent()) {
            return checked;
        }
        // Checked before the count existed (V41)
        return jdbc.query("SELECT a.* FROM shops_aud a WHERE a.id = ? AND a.status = 'APPROVED' "
                        + "AND a.recheck_since IS NULL AND (SELECT p.status <> 'APPROVED' OR p.recheck_since IS NOT NULL "
                        + "FROM shops_aud p WHERE p.id = a.id AND p.rev < a.rev ORDER BY p.rev DESC LIMIT 1) "
                        + "IS NOT FALSE ORDER BY a.rev DESC LIMIT 1",
                rs -> rs.next() ? Optional.of(snapshot(rs)) : Optional.<Snapshot>empty(), shopId);
    }

    /**
     * Whether a file was a photo of some shop's last checked version (kept so the field team can compare): the newest
     * approved history row with no re-check waiting (photos always need one, so those rows hold checked photos).
     */
    public boolean isLastApprovedPhoto(UUID fileId) {
        Boolean yes = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM shops_aud a WHERE a.status = 'APPROVED' "
                + "AND a.recheck_since IS NULL AND (a.owner_photo_file_id = ? OR a.shop_photo_file_id = ?) "
                + "AND a.rev = (SELECT max(b.rev) FROM shops_aud b WHERE b.id = a.id AND b.status = 'APPROVED' "
                + "AND b.recheck_since IS NULL))", Boolean.class, fileId, fileId);
        return Boolean.TRUE.equals(yes);
    }

    /** The shop this person closed most recently after {@code since}, if any (ix_shop_events_closed_by). */
    public Optional<UUID> closedBy(UUID userId, Instant since) {
        return jdbc.queryForList("SELECT shop_id FROM shop_events WHERE by_user = ? AND event = 'CLOSED' AND at > ? "
                + "ORDER BY at DESC LIMIT 1", UUID.class, userId, Timestamp.from(since)).stream().findFirst();
    }

    /** Writes a timeline entry; a trigger (V16) keeps the shop's status_since and ever_approved from it. */
    public void addEvent(UUID shopId, Instant at, UUID by, boolean byAdmin, ShopEventType event, String reason,
                         String note, Collection<VisitIssue> checklist) {
        jdbc.update("INSERT INTO shop_events (id, shop_id, at, by_user, by_admin, event, reason, note, checklist) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), shopId, Timestamp.from(at), by, byAdmin, event.name(), reason, note,
                checklist == null || checklist.isEmpty() ? null
                        : String.join(",", checklist.stream().map(Enum::name).toList()));
    }

    /** Timeline entries per page (the shop page shows the first; the next ones come from .../timeline). */
    public static final int TIMELINE_PAGE = 50;

    private static final String TIMELINE = "SELECT e.*, u.name AS by_name FROM shop_events e "
            + "LEFT JOIN users u ON u.id = e.by_user WHERE e.shop_id = ?";
    private static final String NEWEST_FIRST = " ORDER BY e.at DESC, e.seq DESC LIMIT ?";

    /**
     * Newest first, at most limit, after the cursor (null: from the newest): one range of ix_shop_events_shop
     * (shop_id, at DESC, seq DESC), however long the timeline.
     */
    public List<Event> timeline(UUID shopId, TimelineCursor after, int limit) {
        RowMapper<Event> row = (rs, i) -> {
            String list = rs.getString("checklist");
            return new Event(rs.getTimestamp("at").toInstant(), ShopEventType.valueOf(rs.getString("event")),
                    rs.getString("by_name"), rs.getBoolean("by_admin"), rs.getString("reason"),
                    rs.getString("note"), list == null ? List.of()
                            : Arrays.stream(list.split(",")).map(VisitIssue::valueOf).toList(), rs.getLong("seq"));
        };
        return after == null
                ? jdbc.query(TIMELINE + NEWEST_FIRST, row, shopId, limit)
                : jdbc.query(TIMELINE + " AND (e.at, e.seq) < (?, ?)" + NEWEST_FIRST, row, shopId,
                        Timestamp.from(after.afterAt()), after.afterSeq(), limit);
    }

    private static Snapshot snapshot(ResultSet rs) throws SQLException {
        return new Snapshot(rs.getString("name"), rs.getString("owner_name"), rs.getString("address_line"),
                rs.getString("area"), rs.getString("city"), rs.getString("pincode"), rs.getBigDecimal("latitude"),
                rs.getBigDecimal("longitude"), rs.getString("gstin"), rs.getString("udyam_number"),
                rs.getObject("owner_photo_file_id", UUID.class), rs.getObject("shop_photo_file_id", UUID.class),
                rs.getObject("shop_video_file_id", UUID.class), rs.getString("registration_doc_type"),
                rs.getObject("registration_doc_file_id", UUID.class));
    }

    /** A shop's details at one point in its history. */
    public record Snapshot(String name, String ownerName, String addressLine, String area, String city,
                           String pincode, BigDecimal latitude, BigDecimal longitude, String gstin,
                           String udyamNumber, UUID ownerPhotoFileId, UUID shopPhotoFileId,
                           UUID shopVideoFileId, String registrationDocType, UUID registrationDocFileId) {
    }

    /**
     * @param byName who did it (null for the system, or an account deleted since)
     * @param seq    the order of entries made in the same instant (for the next page's cursor)
     */
    public record Event(Instant at, ShopEventType event, String byName, boolean byAdmin, String reason, String note,
                        List<VisitIssue> checklist, long seq) {

        public Event {
            checklist = List.copyOf(checklist);
        }
    }
}
