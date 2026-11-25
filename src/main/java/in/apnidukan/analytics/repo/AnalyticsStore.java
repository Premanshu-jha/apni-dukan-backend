package in.apnidukan.analytics.repo;

import in.apnidukan.analytics.domain.FeeSettings;
import in.apnidukan.common.config.ReadCopy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static in.apnidukan.analytics.repo.DailyTotals.NOT_TEST_SALON;

/**
 * What the admin Analytics page reads. Everything is read from the read copy (ReadCopy) and is small: the daily totals
 * (analytics_daily, a few rows a day), the payments ledger by month, one row per salon (salon_subscriptions,
 * salon_usage) or app versions; never the users or appointments tables. The settings (Google's fee rates) are read and
 * saved on the main database.
 *
 * Test-mode payments (Razorpay test keys, Google's licence testers) are never counted as money.
 */
@Repository
public class AnalyticsStore {

    /** A salon still counts as paying this long past its paid month's end while the next charge arrives. */
    public static final Duration LATE = Duration.ofDays(1);
    /** Same as SubscriptionStore.GRACE: the days a salon stays visible after its paid or free time. */
    public static final Duration GRACE = Duration.ofDays(3);

    private final ReadCopy reads;
    private final JdbcTemplate jdbc;

    public AnalyticsStore(ReadCopy reads, JdbcTemplate jdbc) {
        this.reads = reads;
        this.jdbc = jdbc;
    }

    // ---- Money ---------------------------------------------------------------------------------------------------

    /** One month and payment source's totals, in paise. Refunds are positive here (taken off). */
    public record MonthMoney(String month, String source, long charges, long grossPaise, long refundsPaise,
                             long refundCount, long razorpayFeePaise, long salons) {
    }

