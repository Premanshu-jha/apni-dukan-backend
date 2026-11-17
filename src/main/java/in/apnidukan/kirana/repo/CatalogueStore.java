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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static in.apnidukan.kirana.repo.KiranaSettingsStore.at;

/**
 * kirana_catalogue_items and kirana_catalogue_barcodes (V68): the shared catalogue, one entry per real product, in
 * plain SQL. A barcode belongs to one entry for every shop.
 */
@Repository
public class CatalogueStore {

    private static final String ITEM = "SELECT id, name, brand, size, unit, mrp_paise, category, version, updated_at "
            + "FROM kirana_catalogue_items";

    private final JdbcTemplate jdbc;

    public CatalogueStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A catalogue entry. */
    public record Item(UUID id, String name, String brand, String size, Unit unit, Long mrpPaise, String category,
                       long version, Instant updatedAt) {
    }

    /** A barcode's entry, and how many of it one scan of that barcode means. */
    public record Match(Item item, BigDecimal pack) {
    }

    /** An entry on a page, with the key it was sorted by (Postgres's lower(name), for the next page's token). */
    public record Row(Item item, String sortKey) {
    }

    public Optional<Match> findByCode(String code) {
        return jdbc.query("SELECT i.id, i.name, i.brand, i.size, i.unit, i.mrp_paise, i.category, i.version, "
                        + "i.updated_at, b.pack FROM kirana_catalogue_barcodes b JOIN kirana_catalogue_items i "
                        + "ON i.id = b.item_id WHERE b.code = ?",
                (rs, i) -> new Match(item(rs, i), rs.getBigDecimal("pack")), code).stream().findFirst();
    }

    public Optional<Item> find(UUID id) {
        return jdbc.query(ITEM + " WHERE id = ?", CatalogueStore::item, id).stream().findFirst();
    }

    /** One entry, its row locked until the transaction ends (a super admin's correction). */
    public Optional<Item> lock(UUID id) {
        return jdbc.query(ITEM + " WHERE id = ? FOR NO KEY UPDATE", CatalogueStore::item, id).stream().findFirst();
    }

    /**
     * A new entry, made by a shop adding a product the catalogue doesn't know (its first barcode follows with
     * {@link #addCode}; when another shop's barcode won the race, {@link #deleteItem} takes this one back).
     */
    public void insertItem(Item item, UUID shopId, Instant now) {
        jdbc.update("INSERT INTO kirana_catalogue_items (id, name, brand, size, unit, mrp_paise, category, "
                        + "created_by_shop, created_at, updated_at, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
                item.id(), item.name(), item.brand(), item.size(), item.unit().name(), item.mrpPaise(), item.category(),
                shopId, at(now), at(now));
    }

    /** Removes an entry that never got its barcode (another shop's barcode won the race). */
    public void deleteItem(UUID id) {
        jdbc.update("DELETE FROM kirana_catalogue_items i WHERE i.id = ? AND NOT EXISTS (SELECT 1 FROM "
                + "kirana_catalogue_barcodes b WHERE b.item_id = i.id) AND NOT EXISTS (SELECT 1 FROM kirana_products p "
                + "WHERE p.catalogue_item_id = i.id)", id);
    }

    /** Adds a barcode to an entry; false (nothing changed) when the barcode belongs to an entry already. */
    public boolean addCode(UUID itemId, String code, BigDecimal pack, Instant now) {
        return jdbc.update("INSERT INTO kirana_catalogue_barcodes (code, item_id, pack, created_at) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT (code) DO NOTHING", code, itemId, pack, at(now)) > 0;
    }

    /** An entry's barcodes, in code order. */
    public List<String> codes(UUID itemId) {
        return jdbc.queryForList("SELECT code FROM kirana_catalogue_barcodes WHERE item_id = ? ORDER BY code",
                String.class, itemId);
    }

    /** The barcodes of these entries in one query, by entry. */
    public java.util.Map<UUID, List<String>> codes(java.util.Collection<UUID> itemIds) {
        java.util.Map<UUID, List<String>> out = new java.util.HashMap<>();
        if (itemIds.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT item_id, code FROM kirana_catalogue_barcodes WHERE item_id = ANY (?) ORDER BY item_id, code",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", itemIds.toArray())),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> out
                        .computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>()).add(rs.getString(2)));
        return out;
    }

    /** Saves a super admin's correction and moves the version on. */
    public void update(Item item, Instant now) {
        jdbc.update("UPDATE kirana_catalogue_items SET name = ?, brand = ?, size = ?, mrp_paise = ?, category = ?, "
                        + "version = version + 1, updated_at = ? WHERE id = ?",
                item.name(), item.brand(), item.size(), item.mrpPaise(), item.category(), at(now), item.id());
    }

    /**
     * One page of the catalogue in name order, after (afterName, afterId) when given. With {@code words} (lower case,
     * LIKE wildcards escaped): names containing it, or a barcode starting with it. Reads one more than limit.
     */
    public List<Row> page(String words, String afterName, UUID afterId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT id, name, brand, size, unit, mrp_paise, category, version, "
                + "updated_at, lower(name) AS sort_key FROM kirana_catalogue_items i WHERE true");
        List<Object> args = new ArrayList<>();
        if (words != null) {
            sql.append(" AND (lower(name) LIKE ? ESCAPE '\\' OR EXISTS (SELECT 1 FROM kirana_catalogue_barcodes b "
                    + "WHERE b.item_id = i.id AND b.code LIKE ? ESCAPE '\\'))");
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
        return jdbc.query(sql.toString(), (rs, i) -> new Row(item(rs, i), rs.getString("sort_key")), args.toArray());
    }

    static Item item(ResultSet rs, int i) throws SQLException {
        return new Item(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("brand"),
                rs.getString("size"), Unit.valueOf(rs.getString("unit")), (Long) rs.getObject("mrp_paise"),
                rs.getString("category"), rs.getLong("version"),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
