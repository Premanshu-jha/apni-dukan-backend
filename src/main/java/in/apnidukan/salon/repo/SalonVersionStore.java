package in.apnidukan.salon.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * salon_versions (V17): each salon's change counters, kept by database triggers on every table its day board,
 * upcoming list and setup are read from. Read with one primary-key lookup; never written by the app.
 */
@Repository
public class SalonVersionStore {

    /**
     * @param version      moves on any change the salon's screens could show (appointments, barbers, hours, services,
     *                     members, walk-in access, pauses, settings, a customer's name)
     * @param setupVersion moves only when the setup changes (barbers, their hours and services, services, settings)
     */
    public record Versions(long version, long setupVersion) {
    }

    private final JdbcTemplate jdbc;

    public SalonVersionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The salon's counters; 0 and 0 if nothing of it changed since V17 (no row yet). */
    public Versions find(UUID shopId) {
        return jdbc.query("SELECT version, setup_version FROM salon_versions WHERE shop_id = ?",
                        (rs, i) -> new Versions(rs.getLong(1), rs.getLong(2)), shopId)
                .stream().findFirst().orElse(new Versions(0, 0));
    }

    /**
     * Moves the counters of every salon this customer has appointments with (found through ix_appointments_customer),
     * in one statement and in shop id order (salon_versions_bump). Their name and phone show on those screens.
     */
    public void bumpForCustomer(UUID customerId) {
        jdbc.query("SELECT salon_versions_bump(ARRAY(SELECT shop_id FROM appointments WHERE customer_id = ?), false)",
                rs -> null, customerId);
    }
}
