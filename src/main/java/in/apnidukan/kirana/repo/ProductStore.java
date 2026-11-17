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
            + "active, version, updated_at FROM kirana_products";

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

    /** The shop's active product with this barcode, and the barcode's pack. */
    public Optional<Scan> findByBarcode(UUID shopId, String barcode) {
        return jdbc.query("SELECT p.id, p.shop_id, p.name, p.unit, p.price_paise, p.stock, p.low_stock_at, p.active, "
                        + "p.version, p.updated_at, b.pack FROM kirana_barcodes b JOIN kirana_products p "
                        + "ON p.id = b.product_id WHERE b.shop_id = ? AND b.code = ? AND b.active",
                (rs, i) -> new Scan(product(rs, i), rs.getBigDecimal("pack")), shopId, barcode).stream().findFirst();
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

    /** Whether the product was sold on any bill. */
    public boolean everSold(UUID productId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM kirana_bill_lines "
                + "WHERE product_id = ?)", Boolean.class, productId));
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
        jdbc.update("UPDATE kirana_products SET stock = ?, updated_at = ? WHERE id = ?", stock, at(now), productId);
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
                rs.getLong("version"), rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
