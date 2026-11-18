package in.apnidukan.kirana.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * kirana_purchases, kirana_purchase_lines and kirana_wordings (V72): distributors' bills read from their photos (a
 * draft the shop checks, then adds to stock) and the wordings each distributor uses that the shop matched before, in
 * plain SQL.
 */
@Repository
public class PurchaseStore {

    private static final String PURCHASE = "SELECT id, shop_id, read_id, status, distributor, distributor_key, bill_no, "
            + "bill_date, total_paise, version, created_by, created_at, updated_at, added_by, added_at "
            + "FROM kirana_purchases";

    private static final String LINE = "SELECT purchase_id, line_no, wording, brand, name, size, quantity, rate_paise, "
            + "mrp_paise, amount_paise, product_id, pick FROM kirana_purchase_lines";

    private static final String REMEMBER = "INSERT INTO kirana_wordings (shop_id, distributor_key, wording_key, "
            + "product_id, updated_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT (shop_id, distributor_key, wording_key) "
            + "DO UPDATE SET product_id = EXCLUDED.product_id, updated_at = EXCLUDED.updated_at";

    private final JdbcTemplate jdbc;

    public PurchaseStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A distributor's bill.
     * @param status         DRAFT (being checked), ADDED (its lines added to stock), DISCARDED
     * @param distributorKey the distributor's name as compared ('' when none was read): its wordings are remembered
     */
    public record Purchase(UUID id, UUID shopId, UUID readId, String status, String distributor,
                           String distributorKey, String billNo, LocalDate billDate, Long totalPaise, long version,
                           UUID createdBy, Instant createdAt, Instant updatedAt, UUID addedBy, Instant addedAt) {
    }

    /**
     * A bill's line.
     * @param pick NONE, SURE, LIKELY, REMEMBERED, PICKED or SKIPPED (how its product was found)
     */
    public record Line(UUID purchaseId, int lineNo, String wording, String brand, String name, String size,
                       BigDecimal quantity, Long ratePaise, Long mrpPaise, Long amountPaise, UUID productId,
                       String pick) {

        public Line with(UUID product, String howPicked, BigDecimal qty) {
            return new Line(purchaseId, lineNo, wording, brand, name, size, qty, ratePaise, mrpPaise, amountPaise,
                    product, howPicked);
        }

        public Line withRate(Long rate) {
            return new Line(purchaseId, lineNo, wording, brand, name, size, quantity, rate, mrpPaise, amountPaise,
                    productId, pick);
        }
    }

    /** A new draft with its lines (the reading job, once the bill is read). */
    public void insert(Purchase p, List<Line> lines) {
        jdbc.update("INSERT INTO kirana_purchases (id, shop_id, read_id, status, distributor, distributor_key, bill_no, "
                        + "bill_date, total_paise, version, created_by, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, 0, ?, ?, ?)", p.id(), p.shopId(), p.readId(),
                p.distributor(), p.distributorKey(), p.billNo(), date(p.billDate()), p.totalPaise(), p.createdBy(),
                at(p.createdAt()), at(p.createdAt()));
        addLines(p.id(), lines);
    }

