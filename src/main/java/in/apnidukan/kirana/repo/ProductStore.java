package in.apnidukan.kirana.repo;

import in.apnidukan.kirana.domain.Barcode;
import in.apnidukan.kirana.domain.Product;
import in.apnidukan.kirana.domain.Unit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static in.apnidukan.kirana.repo.KiranaSettingsStore.at;

/** kirana_products, kirana_barcodes and kirana_stock_moves (V66), in plain SQL. */
@Repository
public class ProductStore {

    private static final String COLUMNS = "SELECT id, shop_id, name, unit, price_paise, stock, low_stock_at, "
            + "active, version, updated_at, counted_at FROM kirana_products";

    private static final String BILL_MOVES = "SELECT m.product_id, p.name, p.unit, m.change, m.stock_after, "
            + "p.low_stock_at FROM kirana_stock_moves m JOIN kirana_products p ON p.id = m.product_id "
            + "WHERE m.bill_id = ? AND m.reason = ?";

    private final JdbcTemplate jdbc;

    public ProductStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A stock change to record: the product, by how much, why, and the stock after it.
     * @param clientRef the app's id for a RESTOCK/DAMAGED/COUNT (a retry is kept once); null for bills
     */
    public record Move(UUID productId, BigDecimal change, BigDecimal stockAfter, String reason, UUID billId,
                       String note, UUID clientRef) {
    }

    /** A bill's change to one product: the product's name and unit, how much, and the stock after it. */
    public record BillMove(UUID productId, String name, String unit, BigDecimal change, BigDecimal stockAfter,
                           BigDecimal lowStockAt) {
    }

    /** A recorded stock change, for the product's history. */
    public record MoveRow(BigDecimal change, BigDecimal stockAfter, String reason, UUID billId, Long billNo,
                          String note, UUID by, Instant at) {
    }

    /** A scan's match: the active product and how many of it the scanned pack holds. */
    public record Scan(Product product, BigDecimal pack) {
    }

    public void insert(Product p, UUID by, Instant now) {
        jdbc.update("INSERT INTO kirana_products (id, shop_id, name, unit, price_paise, stock, low_stock_at, "
                        + "active, version, created_by, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, true, 0, ?, ?, ?)",
                p.id(), p.shopId(), p.name(), p.unit().name(), p.pricePaise(), p.stock(), p.lowStockAt(),
                by, at(now), at(now));
    }

    /** Links a barcode to an active product (the caller checked it is free and holds the product's lock). */
    public void addBarcode(UUID shopId, UUID productId, Barcode b, UUID by, Instant now) {
        jdbc.update("INSERT INTO kirana_barcodes (product_id, code, shop_id, pack, active, created_by, created_at) "
                + "VALUES (?, ?, ?, ?, true, ?, ?)", productId, b.code(), shopId, b.pack(), by, at(now));
    }

    /** Corrects the pack of one of the product's barcodes. */
    public void setPack(UUID productId, String code, BigDecimal pack) {
        jdbc.update("UPDATE kirana_barcodes SET pack = ? WHERE product_id = ? AND code = ?", pack, productId, code);
    }

    /** Unlinks a barcode from the product; whether it had it. */
    public boolean removeBarcode(UUID productId, String code) {
        return jdbc.update("DELETE FROM kirana_barcodes WHERE product_id = ? AND code = ?", productId, code) > 0;
    }

    /** The product's barcodes, active or not (a removed product keeps them inactive), in the order linked. */
    public List<Barcode> barcodes(UUID productId) {
        return jdbc.query("SELECT code, pack FROM kirana_barcodes WHERE product_id = ? ORDER BY created_at, code",
                (rs, i) -> new Barcode(rs.getString(1), rs.getBigDecimal(2)), productId);
    }

