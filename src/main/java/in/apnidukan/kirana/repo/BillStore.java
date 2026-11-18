package in.apnidukan.kirana.repo;

import in.apnidukan.kirana.domain.Unit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static in.apnidukan.kirana.repo.KiranaSettingsStore.at;

/**
 * kirana_bills with their lines, returns and payment changes (V66): the shop's walk-in bills, in plain SQL.
 */
@Repository
public class BillStore {

    private static final String BILL = "SELECT b.id, b.shop_id, b.bill_no, b.client_ref, b.subtotal_paise, "
            + "b.discount_paise, b.total_paise, b.payment, b.khata_customer_id, c.name AS khata_name, b.paid_now_paise, "
            + "b.paid_now_by, b.refunded_paise, b.version, b.created_by, b.created_at, b.voided_at, b.void_reason "
            + "FROM kirana_bills b LEFT JOIN kirana_khata_customers c ON c.id = b.khata_customer_id";

    /** The list: each bill with its line count and the first three names. */
    private static final String SUMMARY = "SELECT b.id, b.bill_no, b.total_paise, b.discount_paise, b.payment, "
            + "c.name AS khata_name, b.paid_now_paise, b.created_at, b.voided_at IS NOT NULL AS voided, b.refunded_paise, "
            + "EXISTS (SELECT 1 FROM kirana_bill_changes ch WHERE ch.bill_id = b.id) AS changed, "
            + "(SELECT count(*) FROM kirana_bill_lines l WHERE l.bill_id = b.id) AS lines, "
            + "(SELECT string_agg(x.name, ', ' ORDER BY x.line_no) FROM (SELECT l.name, l.line_no FROM kirana_bill_lines l "
            + "WHERE l.bill_id = b.id ORDER BY l.line_no LIMIT 3) x) AS preview "
            + "FROM kirana_bills b LEFT JOIN kirana_khata_customers c ON c.id = b.khata_customer_id";

    private final JdbcTemplate jdbc;

    public BillStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A bill as stored, without its lines.
     * @param paidNowPaise for a part-paid KHATA bill: paid at the counter (paidNowBy CASH or UPI); else null
     */
    public record Bill(UUID id, UUID shopId, long billNo, UUID clientRef, long subtotalPaise, long discountPaise,
                       long totalPaise, String payment, UUID khataCustomerId, String khataName, Long paidNowPaise,
                       String paidNowBy, long refundedPaise, long version, UUID by, Instant createdAt,
                       Instant voidedAt, String voidReason) {

        /** What this bill put on its customer's khata: the total less what was paid at once; 0 unless KHATA. */
        public long onKhataPaise() {
            return "KHATA".equals(payment) ? totalPaise - (paidNowPaise == null ? 0 : paidNowPaise) : 0;
        }
    }

    /**
     * A line as sold.
     * @param productId null for an item not in the list
     */
    public record Line(int lineNo, UUID productId, String name, Unit unit, BigDecimal quantity, long pricePaise,
                       long amountPaise, BigDecimal returnedQuantity) {
    }

    /** A bill in the list. */
    public record Summary(UUID id, long billNo, long totalPaise, long discountPaise, String payment, String khataName,
                          Long paidNowPaise, Instant createdAt, boolean voided, long refundedPaise, boolean changed,
                          int lines, String preview) {
    }

    /**
     * A return as stored, with its lines.
     * @param khataPaise  of the refund, taken off the khata
     * @param handedPaise of the refund, handed back (handedBy CASH or UPI; null when nothing was)
     */
    public record Return(UUID id, UUID billId, UUID clientRef, long refundPaise, long khataPaise, long handedPaise,
                         String handedBy, String reason, UUID by, Instant at, List<ReturnLine> lines) {

        public Return {
            lines = List.copyOf(lines);
        }
    }

    /** A line of a return: how much of the bill's line came back, and that line's amount for it. */
    public record ReturnLine(int lineNo, BigDecimal quantity, long valuePaise) {
    }

    /** A payment change as stored. */
    public record Change(String fromPayment, UUID fromCustomerId, String fromCustomerName, Long fromPaidNow,
                         String fromPaidNowBy, String toPayment, UUID toCustomerId, String toCustomerName,
                         Long toPaidNow, String toPaidNowBy, UUID by, Instant at) {
    }

    /**
     * Money by the way it was paid: cash (at the counter), UPI (to the shop's QR), khata (put on it). For bills: as they
     * were paid when made; for payment changes: what moved (may be below zero); for cancellations and returns: what
     * went back.
     */
    public record Money(int count, long totalPaise, long cashPaise, long upiPaise, long khataPaise) {
    }

