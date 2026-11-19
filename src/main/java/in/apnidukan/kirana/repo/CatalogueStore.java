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

    /** {@link #findByCode} for many barcodes in one query, with whether each link is trusted, by barcode. */
    public java.util.Map<String, Known> findByCodes(java.util.Collection<String> codes) {
        java.util.Map<String, Known> out = new java.util.HashMap<>();
        if (codes.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT i.id, i.name, i.brand, i.size, i.unit, i.mrp_paise, i.category, i.version, "
                        + "i.updated_at, b.pack, b.code, b.confirmed FROM kirana_catalogue_barcodes b "
                        + "JOIN kirana_catalogue_items i ON i.id = b.item_id WHERE b.code = ANY (?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("varchar", codes.toArray())),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> out.put(rs.getString("code"),
                        new Known(item(rs, 0), rs.getBigDecimal("pack"), rs.getBoolean("confirmed"))));
        return out;
    }

    /** A barcode's entry with its pack and whether the link is trusted. */
    public record Known(Item item, BigDecimal pack, boolean trusted) {
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
        boolean added = jdbc.update("INSERT INTO kirana_catalogue_barcodes (code, item_id, pack, created_at) "
                + "VALUES (?, ?, ?, ?) ON CONFLICT (code) DO NOTHING", code, itemId, pack, at(now)) > 0;
        if (added) {
            queuePicture(code, itemId, now);
        }
        return added;
    }

    /** A barcode new to the catalogue: its item's official picture looked up on GS1, if it has none (Gs1Store). */
    private void queuePicture(String code, UUID itemId, Instant now) {
        jdbc.update("INSERT INTO kirana_gs1_lookups (id, kind, code, item_id, status, next_try_at, created_at, "
                + "updated_at) SELECT ?, 'CODE', ?, i.id, 'WAITING', ?, ?, ? FROM kirana_catalogue_items i "
                + "WHERE i.id = ? AND i.photo_file_id IS NULL ON CONFLICT (code) WHERE kind = 'CODE' DO NOTHING",
                UUID.randomUUID(), code, at(now), at(now), at(now), itemId);
    }

    /**
     * Where a barcode's link came from and whether it is trusted.
     * @param source    SHOP (an owner's pick), MASTER (a distributor's product master) or ADMIN
     * @param confirmed trusted: from a product master or the team, or two shops agree; a shop's own pick alone is
     *                  only a suggestion to other shops
     */
    public record Link(String code, UUID itemId, BigDecimal pack, String source, boolean confirmed) {
    }

    public Optional<Link> link(String code) {
        return jdbc.query("SELECT code, item_id, pack, source, confirmed FROM kirana_catalogue_barcodes WHERE code = ?",
                (rs, i) -> new Link(rs.getString(1), rs.getObject(2, UUID.class), rs.getBigDecimal(3), rs.getString(4),
                        rs.getBoolean(5)), code).stream().findFirst();
    }

    /** The barcode's link row locked (votes and corrections on one barcode go one at a time). */
    public Optional<Link> lockLink(String code) {
        return jdbc.query("SELECT code, item_id, pack, source, confirmed FROM kirana_catalogue_barcodes WHERE code = ? "
                + "FOR NO KEY UPDATE", (rs, i) -> new Link(rs.getString(1), rs.getObject(2, UUID.class),
                rs.getBigDecimal(3), rs.getString(4), rs.getBoolean(5)), code).stream().findFirst();
    }

    /** Adds a barcode with where it came from; false when another entry has it already. */
    public boolean addCode(UUID itemId, String code, BigDecimal pack, String source, boolean confirmed, Instant now) {
        boolean added = jdbc.update("INSERT INTO kirana_catalogue_barcodes (code, item_id, pack, source, confirmed, "
                + "created_at) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (code) DO NOTHING", code, itemId, pack, source,
                confirmed, at(now)) > 0;
        if (added) {
            queuePicture(code, itemId, now);
        }
        return added;
    }

    /** Points a barcode at another entry (a correction or the team's decision). */
    public void relink(String code, UUID itemId, BigDecimal pack, String source, boolean confirmed) {
        jdbc.update("UPDATE kirana_catalogue_barcodes SET item_id = ?, pack = coalesce(?, pack), source = ?, "
                + "confirmed = ? WHERE code = ?", itemId, pack, source, confirmed, code);
        // Its GS1 lookup follows it (a picture found is checked against the item it belongs to now)
        jdbc.update("UPDATE kirana_gs1_lookups SET item_id = ? WHERE kind = 'CODE' AND code = ?", itemId, code);
    }

    public void confirm(String code) {
        jdbc.update("UPDATE kirana_catalogue_barcodes SET confirmed = true WHERE code = ?", code);
    }

    /** The shop says this barcode is this entry (its own link); replaces the shop's earlier say. */
    public void vote(String code, UUID shopId, UUID itemId, Instant now) {
        jdbc.update("INSERT INTO kirana_barcode_votes (code, shop_id, item_id, created_at) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT (code, shop_id) DO UPDATE SET item_id = EXCLUDED.item_id, "
                + "created_at = EXCLUDED.created_at", code, shopId, itemId, at(now));
    }

    /** How many shops say this barcode is this entry. */
    public int votes(String code, UUID itemId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM kirana_barcode_votes WHERE code = ? AND item_id = ?",
                Integer.class, code, itemId);
        return n == null ? 0 : n;
    }

    /** Which shops said what about a barcode (the review list shows it). */
    public java.util.Map<UUID, Integer> votesByItem(String code) {
        java.util.Map<UUID, Integer> out = new java.util.HashMap<>();
        jdbc.query("SELECT item_id, count(*) FROM kirana_barcode_votes WHERE code = ? GROUP BY item_id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> out.put(rs.getObject(1, UUID.class),
                        rs.getInt(2)), code);
        return out;
    }

    /**
     * The brand most catalogue items with barcodes starting with {@code prefix} have (the same company prefix: very
     * likely the same brand owner); null when none is known. {@code prefix} is digits only.
     */
    public String brandByPrefix(String prefix) {
        List<String> b = jdbc.queryForList("SELECT i.brand FROM kirana_catalogue_barcodes c JOIN kirana_catalogue_items i "
                + "ON i.id = c.item_id WHERE c.code LIKE ? AND i.brand IS NOT NULL GROUP BY i.brand "
                + "ORDER BY count(*) DESC, i.brand LIMIT 1", String.class, prefix + "%");
        return b.isEmpty() ? null : b.get(0);
    }

    /** The entry's shared photo, when it has none yet; whether it was set. */
    public boolean setPhotoIfNone(UUID itemId, UUID fileId) {
        return jdbc.update("UPDATE kirana_catalogue_items SET photo_file_id = ? WHERE id = ? AND photo_file_id IS NULL",
                fileId, itemId) > 0;
    }

    /** The shared photos of these entries, by entry (entries without one are left out). */
    public java.util.Map<UUID, UUID> photos(java.util.Collection<UUID> itemIds) {
        java.util.Map<UUID, UUID> out = new java.util.HashMap<>();
        if (itemIds.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT id, photo_file_id FROM kirana_catalogue_items WHERE id = ANY (?) AND photo_file_id IS NOT NULL",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", itemIds.toArray())),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> out.put(rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class)));
        return out;
    }

    /** The entry an imported row (or a shop's new product) matches by name, size and unit, if one does exactly. */
    public Optional<Item> sameItem(String name, String size, Unit unit) {
        return jdbc.query(ITEM + " WHERE lower(name) = lower(?) AND size IS NOT DISTINCT FROM ? AND unit = ? "
                + "ORDER BY created_at, id LIMIT 1", CatalogueStore::item, name, size, unit.name()).stream().findFirst();
    }

    /** Fills an entry's brand, size, MRP and category where they are still empty (a product master's row). */
    public void fillIn(UUID itemId, String brand, String size, Long mrpPaise, String category, Instant now) {
        jdbc.update("UPDATE kirana_catalogue_items SET brand = coalesce(brand, ?), size = coalesce(size, ?), "
                + "mrp_paise = coalesce(mrp_paise, ?), category = coalesce(category, ?), updated_at = ? WHERE id = ?",
                brand, size, mrpPaise, category, at(now), itemId);
    }

    // ---------- the team's review list ----------

    /**
     * A barcode to review.
     * @param kind CONFLICT (shops disagree), CORRECTION (a trusted link corrected by a shop), IMPORT (a product master's
     *             row clashes)
     */
    public record Review(UUID id, String code, String kind, UUID itemId, UUID otherItemId, UUID shopId, String note,
                         String status, Instant createdAt) {
    }

    /** Opens a review of the barcode; nothing when one is open for it already. */
    public boolean addReview(Review r) {
        return jdbc.update("INSERT INTO kirana_catalogue_reviews (id, code, kind, item_id, other_item_id, shop_id, note, status, "
                        + "created_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'OPEN', ?) ON CONFLICT (code) WHERE status = 'OPEN' "
                        + "DO NOTHING", r.id(), r.code(), r.kind(), r.itemId(), r.otherItemId(), r.shopId(), r.note(),
                at(r.createdAt())) > 0;
    }

    /**
     * Removes an item nothing uses (no barcode, no shop's product, no shop's say, no open review): one a product master
     * proposed that lost.
     */
    public void deleteIfUnused(UUID itemId) {
        jdbc.update("DELETE FROM kirana_catalogue_items i WHERE id = ? "
                + "AND NOT EXISTS (SELECT 1 FROM kirana_catalogue_barcodes WHERE item_id = i.id) "
                + "AND NOT EXISTS (SELECT 1 FROM kirana_products WHERE catalogue_item_id = i.id) "
                + "AND NOT EXISTS (SELECT 1 FROM kirana_barcode_votes WHERE item_id = i.id) "
                + "AND NOT EXISTS (SELECT 1 FROM kirana_catalogue_reviews WHERE status = 'OPEN' "
                + "AND (item_id = i.id OR other_item_id = i.id)) "
                + "AND NOT EXISTS (SELECT 1 FROM kirana_order_lines WHERE catalogue_item_id = i.id)", itemId);
    }

    /** Open reviews, oldest first. */
    public List<Review> openReviews(int limit) {
        return jdbc.query("SELECT id, code, kind, item_id, other_item_id, shop_id, note, status, created_at "
                + "FROM kirana_catalogue_reviews WHERE status = 'OPEN' ORDER BY created_at, id LIMIT ?",
                CatalogueStore::review, limit);
    }

    public Optional<Review> lockReview(UUID id) {
        return jdbc.query("SELECT id, code, kind, item_id, other_item_id, shop_id, note, status, created_at "
                + "FROM kirana_catalogue_reviews WHERE id = ? FOR NO KEY UPDATE", CatalogueStore::review, id).stream()
                .findFirst();
    }

    public void closeReview(UUID id, UUID by, Instant now) {
        jdbc.update("UPDATE kirana_catalogue_reviews SET status = 'DONE', decided_by = ?, decided_at = ? WHERE id = ?",
                by, at(now), id);
    }

    /** Items by id (the review list's names). */
    public java.util.Map<UUID, Item> items(java.util.Collection<UUID> ids) {
        java.util.Map<UUID, Item> out = new java.util.HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        jdbc.query(ITEM + " WHERE id = ANY (?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", ids.toArray())),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    Item it = item(rs, 0);
                    out.put(it.id(), it);
                });
        return out;
    }

    private static Review review(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Review(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("kind"),
                rs.getObject("item_id", UUID.class), rs.getObject("other_item_id", UUID.class),
                rs.getObject("shop_id", UUID.class), rs.getString("note"), rs.getString("status"),
                rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant());
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
