package in.apnidukan.salon.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the salon list's cards show besides each salon's own details: ratings, top services, today's hours and whether
 * it is closed today. One query for a whole page of salons (at most 30), each part found by its index.
 */
@Repository
public class SalonCardStore {

    /** How many services a card names (the rest are counted: "+5 more"); the [1:3] in the query. */
    public static final int TOP_SERVICES = 3;

    /**
     * One salon's card facts as stored.
     * @param hasHours   the salon has set its opening hours
     * @param openMin    today's opening (minutes after midnight); null if no hours today
     * @param closeMin   today's closing; null if no hours today
     * @param closedDay  today is one of its closed days (holidays)
     * @param services   its active services that an active barber does
     * @param top        the first TOP_SERVICES of them in the salon's order
     * @param registered the salon's trade licence (optional) passed the automatic check and is valid that day
     * @param worksToday a working barber has hours on today's weekday (none: the salon is closed today, whatever its
     *                   opening hours say)
     * @param offer      its offer running today or coming next (not ended early, its last day today or later, some
     *                   of its services still on): the soonest, the biggest of those starting the same day; null if
     *                   none
     */
    public record Facts(int ratings, long starsSum, boolean hasHours, Integer openMin, Integer closeMin,
                        boolean closedDay, int services, List<Top> top, boolean registered, boolean worksToday,
                        Offer offer) {

        public Facts {
            top = List.copyOf(top);
        }
    }

    public record Top(String name, long pricePaise, boolean priceIsFrom) {
    }

    public record Offer(int percentOff, LocalDate startsOn, LocalDate endsOn) {
    }

    private static final String SQL = """
            SELECT u.id, COALESCE(t.ratings, 0) AS ratings, COALESCE(t.stars_sum, 0) AS stars_sum,
                EXISTS (SELECT 1 FROM salon_hours x WHERE x.shop_id = u.id) AS has_hours,
                h.open_min, h.close_min,
                EXISTS (SELECT 1 FROM salon_closed_days c WHERE c.shop_id = u.id
                        AND c.starts_at >= ? AND c.starts_at < ?) AS closed_day,
                EXISTS (SELECT 1 FROM shops r WHERE r.id = u.id AND r.licence_check_status = 'PASSED'
                        AND r.licence_valid_until >= ?) AS registered,
                EXISTS (SELECT 1 FROM salon_staff w JOIN staff_hours wh ON wh.staff_id = w.id
                        WHERE w.shop_id = u.id AND w.active AND wh.weekday = h.weekday) AS works_today,
                sv.n, sv.names, sv.prices, sv.froms, f.percent_off, f.starts_on, f.ends_on
            FROM unnest(?::uuid[]) AS u(id)
            LEFT JOIN salon_rating_totals t ON t.shop_id = u.id
            LEFT JOIN salon_hours h ON h.shop_id = u.id AND h.weekday = ?
            LEFT JOIN LATERAL (
                SELECT count(*) AS n,
                    (array_agg(o.name ORDER BY o.sort_order, o.name))[1:3] AS names,
                    (array_agg(o.price_paise ORDER BY o.sort_order, o.name))[1:3] AS prices,
                    (array_agg(o.price_is_from ORDER BY o.sort_order, o.name))[1:3] AS froms
                FROM salon_services o
                WHERE o.shop_id = u.id AND o.active AND o.id IN (
                    SELECT k.service_id FROM salon_staff st JOIN staff_services k ON k.staff_id = st.id
                    WHERE st.shop_id = u.id AND st.active)) sv ON true
            LEFT JOIN LATERAL (
                SELECT o.percent_off, o.starts_on, o.ends_on FROM salon_offers o
                WHERE o.shop_id = u.id AND o.ended_at IS NULL AND o.ends_on >= ?
                  AND (o.all_services OR EXISTS (SELECT 1 FROM salon_offer_services x JOIN salon_services s
                       ON s.id = x.service_id WHERE x.offer_id = o.id AND s.active))
                ORDER BY o.starts_on, o.percent_off DESC LIMIT 1) f ON true""";

    private final JdbcTemplate jdbc;

    public SalonCardStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The last valid day of the salon's trade licence if it passed the automatic check and is still valid
     * {@code today} (the salon's page says "Licensed until …"); empty otherwise. One primary-key read.
     */
    public java.util.Optional<LocalDate> licensedUntil(UUID shopId, LocalDate today) {
        return jdbc.queryForList("SELECT licence_valid_until FROM shops WHERE id = ? AND licence_check_status = 'PASSED' "
                + "AND licence_valid_until >= ?", LocalDate.class, shopId, today).stream().findFirst();
    }

    /**
     * The card facts of these salons for one India day.
     * @param weekday  that day's weekday (1 = Monday)
     * @param dayStart that day's first moment
     * @param dayEnd   the next day's first moment
     * @param today    that day's date (offers lasting until it or later)
     */
    public Map<UUID, Facts> facts(Collection<UUID> shopIds, int weekday, Instant dayStart, Instant dayEnd,
                                  LocalDate today) {
        Map<UUID, Facts> out = new HashMap<>();
        if (shopIds.isEmpty()) {
            return out;
        }
        jdbc.query(SQL, ps -> {
            ps.setObject(1, AppointmentStore.at(dayStart));
            ps.setObject(2, AppointmentStore.at(dayEnd));
            ps.setObject(3, today);
            ps.setArray(4, ps.getConnection().createArrayOf("uuid", shopIds.toArray()));
            ps.setInt(5, weekday);
            ps.setObject(6, today);
        }, rs -> {
            List<Top> top = new ArrayList<>();
            Object[] names = array(rs.getArray("names"));
            Object[] prices = array(rs.getArray("prices"));
            Object[] froms = array(rs.getArray("froms"));
            for (int i = 0; i < names.length; i++) {
                top.add(new Top((String) names[i], ((Number) prices[i]).longValue(), (Boolean) froms[i]));
            }
            out.put(rs.getObject("id", UUID.class), new Facts(rs.getInt("ratings"), rs.getLong("stars_sum"),
                    rs.getBoolean("has_hours"), (Integer) rs.getObject("open_min"), (Integer) rs.getObject("close_min"),
                    rs.getBoolean("closed_day"), rs.getInt("n"), top, rs.getBoolean("registered"),
                    rs.getBoolean("works_today"), rs.getObject("percent_off") == null ? null
                            : new Offer(rs.getInt("percent_off"), rs.getObject("starts_on", LocalDate.class),
                                    rs.getObject("ends_on", LocalDate.class))));
        });
        return out;
    }

    private static Object[] array(Array a) throws SQLException {
        return a == null ? new Object[0] : (Object[]) a.getArray();
    }
}
