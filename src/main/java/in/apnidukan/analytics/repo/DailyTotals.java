package in.apnidukan.analytics.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Works out one day's totals (India's day) from the live tables into analytics_daily, and each salon's use into
 * salon_usage (V93). Used by AnalyticsJob only, in its own transactions. Each day's rows are replaced whole, so
 * working a day out again (every hour for the last three days) never counts twice.
 *
 * Metrics (dim in brackets):
 * <ul>
 *   <li>signups [CUSTOMER, OWNER, STAFF, ADMIN]: accounts made that day (test accounts left out), by what the person
 *       is now</li>
 *   <li>salons_registered, salons_approved: salons started that day; salons approved for the first time that day (the
 *       day their free trial started)</li>
 *   <li>bookings_created [''] and bookings_by_pincode [pincode]: bookings made that day</li>
 *   <li>visits [BOOKING:COMPLETED, WALK_IN:COMPLETED, BOOKING:NO_SHOW, BOOKING:CANCELLED_BY_CUSTOMER, …]: bookings and
 *       walk-ins for that day, by what became of them</li>
 *   <li>completed_by_pincode [pincode], visit_hours [00…23]: that day's visits that happened (or are still to),
 *       by area and by the hour they were for</li>
 *   <li>active_day, active_week, active_month [CUSTOMER:APP, CUSTOMER:WEB, CUSTOMER:ALL, PARTNER:…, ADMIN:…]: people
 *       active that day, in the 7 days and in the 30 days up to it</li>
 * </ul>
 */
@Repository
public class DailyTotals {

    public static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    /** A salon of a test account (an OTP test number, Google's reviewer): never in the counts. Alias s = shops. */
    public static final String NOT_TEST_SALON = "NOT EXISTS (SELECT 1 FROM shop_members tm JOIN users tu "
            + "ON tu.id = tm.user_id WHERE tm.shop_id = s.id AND tm.role = 'OWNER' AND tu.test_account)";
    /** Metrics that are a count of "now", kept per day as they stood at that day's last hour (never redone). */
    private static final String SNAPSHOTS = "('users_now', 'salons_now', 'salons_ever_approved')";
    /** Active people are kept this long (daily_activity); a month's count needs 30 of these days. */
    public static final int ACTIVITY_DAYS = 35;

    private final JdbcTemplate jdbc;

