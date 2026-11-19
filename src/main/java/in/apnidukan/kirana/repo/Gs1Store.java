package in.apnidukan.kirana.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * What the GS1 job looks up (kirana_gs1_lookups, V79): catalogue barcodes whose item has no picture (CODE, queued by
 * CatalogueStore.addCode), and products added without a barcode (NAME). Queued as they appear, whether or not GS1 is switched on: switched on later,
 * the job starts on them.
 */
@Repository
public class Gs1Store {

    private final JdbcTemplate jdbc;

    public Gs1Store(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One thing to look up.
     * @param code CODE: the barcode looked up; NAME: the barcode found (null until then)
     */
    public record Lookup(UUID id, String kind, String code, UUID itemId, UUID shopId, UUID productId, String status,
                         int tries) {
    }

    /** A shop's product added without a barcode, to find its barcode by name (once per product). */
    public void queueName(UUID shopId, UUID productId, Instant now) {
        jdbc.update("INSERT INTO kirana_gs1_lookups (id, kind, shop_id, product_id, status, next_try_at, created_at, "
                + "updated_at) VALUES (?, 'NAME', ?, ?, 'WAITING', ?, ?, ?) "
                + "ON CONFLICT (product_id) WHERE kind = 'NAME' DO NOTHING", UUID.randomUUID(), shopId, productId,
                at(now), at(now), at(now));
    }

    /**
     * Whether GS1 matched the product by its name (FOUND with a barcode): not when the job found nothing to do because
     * the shop had already linked it (a scan, a barcode since removed).
     */
    public boolean matchedByName(UUID productId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM kirana_gs1_lookups WHERE "
                + "kind = 'NAME' AND product_id = ? AND status = 'FOUND' AND code IS NOT NULL)", Boolean.class,
                productId));
    }

    /**
     * Lookups taken for a run that couldn't use DataKart at all (a wrong key or address): given back as they were, the
     * try not counted, to be taken again after {@code until}.
     */
    public void giveBack(List<UUID> ids, Instant until, Instant now) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.update("UPDATE kirana_gs1_lookups SET tries = greatest(tries - 1, 0), next_try_at = ?, updated_at = ? "
                + "WHERE id = ANY (?) AND status IN ('WAITING', 'NOT_FOUND')", ps -> {
                    ps.setObject(1, at(until));
                    ps.setObject(2, at(now));
                    ps.setArray(3, ps.getConnection().createArrayOf("uuid", ids.toArray()));
                });
    }

    /** Locks the lookup: true while it is still being looked for (not dropped, refused or found meanwhile). */
    public boolean stillOpen(UUID id) {
        return !jdbc.queryForList("SELECT 1 FROM kirana_gs1_lookups WHERE id = ? AND status IN ('WAITING', "
                + "'NOT_FOUND') FOR UPDATE", Integer.class, id).isEmpty();
    }

    /** The product's name lookup forgotten (renamed: looked for afresh under its new name, if at all). */
    public void dropName(UUID productId) {
        jdbc.update("DELETE FROM kirana_gs1_lookups WHERE kind = 'NAME' AND product_id = ?", productId);
    }

    /** The shop said GS1's match isn't its product: not looked for again under this name. */
    public void refuseName(UUID productId, String note, Instant now) {
        jdbc.update("UPDATE kirana_gs1_lookups SET status = 'REFUSED', note = ?, updated_at = ? WHERE kind = 'NAME' "
                + "AND product_id = ?", note, at(now), productId);
    }

    /**
     * How many lookups stand at each status (admin): WAITING, FOUND, NOT_FOUND, REFUSED.
     */
    public java.util.Map<String, Long> counts() {
        java.util.Map<String, Long> out = new java.util.TreeMap<>();
        jdbc.query("SELECT status, count(*) FROM kirana_gs1_lookups GROUP BY status",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> out.put(rs.getString(1), rs.getLong(2)));
        return out;
    }

    /** A lookup as the admin sees it: what was looked up, for which shop and product, and how it ended. */
    public record AdminRow(UUID id, String kind, String code, String status, String note, int tries, UUID shopId,
                           String shopName, UUID productId, String productName, String itemName, Instant updatedAt) {
    }

    /** Lookups at one status, newest change first, a page at a time (after the last row's time and id). */
    public List<AdminRow> byStatus(String status, Instant afterAt, UUID afterId, int limit) {
        return jdbc.query("SELECT l.id, l.kind, l.code, l.status, l.note, l.tries, l.shop_id, s.name AS shop_name, "
                        + "l.product_id, p.name AS product_name, i.name AS item_name, l.updated_at "
                        + "FROM kirana_gs1_lookups l LEFT JOIN shops s ON s.id = l.shop_id "
                        + "LEFT JOIN kirana_products p ON p.id = l.product_id "
                        + "LEFT JOIN kirana_catalogue_items i ON i.id = l.item_id WHERE l.status = ? "
                        + "AND (?::timestamptz IS NULL OR (l.updated_at, l.id) < (?::timestamptz, ?::uuid)) "
                        + "ORDER BY l.updated_at DESC, l.id DESC LIMIT ?",
                (rs, i) -> new AdminRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getInt(6), rs.getObject(7, UUID.class), rs.getString(8),
                        rs.getObject(9, UUID.class), rs.getString(10), rs.getString(11),
                        rs.getObject(12, OffsetDateTime.class).toInstant()),
                status, afterAt == null ? null : at(afterAt), afterAt == null ? null : at(afterAt), afterId, limit);
    }

    /**
     * Up to {@code limit} lookups due, taken for this run: not due again until {@code holdUntil} (a server that stops
     * midway leaves them to be taken again then), each try counted. Servers never take the same rows.
     */
    public List<Lookup> claim(Instant now, Instant holdUntil, int limit) {
        return jdbc.query("UPDATE kirana_gs1_lookups l SET next_try_at = ?, tries = l.tries + 1, updated_at = ? "
                + "WHERE l.id IN (SELECT id FROM kirana_gs1_lookups WHERE status IN ('WAITING', 'NOT_FOUND') "
                + "AND next_try_at <= ? ORDER BY next_try_at LIMIT ? FOR UPDATE SKIP LOCKED) "
                + "RETURNING l.id, l.kind, l.code, l.item_id, l.shop_id, l.product_id, l.status, l.tries",
                Gs1Store::lookup, at(holdUntil), at(now), at(now), limit);
    }

    /**
     * How a lookup ended (or when to try again, still WAITING or NOT_FOUND), with the barcode found and why. One the
     * shop refused meanwhile ("Not this product") stays refused.
     */
    public void done(UUID id, String status, String code, String note, Instant nextTryAt, Instant now) {
        // tries counts failures in a row: an answer (anything but still WAITING) starts it again
        jdbc.update("UPDATE kirana_gs1_lookups SET status = ?, code = coalesce(?, code), note = ?, next_try_at = ?, "
                + "tries = CASE WHEN ? THEN 0 ELSE tries END, updated_at = ? "
                + "WHERE id = ? AND status IN ('WAITING', 'NOT_FOUND')", status, code,
                note == null || note.length() <= 300 ? note : note.substring(0, 299) + "…", at(nextTryAt),
                !"WAITING".equals(status), at(now), id);
    }

    private static Lookup lookup(ResultSet rs, int i) throws SQLException {
        return new Lookup(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("code"),
                rs.getObject("item_id", UUID.class), rs.getObject("shop_id", UUID.class),
                rs.getObject("product_id", UUID.class), rs.getString("status"), rs.getInt("tries"));
    }

    private static OffsetDateTime at(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}
