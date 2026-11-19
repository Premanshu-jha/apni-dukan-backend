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
            + "rating_count, updated_at, fssai_kept_until, fssai_kept_file_id FROM kirana_online";

    private final JdbcTemplate jdbc;

    public OnlineStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A shop's online-order row.
     * @param fssaiStatus NONE, CHECKING (the AI reads it), VERIFIED or REJECTED (fssaiReason says why)
     * @param upiRecheck  its UPI ID changed after approval: payouts wait until the field team checks it
     * @param fssaiKeptUntil while a renewed certificate is checked or was refused: the last day of the earlier verified
     *                    licence (the same number), which keeps the shop taking orders until then
     * @param fssaiKeptFileId that earlier licence's certificate, kept on record meanwhile
     */
    public record Online(UUID shopId, boolean takingOrders, String fssaiNumber, UUID fssaiFileId, String fssaiStatus,
                         String fssaiReason, LocalDate fssaiValidUntil, Instant fssaiCheckedAt, int fssaiTries,
                         boolean upiRecheck, Instant upiCheckedAt, long ratingSum, int ratingCount,
                         Instant updatedAt, LocalDate fssaiKeptUntil, UUID fssaiKeptFileId) {

        /** Its FSSAI licence was checked and is still valid on this day (or an earlier one is, during a renewal). */
        public boolean fssaiValidOn(LocalDate day) {
            return ("VERIFIED".equals(fssaiStatus) && fssaiValidUntil != null && !fssaiValidUntil.isBefore(day))
                    || keptOn(day);
        }

        /** A renewal is being checked or was refused while the earlier licence still holds on this day. */
        public boolean keptOn(LocalDate day) {
            return fssaiKeptUntil != null && !fssaiKeptUntil.isBefore(day);
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

    /**
     * A new certificate (or number) to check: the AI reads it next. {@code renewalOn}: the owner's own upload on that
     * day. A renewal of the same licence (the same number) while the verified one is still valid keeps that one's last
     * day and certificate, so the shop keeps taking orders until then; a different number keeps nothing (a renewal is
     * of the same licence). Null for the team's re-decision, which changes nothing kept.
     */
    public void setFssai(UUID shopId, String number, UUID fileId, LocalDate renewalOn, UUID setBy, Instant now) {
        jdbc.update("UPDATE kirana_online SET "
                        + "fssai_kept_file_id = CASE WHEN ?::date IS NULL THEN fssai_kept_file_id "
                        + "WHEN fssai_status = 'VERIFIED' AND fssai_valid_until >= ?::date AND fssai_number = ? "
                        + "THEN fssai_file_id WHEN fssai_number = ? THEN fssai_kept_file_id END, "
                        + "fssai_kept_until = CASE WHEN ?::date IS NULL THEN fssai_kept_until "
                        + "WHEN fssai_status = 'VERIFIED' AND fssai_valid_until >= ?::date AND fssai_number = ? "
                        + "THEN fssai_valid_until WHEN fssai_number = ? THEN fssai_kept_until END, "
                        + "fssai_number = ?, fssai_file_id = ?, fssai_status = 'CHECKING', "
                        + "fssai_set_by = coalesce(?, fssai_set_by), "
                        + "fssai_reason = NULL, fssai_valid_until = NULL, fssai_checked_at = NULL, fssai_tries = 0, "
                        + "updated_at = ? WHERE shop_id = ?", date(renewalOn), date(renewalOn), number, number,
                date(renewalOn), date(renewalOn), number, number, number, fileId, setBy, at(now), shopId);
    }

    /**
     * Nothing kept any more (the renewal is verified, or the team ends the earlier licence). Returns the certificate
     * that was kept, to be let go.
     */
    public Optional<UUID> endKept(UUID shopId, Instant now) {
        List<UUID> kept = jdbc.query("SELECT fssai_kept_file_id FROM kirana_online WHERE shop_id = ? "
                + "AND fssai_kept_file_id IS NOT NULL", (rs, i) -> rs.getObject(1, UUID.class), shopId);
        jdbc.update("UPDATE kirana_online SET fssai_kept_until = NULL, fssai_kept_file_id = NULL, updated_at = ? "
                + "WHERE shop_id = ? AND (fssai_kept_until IS NOT NULL OR fssai_kept_file_id IS NOT NULL)", at(now),
                shopId);
        return kept.stream().findFirst();
    }

    /**
     * The owner who added the FSSAI licence left the shop (handed over): it was issued to him as the food business
     * operator, so it is forgotten and orders stop until the new owner adds his own. Returns its certificates to let
     * go (none when someone else added it, or there is none).
     */
    public List<UUID> forgetFssaiSetBy(UUID shopId, UUID userId, Instant now) {
        List<UUID> files = jdbc.query("SELECT unnest(ARRAY[fssai_file_id, fssai_kept_file_id]) FROM kirana_online "
                + "WHERE shop_id = ? AND fssai_set_by = ?", (rs, i) -> rs.getObject(1, UUID.class), shopId, userId)
                .stream().filter(java.util.Objects::nonNull).toList();
        jdbc.update("UPDATE kirana_online SET fssai_number = NULL, fssai_file_id = NULL, fssai_status = 'NONE', "
                + "fssai_reason = NULL, fssai_valid_until = NULL, fssai_checked_at = NULL, fssai_tries = 0, "
                + "fssai_kept_until = NULL, fssai_kept_file_id = NULL, fssai_set_by = NULL, taking_orders = false, "
                + "updated_at = ? WHERE shop_id = ? AND fssai_set_by = ?", at(now), shopId, userId);
        return files;
    }

    private static Date date(LocalDate d) {
        return d == null ? null : Date.valueOf(d);
    }

    /**
     * The check's answer, kept only if the shop still has this certificate (a newer upload meanwhile is checked
     * afresh). Returns whether it was kept.
     */
    public boolean fssaiResult(UUID shopId, String number, UUID fileId, String status, String reason,
                               LocalDate validUntil, Instant now) {
        // Refused: the earlier licence still holds (the caller ends what is kept once a renewal is verified)
        return jdbc.update("UPDATE kirana_online SET fssai_status = ?, fssai_reason = ?, fssai_valid_until = ?, "
                        + "fssai_checked_at = ?, updated_at = ? WHERE shop_id = ? AND fssai_number = ? "
                        + "AND fssai_file_id = ? AND fssai_status = 'CHECKING'", status, reason, date(validUntil),
                at(now), at(now), shopId, number, fileId) > 0;
    }

    /** The certificate goes to a person (the AI couldn't decide, or the owner asks again after a refusal). */
    public void fssaiToPerson(UUID shopId, int maxTries, Instant now) {
        jdbc.update("UPDATE kirana_online SET fssai_status = 'CHECKING', fssai_reason = NULL, fssai_tries = ?, "
                + "fssai_checked_at = ?, updated_at = ? WHERE shop_id = ?", maxTries, at(now), at(now), shopId);
    }

    /** A shop's certificate as the admin website lists it. */
    public record FssaiCheck(UUID shopId, String shopName, String number, UUID fileId, String status, String reason,
                             int tries, Instant checkedAt, LocalDate keptUntil) {
    }

    /**
     * Certificates in this status for the admin website: CHECKING ones the AI gave to a person (tried
     * {@code minTries} times or more) oldest first, or REJECTED ones newest first.
     */
    public List<FssaiCheck> fssaiChecks(String status, int minTries, int limit) {
        StringBuilder sql = new StringBuilder("SELECT o.shop_id, s.name, o.fssai_number, o.fssai_file_id, "
                + "o.fssai_status, o.fssai_reason, o.fssai_tries, o.fssai_checked_at, "
                + "CASE WHEN o.fssai_kept_until >= current_date THEN o.fssai_kept_until END "
                + "FROM kirana_online o "
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
                    checked == null ? null : checked.toInstant(),
                    rs.getDate(9) == null ? null : rs.getDate(9).toLocalDate());
        }, status, minTries, limit);
    }

    /** One more read tried for this certificate (the AI didn't answer). */
    public void fssaiTried(UUID shopId, UUID fileId, Instant now) {
        jdbc.update("UPDATE kirana_online SET fssai_tries = fssai_tries + 1, fssai_checked_at = ? "
                + "WHERE shop_id = ? AND fssai_file_id = ?", at(now), shopId, fileId);
    }

    /**
     * Certificates waiting for the AI and tried fewer than {@code maxTries} times (after that a super admin decides),
     * those never tried first. Only a kirana's: a shop that became a salon while registering keeps its row (for if it
     * changes back) but no paid AI read is spent on it meanwhile.
     */
    public List<Checking> checking(int maxTries, int limit) {
        return jdbc.query("SELECT o.shop_id, o.fssai_number, o.fssai_file_id, o.fssai_tries FROM kirana_online o "
                        + "JOIN shops s ON s.id = o.shop_id AND s.type = 'KIRANA' "
                        + "WHERE o.fssai_status = 'CHECKING' AND o.fssai_tries < ? "
                        + "ORDER BY o.fssai_checked_at NULLS FIRST, o.shop_id LIMIT ?",
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

    /** As takeAiCall, from photo checks' own daily allowance (they never hold up bills). */
    public boolean takePhotoAiCall(LocalDate day, int cap) {
        List<Integer> n = jdbc.queryForList("INSERT INTO kirana_photo_ai_calls (day, calls) VALUES (?, 1) "
                + "ON CONFLICT (day) DO UPDATE SET calls = kirana_photo_ai_calls.calls + 1 "
                + "WHERE kirana_photo_ai_calls.calls < ? RETURNING calls", Integer.class, Date.valueOf(day), cap);
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
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                rs.getDate("fssai_kept_until") == null ? null : rs.getDate("fssai_kept_until").toLocalDate(),
                rs.getObject("fssai_kept_file_id", UUID.class));
    }
}
