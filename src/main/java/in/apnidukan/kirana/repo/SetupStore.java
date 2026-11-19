package in.apnidukan.kirana.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A kirana's product setup in plain SQL: the lines read from the pages of its own list of products (V76), each to be
 * added as a product with its count, or skipped.
 */
@Repository
public class SetupStore {

    private static final String LINE = "SELECT l.id, l.shop_id, l.read_id, l.line_no, l.wording, l.brand, l.name, "
            + "l.size, l.by_weight, l.mrp_paise, l.quantity, l.status, l.product_id, l.counted, l.updated_at "
            + "FROM kirana_list_lines l";
    private static final String IN_STATUS = LINE + " JOIN kirana_reads r ON r.id = l.read_id WHERE l.shop_id = ? "
            + "AND l.status = ? AND r.status <> 'DISCARDED'";

    private final JdbcTemplate jdbc;

    public SetupStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One line of a list page.
     * @param quantity what the list says the shop has (a hint: the shop counts it again); null if not written
     * @param status   NEW (to check), ADDED (a product, with its count), SKIPPED
     * @param counted  ADDED: what the shop counted
     */
    public record Line(UUID id, UUID shopId, UUID readId, int lineNo, String wording, String brand, String name,
                       String size, boolean byWeight, Long mrpPaise, BigDecimal quantity, String status,
                       UUID productId, BigDecimal counted, Instant updatedAt) {
    }

    /** A page's lines as read (the reading job, once per page: a page read again adds nothing). */
    public void insertLines(List<Line> lines) {
        if (lines.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO kirana_list_lines (id, shop_id, read_id, line_no, wording, brand, name, size, "
                        + "by_weight, mrp_paise, quantity, status, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + "'NEW', ?) ON CONFLICT (read_id, line_no) DO NOTHING",
                lines, lines.size(), (ps, l) -> {
                    ps.setObject(1, l.id());
                    ps.setObject(2, l.shopId());
                    ps.setObject(3, l.readId());
                    ps.setInt(4, l.lineNo());
                    ps.setString(5, l.wording());
                    ps.setString(6, l.brand());
                    ps.setString(7, l.name());
                    ps.setString(8, l.size());
                    ps.setBoolean(9, l.byWeight());
                    ps.setObject(10, l.mrpPaise());
                    ps.setBigDecimal(11, l.quantity());
                    ps.setObject(12, at(l.updatedAt()));
                });
    }

    /**
     * The shop's lines in one status (NEW: still to check), page by page in the order the pages were sent, then by
     * line; after the cursor (the page's sending time, the page, the line); one more than limit. Pages thrown away are
     * left out.
     */
    public List<Line> lines(UUID shopId, String status, Instant afterAt, UUID afterRead, Integer afterLine,
                            int limit) {
        if (afterAt == null) {
            return jdbc.query(IN_STATUS + " ORDER BY r.created_at, r.id, l.line_no LIMIT ?", SetupStore::line, shopId,
                    status, limit + 1);
        }
        return jdbc.query(IN_STATUS + " AND (r.created_at, r.id, l.line_no) > (?, ?, ?) ORDER BY r.created_at, r.id, "
                + "l.line_no LIMIT ?", SetupStore::line, shopId, status, at(afterAt), afterRead, afterLine, limit + 1);
    }

    /**
     * A page of the shop's list as the setup shows it: its photo, how its reading went, and its lines.
     * @param linesLeftOut products past the 150 one read keeps (to photograph again), or 0
     */
    public record Page(UUID readId, UUID fileId, String status, String failure, int linesLeftOut, long toCheck,
                       long added, long skipped, Instant sentAt) {
    }