    public DailyTotals(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The first day anything happened (the first account), or null in an empty database. */
    public LocalDate firstDay() {
        Timestamp first = jdbc.queryForObject("SELECT min(created_at) FROM users", Timestamp.class);
        return first == null ? null : first.toInstant().atZone(INDIA).toLocalDate();
    }

    /** Works out every metric of one day again. In the caller's transaction (with a longer statement limit). */
    public void workOut(LocalDate day, LocalDate today) {
        Timestamp from = Timestamp.from(day.atStartOfDay(INDIA).toInstant());
        Timestamp to = Timestamp.from(day.plusDays(1).atStartOfDay(INDIA).toInstant());
        Date d = Date.valueOf(day);
        List<Object[]> rows = new ArrayList<>();

        jdbc.query("SELECT CASE WHEN EXISTS (SELECT 1 FROM shop_members m WHERE m.user_id = u.id AND m.role = 'OWNER') "
                + "THEN 'OWNER' WHEN EXISTS (SELECT 1 FROM shop_members m WHERE m.user_id = u.id) THEN 'STAFF' "
                + "WHEN u.admin_role IS NOT NULL AND u.admin_role <> 'NONE' THEN 'ADMIN' ELSE 'CUSTOMER' END AS who, "
                + "count(*) FROM users u WHERE u.created_at >= ? AND u.created_at < ? "
                + "AND NOT coalesce(u.test_account, false) GROUP BY 1", rs -> {
                    rows.add(row("signups", d, rs.getString(1), rs.getLong(2)));
                }, from, to);
        rows.add(row("salons_registered", d, "", jdbc.queryForObject("SELECT count(*) FROM shops s "
                + "WHERE s.type = 'SALON' AND s.created_at >= ? AND s.created_at < ? AND " + NOT_TEST_SALON,
                Long.class, from, to)));
        // First approvals: the salon's first APPROVED (approving again after a send-back isn't a new salon)
        rows.add(row("salons_approved", d, "", jdbc.queryForObject("SELECT count(*) FROM shop_events e "
                + "JOIN shops s ON s.id = e.shop_id WHERE e.event = 'APPROVED' AND e.at >= ? AND e.at < ? "
                + "AND s.type = 'SALON' AND " + NOT_TEST_SALON + " AND NOT EXISTS (SELECT 1 FROM shop_events x "
                + "WHERE x.shop_id = e.shop_id AND x.event = 'APPROVED' AND (x.at < e.at OR x.at = e.at "
                + "AND x.seq < e.seq))", Long.class, from, to)));
        rows.add(row("bookings_created", d, "", jdbc.queryForObject("SELECT count(*) FROM appointments a "
                + "JOIN shops s ON s.id = a.shop_id WHERE a.kind = 'BOOKING' AND a.created_at >= ? "
                + "AND a.created_at < ? AND " + NOT_TEST_SALON, Long.class, from, to)));
        jdbc.query("SELECT s.pincode, count(*) FROM appointments a JOIN shops s ON s.id = a.shop_id "
                + "WHERE a.kind = 'BOOKING' AND a.created_at >= ? AND a.created_at < ? AND s.pincode IS NOT NULL AND "
                + NOT_TEST_SALON + " GROUP BY s.pincode", rs -> {
                    rows.add(row("bookings_by_pincode", d, rs.getString(1), rs.getLong(2)));
                }, from, to);
        jdbc.query("SELECT a.kind || ':' || a.status, count(*) FROM appointments a JOIN shops s "
                + "ON s.id = a.shop_id WHERE a.kind <> 'BLOCK' AND a.starts_at >= ? AND a.starts_at < ? AND "
                + NOT_TEST_SALON + " GROUP BY 1", rs -> {
                    rows.add(row("visits", d, rs.getString(1), rs.getLong(2)));
                }, from, to);
        jdbc.query("SELECT s.pincode, count(*) FROM appointments a JOIN shops s ON s.id = a.shop_id "
                + "WHERE a.kind <> 'BLOCK' AND a.status IN ('COMPLETED', 'IN_SERVICE', 'CONFIRMED') "
                + "AND a.starts_at >= ? AND a.starts_at < ? AND s.pincode IS NOT NULL AND " + NOT_TEST_SALON
                + " GROUP BY s.pincode", rs -> {
                    rows.add(row("completed_by_pincode", d, rs.getString(1), rs.getLong(2)));
                }, from, to);
        jdbc.query("SELECT to_char(a.starts_at AT TIME ZONE 'Asia/Kolkata', 'HH24'), count(*) FROM appointments a "
                + "JOIN shops s ON s.id = a.shop_id WHERE a.kind <> 'BLOCK' "
                + "AND a.status IN ('COMPLETED', 'IN_SERVICE', 'CONFIRMED') AND a.starts_at >= ? AND a.starts_at < ? "
                + "AND " + NOT_TEST_SALON + " GROUP BY 1", rs -> {
                    rows.add(row("visit_hours", d, rs.getString(1), rs.getLong(2)));
                }, from, to);
        // How many there are now (one scan an hour, so the page never counts the users table): kept per day, so
        // each past day keeps its count as of its last hour (the reruns of past days leave these alone)
        if (day.equals(today)) {
            jdbc.query("SELECT CASE WHEN EXISTS (SELECT 1 FROM shop_members m WHERE m.user_id = u.id "
                    + "AND m.role = 'OWNER') THEN 'OWNER' WHEN EXISTS (SELECT 1 FROM shop_members m "
                    + "WHERE m.user_id = u.id) THEN 'STAFF' WHEN u.admin_role IS NOT NULL AND u.admin_role <> 'NONE' "
                    + "THEN 'ADMIN' ELSE 'CUSTOMER' END, count(*) FROM users u WHERE u.deleted_at IS NULL "
                    + "AND NOT coalesce(u.test_account, false) GROUP BY 1", rs -> {
                        rows.add(row("users_now", d, rs.getString(1), rs.getLong(2)));
                    });
            jdbc.query("SELECT s.status, count(*) FROM shops s WHERE s.type = 'SALON' AND " + NOT_TEST_SALON
                    + " GROUP BY s.status", rs -> {
                rows.add(row("salons_now", d, rs.getString(1), rs.getLong(2)));
            });
            rows.add(row("salons_ever_approved", d, "", jdbc.queryForObject("SELECT count(*) FROM shops s "
                    + "WHERE s.type = 'SALON' AND s.ever_approved AND " + NOT_TEST_SALON, Long.class)));
        }
        // Active people: only while the days needed are still kept
        if (!day.isBefore(today.minusDays(ACTIVITY_DAYS - 30L))) {
            active(rows, d, "active_day", day, day);
            active(rows, d, "active_week", day.minusDays(6), day);
            active(rows, d, "active_month", day.minusDays(29), day);
        }

        // Today's rows all go; an earlier day keeps its counts of "now" (worked out only while it was today)
        if (day.equals(today)) {
            jdbc.update("DELETE FROM analytics_daily WHERE day = ?", d);
        } else {
            jdbc.update("DELETE FROM analytics_daily WHERE day = ? AND metric NOT IN " + SNAPSHOTS, d);
        }
        jdbc.batchUpdate("INSERT INTO analytics_daily (metric, day, dim, value) VALUES (?, ?, ?, ?)", rows);
    }

    private void active(List<Object[]> rows, Date d, String metric, LocalDate from, LocalDate to) {
        jdbc.query("SELECT audience, channel, count(DISTINCT user_id) FROM daily_activity "
                + "WHERE day BETWEEN ? AND ? GROUP BY GROUPING SETS ((audience, channel), (audience))", rs -> {
                    String channel = rs.getString(2);
                    rows.add(row(metric, d, rs.getString(1) + ":" + (channel == null ? "ALL" : channel),
                            rs.getLong(3)));
                }, Date.valueOf(from), Date.valueOf(to));
    }

    /** Activity older than {@link #ACTIVITY_DAYS} days goes: no longer needed for any count. */
    public int forgetOldActivity(LocalDate today) {
        // Refunds waiting for a subscription charge that never came (a kirana order's, say)
        jdbc.update("DELETE FROM subscription_refunds_waiting WHERE created_at < now() - INTERVAL '30 days'");
        return jdbc.update("DELETE FROM daily_activity WHERE day < ?", Date.valueOf(today.minusDays(ACTIVITY_DAYS)));
    }

    /**
     * Every salon's use over the last 30 days and its setup (not closed ones, not kiranas), in one statement. The last
     * active day never goes back (activity older than 35 days is gone by then).
     */
    public int refreshSalonUsage(Instant now) {
        Timestamp since = Timestamp.from(now.minus(java.time.Duration.ofDays(30)));
        return jdbc.update("INSERT INTO salon_usage (shop_id, last_active_day, bookings_30d, completed_30d, "
                + "walk_ins_30d, no_shows_30d, services, staff, pictures, updated_at) "
                + "SELECT s.id, "
                + "(SELECT max(a.day) FROM shop_members m JOIN daily_activity a ON a.user_id = m.user_id "
                + "WHERE m.shop_id = s.id AND a.audience = 'PARTNER'), "
                + "coalesce(v.bookings, 0), coalesce(v.completed, 0), coalesce(v.walk_ins, 0), coalesce(v.no_shows, 0), "
                + "(SELECT count(*) FROM salon_services x WHERE x.shop_id = s.id AND x.active), "
                + "(SELECT count(*) FROM salon_staff x WHERE x.shop_id = s.id AND x.active AND x.removed_at IS NULL), "
                + "(s.owner_photo_file_id IS NOT NULL)::int + (s.shop_photo_file_id IS NOT NULL)::int "
                + "+ (s.shop_video_file_id IS NOT NULL)::int, ? "
                + "FROM shops s LEFT JOIN (SELECT shop_id, "
                + "count(*) FILTER (WHERE kind = 'BOOKING') AS bookings, "
                + "count(*) FILTER (WHERE status = 'COMPLETED') AS completed, "
                + "count(*) FILTER (WHERE kind = 'WALK_IN') AS walk_ins, "
                + "count(*) FILTER (WHERE status = 'NO_SHOW') AS no_shows "
                + "FROM appointments WHERE kind <> 'BLOCK' AND starts_at >= ? AND starts_at < ? GROUP BY shop_id) v "
                + "ON v.shop_id = s.id WHERE s.type = 'SALON' AND s.status <> 'CLOSED' AND " + NOT_TEST_SALON + " "
                + "ON CONFLICT (shop_id) DO UPDATE SET "
                + "last_active_day = GREATEST(salon_usage.last_active_day, EXCLUDED.last_active_day), "
                + "bookings_30d = EXCLUDED.bookings_30d, completed_30d = EXCLUDED.completed_30d, "
                + "walk_ins_30d = EXCLUDED.walk_ins_30d, no_shows_30d = EXCLUDED.no_shows_30d, "
                + "services = EXCLUDED.services, staff = EXCLUDED.staff, pictures = EXCLUDED.pictures, "
                + "updated_at = EXCLUDED.updated_at",
                Timestamp.from(now), since, Timestamp.from(now));
    }

    /** Whether a one-time piece of work (analytics_jobs) has been done. */
    public boolean done(String job) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM analytics_jobs WHERE name = ?)",
                Boolean.class, job));
    }

    public void markDone(String job, Instant now) {
        jdbc.update("INSERT INTO analytics_jobs (name, done_at) VALUES (?, ?) ON CONFLICT (name) DO NOTHING", job,
                Timestamp.from(now));
    }

    private static Object[] row(String metric, Date day, String dim, Long value) {
        Object v = value == null ? Long.valueOf(0) : value;
        return new Object[] {metric, day, dim == null ? "" : dim, v};
    }
}