    /** The barcodes of these products in one query, by product (products without any are left out). */
    public java.util.Map<UUID, List<Barcode>> barcodes(Collection<UUID> productIds) {
        java.util.Map<UUID, List<Barcode>> out = new java.util.HashMap<>();
        if (productIds.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT product_id, code, pack FROM kirana_barcodes WHERE product_id = ANY (?) "
                        + "ORDER BY product_id, created_at, code",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", productIds.toArray())),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> out
                        .computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                        .add(new Barcode(rs.getString(2), rs.getBigDecimal(3))));
        return out;
    }

    /**
     * Follows the product being removed (active false: its barcodes are freed for other products, kept to come back)
     * or put back (true: its barcodes come back, except any another active product of the shop has now, which are
     * dropped). The caller holds the product's lock.
     */
    public void setBarcodesActive(UUID shopId, UUID productId, boolean active) {
        if (!active) {
            jdbc.update("UPDATE kirana_barcodes SET active = false WHERE product_id = ?", productId);
            return;
        }
        jdbc.update("DELETE FROM kirana_barcodes b WHERE b.product_id = ? AND EXISTS (SELECT 1 FROM kirana_barcodes o "
                + "WHERE o.shop_id = ? AND o.code = b.code AND o.active AND o.product_id <> b.product_id)",
                productId, shopId);
        jdbc.update("UPDATE kirana_barcodes SET active = true WHERE product_id = ?", productId);
    }

    public Optional<Product> find(UUID shopId, UUID id) {
        return jdbc.query(COLUMNS + " WHERE id = ? AND shop_id = ?", ProductStore::product, id, shopId).stream()
                .findFirst();
    }

    /** These products of the shop (active or not) in one query. */
    public List<Product> findAll(UUID shopId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query(COLUMNS + " WHERE shop_id = ? AND id = ANY (?)", ps -> {
            ps.setObject(1, shopId);
            ps.setArray(2, ps.getConnection().createArrayOf("uuid", ids.toArray()));
        }, ProductStore::product);
    }

    /** The shop's active product with this barcode, and the barcode's pack. */
    public Optional<Scan> findByBarcode(UUID shopId, String barcode) {
        return jdbc.query("SELECT p.id, p.shop_id, p.name, p.unit, p.price_paise, p.stock, p.low_stock_at, p.active, "
                        + "p.version, p.updated_at, p.counted_at, b.pack FROM kirana_barcodes b JOIN kirana_products p "
                        + "ON p.id = b.product_id WHERE b.shop_id = ? AND b.code = ? AND b.active",
                (rs, i) -> new Scan(product(rs, i), rs.getBigDecimal("pack")), shopId, barcode).stream().findFirst();
    }

    /** {@link #findByBarcode} for many barcodes in one query, by barcode (unknown ones left out). */
    public java.util.Map<String, Scan> findByBarcodes(UUID shopId, Collection<String> codes) {
        java.util.Map<String, Scan> out = new java.util.HashMap<>();
        if (codes.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT p.id, p.shop_id, p.name, p.unit, p.price_paise, p.stock, p.low_stock_at, p.active, "
                        + "p.version, p.updated_at, p.counted_at, b.pack, b.code FROM kirana_barcodes b "
                        + "JOIN kirana_products p ON p.id = b.product_id WHERE b.shop_id = ? AND b.code = ANY (?) AND b.active",
                ps -> {
                    ps.setObject(1, shopId);
                    ps.setArray(2, ps.getConnection().createArrayOf("varchar", codes.toArray()));
                },
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> out.put(rs.getString("code"),
                        new Scan(product(rs, 0), rs.getBigDecimal("pack"))));
        return out;
    }