    /** A bill made from a paper bill's photo: the day written on the paper, and the photo. */
    public record Paper(java.time.LocalDate writtenOn, UUID photoFileId) {
    }

    public void setPaper(UUID billId, java.time.LocalDate writtenOn, UUID photoFileId) {
        jdbc.update("UPDATE kirana_bills SET written_on = ?, photo_file_id = ? WHERE id = ?",
                java.sql.Date.valueOf(writtenOn), photoFileId, billId);
    }

    public Optional<Paper> paper(UUID billId) {
        return jdbc.query("SELECT written_on, photo_file_id FROM kirana_bills WHERE id = ? AND written_on IS NOT NULL",
                (rs, i) -> new Paper(rs.getDate(1).toLocalDate(), rs.getObject(2, UUID.class)), billId).stream()
                .findFirst();
    }

    /** One of a shop's products often sold, with how often. */
    public record Often(UUID productId, long times) {
    }

    /**
     * The shop's products sold most since {@code since}, on bills made in this hour of the day (Indian time, ±1 hour, across midnight too),
     * at most {@code limit}: what to suggest at the counter now.
     */
    public List<Often> soldAtThisHour(UUID shopId, Instant since, int hour, int limit) {
        return jdbc.query("SELECT l.product_id, count(*) AS n FROM kirana_bills b JOIN kirana_bill_lines l "
                        + "ON l.bill_id = b.id WHERE b.shop_id = ? AND b.created_at >= ? AND b.voided_at IS NULL "
                        + "AND l.product_id IS NOT NULL AND (extract(hour FROM b.created_at AT TIME ZONE "
                        + "'Asia/Kolkata')::int - ? + 25) % 24 <= 2 GROUP BY l.product_id ORDER BY n DESC, l.product_id LIMIT ?",
                (rs, i) -> new Often(rs.getObject(1, UUID.class), rs.getLong(2)), shopId, at(since), hour, limit);
    }

    /** What this khata customer bought most since {@code since}, at most {@code limit}. */
    public List<Often> usualFor(UUID shopId, UUID khataCustomerId, Instant since, int limit) {
        return jdbc.query("SELECT l.product_id, count(*) AS n FROM kirana_bills b JOIN kirana_bill_lines l "
                        + "ON l.bill_id = b.id WHERE b.khata_customer_id = ? AND b.shop_id = ? AND b.created_at >= ? "
                        + "AND b.voided_at IS NULL AND l.product_id IS NOT NULL GROUP BY l.product_id "
                        + "ORDER BY n DESC, l.product_id LIMIT ?",
                (rs, i) -> new Often(rs.getObject(1, UUID.class), rs.getLong(2)), khataCustomerId, shopId, at(since),
                limit);
    }

    /**
     * Products most often on the same bill as any of {@code with} since {@code since} (not those themselves), at most
     * {@code limit}: "often bought together".
     */
    public List<Often> boughtWith(UUID shopId, java.util.Collection<UUID> with, Instant since, int limit) {
        if (with.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT o.product_id, count(DISTINCT o.bill_id) AS n FROM kirana_bill_lines w "
                        + "JOIN kirana_bills b ON b.id = w.bill_id JOIN kirana_bill_lines o ON o.bill_id = w.bill_id "
                        + "WHERE b.shop_id = ? AND b.created_at >= ? AND b.voided_at IS NULL AND w.product_id = ANY (?) "
                        + "AND o.product_id IS NOT NULL AND NOT (o.product_id = ANY (?)) "
                        + "GROUP BY o.product_id ORDER BY n DESC, o.product_id LIMIT ?",
                ps -> {
                    java.sql.Array ids = ps.getConnection().createArrayOf("uuid", with.toArray());
                    ps.setObject(1, shopId);
                    ps.setObject(2, at(since));
                    ps.setArray(3, ids);
                    ps.setArray(4, ids);
                    ps.setInt(5, limit);
                },
                (rs, i) -> new Often(rs.getObject(1, UUID.class), rs.getLong(2)));
    }

