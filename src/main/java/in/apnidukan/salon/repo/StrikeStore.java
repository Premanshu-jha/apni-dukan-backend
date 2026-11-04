package in.apnidukan.salon.repo;

import in.apnidukan.salon.domain.StrikeKind;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** The customer_strikes table: no-shows and late cancels, one per booking at most. */
@Repository
public class StrikeStore {

    private final JdbcTemplate jdbc;

    public StrikeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Records a strike; a second one for the same booking is ignored (unique appointment_id). */
    public void add(UUID customerId, UUID appointmentId, StrikeKind kind, Instant at) {
        jdbc.update("INSERT INTO customer_strikes (id, customer_id, appointment_id, kind, struck_at) "
                        + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (appointment_id) DO NOTHING",
                UUID.randomUUID(), customerId, appointmentId, kind.name(), AppointmentStore.at(at));
    }

    /** When this customer's strikes since {@code since} happened, latest first. */
    public List<Instant> since(UUID customerId, Instant since) {
        return jdbc.query("SELECT struck_at FROM customer_strikes WHERE customer_id = ? AND struck_at >= ? "
                        + "ORDER BY struck_at DESC",
                (rs, i) -> rs.getObject(1, OffsetDateTime.class).toInstant(), customerId, AppointmentStore.at(since));
    }
}
