package in.apnidukan.salon.service;

import in.apnidukan.common.context.SalonFacts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** How big a salon is, for the field team. */
@Service
class SalonFactsService implements SalonFacts {

    private final JdbcTemplate jdbc;

    SalonFactsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Size size(UUID shopId) {
        Integer barbers = jdbc.queryForObject("SELECT count(*) FROM salon_staff WHERE shop_id = ? AND active",
                Integer.class, shopId);
        Integer services = jdbc.queryForObject("SELECT count(*) FROM salon_services WHERE shop_id = ? AND active",
                Integer.class, shopId);
        return new Size(barbers == null ? 0 : barbers, services == null ? 0 : services);
    }
}
