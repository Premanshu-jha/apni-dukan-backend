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
    public record Settings(String upiId, Instant upiUpdatedAt) {
    }

    public Settings find(UUID shopId) {
        List<Settings> rows = jdbc.query("SELECT upi_id, upi_updated_at FROM kirana_settings WHERE shop_id = ?",
                (rs, i) -> {
                    OffsetDateTime at = rs.getObject(2, OffsetDateTime.class);
                    return new Settings(rs.getString(1), at == null ? null : at.toInstant());
                }, shopId);
        return rows.isEmpty() ? new Settings(null, null) : rows.get(0);
    }

    public boolean hasUpiId(UUID shopId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM kirana_settings WHERE shop_id = ? AND upi_id IS NOT NULL)",
                Boolean.class, shopId));
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