    /** The shop's list pages not thrown away (at most 60), in the order sent, with their lines' counts. */
    public List<Page> pages(UUID shopId) {
        return jdbc.query("SELECT r.id, r.file_ids[1] AS file_id, r.status, r.failure, "
                + "coalesce(r.lines_left_out, 0) AS left_out, r.created_at, "
                + "count(*) FILTER (WHERE l.status = 'NEW') AS to_check, "
                + "count(*) FILTER (WHERE l.status = 'ADDED') AS added, "
                + "count(*) FILTER (WHERE l.status = 'SKIPPED') AS skipped "
                + "FROM kirana_reads r LEFT JOIN kirana_list_lines l ON l.read_id = r.id "
                + "WHERE r.shop_id = ? AND r.kind = 'STOCK_LIST' AND r.status <> 'DISCARDED' "
                + "GROUP BY r.id ORDER BY r.created_at, r.id",
                (rs, i) -> new Page(rs.getObject("id", UUID.class), rs.getObject("file_id", UUID.class),
                        rs.getString("status"), rs.getString("failure"), rs.getInt("left_out"),
                        rs.getLong("to_check"), rs.getLong("added"), rs.getLong("skipped"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()), shopId);
    }

    /** Products past what one read keeps on this page (the reading job). */
    public void setLeftOut(UUID readId, int leftOut) {
        jdbc.update("UPDATE kirana_reads SET lines_left_out = ? WHERE id = ?", Math.min(leftOut, Short.MAX_VALUE),
                readId);
    }

    /** Whether another line of this shop's list was already added onto this product (its count taken). */
    public boolean addedElsewhere(UUID shopId, UUID productId, UUID exceptLine) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM kirana_list_lines l JOIN "
                + "kirana_reads r ON r.id = l.read_id WHERE l.shop_id = ? AND l.product_id = ? AND l.id <> ? "
                + "AND r.status <> 'DISCARDED')", Boolean.class, shopId, productId, exceptLine));
    }

    /** When the line's page was sent (for the cursor). */
    public Instant pageSentAt(UUID readId) {
        OffsetDateTime at = jdbc.queryForObject("SELECT created_at FROM kirana_reads WHERE id = ?",
                OffsetDateTime.class, readId);
        return java.util.Objects.requireNonNull(at, "created_at").toInstant();
    }

    /** The shop's line on a page not thrown away, its row locked until the transaction ends. */
    public Optional<Line> lock(UUID shopId, UUID lineId) {
        return jdbc.query(LINE + " JOIN kirana_reads r ON r.id = l.read_id WHERE l.id = ? AND l.shop_id = ? "
                + "AND r.status <> 'DISCARDED' FOR UPDATE OF l", SetupStore::line, lineId, shopId).stream().findFirst();
    }

    public void setStatus(UUID lineId, String status, UUID productId, BigDecimal counted, Instant now) {
        jdbc.update("UPDATE kirana_list_lines SET status = ?, product_id = ?, counted = ?, updated_at = ? WHERE id = ?",
                status, productId, counted, at(now), lineId);
    }

    /**
     * How the list stands, on pages not thrown away: lines to check, added, skipped; and pages still being read or
     * that couldn't be read.
     */
    public record Totals(long toCheck, long added, long skipped, long reading, long failed) {
    }

    public Totals totals(UUID shopId) {
        return jdbc.queryForObject("SELECT "
                + "(SELECT count(*) FILTER (WHERE l.status = 'NEW') FROM kirana_list_lines l JOIN kirana_reads r "
                + " ON r.id = l.read_id WHERE l.shop_id = ? AND r.status <> 'DISCARDED'), "
                + "(SELECT count(*) FILTER (WHERE l.status = 'ADDED') FROM kirana_list_lines l JOIN kirana_reads r "
                + " ON r.id = l.read_id WHERE l.shop_id = ? AND r.status <> 'DISCARDED'), "
                + "(SELECT count(*) FILTER (WHERE l.status = 'SKIPPED') FROM kirana_list_lines l JOIN kirana_reads r "
                + " ON r.id = l.read_id WHERE l.shop_id = ? AND r.status <> 'DISCARDED'), "
                + "(SELECT count(*) FROM kirana_reads WHERE shop_id = ? AND kind = 'STOCK_LIST' AND status = 'READING'), "
                + "(SELECT count(*) FROM kirana_reads WHERE shop_id = ? AND kind = 'STOCK_LIST' AND status = 'FAILED')",
                (rs, i) -> new Totals(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)),
                shopId, shopId, shopId, shopId, shopId);
    }

    private static Line line(ResultSet rs, int i) throws SQLException {
        return new Line(rs.getObject("id", UUID.class), rs.getObject("shop_id", UUID.class),
                rs.getObject("read_id", UUID.class), rs.getInt("line_no"), rs.getString("wording"),
                rs.getString("brand"), rs.getString("name"), rs.getString("size"), rs.getBoolean("by_weight"),
                rs.getObject("mrp_paise", Long.class), rs.getBigDecimal("quantity"), rs.getString("status"),
                rs.getObject("product_id", UUID.class), rs.getBigDecimal("counted"),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime at(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }

    /** Lines of a page as read, numbered from 1 (the reading job). */
    public static List<Line> numbered(UUID shopId, UUID readId, List<Line> read) {
        List<Line> out = new ArrayList<>();
        for (int n = 0; n < read.size(); n++) {
            Line l = read.get(n);
            out.add(new Line(UUID.randomUUID(), shopId, readId, n + 1, l.wording(), l.brand(), l.name(), l.size(),
                    l.byWeight(), l.mrpPaise(), l.quantity(), "NEW", null, null, l.updatedAt()));
        }
        return out;
    }
}