    public void insert(Bill b, List<Line> lines) {
        jdbc.update("INSERT INTO kirana_bills (id, shop_id, bill_no, client_ref, subtotal_paise, discount_paise, "
                        + "total_paise, payment, khata_customer_id, paid_now_paise, paid_now_by, refunded_paise, "
                        + "version, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?)",
                b.id(), b.shopId(), b.billNo(), b.clientRef(), b.subtotalPaise(), b.discountPaise(), b.totalPaise(),
                b.payment(), b.khataCustomerId(), b.paidNowPaise(), b.paidNowBy(), b.by(), at(b.createdAt()));
        jdbc.batchUpdate("INSERT INTO kirana_bill_lines (bill_id, line_no, product_id, name, unit, quantity, "
                        + "price_paise, amount_paise, returned_quantity) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)", lines,
                lines.size(),
                (ps, l) -> {
                    ps.setObject(1, b.id());
                    ps.setInt(2, l.lineNo());
                    ps.setObject(3, l.productId());
                    ps.setString(4, l.name());
                    ps.setString(5, l.unit().name());
                    ps.setBigDecimal(6, l.quantity());
                    ps.setLong(7, l.pricePaise());
                    ps.setLong(8, l.amountPaise());
                });
    }

    public Optional<Bill> find(UUID shopId, UUID id) {
        return jdbc.query(BILL + " WHERE b.id = ? AND b.shop_id = ?", BillStore::bill, id, shopId).stream()
                .findFirst();
    }

    /** The bill saved with this clientRef in the shop, if any. */
    public Optional<Bill> findByClientRef(UUID shopId, UUID clientRef) {
        return jdbc.query(BILL + " WHERE b.shop_id = ? AND b.client_ref = ?", BillStore::bill, shopId, clientRef)
                .stream().findFirst();
    }

    /** One of the shop's bills, its row locked until the transaction ends (to cancel, return or change it). */
    public Optional<Bill> lock(UUID shopId, UUID id) {
        return jdbc.query(BILL + " WHERE b.id = ? AND b.shop_id = ? FOR NO KEY UPDATE OF b", BillStore::bill, id,
                shopId).stream().findFirst();
    }

    public List<Line> lines(UUID billId) {
        return jdbc.query("SELECT line_no, product_id, name, unit, quantity, price_paise, amount_paise, "
                        + "returned_quantity FROM kirana_bill_lines WHERE bill_id = ? ORDER BY line_no",
                (rs, i) -> new Line(rs.getInt(1), rs.getObject(2, UUID.class), rs.getString(3),
                        Unit.valueOf(rs.getString(4)), rs.getBigDecimal(5), rs.getLong(6), rs.getLong(7),
                        rs.getBigDecimal(8)),
                billId);
    }

