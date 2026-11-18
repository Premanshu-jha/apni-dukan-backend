package in.apnidukan.kirana.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * kirana_unknown_scans and kirana_scan_stats (V72), and the reads matching needs: barcodes scanned that no product of
 * the shop has (waiting for one tap), how scanning goes per shop and day, and the shop's products and the catalogue's
 * items to match a name against, in plain SQL.
 */
@Repository
public class ScanStore {

    private static final String UNKNOWN = "SELECT id, shop_id, code, scans, purchase_id, read_id, status, product_id, "
            + "created_by, created_at, updated_at FROM kirana_unknown_scans";

    /** A shop may have this many active products at most for matching (a large kirana has 3,000). */
    public static final int MATCH_MAX = 8000;

    private final JdbcTemplate jdbc;

    public ScanStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A barcode no product of the shop had when scanned.
     * @param scans  pieces scanned while it waited (packs counted in once linked)
     * @param status OPEN (needs a tap), LINKED (to {@code productId}), DISMISSED
     */
    public record Unknown(UUID id, UUID shopId, String code, BigDecimal scans, UUID purchaseId, UUID readId,
                          String status, UUID productId, UUID createdBy, Instant createdAt, Instant updatedAt) {
    }

    /**
     * Something a name is matched against: a shop's product (with its catalogue item's brand and size when linked) or
     * a catalogue item.
     * @param hasBarcode the product already has a barcode (an unknown barcode is more likely one without)
     */
    public record Candidate(UUID productId, UUID itemId, String name, String unit, String brand, String size,
                            boolean hasBarcode, Long pricePaise) {
    }

    // ---------- unknown barcodes ----------

    /** The shop's open unknown scan of this barcode with this bill (null: scanned without one), its row locked. */
    public Optional<Unknown> lockOpen(UUID shopId, String code, UUID purchaseId) {
        return jdbc.query(UNKNOWN + " WHERE shop_id = ? AND code = ? AND status = 'OPEN' "
                + "AND purchase_id IS NOT DISTINCT FROM ? FOR NO KEY UPDATE", ScanStore::unknown, shopId, code,
                purchaseId).stream().findFirst();
    }

