package in.apnidukan.kirana.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** kirana_settings (V66): one row per kirana, made on first use. */
@Repository
public class KiranaSettingsStore {

    private final JdbcTemplate jdbc;

    public KiranaSettingsStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The shop's UPI ID and when it was last set; both null until the owner sets it. */
    /** @param sellsFood whether it sells any food (packed or loose); null until the owner answers */
    public record Settings(String upiId, Instant upiUpdatedAt, Boolean sellsFood) {
    }

    public Settings find(UUID shopId) {
        List<Settings> rows = jdbc.query(
                "SELECT upi_id, upi_updated_at, sells_food FROM kirana_settings WHERE shop_id = ?",
                (rs, i) -> {
                    OffsetDateTime at = rs.getObject(2, OffsetDateTime.class);
                    return new Settings(rs.getString(1), at == null ? null : at.toInstant(),
                            rs.getObject(3, Boolean.class));
                }, shopId);
        return rows.isEmpty() ? new Settings(null, null, null) : rows.get(0);
    }

    public boolean hasUpiId(UUID shopId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM kirana_settings WHERE shop_id = ? AND upi_id IS NOT NULL)",
                Boolean.class, shopId));
    }

    /** Whether the shop sells any food (made first if missing). */
    public void setSellsFood(UUID shopId, boolean sellsFood) {
        jdbc.update("INSERT INTO kirana_settings (shop_id, next_bill_no, sells_food) VALUES (?, 1, ?) "
                + "ON CONFLICT (shop_id) DO UPDATE SET sells_food = EXCLUDED.sells_food", shopId, sellsFood);
    }

    /** The FSSAI licence's state (kirana_online): NONE when never added. */
    public String fssaiStatus(UUID shopId) {
        List<String> rows = jdbc.queryForList("SELECT fssai_status FROM kirana_online WHERE shop_id = ?", String.class,
                shopId);
        return rows.isEmpty() ? "NONE" : rows.get(0);
    }

    /** The FSSAI licence as checked so far (NONE when not added), by the primary key. */
    public in.apnidukan.common.context.KiranaFacts.Fssai fssai(UUID shopId) {
        List<in.apnidukan.common.context.KiranaFacts.Fssai> rows = jdbc.query("SELECT fssai_status, fssai_number, "
                        + "fssai_valid_until, fssai_reason FROM kirana_online WHERE shop_id = ?",
                (rs, i) -> new in.apnidukan.common.context.KiranaFacts.Fssai(rs.getString("fssai_status"),
                        rs.getString("fssai_number"), rs.getObject("fssai_valid_until", java.time.LocalDate.class),
                        rs.getString("fssai_reason")), shopId);
        return rows.isEmpty() ? new in.apnidukan.common.context.KiranaFacts.Fssai("NONE", null, null, null)
                : rows.get(0);
    }

    /**
     * What stops a food-selling kirana's FSSAI licence from counting before the visit, in the owner's words; null
     * when it counts (being checked, or verified and not expired by {@code today}, India's day).
     */
    public static String fssaiGap(in.apnidukan.common.context.KiranaFacts.Fssai f, java.time.LocalDate today) {
        return switch (f.status()) {
            case "NONE" -> "add the shop's FSSAI licence: it sells food";
            case "REJECTED" -> "the FSSAI certificate wasn't accepted: add the right one";
            case "VERIFIED" -> f.validUntil() != null && f.validUntil().isBefore(today)
                    ? "the FSSAI licence has expired: add the renewed one" : null;
            default -> null;
        };
    }

    /**
     * Any bill, active product, active khata customer or page of its list of products kept (each an index lookup:
     * V66, ix_kirana_reads_shop).
     */
    public boolean hasCounterData(UUID shopId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM kirana_bills WHERE shop_id = ?) "
                + "OR EXISTS (SELECT 1 FROM kirana_products WHERE shop_id = ? AND active) "
                + "OR EXISTS (SELECT 1 FROM kirana_khata_customers WHERE shop_id = ? AND active) "
                + "OR EXISTS (SELECT 1 FROM kirana_reads WHERE shop_id = ? AND kind = 'STOCK_LIST' "
                + "AND status <> 'DISCARDED')", Boolean.class, shopId, shopId, shopId, shopId));
    }

    /** Whether a kirana's FSSAI licence (now or kept from before) is this file. */
    public boolean fssaiUsesFile(UUID fileId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM kirana_online "
                + "WHERE fssai_file_id = ? OR fssai_kept_file_id = ?)", Boolean.class, fileId, fileId));
    }

    public void setUpiId(UUID shopId, String upiId, UUID by, Instant now) {
        jdbc.update("INSERT INTO kirana_settings (shop_id, next_bill_no, upi_id, upi_updated_by, upi_updated_at) "
                        + "VALUES (?, 1, ?, ?, ?) ON CONFLICT (shop_id) DO UPDATE SET upi_id = EXCLUDED.upi_id, "
                        + "upi_updated_by = EXCLUDED.upi_updated_by, upi_updated_at = EXCLUDED.upi_updated_at",
                shopId, upiId, by, at(now));
    }

    /** Forgets the shop's UPI ID if this person set it (the owner who handed the shop over): the new owner sets his. */
    public void clearUpiIdSetBy(UUID shopId, UUID userId) {
        jdbc.update("UPDATE kirana_settings SET upi_id = NULL, upi_updated_by = NULL, upi_updated_at = NULL "
                + "WHERE shop_id = ? AND upi_updated_by = ?", shopId, userId);
    }

    /**
     * The shop's next bill number, its row locked until the transaction ends (made first if missing). Every bill of the
     * shop takes this lock first, so they are saved one after the other: each gets its own number, and a double tap
     * waits and then finds the bill the first tap saved.
     */
    public long lockNextBillNo(UUID shopId) {
        jdbc.update("INSERT INTO kirana_settings (shop_id, next_bill_no) VALUES (?, 1) ON CONFLICT DO NOTHING", shopId);
        Long n = jdbc.queryForObject("SELECT next_bill_no FROM kirana_settings WHERE shop_id = ? FOR NO KEY UPDATE",
                Long.class, shopId);
        return n == null ? 1 : n;
    }

    public void setNextBillNo(UUID shopId, long next) {
        jdbc.update("UPDATE kirana_settings SET next_bill_no = ? WHERE shop_id = ?", next, shopId);
    }

    static OffsetDateTime at(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