    /** How many barcodes the product has (active or not). */
    public int barcodeCount(UUID productId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM kirana_barcodes WHERE product_id = ?", Integer.class,
                productId);
        return n == null ? 0 : n;
    }

    /** The shop's active product with this name, case aside. */
    public Optional<Product> findByName(UUID shopId, String name) {
        return jdbc.query(COLUMNS + " WHERE shop_id = ? AND active AND lower(name) = lower(?)", ProductStore::product,
                shopId, name).stream().findFirst();
    }

    /**
     * Waits until no other request handles the same barcode in the shop (linking it, a product with it restored), so
     * their checks for a free barcode never race. A transaction-level advisory lock: released at commit or rollback.
     * Callers take these in code order.
     */
    public void lockBarcode(UUID shopId, String code) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class,
                "kirana-barcode:" + shopId + ":" + code);
    }

    /** Links the product to a shared catalogue entry (the same item in every shop). */
    public void setCatalogueItem(UUID productId, UUID itemId) {
        jdbc.update("UPDATE kirana_products SET catalogue_item_id = ? WHERE id = ?", itemId, productId);
    }

    /** The product's shared catalogue entry, if it has one. */
    public UUID catalogueItemOf(UUID productId) {
        List<UUID> ids = jdbc.queryForList("SELECT catalogue_item_id FROM kirana_products WHERE id = ?", UUID.class,
                productId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** Whether the product was sold on any bill. */
    public boolean everSold(UUID productId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM kirana_bill_lines "
                + "WHERE product_id = ?) OR EXISTS (SELECT 1 FROM kirana_order_lines WHERE product_id = ?)",
                Boolean.class, productId, productId));
    }

    /** Whether another active product of the shop (not {@code self}) has this name, case aside. */
    public boolean nameTaken(UUID shopId, String name, UUID self) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM kirana_products WHERE shop_id = ? "
                + "AND active AND lower(name) = lower(?) AND id IS DISTINCT FROM ?)", Boolean.class, shopId, name, self));
    }

    /**
     * One page of the shop's active products in name order, after (afterName, afterId) when given. With
     * {@code words} (lower case, LIKE wildcards escaped): names containing it anywhere, or one of its barcodes starting
     * with it (typed digits of a pack that won't scan). Reads one more than {@code limit}, so the caller knows whether another
     * page follows.
     */
    public List<Row> page(UUID shopId, String words, String afterName, UUID afterId, int limit) {
        StringBuilder sql = new StringBuilder(COLUMNS.replace(" FROM", ", lower(name) AS sort_key FROM"))
                .append(" WHERE shop_id = ? AND active");
        List<Object> args = new ArrayList<>();
        args.add(shopId);
        if (words != null) {
            sql.append(" AND (lower(name) LIKE ? ESCAPE '\\' OR EXISTS (SELECT 1 FROM kirana_barcodes b WHERE "
                    + "b.shop_id = kirana_products.shop_id AND b.product_id = kirana_products.id AND b.active "
                    + "AND b.code LIKE ? ESCAPE '\\'))");
            args.add("%" + words + "%");
            args.add(words.toUpperCase(java.util.Locale.ROOT) + "%");
        }
        if (afterName != null) {
            sql.append(" AND (lower(name) COLLATE \"C\", id) > (?, ?)");
            args.add(afterName);
            args.add(afterId);
        }
        sql.append(" ORDER BY lower(name) COLLATE \"C\", id LIMIT ?");
        args.add(limit + 1);
        return jdbc.query(sql.toString(), (rs, i) -> new Row(product(rs, i), rs.getString("sort_key")),
                args.toArray());
    }

    /** A product on a page, with the key it was sorted by (Postgres's lower(name), for the next page's token). */
    public record Row(Product product, String sortKey) {
    }

    /** The shop's active products at or below their alert, or below zero; fewest days' worth first is the app's job. */
    public List<Product> runningOut(UUID shopId, int limit) {
        return jdbc.query(COLUMNS + " WHERE shop_id = ? AND active AND ((low_stock_at IS NOT NULL AND stock <= "
                + "low_stock_at) OR stock < 0) ORDER BY lower(name), id LIMIT ?", ProductStore::product, shopId, limit);
    }

    /**
     * These products of the shop, each row locked until the transaction ends, in id order (every writer locks them in
     * this order, so two bills with the same products never wait on each other in a circle).
     */
    public List<Product> lockAll(UUID shopId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query(COLUMNS + " WHERE shop_id = ? AND id = ANY (?) ORDER BY id FOR NO KEY UPDATE",
                ps -> {
                    ps.setObject(1, shopId);
                    ps.setArray(2, ps.getConnection().createArrayOf("uuid", ids.toArray()));
                },
                ProductStore::product);
    }

    /** One product of the shop, its row locked until the transaction ends. */
    public Optional<Product> lock(UUID shopId, UUID id) {
        return jdbc.query(COLUMNS + " WHERE id = ? AND shop_id = ? FOR NO KEY UPDATE", ProductStore::product, id,
                shopId).stream().findFirst();
    }

    /** Saves the product's details (not its stock) and moves its version on. */
    public void update(Product p, Instant now) {
        jdbc.update("UPDATE kirana_products SET name = ?, unit = ?, price_paise = ?, low_stock_at = ?, active = ?, "
                        + "version = version + 1, updated_at = ? WHERE id = ? AND shop_id = ?",
                p.name(), p.unit().name(), p.pricePaise(), p.lowStockAt(), p.active(), at(now), p.id(), p.shopId());
    }

    /** Sets the stock of a locked product (the caller worked out the new value under the lock). */
    public void setStock(UUID productId, BigDecimal stock, Instant now) {
        // Below zero: sold more than was counted, so the count no longer holds (it isn't offered online until counted)
        jdbc.update("UPDATE kirana_products SET stock = ?, updated_at = ?, counted_at = CASE WHEN ? < 0 THEN NULL "
                + "ELSE counted_at END WHERE id = ?", stock, at(now), stock, productId);
    }

    /** Its stock was just counted by hand (added with a count, or Count): it may be offered online. */
    public void markCounted(UUID productId, Instant now) {
        jdbc.update("UPDATE kirana_products SET counted_at = ? WHERE id = ?", at(now), productId);
    }

    /** These products were missing for an online order: their count no longer holds until counted again. */
    public void uncount(UUID shopId, java.util.Collection<UUID> productIds) {
        if (productIds.isEmpty()) {
            return;
        }
        jdbc.update("UPDATE kirana_products SET counted_at = NULL WHERE shop_id = ? AND id = ANY (?)",
                ps -> {
                    ps.setObject(1, shopId);
                    ps.setArray(2, ps.getConnection().createArrayOf("uuid", productIds.toArray()));
                });
    }

    /** The shop's active products, and how many of them were never counted (ix_kirana_products_uncounted). */
    public long[] countedTotals(UUID shopId) {
        return jdbc.queryForObject("SELECT count(*), count(*) FILTER (WHERE counted_at IS NULL) FROM kirana_products "
                + "WHERE shop_id = ? AND active", (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)}, shopId);
    }

    /** A product by id and name. */
    public record Named(UUID id, String name) {
    }

    /** Up to {@code limit} active products not counted yet, by name (to show what is left). */
    public List<Named> uncounted(UUID shopId, int limit) {
        return jdbc.query("SELECT id, name FROM kirana_products WHERE shop_id = ? AND active AND counted_at IS NULL "
                + "ORDER BY lower(name) LIMIT ?", (rs, i) -> new Named(rs.getObject("id", UUID.class),
                rs.getString("name")), shopId, limit);
    }

    /** Records stock changes in one round trip. */
    public void addMoves(UUID shopId, List<Move> moves, UUID by, Instant now) {
        if (moves.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO kirana_stock_moves (id, shop_id, product_id, change, stock_after, reason, bill_id, "
                        + "note, client_ref, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", moves,
                moves.size(),
                (ps, m) -> {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, shopId);
                    ps.setObject(3, m.productId());
                    ps.setBigDecimal(4, m.change());
                    ps.setBigDecimal(5, m.stockAfter());
                    ps.setString(6, m.reason());
                    ps.setObject(7, m.billId());
                    ps.setString(8, m.note());
                    ps.setObject(9, m.clientRef());
                    ps.setObject(10, by);
                    ps.setObject(11, at(now));
                });
    }

    /** Records an online order part's stock changes (ORDER taken on accepting, ORDER_BACK given back) in one round trip. */
    public void addOrderMoves(UUID shopId, UUID partId, List<Move> moves, UUID by, Instant now) {
        if (moves.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO kirana_stock_moves (id, shop_id, product_id, change, stock_after, reason, "
                        + "order_part_id, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", moves, moves.size(),
                (ps, m) -> {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, shopId);
                    ps.setObject(3, m.productId());
                    ps.setBigDecimal(4, m.change());
                    ps.setBigDecimal(5, m.stockAfter());
                    ps.setString(6, m.reason());
                    ps.setObject(7, partId);
                    ps.setObject(8, by);
                    ps.setObject(9, at(now));
                });
    }

    /**
     * A stock change from a bill or a scan: as {@link Move}, with the distributor's bill it came on or the barcode
     * scanned.
     */
    public record StockMove(UUID productId, BigDecimal change, BigDecimal stockAfter, String reason, UUID purchaseId,
                            String barcode, String note, UUID clientRef) {
    }

    /** Records stock changes from bills and scans in one round trip. */
    public void addStockMoves(UUID shopId, List<StockMove> moves, UUID by, Instant now) {
        if (moves.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO kirana_stock_moves (id, shop_id, product_id, change, stock_after, reason, "
                        + "purchase_id, barcode, note, client_ref, created_by, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", moves, moves.size(),
                (ps, m) -> {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, shopId);
                    ps.setObject(3, m.productId());
                    ps.setBigDecimal(4, m.change());
                    ps.setBigDecimal(5, m.stockAfter());
                    ps.setString(6, m.reason());
                    ps.setObject(7, m.purchaseId());
                    ps.setString(8, m.barcode());
                    ps.setString(9, m.note());
                    ps.setObject(10, m.clientRef());
                    ps.setObject(11, by);
                    ps.setObject(12, at(now));
                });
    }

    /** Stock that came in by scanning this barcode into this product (what a barcode moved elsewhere brought). */
    public BigDecimal scannedIn(UUID productId, String code) {
        BigDecimal n = jdbc.queryForObject("SELECT coalesce(sum(change), 0) FROM kirana_stock_moves "
                + "WHERE product_id = ? AND barcode = ? AND reason = 'RESTOCK'", BigDecimal.class, productId, code);
        return n == null ? BigDecimal.ZERO : n;
    }

    /** Moves one of a product's barcodes to another product (both locked by the caller). */
    public void moveBarcode(UUID fromProductId, UUID toProductId, String code) {
        jdbc.update("UPDATE kirana_barcodes SET product_id = ? WHERE product_id = ? AND code = ?", toProductId,
                fromProductId, code);
    }

    /** What was paid per piece (or kg) on the last distributor's bill. */
    public void setCost(UUID productId, Long costPaise) {
        jdbc.update("UPDATE kirana_products SET cost_paise = ? WHERE id = ?", costPaise, productId);
    }

    /** The shop's own photo of the product, if it has one. */
    public UUID ownPhoto(UUID productId) {
        List<UUID> f = jdbc.queryForList("SELECT photo_file_id FROM kirana_products WHERE id = ? "
                + "AND photo_file_id IS NOT NULL", UUID.class, productId);
        return f.isEmpty() ? null : f.get(0);
    }

    /**
     * Whether a kirana still points at this file: a product's photo, a catalogue item's shared photo, a paper bill's
     * photo, an FSSAI certificate, or a read (other than {@code exceptRead}) still being read, read or used. Uploads of
     * the same bytes share one file, so one product's old photo may be another's.
     */
    public boolean fileInUse(UUID fileId, UUID exceptRead) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM kirana_products "
                + "WHERE photo_file_id = ?) OR EXISTS (SELECT 1 FROM kirana_catalogue_items WHERE photo_file_id = ?) "
                + "OR EXISTS (SELECT 1 FROM kirana_bills WHERE photo_file_id = ?) "
                + "OR EXISTS (SELECT 1 FROM kirana_online WHERE fssai_file_id = ? OR fssai_kept_file_id = ?) "
                + "OR EXISTS (SELECT 1 FROM kirana_reads WHERE file_ids @> ARRAY[?]::uuid[] "
                + "AND status IN ('READING', 'READ', 'DONE') AND id IS DISTINCT FROM ?)", Boolean.class, fileId,
                fileId, fileId, fileId, fileId, fileId, exceptRead));
    }

    /** The shop's own photo of the product (null: none, the catalogue item's shows). */
    public void setPhoto(UUID productId, UUID fileId) {
        jdbc.update("UPDATE kirana_products SET photo_file_id = ? WHERE id = ?", fileId, productId);
    }

    /**
     * What a product view adds: the cost (owner only), the photo (the shop's own, else the catalogue item's shared
     * one) and the catalogue item.
     */
    public record Extra(Long costPaise, UUID photoFileId, UUID catalogueItemId, String category) {
    }

    /** The extras of these products in one query. */
    public java.util.Map<UUID, Extra> extras(Collection<UUID> productIds) {
        java.util.Map<UUID, Extra> out = new java.util.HashMap<>();
        if (productIds.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT p.id, p.cost_paise, coalesce(p.photo_file_id, i.photo_file_id), p.catalogue_item_id, "
                        + "i.category FROM kirana_products p LEFT JOIN kirana_catalogue_items i "
                        + "ON i.id = p.catalogue_item_id WHERE p.id = ANY (?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", productIds.toArray())),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> out.put(rs.getObject(1, UUID.class),
                        new Extra((Long) rs.getObject(2), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                                rs.getString(5))));
        return out;
    }

    /** Whether a stock change with this clientRef was already recorded in the shop (a retry). */
    public boolean moveRecorded(UUID shopId, UUID clientRef) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM kirana_stock_moves WHERE "
                + "shop_id = ? AND client_ref = ?)", Boolean.class, shopId, clientRef));
    }

    /** A bill's stock changes of one kind (SALE, VOID or RETURN), with each product's name, unit and alert, in product order. */
    public List<BillMove> billMoves(UUID billId, String reason) {
        return billMoves(billId, reason, null);
    }

    /** As {@link #billMoves(UUID, String)}, only those made at {@code at} when given (one return of several). */
    public List<BillMove> billMoves(UUID billId, String reason, Instant at) {
        org.springframework.jdbc.core.RowMapper<BillMove> row = (rs, i) -> new BillMove(rs.getObject(1, UUID.class),
                rs.getString(2), rs.getString(3), rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6));
        if (at == null) {
            return jdbc.query(BILL_MOVES + " ORDER BY m.product_id", row, billId, reason);
        }
        return jdbc.query(BILL_MOVES + " AND m.created_at = ? ORDER BY m.product_id", row, billId, reason, at(at));
    }

    /** The product's latest stock changes, newest first. */
    public List<MoveRow> moves(UUID productId, int limit) {
        return jdbc.query("SELECT m.change, m.stock_after, m.reason, m.bill_id, b.bill_no, m.note, m.created_by, "
                        + "m.created_at FROM kirana_stock_moves m LEFT JOIN kirana_bills b ON b.id = m.bill_id "
                        + "WHERE m.product_id = ? ORDER BY m.created_at DESC, m.id DESC LIMIT ?",
                (rs, i) -> new MoveRow(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getString(3),
                        rs.getObject(4, UUID.class), (Long) rs.getObject(5), rs.getString(6),
                        rs.getObject(7, UUID.class), rs.getObject(8, OffsetDateTime.class).toInstant()),
                productId, limit);
    }

    static Product product(ResultSet rs, int i) throws SQLException {
        return new Product(rs.getObject("id", UUID.class), rs.getObject("shop_id", UUID.class), rs.getString("name"),
                Unit.valueOf(rs.getString("unit")), rs.getLong("price_paise"),
                rs.getBigDecimal("stock"), rs.getBigDecimal("low_stock_at"), rs.getBoolean("active"),
                rs.getLong("version"), rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                Optional.ofNullable(rs.getObject("counted_at", OffsetDateTime.class)).map(OffsetDateTime::toInstant)
                        .orElse(null));
    }
}