    /** Lines added to a bill (when it is made, or a line the reading missed). */
    public void addLines(UUID purchaseId, List<Line> lines) {
        if (lines.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO kirana_purchase_lines (purchase_id, line_no, wording, brand, name, size, quantity, "
                        + "rate_paise, mrp_paise, amount_paise, product_id, pick) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", lines, lines.size(),
                (ps, l) -> {
                    ps.setObject(1, purchaseId);
                    ps.setInt(2, l.lineNo());
                    ps.setString(3, l.wording());
                    ps.setString(4, l.brand());
                    ps.setString(5, l.name());
                    ps.setString(6, l.size());
                    ps.setBigDecimal(7, l.quantity());
                    ps.setObject(8, l.ratePaise());
                    ps.setObject(9, l.mrpPaise());
                    ps.setObject(10, l.amountPaise());
                    ps.setObject(11, l.productId());
                    ps.setString(12, l.pick());
                });
    }

    public Optional<Purchase> find(UUID shopId, UUID id) {
        return jdbc.query(PURCHASE + " WHERE id = ? AND shop_id = ?", PurchaseStore::purchase, id, shopId).stream()
                .findFirst();
    }

    public Optional<Purchase> byRead(UUID readId) {
        return jdbc.query(PURCHASE + " WHERE read_id = ?", PurchaseStore::purchase, readId).stream().findFirst();
    }

    /** The bill's row locked until the transaction ends (every change to a draft and its lines takes it first). */
    public Optional<Purchase> lock(UUID shopId, UUID id) {
        return jdbc.query(PURCHASE + " WHERE id = ? AND shop_id = ? FOR NO KEY UPDATE", PurchaseStore::purchase, id,
                shopId).stream().findFirst();
    }

    public List<Line> lines(UUID purchaseId) {
        return jdbc.query(LINE + " WHERE purchase_id = ? ORDER BY line_no", PurchaseStore::line, purchaseId);
    }

    /** Lines of many bills in one query, by bill. */
    public Map<UUID, List<Line>> lines(Collection<UUID> purchaseIds) {
        Map<UUID, List<Line>> out = new HashMap<>();
        if (purchaseIds.isEmpty()) {
            return out;
        }
        jdbc.query(LINE + " WHERE purchase_id = ANY (?) ORDER BY purchase_id, line_no",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", purchaseIds.toArray())),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> out
                        .computeIfAbsent(rs.getObject("purchase_id", UUID.class), k -> new ArrayList<>())
                        .add(line(rs, 0)));
        return out;
    }

    /** Saves the shop's decision on a line (its product, quantity, rate, how it was picked). */
    public void saveLine(Line l) {
        jdbc.update("UPDATE kirana_purchase_lines SET product_id = ?, pick = ?, quantity = ?, rate_paise = ? "
                + "WHERE purchase_id = ? AND line_no = ?", l.productId(), l.pick(), l.quantity(), l.ratePaise(),
                l.purchaseId(), l.lineNo());
    }

    /** The bill's distributor, number and date as the shop corrected them. */
    public void setHeader(UUID id, String distributor, String distributorKey, String billNo, LocalDate billDate) {
        jdbc.update("UPDATE kirana_purchases SET distributor = ?, distributor_key = ?, bill_no = ?, bill_date = ? "
                + "WHERE id = ?", distributor, distributorKey, billNo, date(billDate), id);
    }

    /** Pieces (or kg) of a product scanned while shelving this bill (held with it, not in stock). */
    public void addScanned(UUID purchaseId, UUID productId, BigDecimal quantity) {
        jdbc.update("INSERT INTO kirana_purchase_scans (purchase_id, product_id, quantity) VALUES (?, ?, ?) "
                + "ON CONFLICT (purchase_id, product_id) DO UPDATE SET quantity = kirana_purchase_scans.quantity "
                + "+ EXCLUDED.quantity", purchaseId, productId, quantity);
    }

    /** Takes some of what was held of a product off (it is now counted by a line). */
    public void takeScanned(UUID purchaseId, UUID productId, BigDecimal quantity) {
        jdbc.update("DELETE FROM kirana_purchase_scans WHERE purchase_id = ? AND product_id = ? AND quantity <= ?",
                purchaseId, productId, quantity);
        jdbc.update("UPDATE kirana_purchase_scans SET quantity = quantity - ? WHERE purchase_id = ? "
                + "AND product_id = ?", quantity, purchaseId, productId);
    }

    /** What was scanned while shelving this bill, by product. */
    public Map<UUID, BigDecimal> scanned(UUID purchaseId) {
        Map<UUID, BigDecimal> out = new HashMap<>();
        jdbc.query("SELECT product_id, quantity FROM kirana_purchase_scans WHERE purchase_id = ?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> out.put(rs.getObject(1, UUID.class),
                        rs.getBigDecimal(2)), purchaseId);
        return out;
    }