    /**
     * Holds this barcode's entry for this bill (null: without a bill) until the transaction ends: a scan opening it and
     * a put-away one brought back go one at a time, so they end on one open entry.
     */
    public void lockKey(UUID shopId, String code, UUID purchaseId) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class,
                "kirana-unknown:" + shopId + ":" + code + ":" + purchaseId);
    }

    /** Every open unknown scan of this barcode in the shop (one per bill it was scanned with), not locked. */
    public List<Unknown> openFor(UUID shopId, String code) {
        return jdbc.query(UNKNOWN + " WHERE shop_id = ? AND code = ? AND status = 'OPEN' ORDER BY id",
                ScanStore::unknown, shopId, code);
    }

    /** Open unknown scans made with this bill. */
    public int openForBill(UUID purchaseId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM kirana_unknown_scans WHERE purchase_id = ? "
                + "AND status = 'OPEN'", Integer.class, purchaseId);
        return n == null ? 0 : n;
    }

    /** Forgets batches older than this (a retry comes within minutes); how many, at most {@code limit}. */
    public int forgetBatches(Instant before, int limit) {
        return jdbc.update("DELETE FROM kirana_scan_batches WHERE ctid IN (SELECT ctid FROM kirana_scan_batches "
                + "WHERE created_at < ? LIMIT ?)", at(before), limit);
    }

    public Optional<Unknown> lock(UUID shopId, UUID id) {
        return jdbc.query(UNKNOWN + " WHERE id = ? AND shop_id = ? FOR NO KEY UPDATE", ScanStore::unknown, id, shopId)
                .stream().findFirst();
    }

    public Optional<Unknown> find(UUID shopId, UUID id) {
        return jdbc.query(UNKNOWN + " WHERE id = ? AND shop_id = ?", ScanStore::unknown, id, shopId).stream()
                .findFirst();
    }

    /** A new open unknown scan; false when one for this barcode opened at the same moment (add to it instead). */
    public boolean insert(Unknown u) {
        return jdbc.update("INSERT INTO kirana_unknown_scans (id, shop_id, code, scans, purchase_id, read_id, status, "
                        + "created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?) "
                        + "ON CONFLICT (shop_id, code, coalesce(purchase_id, "
                        + "'00000000-0000-0000-0000-000000000000'::uuid)) WHERE status = 'OPEN' DO NOTHING", u.id(), u.shopId(), u.code(),
                u.scans(), u.purchaseId(), u.readId(), u.createdBy(), at(u.createdAt()), at(u.createdAt())) > 0;
    }

    /** More scans of the same unknown barcode (and its label, when this scan brought the first). */
    public void addScans(UUID id, BigDecimal times, Instant now) {
        jdbc.update("UPDATE kirana_unknown_scans SET scans = least(scans + ?, 999999), updated_at = ? WHERE id = ?",
                times, at(now), id);
    }

    /** Its label to read (sent after the scan). */
    public void setRead(UUID id, UUID readId, Instant now) {
        jdbc.update("UPDATE kirana_unknown_scans SET read_id = ?, updated_at = ? WHERE id = ?", readId, at(now), id);
    }

    /** Settled: LINKED to a product, or DISMISSED. The caller holds its lock. */
    public void settle(UUID id, String status, UUID productId, Instant now) {
        jdbc.update("UPDATE kirana_unknown_scans SET status = ?, product_id = ?, updated_at = ? WHERE id = ?", status,
                productId, at(now), id);
    }

    /**
     * The shop's unknown scans of a status (OPEN: "Needs a tap"; DISMISSED: put away), newest first, after the cursor;
     * one more than limit.
     */
    public List<Unknown> page(UUID shopId, String status, Instant beforeAt, UUID beforeId, int limit) {
        if (beforeAt == null) {
            return jdbc.query(UNKNOWN + " WHERE shop_id = ? AND status = ? ORDER BY created_at DESC, id DESC "
                    + "LIMIT ?", ScanStore::unknown, shopId, status, limit + 1);
        }
        return jdbc.query(UNKNOWN + " WHERE shop_id = ? AND status = ? AND (created_at, id) < (?, ?) "
                + "ORDER BY created_at DESC, id DESC LIMIT ?", ScanStore::unknown, shopId, status, at(beforeAt),
                beforeId, limit + 1);
    }

    /**
     * A read of this barcode's label by any shop, still being read or read (failed ones left out): a pack is read once
     * across all shops.
     */
    public Optional<UUID> networkRead(String code) {
        return jdbc.queryForList("SELECT r.id FROM kirana_unknown_scans u JOIN kirana_reads r ON r.id = u.read_id "
                + "WHERE u.code = ? AND u.read_id IS NOT NULL AND r.status IN ('READING', 'READ', 'DONE') "
                + "ORDER BY (r.status = 'READING'), r.created_at LIMIT 1", UUID.class, code).stream().findFirst();
    }

    /** Records a batch the app sent; false when it was recorded before (a retry: nothing is counted again). */
    public boolean firstBatch(UUID shopId, UUID clientRef, Instant now) {
        return jdbc.update("INSERT INTO kirana_scan_batches (shop_id, client_ref, created_at) VALUES (?, ?, ?) "
                + "ON CONFLICT DO NOTHING", shopId, clientRef, at(now)) > 0;
    }

    // ---------- how scanning goes ----------

    /** Adds to the shop's counts for the day. */
    public void count(UUID shopId, LocalDate day, int scans, int unknown, int sure, int picked, int created) {
        jdbc.update("INSERT INTO kirana_scan_stats (shop_id, day, scans, unknown, sure, picked, created) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (shop_id, day) DO UPDATE SET "
                        + "scans = kirana_scan_stats.scans + EXCLUDED.scans, "
                        + "unknown = kirana_scan_stats.unknown + EXCLUDED.unknown, "
                        + "sure = kirana_scan_stats.sure + EXCLUDED.sure, "
                        + "picked = kirana_scan_stats.picked + EXCLUDED.picked, "
                        + "created = kirana_scan_stats.created + EXCLUDED.created",
                shopId, java.sql.Date.valueOf(day), scans, unknown, sure, picked, created);
    }

    /** One day's counts over every shop. */
    public record DayStats(LocalDate day, long shops, long scans, long unknown, long sure, long picked, long created) {
    }

    /** Every shop's counts per day, from {@code from} on, newest first. */
    public List<DayStats> stats(LocalDate from) {
        return jdbc.query("SELECT day, count(*), sum(scans), sum(unknown), sum(sure), sum(picked), sum(created) "
                        + "FROM kirana_scan_stats WHERE day >= ? GROUP BY day ORDER BY day DESC",
                (rs, i) -> new DayStats(rs.getDate(1).toLocalDate(), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                        rs.getLong(5), rs.getLong(6), rs.getLong(7)), java.sql.Date.valueOf(from));
    }

    // ---------- what a name is matched against ----------

    /**
     * The shop's active products with their catalogue item's brand and size, at most {@link #MATCH_MAX} (one query,
     * matched in code).
     */
    public List<Candidate> products(UUID shopId) {
        return jdbc.query("SELECT p.id, p.catalogue_item_id, p.name, p.unit, i.brand, i.size, p.price_paise, "
                        + "EXISTS (SELECT 1 FROM kirana_barcodes b WHERE b.product_id = p.id AND b.active) "
                        + "FROM kirana_products p LEFT JOIN kirana_catalogue_items i ON i.id = p.catalogue_item_id "
                        + "WHERE p.shop_id = ? AND p.active ORDER BY p.id LIMIT ?",
                (rs, i) -> new Candidate(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getBoolean(8), rs.getLong(7)),
                shopId, MATCH_MAX);
    }

    /**
     * Catalogue items whose name shares letter groups with {@code text} (trigram search), most alike first, at most
     * {@code limit}: what a label or bill line may be when the shop has no such product yet.
     */
    public List<Candidate> catalogue(String text, int limit) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return jdbc.query("SELECT i.id, i.name, i.unit, i.brand, i.size, i.mrp_paise FROM kirana_catalogue_items i "
                        + "WHERE lower(i.name) % lower(?) ORDER BY similarity(lower(i.name), lower(?)) DESC, i.id LIMIT ?",
                (rs, i) -> new Candidate(null, rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), false, (Long) rs.getObject(6)), text, text, limit);
    }

    private static Unknown unknown(ResultSet rs, int i) throws SQLException {
        return new Unknown(rs.getObject("id", UUID.class), rs.getObject("shop_id", UUID.class), rs.getString("code"),
                rs.getBigDecimal("scans"), rs.getObject("purchase_id", UUID.class), rs.getObject("read_id", UUID.class),
                rs.getString("status"), rs.getObject("product_id", UUID.class), rs.getObject("created_by", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime at(Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }
}
