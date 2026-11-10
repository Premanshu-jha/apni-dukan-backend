package in.apnidukan.salon.service;

import in.apnidukan.common.context.SalonFacts;
import in.apnidukan.salon.domain.SalonTime;
import in.apnidukan.salon.repo.RatingStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** How big a salon is and how it is set up, for registration and the field team. */
@Service
class SalonFactsService implements SalonFacts {

    private final JdbcTemplate jdbc;
    private final RatingStore ratings;

    SalonFactsService(JdbcTemplate jdbc, RatingStore ratings) {
        this.jdbc = jdbc;
        this.ratings = ratings;
    }

    @Override
    public Size size(UUID shopId) {
        Integer barbers = jdbc.queryForObject("SELECT count(*) FROM salon_staff WHERE shop_id = ? AND active",
                Integer.class, shopId);
        Integer services = jdbc.queryForObject("SELECT count(*) FROM salon_services WHERE shop_id = ? AND active",
                Integer.class, shopId);
        return new Size(barbers == null ? 0 : barbers, services == null ? 0 : services);
    }

    @Override
    public boolean hasOpeningHours(UUID shopId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM salon_hours WHERE shop_id = ?)", Boolean.class, shopId));
    }

    @Override
    public Profile profile(UUID shopId) {
        List<SalonFacts.Service> services = jdbc.query("SELECT name, duration_min, price_paise, price_is_from, "
                        + "return_after_days FROM salon_services WHERE shop_id = ? AND active "
                        + "ORDER BY sort_order, name",
                (rs, i) -> new SalonFacts.Service(rs.getString("name"), rs.getInt("duration_min"),
                        rs.getLong("price_paise"), rs.getBoolean("price_is_from"), rs.getInt("return_after_days")),
                shopId);
        List<Day> hours = jdbc.query("SELECT weekday, open_min, close_min FROM salon_hours WHERE shop_id = ? "
                        + "ORDER BY weekday",
                (rs, i) -> new Day(rs.getInt("weekday"), SalonTime.format(rs.getInt("open_min")),
                        SalonTime.format(rs.getInt("close_min"))),
                shopId);
        RatingStore.Totals totals = ratings.totals(shopId);
        return new Profile(services, hours, totals.ratings(), totals.average());
    }
}