    /**
     * Holds this bill's identity (distributor, number) until the transaction ends: two copies of one bill added at the
     * same moment go one at a time, so the second sees the first as added.
     */
    public void lockBillKey(UUID shopId, String distributorKey, String billNo) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class,
                "kirana-bill:" + shopId + ":" + distributorKey + ":" + billNo);
    }

    /** The bills made from these reads, by read (the photo list's links). */
    public Map<UUID, UUID> byReads(Collection<UUID> readIds) {
        Map<UUID, UUID> out = new HashMap<>();
        if (readIds.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT read_id, id FROM kirana_purchases WHERE read_id = ANY (?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", readIds.toArray())),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> out.put(rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class)));
        return out;
    }

    /** Moves the version on (a line changed). */
    public void touch(UUID id, Instant now) {
        jdbc.update("UPDATE kirana_purchases SET version = version + 1, updated_at = ? WHERE id = ?", at(now), id);
    }

    /** Added to stock (ADDED) or thrown away (DISCARDED). The caller holds its lock. */
    public void setStatus(UUID id, String status, UUID by, Instant now) {
        boolean added = "ADDED".equals(status);
        jdbc.update("UPDATE kirana_purchases SET status = ?, added_by = ?, added_at = ?, version = version + 1, "
                + "updated_at = ? WHERE id = ?", status, added ? by : null, added ? at(now) : null, at(now), id);
    }

    /**
     * A bill of the same distributor, number and date already added to stock ("this bill was already added"), other
     * than this one.
     */
    public Optional<Purchase> alreadyAdded(UUID shopId, String distributorKey, String billNo, LocalDate billDate,
                                           UUID except) {
        if (billNo == null) {
            return Optional.empty();
        }
        return jdbc.query(PURCHASE + " WHERE shop_id = ? AND distributor_key = ? AND bill_no = ? AND status = 'ADDED' "
                        + "AND bill_date IS NOT DISTINCT FROM ? AND id <> ? LIMIT 1", PurchaseStore::purchase, shopId,
                distributorKey, billNo, date(billDate), except).stream().findFirst();
    }

    /** A shop's bills, newest first, after the cursor; one more than limit. */
    public List<Purchase> page(UUID shopId, Instant beforeAt, UUID beforeId, int limit) {
        if (beforeAt == null) {
            return jdbc.query(PURCHASE + " WHERE shop_id = ? ORDER BY created_at DESC, id DESC LIMIT ?",
                    PurchaseStore::purchase, shopId, limit + 1);
        }
        return jdbc.query(PURCHASE + " WHERE shop_id = ? AND (created_at, id) < (?, ?) "
                + "ORDER BY created_at DESC, id DESC LIMIT ?", PurchaseStore::purchase, shopId, at(beforeAt), beforeId,
                limit + 1);
    }

    /** The shop's open drafts, newest first (scans while shelving tick their lines). */
    public List<Purchase> drafts(UUID shopId, int limit) {
        return jdbc.query(PURCHASE + " WHERE shop_id = ? AND status = 'DRAFT' ORDER BY created_at DESC, id DESC LIMIT ?",
                PurchaseStore::purchase, shopId, limit);
    }

    // ---------- the distributor's wordings the shop matched before ----------

    /** The products this distributor's wordings were matched to before, by wording key. */
    public Map<String, UUID> wordings(UUID shopId, String distributorKey, Collection<String> wordingKeys) {
        Map<String, UUID> out = new HashMap<>();
        if (wordingKeys.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT w.wording_key, w.product_id FROM kirana_wordings w JOIN kirana_products p "
                        + "ON p.id = w.product_id AND p.active WHERE w.shop_id = ? AND w.distributor_key = ? "
                        + "AND w.wording_key = ANY (?)",
                ps -> {
                    ps.setObject(1, shopId);
                    ps.setString(2, distributorKey);
                    ps.setArray(3, ps.getConnection().createArrayOf("varchar", wordingKeys.toArray()));
                },
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> out.put(rs.getString(1),
                        rs.getObject(2, UUID.class)));
        return out;
    }

    /** Remembers (or corrects) what this distributor's wordings mean for the shop (wording key → product). */
    public void rememberAll(UUID shopId, String distributorKey, Map<String, UUID> wordings, Instant now) {
        if (wordings.isEmpty()) {
            return;
        }
        List<Object[]> rows = new ArrayList<>();
        wordings.forEach((key, product) -> rows.add(new Object[] {shopId, distributorKey, key, product, at(now)}));
        jdbc.batchUpdate(REMEMBER, rows);
    }

    private static Purchase purchase(ResultSet rs, int i) throws SQLException {
        Date billDate = rs.getDate("bill_date");
        OffsetDateTime added = rs.getObject("added_at", OffsetDateTime.class);
        return new Purchase(rs.getObject("id", UUID.class), rs.getObject("shop_id", UUID.class),
                rs.getObject("read_id", UUID.class), rs.getString("status"), rs.getString("distributor"),
                rs.getString("distributor_key"), rs.getString("bill_no"),
                billDate == null ? null : billDate.toLocalDate(), (Long) rs.getObject("total_paise"),
                rs.getLong("version"), rs.getObject("created_by", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(), rs.getObject("added_by", UUID.class),
                added == null ? null : added.toInstant());
    }

    private static Line line(ResultSet rs, int i) throws SQLException {
        return new Line(rs.getObject("purchase_id", UUID.class), rs.getInt("line_no"), rs.getString("wording"),
                rs.getString("brand"), rs.getString("name"), rs.getString("size"), rs.getBigDecimal("quantity"),
                (Long) rs.getObject("rate_paise"), (Long) rs.getObject("mrp_paise"),
                (Long) rs.getObject("amount_paise"), rs.getObject("product_id", UUID.class), rs.getString("pick"));
    }

    private static Date date(LocalDate d) {
        return d == null ? null : Date.valueOf(d);
    }

    private static OffsetDateTime at(Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }
}
