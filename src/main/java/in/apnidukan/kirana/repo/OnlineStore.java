package in.apnidukan.kirana.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static in.apnidukan.kirana.repo.KiranaSettingsStore.at;

/**
 * kirana_online and kirana_ai_calls (V68): a kirana's online orders (its switch, its FSSAI licence and its check, the
 * field team's re-check of a changed UPI ID, its rating) and the daily cap on the kirana AI's reads, in plain SQL.
 */
@Repository
public class OnlineStore {

    private static final String COLUMNS = "SELECT shop_id, taking_orders, fssai_number, fssai_file_id, fssai_status, "
            + "fssai_reason, fssai_valid_until, fssai_checked_at, fssai_tries, upi_recheck, upi_checked_at, rating_sum, "
            + "rating_count, updated_at FROM kirana_online";

    private final JdbcTemplate jdbc;

    public OnlineStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A shop's online-order row.
     * @param fssaiStatus NONE, CHECKING (the AI reads it), VERIFIED or REJECTED (fssaiReason says why)
     * @param upiRecheck  its UPI ID changed after approval: payouts wait until the field team checks it
     */
    public record Online(UUID shopId, boolean takingOrders, String fssaiNumber, UUID fssaiFileId, String fssaiStatus,
                         String fssaiReason, LocalDate fssaiValidUntil, Instant fssaiCheckedAt, int fssaiTries,
                         boolean upiRecheck, Instant upiCheckedAt, long ratingSum, int ratingCount,
                         Instant updatedAt) {

        /** Its FSSAI licence was checked and is still valid on this day. */
        public boolean fssaiValidOn(LocalDate day) {
            return "VERIFIED".equals(fssaiStatus) && fssaiValidUntil != null && !fssaiValidUntil.isBefore(day);
        }
    }

    /** A UPI ID waiting for the field team's re-check, with the shop's name. */
    public record Recheck(UUID shopId, String shopName, String upiId, Instant changedAt) {
    }

    /** A certificate waiting for the AI's read. */
    public record Checking(UUID shopId, String fssaiNumber, UUID fileId, int tries) {
    }

    public Optional<Online> find(UUID shopId) {
        return jdbc.query(COLUMNS + " WHERE shop_id = ?", OnlineStore::online, shopId).stream().findFirst();
    }

    /** The shop's row, made on first use and locked until the transaction ends. */
    public Online lock(UUID shopId, Instant now) {
        jdbc.update("INSERT INTO kirana_online (shop_id, taking_orders, fssai_status, fssai_tries, upi_recheck, "
                + "rating_sum, rating_count, updated_at) VALUES (?, false, 'NONE', 0, false, 0, 0, ?) "
                + "ON CONFLICT (shop_id) DO NOTHING", shopId, at(now));
        return jdbc.query(COLUMNS + " WHERE shop_id = ? FOR NO KEY UPDATE", OnlineStore::online, shopId).get(0);
    }

    public void setTakingOrders(UUID shopId, boolean on, Instant now) {
        jdbc.update("UPDATE kirana_online SET taking_orders = ?, updated_at = ? WHERE shop_id = ?", on, at(now),
                shopId);
    }

    /** A new certificate (or number) to check: the AI reads it next. */
    public void setFssai(UUID shopId, String number, UUID fileId, Instant now) {
        jdbc.update("UPDATE kirana_online SET fssai_number = ?, fssai_file_id = ?, fssai_status = 'CHECKING', "
                        + "fssai_reason = NULL, fssai_valid_until = NULL, fssai_checked_at = NULL, fssai_tries = 0, "
                        + "updated_at = ? WHERE shop_id = ?", number, fileId, at(now), shopId);
    }

    /**
     * The check's answer, kept only if the shop still has this certificate (a newer upload meanwhile is checked
     * afresh). Returns whether it was kept.
     */
    public boolean fssaiResult(UUID shopId, String number, UUID fileId, String status, String reason,
                               LocalDate validUntil, Instant now) {
        return jdbc.update("UPDATE kirana_online SET fssai_status = ?, fssai_reason = ?, fssai_valid_until = ?, "
                        + "fssai_checked_at = ?, updated_at = ? WHERE shop_id = ? AND fssai_number = ? "
                        + "AND fssai_file_id = ? AND fssai_status = 'CHECKING'", status, reason, validUntil == null
                        ? null : Date.valueOf(validUntil), at(now), at(now), shopId, number, fileId) > 0;
    }

    /** The certificate goes to a person (the AI couldn't decide, or the owner asks again after a refusal). */
    public void fssaiToPerson(UUID shopId, int maxTries, Instant now) {
        jdbc.update("UPDATE kirana_online SET fssai_status = 'CHECKING', fssai_reason = NULL, fssai_tries = ?, "
                + "fssai_checked_at = ?, updated_at = ? WHERE shop_id = ?", maxTries, at(now), at(now), shopId);
    }

    /** A shop's certificate as the admin website lists it. */
    public record FssaiCheck(UUID shopId, String shopName, String number, UUID fileId, String status, String reason,
                             int tries, Instant checkedAt) {
    }