    /** Totals per India month (yyyy-MM) and source, from {@code from} on, oldest first. */
    public List<MonthMoney> money(Instant from) {
        return reads.read(j -> j.query("SELECT to_char(paid_at AT TIME ZONE 'Asia/Kolkata', 'YYYY-MM') AS m, source, "
                + "count(*) FILTER (WHERE kind = 'CHARGE'), "
                + "coalesce(sum(amount_paise) FILTER (WHERE kind = 'CHARGE'), 0), "
                + "coalesce(-sum(amount_paise) FILTER (WHERE kind = 'REFUND'), 0), "
                + "count(*) FILTER (WHERE kind = 'REFUND'), "
                + "coalesce(sum(fee_paise) FILTER (WHERE kind = 'CHARGE'), 0), "
                + "count(DISTINCT shop_id) FILTER (WHERE kind = 'CHARGE') "
                + "FROM subscription_payments WHERE NOT test AND paid_at >= ? GROUP BY 1, 2 ORDER BY 1, 2",
                (rs, i) -> new MonthMoney(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                        rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8)),
                Timestamp.from(from)));
    }

    /**
     * The monthly money coming in now: each salon still paid up, at what it last paid less any part refunded; per
     * source, in paise. A salon whose last charge was refunded in full isn't counted.
     */
    public record Recurring(String source, long salons, long monthlyPaise) {
    }

    public List<Recurring> recurring(Instant now) {
        return reads.read(j -> j.query("SELECT source, count(*), coalesce(sum(kept), 0) FROM (SELECT source, "
                + "amount_paise + coalesce((SELECT sum(r.amount_paise) FROM subscription_payments r "
                + "WHERE r.kind = 'REFUND' AND r.charge_id = last.external_id), 0) AS kept FROM ("
                + "SELECT DISTINCT ON (shop_id) external_id, shop_id, source, amount_paise, period_end FROM "
                + "subscription_payments WHERE kind = 'CHARGE' AND NOT test ORDER BY shop_id, paid_at DESC, external_id) last "
                + "WHERE period_end IS NOT NULL AND period_end > ?) k WHERE kept > 0 GROUP BY source ORDER BY source",
                (rs, i) -> new Recurring(rs.getString(1), rs.getLong(2), rs.getLong(3)),
                Timestamp.from(now.minus(LATE))));
    }

    /** One line of a month's payments list (the CSV download). */
    public record PaymentLine(Instant paidAt, String shopName, String area, String source, String kind,
                              long amountPaise, Long feePaise, String externalId, boolean imported) {
    }

    /** A month's payments (not test ones), oldest first; at most {@code limit}. */
    public List<PaymentLine> payments(Instant from, Instant to, int limit) {
        return reads.read(j -> j.query("SELECT p.paid_at, s.name, s.area, p.source, p.kind, p.amount_paise, "
                + "p.fee_paise, p.external_id, p.imported FROM subscription_payments p JOIN shops s ON s.id = p.shop_id "
                + "WHERE NOT p.test AND p.paid_at >= ? AND p.paid_at < ? ORDER BY p.paid_at, p.id LIMIT ?",
                (rs, i) -> new PaymentLine(rs.getTimestamp(1).toInstant(), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6), (Long) rs.getObject(7), rs.getString(8),
                        rs.getBoolean(9)),
                Timestamp.from(from), Timestamp.from(to), limit));
    }

    // ---- Google's fee rates (estimates: Google reports its fees only in the Play Console) -------------------------

    public FeeSettings settings() {
        return jdbc.queryForObject("SELECT a.play_fee_bps, a.user_choice_fee_bps, a.version, a.changed_at, u.name "
                + "FROM analytics_settings a LEFT JOIN users u ON u.id = a.changed_by WHERE a.id = 1",
                (rs, i) -> new FeeSettings(rs.getInt(1), rs.getInt(2), rs.getInt(3), instant(rs.getTimestamp(4)),
                        rs.getString(5)));
    }

    /** Saves the rates if still at {@code version}; false if someone changed them meanwhile. */
    public boolean saveSettings(int playFeeBps, int userChoiceFeeBps, int version, UUID by, Instant now) {
        return jdbc.update("UPDATE analytics_settings SET play_fee_bps = ?, user_choice_fee_bps = ?, "
                + "version = version + 1, changed_at = ?, changed_by = ? WHERE id = 1 AND version = ?",
                playFeeBps, userChoiceFeeBps, Timestamp.from(now), by, version) == 1;
    }

    /** A line in the team's change log (admin_events), in the caller's transaction. */
    public void logEvent(UUID by, String event, String detail, Instant now) {
        jdbc.update("INSERT INTO admin_events (id, at, by_user, event, phone, detail) VALUES (?, ?, ?, ?, NULL, ?)",
                UUID.randomUUID(), Timestamp.from(now), by, event, detail);
    }

    // ---- Subscriptions -------------------------------------------------------------------------------------------

    /**
     * Where each approved salon (not closed) stands now, as the salon's own Subscription screen would say it.
     * State: TRIAL, PAYING, GRACE (time over, still visible, 3 days to pay), LAPSED (hidden from customers).
     * Source (PAYING only): GOOGLE_PLAY, APP_RAZORPAY (paid in the app with Razorpay), WEBSITE. renewing: AutoPay or
     * Google renews by itself; problem: the last AutoPay charge failed or Google couldn't take a renewal.
     */
    private static final String STANDING = "SELECT x.shop_id, s.name, s.area, s.pincode, x.trial_ends_at, x.access, "
            + "CASE WHEN x.renewing OR ? < x.access THEN (CASE WHEN x.paid_until IS NULL "
            + "OR x.paid_until <= x.trial_ends_at THEN 'TRIAL' ELSE 'PAYING' END) "
            + "WHEN ? < x.bookable_until THEN 'GRACE' ELSE 'LAPSED' END AS state, "
            // Google Play only while its subscription is (nearly) current: one that ran out keeps its token
            + "CASE WHEN x.play_purchase_token IS NOT NULL AND x.play_expires_at IS NOT NULL "
            + "AND x.play_expires_at + INTERVAL '3 days' > ? THEN 'GOOGLE_PLAY' "
            + "WHEN x.app_billing_subscription_id IS NOT NULL "
            + "AND x.app_billing_subscription_id = x.razorpay_subscription_id THEN 'APP_RAZORPAY' "
            + "ELSE 'WEBSITE' END AS source, x.renewing, "
            // As SubscriptionStore.Row.autoPayFailed: failing, given up, or AutoPay set up but a charge over a day late
            + "coalesce(x.razorpay_status IN ('PENDING', 'HALTED') OR x.play_state IN "
            + "('SUBSCRIPTION_STATE_IN_GRACE_PERIOD', 'SUBSCRIPTION_STATE_ON_HOLD') OR (x.razorpay_status IN "
            + "('AUTHENTICATED', 'ACTIVE') AND NOT x.renewing), false) AS problem "
            + "FROM (SELECT *, GREATEST(trial_ends_at, coalesce(paid_until, trial_ends_at)) AS access, "
            // coalesce: a salon with no Razorpay status is "not renewing", never unknown (NOT unknown drops it)
            + "coalesce(razorpay_on OR (play_auto_renewing AND play_expires_at IS NOT NULL "
            + "AND ? < play_expires_at + INTERVAL '1 day'), false) AS renewing FROM (SELECT *, "
            + "coalesce(razorpay_status IN ('AUTHENTICATED', 'ACTIVE') AND ? < GREATEST(GREATEST(trial_ends_at, "
            + "coalesce(paid_until, trial_ends_at)), coalesce(subscription_made_at, trial_ends_at)) + INTERVAL '1 day', "
            + "false) AS razorpay_on FROM salon_subscriptions) y) x JOIN shops s ON s.id = x.shop_id "
            + "WHERE s.status <> 'CLOSED' AND " + NOT_TEST_SALON;

    public record Count(String state, String source, boolean renewing, boolean problem, long salons) {
    }

    public List<Count> standing(Instant now) {
        Timestamp t = Timestamp.from(now);
        return reads.read(j -> j.query("SELECT state, CASE WHEN state = 'PAYING' THEN source END, renewing, problem, "
                + "count(*) FROM (" + STANDING + ") z GROUP BY 1, 2, 3, 4",
                (rs, i) -> new Count(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getBoolean(4),
                        rs.getLong(5)), t, t, t, t, t));
    }

    public record SalonLine(UUID shopId, String name, String area, String pincode, Instant ends, String state,
                            String source) {
    }

    /** Salons in their trial that ends within {@code ahead}, without AutoPay set up: the ones to call. Soonest first. */
    public List<SalonLine> trialsEnding(Instant now, Duration ahead, int limit) {
        Timestamp t = Timestamp.from(now);
        return reads.read(j -> j.query("SELECT shop_id, name, area, pincode, access, state, source FROM (" + STANDING
                + ") z WHERE state = 'TRIAL' AND NOT renewing AND access < ? ORDER BY access LIMIT ?", SALON_LINE,
                t, t, t, t, t, Timestamp.from(now.plus(ahead)), limit));
    }

    /** Salons whose AutoPay or Google renewal is failing. Soonest end first. */
    public List<SalonLine> paymentProblems(Instant now, int limit) {
        Timestamp t = Timestamp.from(now);
        return reads.read(j -> j.query("SELECT shop_id, name, area, pincode, access, state, source FROM (" + STANDING
                + ") z WHERE problem ORDER BY access LIMIT ?", SALON_LINE, t, t, t, t, t, limit));
    }

    /** Per India month from {@code from}: salons paying for the first time, and salons whose last paid month ended. */
    public record MonthFlow(String month, long firstPayments, long stopped) {
    }

    public List<MonthFlow> flow(Instant from, Instant now) {
        Map<String, long[]> months = new LinkedHashMap<>();
        reads.read(j -> {
            j.query("SELECT to_char(first AT TIME ZONE 'Asia/Kolkata', 'YYYY-MM'), count(*) FROM (SELECT shop_id, "
                    + "min(paid_at) AS first FROM subscription_payments WHERE kind = 'CHARGE' AND NOT test "
                    + "GROUP BY shop_id) f WHERE first >= ? GROUP BY 1", (RowCallbackHandler) rs ->
                    months.computeIfAbsent(rs.getString(1), k -> new long[2])[0] = rs.getLong(2), Timestamp.from(from));
            // Stopped: the salon's last charge's month ended (and its 3 days to pay are over) with no charge after it
            j.query("SELECT to_char(period_end AT TIME ZONE 'Asia/Kolkata', 'YYYY-MM'), count(*) FROM ("
                    + "SELECT DISTINCT ON (shop_id) shop_id, period_end FROM subscription_payments "
                    + "WHERE kind = 'CHARGE' AND NOT test ORDER BY shop_id, paid_at DESC) last "
                    + "WHERE period_end >= ? AND period_end + INTERVAL '3 days' < ? GROUP BY 1",
                    (RowCallbackHandler) rs -> months.computeIfAbsent(rs.getString(1), k -> new long[2])[1] =
                            rs.getLong(2), Timestamp.from(from), Timestamp.from(now));
            return null;
        });
        List<MonthFlow> out = new ArrayList<>();
        months.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.add(new MonthFlow(e.getKey(), e.getValue()[0], e.getValue()[1])));
        return out;
    }

    // ---- Daily totals --------------------------------------------------------------------------------------------

    public record Point(LocalDate day, String dim, long value) {
    }

    /** One metric's daily rows between two days (both included), oldest first. */
    public List<Point> series(String metric, LocalDate from, LocalDate to) {
        return reads.read(j -> j.query("SELECT day, dim, value FROM analytics_daily WHERE metric = ? "
                + "AND day BETWEEN ? AND ? ORDER BY day, dim", POINT, metric, Date.valueOf(from), Date.valueOf(to)));
    }

    /** One metric's rows for one dim value between two days (an area's bookings). */
    public List<Point> series(String metric, String dim, LocalDate from, LocalDate to) {
        return reads.read(j -> j.query("SELECT day, dim, value FROM analytics_daily WHERE metric = ? AND dim = ? "
                + "AND day BETWEEN ? AND ? ORDER BY day", POINT, metric, dim, Date.valueOf(from), Date.valueOf(to)));
    }

    /** A metric summed over the days, per dim, largest first; at most {@code limit}. */
    public List<Point> totals(String metric, LocalDate from, LocalDate to, int limit) {
        return reads.read(j -> j.query("SELECT max(day), dim, sum(value) FROM analytics_daily WHERE metric = ? "
                + "AND day BETWEEN ? AND ? GROUP BY dim ORDER BY 3 DESC, dim LIMIT ?", POINT, metric,
                Date.valueOf(from), Date.valueOf(to), limit));
    }

    /** A metric's rows on the latest day it has (a snapshot such as users_now), or none. */
    public List<Point> latest(String metric) {
        return reads.read(j -> j.query("SELECT day, dim, value FROM analytics_daily WHERE metric = ? AND day = "
                + "(SELECT max(day) FROM analytics_daily WHERE metric = ?) ORDER BY dim", POINT, metric, metric));
    }

    /** The middle time (in hours) from a salon registering to its first approval, for approvals in the range. */
    public Double approvalHours(Instant from, Instant to) {
        // From registering to the salon's first APPROVED (as DailyTotals' salons_approved)
        return reads.read(j -> j.queryForObject("SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY "
                + "extract(epoch FROM (e.at - s.created_at)) / 3600) FROM shop_events e "
                + "JOIN shops s ON s.id = e.shop_id WHERE e.event = 'APPROVED' AND e.at >= ? AND e.at < ? "
                + "AND s.type = 'SALON' AND " + NOT_TEST_SALON + " AND NOT EXISTS (SELECT 1 FROM shop_events x "
                + "WHERE x.shop_id = e.shop_id AND x.event = 'APPROVED' AND (x.at < e.at OR x.at = e.at "
                + "AND x.seq < e.seq))", Double.class, Timestamp.from(from), Timestamp.from(to)));
    }

    // ---- Salons' use ---------------------------------------------------------------------------------------------

    public record SalonUse(UUID shopId, String name, String area, String pincode, String status,
                           LocalDate lastActiveDay, int bookings30d, int completed30d, int walkIns30d, int noShows30d,
                           int services, int staff, int pictures) {
    }

    /**
     * Salons by how much they use the app: {@code quiet} the least active first (never active, then the longest
     * ago), else the most bookings first. Approved salons only; {@code size + 1} rows tell whether there is more.
     */
    public List<SalonUse> salons(boolean quiet, String pincode, int offset, int size) {
        // Two fixed statements (one per order); the pincode is always a parameter (null: every area)
        Object[] args = {pincode, pincode, size + 1, offset};
        if (quiet) {
            return reads.read(j -> j.query(SALONS_SELECT + "ORDER BY u.last_active_day ASC NULLS FIRST, u.bookings_30d, "
                    + "s.id LIMIT ? OFFSET ?", SALON_USE, args));
        }
        return reads.read(j -> j.query(SALONS_SELECT + "ORDER BY u.bookings_30d DESC, u.completed_30d DESC, s.id "
                + "LIMIT ? OFFSET ?", SALON_USE, args));
    }

    private static final String SALONS_SELECT = "SELECT s.id, s.name, s.area, s.pincode, s.status, u.last_active_day, "
            + "u.bookings_30d, u.completed_30d, u.walk_ins_30d, u.no_shows_30d, u.services, u.staff, u.pictures "
            + "FROM salon_usage u JOIN shops s ON s.id = u.shop_id WHERE s.status = 'APPROVED' "
            + "AND (?::varchar IS NULL OR s.pincode = ?) AND " + NOT_TEST_SALON + " ";

    private static final org.springframework.jdbc.core.RowMapper<SalonUse> SALON_USE = (rs, i) -> new SalonUse(
            (UUID) rs.getObject(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
            localDate(rs.getDate(6)), rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getInt(10), rs.getInt(11),
            rs.getInt(12), rs.getInt(13));

    /** Approved salons nobody of theirs has opened the partner app or website for since {@code since} (or ever). */
    public long quietSalons(LocalDate since) {
        Long n = reads.read(j -> j.queryForObject("SELECT count(*) FROM salon_usage u JOIN shops s ON s.id = u.shop_id "
                + "WHERE s.status = 'APPROVED' AND (u.last_active_day IS NULL OR u.last_active_day < ?) AND "
                + NOT_TEST_SALON, Long.class,
                Date.valueOf(since)));
        return n == null ? 0 : n;
    }

    // ---- App versions --------------------------------------------------------------------------------------------

    public record VersionDay(LocalDate day, String app, int versionCode, boolean store, long phones) {
    }

    /** Phones that checked for updates per day, app, version and where the app came from; newest day first. */
    public List<VersionDay> versions(LocalDate from) {
        return reads.read(j -> j.query("SELECT day, app, version_code, store, phones FROM app_version_daily "
                + "WHERE day >= ? ORDER BY day DESC, app, version_code DESC, store DESC",
                (rs, i) -> new VersionDay(rs.getDate(1).toLocalDate(), rs.getString(2), rs.getInt(3),
                        rs.getBoolean(4), rs.getLong(5)), Date.valueOf(from)));
    }

    // ---- Helpers -------------------------------------------------------------------------------------------------

    private static final org.springframework.jdbc.core.RowMapper<Point> POINT = (rs, i) ->
            new Point(rs.getDate(1).toLocalDate(), rs.getString(2), rs.getLong(3));

    private static final org.springframework.jdbc.core.RowMapper<SalonLine> SALON_LINE = (rs, i) -> salonLine(rs);

    private static SalonLine salonLine(ResultSet rs) throws SQLException {
        return new SalonLine((UUID) rs.getObject(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getTimestamp(5).toInstant(), rs.getString(6), rs.getString(7));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static LocalDate localDate(Date d) {
        return d == null ? null : d.toLocalDate();
    }
}