    /** Whether items of the bill came back (even for nothing). */
    public boolean hasReturns(UUID billId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM kirana_bill_returns "
                + "WHERE bill_id = ?)", Boolean.class, billId));
    }

    public void markVoided(UUID billId, UUID by, String reason, Instant now) {
        jdbc.update("UPDATE kirana_bills SET voided_at = ?, voided_by = ?, void_reason = ?, version = version + 1 "
                + "WHERE id = ?", at(now), by, reason, billId);
    }

    /** Sets how a bill is paid (the caller holds its lock) and records the change from what it was. */
    public void changePayment(Bill was, String payment, UUID customerId, Long paidNow, String paidNowBy, UUID by,
                              Instant now) {
        jdbc.update("UPDATE kirana_bills SET payment = ?, khata_customer_id = ?, paid_now_paise = ?, paid_now_by = ?, "
                + "version = version + 1 WHERE id = ?", payment, customerId, paidNow, paidNowBy, was.id());
        jdbc.update("INSERT INTO kirana_bill_changes (id, shop_id, bill_id, from_payment, from_customer_id, "
                        + "from_paid_now, from_paid_now_by, to_payment, to_customer_id, to_paid_now, to_paid_now_by, "
                        + "created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), was.shopId(), was.id(), was.payment(), was.khataCustomerId(), was.paidNowPaise(), was.paidNowBy(),
                payment, customerId, paidNow, paidNowBy, by, at(now));
    }

    /**
     * Records a return (the caller holds the bill's lock and checked every quantity): its lines, what came back of
     * each bill line, and the bill's refunded total.
     */
    public void addReturn(UUID shopId, Return r) {
        jdbc.update("INSERT INTO kirana_bill_returns (id, shop_id, bill_id, client_ref, refund_paise, khata_paise, "
                        + "handed_paise, handed_by, reason, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                r.id(), shopId, r.billId(), r.clientRef(), r.refundPaise(), r.khataPaise(), r.handedPaise(),
                r.handedBy(), r.reason(), r.by(), at(r.at()));
        jdbc.batchUpdate("INSERT INTO kirana_bill_return_lines (return_id, line_no, quantity, value_paise) "
                        + "VALUES (?, ?, ?, ?)", r.lines(), r.lines().size(),
                (ps, l) -> {
                    ps.setObject(1, r.id());
                    ps.setInt(2, l.lineNo());
                    ps.setBigDecimal(3, l.quantity());
                    ps.setLong(4, l.valuePaise());
                });
        jdbc.batchUpdate("UPDATE kirana_bill_lines SET returned_quantity = returned_quantity + ? "
                        + "WHERE bill_id = ? AND line_no = ?", r.lines(), r.lines().size(),
                (ps, l) -> {
                    ps.setBigDecimal(1, l.quantity());
                    ps.setObject(2, r.billId());
                    ps.setInt(3, l.lineNo());
                });
        jdbc.update("UPDATE kirana_bills SET refunded_paise = refunded_paise + ?, version = version + 1 WHERE id = ?",
                r.refundPaise(), r.billId());
    }

    /** The return made with this clientRef in the shop, if any (a repeat of it). */
    public Optional<Return> returnByClientRef(UUID shopId, UUID clientRef) {
        List<UUID> ids = jdbc.queryForList("SELECT id FROM kirana_bill_returns WHERE shop_id = ? AND client_ref = ?",
                UUID.class, shopId, clientRef);
        return ids.isEmpty() ? Optional.empty()
                : withLines(jdbc.query(RETURN + " WHERE r.id = ?", BillStore::returnHead, ids.get(0))).stream()
                .findFirst();
    }

    /** A bill's returns, oldest first, with their lines (two queries for the lot). */
    public List<Return> returns(UUID billId) {
        return withLines(jdbc.query(RETURN + " WHERE r.bill_id = ? ORDER BY r.created_at, r.id", BillStore::returnHead,
                billId));
    }

    /** What the bill's earlier returns took off the khata. */
    public long khataRefunded(UUID billId) {
        Long n = jdbc.queryForObject("SELECT coalesce(sum(khata_paise), 0) FROM kirana_bill_returns WHERE bill_id = ?",
                Long.class, billId);
        return n == null ? 0 : n;
    }

    /** What each line of the bill already came back for: line → value in paise. */
    public Map<Integer, Long> returnedValues(UUID billId) {
        Map<Integer, Long> out = new LinkedHashMap<>();
        jdbc.query("SELECT l.line_no, sum(l.value_paise) FROM kirana_bill_return_lines l JOIN kirana_bill_returns r "
                        + "ON r.id = l.return_id WHERE r.bill_id = ? GROUP BY l.line_no",
                rs -> {
                    out.put(rs.getInt(1), rs.getLong(2));
                }, billId);
        return out;
    }

    /** A bill's payment changes, oldest first, with the khata customers' names. */
    public List<Change> changes(UUID billId) {
        return jdbc.query("SELECT ch.from_payment, ch.from_customer_id, f.name, ch.from_paid_now, ch.from_paid_now_by, "
                        + "ch.to_payment, ch.to_customer_id, t.name, ch.to_paid_now, ch.to_paid_now_by, ch.created_by, "
                        + "ch.created_at FROM kirana_bill_changes ch "
                        + "LEFT JOIN kirana_khata_customers f ON f.id = ch.from_customer_id "
                        + "LEFT JOIN kirana_khata_customers t ON t.id = ch.to_customer_id "
                        + "WHERE ch.bill_id = ? ORDER BY ch.created_at, ch.id",
                (rs, i) -> new Change(rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3),
                        (Long) rs.getObject(4), rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class),
                        rs.getString(8), (Long) rs.getObject(9), rs.getString(10), rs.getObject(11, UUID.class),
                        rs.getObject(12, OffsetDateTime.class).toInstant()),
                billId);
    }

    /** The shop's bills, newest first, older than (beforeAt, beforeId) when given; one more than limit. */
    public List<Summary> page(UUID shopId, Instant beforeAt, UUID beforeId, int limit) {
        if (beforeAt == null) {
            return jdbc.query(SUMMARY + " WHERE b.shop_id = ? ORDER BY b.created_at DESC, b.id DESC LIMIT ?",
                    BillStore::summary, shopId, limit + 1);
        }
        return jdbc.query(SUMMARY + " WHERE b.shop_id = ? AND (b.created_at, b.id) < (?, ?) "
                + "ORDER BY b.created_at DESC, b.id DESC LIMIT ?", BillStore::summary, shopId, at(beforeAt), beforeId,
                limit + 1);
    }

    /**
     * The shop's bills made in [from, to), cancelled since or not, as they were paid when made (a later payment change
     * counts on its own day): how many, their total, and the money by how it was paid. Also their discounts.
     */
    public Money sales(UUID shopId, Instant from, Instant to) {
        return jdbc.queryForObject("SELECT count(*), coalesce(sum(total), 0), "
                        + "coalesce(sum(CASE WHEN p = 'CASH' THEN total WHEN pnb = 'CASH' THEN pn ELSE 0 END), 0), "
                        + "coalesce(sum(CASE WHEN p = 'UPI' THEN total WHEN pnb = 'UPI' THEN pn ELSE 0 END), 0), "
                        + "coalesce(sum(CASE WHEN p = 'KHATA' THEN total - coalesce(pn, 0) ELSE 0 END), 0) "
                        + "FROM (SELECT b.total_paise AS total, "
                        + "CASE WHEN f.bill_id IS NULL THEN b.payment ELSE f.from_payment END AS p, "
                        + "CASE WHEN f.bill_id IS NULL THEN b.paid_now_paise ELSE f.from_paid_now END AS pn, "
                        + "CASE WHEN f.bill_id IS NULL THEN b.paid_now_by ELSE f.from_paid_now_by END AS pnb "
                        + "FROM kirana_bills b LEFT JOIN LATERAL (SELECT c.bill_id, c.from_payment, c.from_paid_now, "
                        + "c.from_paid_now_by FROM kirana_bill_changes c WHERE c.bill_id = b.id "
                        + "ORDER BY c.created_at, c.id LIMIT 1) f ON true "
                        + "WHERE b.shop_id = ? AND b.created_at >= ? AND b.created_at < ?) x",
                (rs, i) -> new Money(rs.getInt(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)),
                shopId, at(from), at(to));
    }

    /** The discounts of the shop's bills made in [from, to). */
    public long discounts(UUID shopId, Instant from, Instant to) {
        Long n = jdbc.queryForObject("SELECT coalesce(sum(discount_paise), 0) FROM kirana_bills WHERE shop_id = ? "
                + "AND created_at >= ? AND created_at < ?", Long.class, shopId, at(from), at(to));
        return n == null ? 0 : n;
    }

    /**
     * Payment changes made in [from, to) (on bills of any day): how many, and what moved between the ways of paying
     * (to less from: UPI failed and paid in cash is -UPI +cash).
     */
    public Money changes(UUID shopId, Instant from, Instant to) {
        return jdbc.queryForObject("SELECT count(*), 0, "
                        + "coalesce(sum((CASE WHEN c.to_payment = 'CASH' THEN b.total_paise "
                        + "WHEN c.to_paid_now_by = 'CASH' THEN c.to_paid_now ELSE 0 END) "
                        + "- (CASE WHEN c.from_payment = 'CASH' THEN b.total_paise "
                        + "WHEN c.from_paid_now_by = 'CASH' THEN c.from_paid_now ELSE 0 END)), 0), "
                        + "coalesce(sum((CASE WHEN c.to_payment = 'UPI' THEN b.total_paise "
                        + "WHEN c.to_paid_now_by = 'UPI' THEN c.to_paid_now ELSE 0 END) "
                        + "- (CASE WHEN c.from_payment = 'UPI' THEN b.total_paise "
                        + "WHEN c.from_paid_now_by = 'UPI' THEN c.from_paid_now ELSE 0 END)), 0), "
                        + "coalesce(sum((CASE WHEN c.to_payment = 'KHATA' THEN b.total_paise - coalesce(c.to_paid_now, 0) "
                        + "ELSE 0 END) - (CASE WHEN c.from_payment = 'KHATA' "
                        + "THEN b.total_paise - coalesce(c.from_paid_now, 0) ELSE 0 END)), 0) "
                        + "FROM kirana_bill_changes c JOIN kirana_bills b ON b.id = c.bill_id "
                        + "WHERE c.shop_id = ? AND c.created_at >= ? AND c.created_at < ?",
                (rs, i) -> new Money(rs.getInt(1), 0, rs.getLong(3), rs.getLong(4), rs.getLong(5)),
                shopId, at(from), at(to));
    }

    /**
     * Bills cancelled in [from, to) (whenever they were made): how many, their total, and how the money went back: the
     * cash and UPI they were paid with handed back, what was on the khata taken off.
     */
    public Money cancelled(UUID shopId, Instant from, Instant to) {
        return jdbc.queryForObject("SELECT count(*), coalesce(sum(total_paise), 0), "
                        + "coalesce(sum(CASE WHEN payment = 'CASH' THEN total_paise WHEN paid_now_by = 'CASH' "
                        + "THEN paid_now_paise ELSE 0 END), 0), "
                        + "coalesce(sum(CASE WHEN payment = 'UPI' THEN total_paise WHEN paid_now_by = 'UPI' "
                        + "THEN paid_now_paise ELSE 0 END), 0), "
                        + "coalesce(sum(CASE WHEN payment = 'KHATA' THEN total_paise - coalesce(paid_now_paise, 0) "
                        + "ELSE 0 END), 0) "
                        + "FROM kirana_bills WHERE shop_id = ? AND voided_at >= ? AND voided_at < ?",
                (rs, i) -> new Money(rs.getInt(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)),
                shopId, at(from), at(to));
    }

    /** Returns made in [from, to) (on bills of any day): how many, the refunds, and how they went back. */
    public Money returned(UUID shopId, Instant from, Instant to) {
        return jdbc.queryForObject("SELECT count(*), coalesce(sum(refund_paise), 0), "
                        + "coalesce(sum(handed_paise) FILTER (WHERE handed_by = 'CASH'), 0), "
                        + "coalesce(sum(handed_paise) FILTER (WHERE handed_by = 'UPI'), 0), "
                        + "coalesce(sum(khata_paise), 0) "
                        + "FROM kirana_bill_returns WHERE shop_id = ? AND created_at >= ? AND created_at < ?",
                (rs, i) -> new Money(rs.getInt(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)),
                shopId, at(from), at(to));
    }

    private static final String RETURN = "SELECT r.id, r.bill_id, r.client_ref, r.refund_paise, r.khata_paise, "
            + "r.handed_paise, r.handed_by, r.reason, r.created_by, r.created_at FROM kirana_bill_returns r";

    private static Return returnHead(ResultSet rs, int i) throws SQLException {
        return new Return(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getString(7), rs.getString(8),
                rs.getObject(9, UUID.class), rs.getObject(10, OffsetDateTime.class).toInstant(), List.of());
    }

    private List<Return> withLines(List<Return> heads) {
        if (heads.isEmpty()) {
            return heads;
        }
        Map<UUID, List<ReturnLine>> lines = new LinkedHashMap<>();
        jdbc.query("SELECT return_id, line_no, quantity, value_paise FROM kirana_bill_return_lines "
                        + "WHERE return_id = ANY (?) ORDER BY return_id, line_no",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid",
                        heads.stream().map(Return::id).toArray())),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> lines
                        .computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                        .add(new ReturnLine(rs.getInt(2), rs.getBigDecimal(3), rs.getLong(4))));
        return heads.stream().map(h -> new Return(h.id(), h.billId(), h.clientRef(), h.refundPaise(), h.khataPaise(),
                h.handedPaise(), h.handedBy(), h.reason(), h.by(), h.at(), lines.getOrDefault(h.id(), List.of())))
                .toList();
    }

    private static Bill bill(ResultSet rs, int i) throws SQLException {
        OffsetDateTime voided = rs.getObject("voided_at", OffsetDateTime.class);
        return new Bill(rs.getObject("id", UUID.class), rs.getObject("shop_id", UUID.class), rs.getLong("bill_no"),
                rs.getObject("client_ref", UUID.class), rs.getLong("subtotal_paise"), rs.getLong("discount_paise"),
                rs.getLong("total_paise"), rs.getString("payment"), rs.getObject("khata_customer_id", UUID.class),
                rs.getString("khata_name"), (Long) rs.getObject("paid_now_paise"), rs.getString("paid_now_by"),
                rs.getLong("refunded_paise"), rs.getLong("version"), rs.getObject("created_by", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                voided == null ? null : voided.toInstant(), rs.getString("void_reason"));
    }

    private static Summary summary(ResultSet rs, int i) throws SQLException {
        return new Summary(rs.getObject("id", UUID.class), rs.getLong("bill_no"), rs.getLong("total_paise"),
                rs.getLong("discount_paise"), rs.getString("payment"), rs.getString("khata_name"),
                (Long) rs.getObject("paid_now_paise"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getBoolean("voided"), rs.getLong("refunded_paise"), rs.getBoolean("changed"), rs.getInt("lines"),
                rs.getString("preview"));
    }
}