    /**
     * Certificates in this status for the admin website: CHECKING ones the AI gave to a person (tried
     * {@code minTries} times or more) oldest first, or REJECTED ones newest first.
     */
    public List<FssaiCheck> fssaiChecks(String status, int minTries, int limit) {
        StringBuilder sql = new StringBuilder("SELECT o.shop_id, s.name, o.fssai_number, o.fssai_file_id, "
                + "o.fssai_status, o.fssai_reason, o.fssai_tries, o.fssai_checked_at FROM kirana_online o "
                + "JOIN shops s ON s.id = o.shop_id WHERE o.fssai_status = ? AND o.fssai_tries >= ?");
        if ("CHECKING".equals(status)) {
            sql.append(" ORDER BY o.fssai_checked_at NULLS FIRST, o.shop_id LIMIT ?");
        } else {
            sql.append(" ORDER BY o.fssai_checked_at DESC NULLS LAST, o.shop_id LIMIT ?");
        }
        return jdbc.query(sql.toString(), (rs, i) -> {
            OffsetDateTime checked = rs.getObject(8, OffsetDateTime.class);
            return new FssaiCheck(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                    rs.getObject(4, UUID.class), rs.getString(5), rs.getString(6), rs.getInt(7),
                    checked == null ? null : checked.toInstant());
        }, status, minTries, limit);
    }

    /** One more read tried for this certificate (the AI didn't answer). */
    public void fssaiTried(UUID shopId, UUID fileId, Instant now) {
        jdbc.update("UPDATE kirana_online SET fssai_tries = fssai_tries + 1, fssai_checked_at = ? "
                + "WHERE shop_id = ? AND fssai_file_id = ?", at(now), shopId, fileId);
    }

    /**
     * Certificates waiting for the AI and tried fewer than {@code maxTries} times (after that a super admin decides),
     * those never tried first.
     */
    public List<Checking> checking(int maxTries, int limit) {
        return jdbc.query("SELECT shop_id, fssai_number, fssai_file_id, fssai_tries FROM kirana_online "
                        + "WHERE fssai_status = 'CHECKING' AND fssai_tries < ? "
                        + "ORDER BY fssai_checked_at NULLS FIRST, shop_id LIMIT ?",
                (rs, i) -> new Checking(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                        rs.getInt(4)), maxTries, limit);
    }

    /** The shop's UPI ID changed after approval: its payouts wait for the field team's re-check. */
    public void markUpiRecheck(UUID shopId, Instant now) {
        lock(shopId, now);
        jdbc.update("UPDATE kirana_online SET upi_recheck = true, upi_checked_by = NULL, upi_checked_at = NULL, "
                + "updated_at = ? WHERE shop_id = ?", at(now), shopId);
    }

    /** The field team checked the UPI ID: payouts go again. Whether it was waiting. */
    public boolean clearUpiRecheck(UUID shopId, UUID by, Instant now) {
        return jdbc.update("UPDATE kirana_online SET upi_recheck = false, upi_checked_by = ?, upi_checked_at = ?, "
                + "updated_at = ? WHERE shop_id = ? AND upi_recheck", by, at(now), at(now), shopId) > 0;
    }

    /** UPI IDs waiting for the field team's re-check, oldest first. */
    public List<Recheck> rechecks(int limit) {
        return jdbc.query("SELECT o.shop_id, s.name, k.upi_id, o.updated_at FROM kirana_online o "
                        + "JOIN shops s ON s.id = o.shop_id LEFT JOIN kirana_settings k ON k.shop_id = o.shop_id "
                        + "WHERE o.upi_recheck ORDER BY o.updated_at, o.shop_id LIMIT ?",
                (rs, i) -> new Recheck(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getObject(4, OffsetDateTime.class).toInstant()), limit);
    }

    /** A customer's rating of a delivered order (1–5), added to the shop's average. */
    public void addRating(UUID shopId, int stars, Instant now) {
        lock(shopId, now);
        jdbc.update("UPDATE kirana_online SET rating_sum = rating_sum + ?, rating_count = rating_count + 1 "
                + "WHERE shop_id = ?", stars, shopId);
    }

    /**
     * Counts one kirana AI read on this India day if the day's cap allows it (atomic: many readers at once never pass
     * the cap). Whether it may go ahead.
     */
    public boolean takeAiCall(LocalDate day, int cap) {
        List<Integer> n = jdbc.queryForList("INSERT INTO kirana_ai_calls (day, calls) VALUES (?, 1) "
                + "ON CONFLICT (day) DO UPDATE SET calls = kirana_ai_calls.calls + 1 "
                + "WHERE kirana_ai_calls.calls < ? RETURNING calls", Integer.class, Date.valueOf(day), cap);
        return !n.isEmpty() && cap > 0;
    }

    static Online online(ResultSet rs, int i) throws SQLException {
        Date valid = rs.getDate("fssai_valid_until");
        OffsetDateTime checked = rs.getObject("fssai_checked_at", OffsetDateTime.class);
        OffsetDateTime upi = rs.getObject("upi_checked_at", OffsetDateTime.class);
        return new Online(rs.getObject("shop_id", UUID.class), rs.getBoolean("taking_orders"),
                rs.getString("fssai_number"), rs.getObject("fssai_file_id", UUID.class), rs.getString("fssai_status"),
                rs.getString("fssai_reason"), valid == null ? null : valid.toLocalDate(),
                checked == null ? null : checked.toInstant(), rs.getInt("fssai_tries"), rs.getBoolean("upi_recheck"),
                upi == null ? null : upi.toInstant(), rs.getLong("rating_sum"), rs.getInt("rating_count"),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
