package in.apnidukan.salon.repo;

import in.apnidukan.salon.domain.Interval;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The earlier_slot_alerts table (V33): which online bookings asked to be told when an earlier time opens the same
 * day, and the start each was offered last. Used by EarlierSlotAlerts (the offers) and BookingService
 * (switching it on and off, and keeping its copy of the start in step on a move).
 */
@Repository
public class EarlierSlotAlertStore {

    private final JdbcTemplate jdbc;

    public EarlierSlotAlertStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Switches the alert on for this booking, at its start now. Already on: what it was offered last stays, unless its
     * copy of the start is out of date (the booking was moved through a copy of the backend from before V33, which
     * doesn't keep it in step): then the copy is put right and the offer forgotten, so switching on again repairs it.
     */
    public void on(UUID appointmentId, UUID shopId, Instant startsAt, Instant now) {
        jdbc.update("INSERT INTO earlier_slot_alerts AS e (appointment_id, shop_id, starts_at, created_at) "
                        + "VALUES (?, ?, ?, ?) ON CONFLICT (appointment_id) DO UPDATE SET starts_at = EXCLUDED.starts_at, "
                        + "offered_start = CASE WHEN e.starts_at = EXCLUDED.starts_at THEN e.offered_start END",
                appointmentId, shopId, AppointmentStore.at(startsAt), AppointmentStore.at(now));
    }

    /** Switches it off (already off: nothing happens). */
    public void off(UUID appointmentId) {
        jdbc.update("DELETE FROM earlier_slot_alerts WHERE appointment_id = ?", appointmentId);
    }

    /**
     * The customer moved the booking: the copy of its start follows, and what it was offered last is forgotten (from
     * the new time, any earlier one is news again). No row: nothing happens.
     */
    public void moved(UUID appointmentId, Instant startsAt) {
        jdbc.update("UPDATE earlier_slot_alerts SET starts_at = ?, offered_start = NULL WHERE appointment_id = ?",
                AppointmentStore.at(startsAt), appointmentId);
    }

    /**
     * Which of these bookings have the alert on, in one query (a page of the customer's bookings): those whose row
     * has the booking's start. One moved through a copy of the backend from before V33 has a stale row that is never
     * offered anything, so it shows off, and switching it on puts it right (on()).
     */
    public Set<UUID> switchedOn(Collection<UUID> appointmentIds) {
        Set<UUID> out = new HashSet<>();
        if (appointmentIds.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT e.appointment_id FROM earlier_slot_alerts e JOIN appointments a "
                        + "ON a.id = e.appointment_id AND a.starts_at = e.starts_at WHERE e.appointment_id = ANY(?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", appointmentIds.toArray())),
                rs -> {
                    out.add(rs.getObject(1, UUID.class));
                });
        return out;
    }

    /**
     * A booking that asked for the alert, as the offer needs it.
     * @param offeredStart the start offered to it last, or null
     */
    public record Candidate(UUID id, UUID customerId, UUID staffId, Instant startsAt, Instant endsAt,
                            Instant offeredStart) {

        public Interval time() {
            return new Interval(startsAt, endsAt);
        }
    }

    /**
     * The salon's online bookings with the alert on that are still waiting (CONFIRMED) and start after {@code after},
     * from {@code from} and before {@code to}, soonest first, at most {@code limit}. One range of
     * ix_earlier_slot_alerts_shop_start, then each booking by its primary key; a row whose booking closed, or moved
     * since its copy was written, is left out by the join.
     */
    public List<Candidate> candidates(UUID shopId, Instant after, Instant from, Instant to, UUID except, int limit) {
        return jdbc.query("SELECT a.id, a.customer_id, a.staff_id, a.starts_at, a.ends_at, e.offered_start "
                        + "FROM earlier_slot_alerts e JOIN appointments a ON a.id = e.appointment_id "
                        + "WHERE e.shop_id = ? AND e.starts_at > ? AND e.starts_at >= ? AND e.starts_at < ? "
                        + "AND e.appointment_id <> ? AND a.status = 'CONFIRMED' AND a.kind = 'BOOKING' "
                        + "AND a.starts_at = e.starts_at ORDER BY e.starts_at, e.appointment_id LIMIT ?",
                (rs, i) -> new Candidate(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), instant(rs, 4), instant(rs, 5), instant(rs, 6)),
                shopId, AppointmentStore.at(after), AppointmentStore.at(from), AppointmentStore.at(to),
                except, limit);
    }

    /** One booking a customer holds, for the clash check: which one, and its time. */
    public record Held(UUID id, Interval time) {
    }

    /**
     * Every booking these customers hold (CONFIRMED or IN_SERVICE) not over by {@code now}, by customer: an offered
     * time must not clash with their other bookings (the move would refuse it with OVERLAPS_YOUR_BOOKING); each
     * candidate leaves out only itself. Through ix_appointments_customer; a customer holds at most a few.
     */
    public Map<UUID, List<Held>> heldBy(Collection<UUID> customerIds, Instant now) {
        Map<UUID, List<Held>> out = new HashMap<>();
        if (customerIds.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT customer_id, id, starts_at, ends_at FROM appointments WHERE customer_id = ANY(?) "
                        + "AND status IN ('CONFIRMED', 'IN_SERVICE') AND ends_at > ?",
                ps -> {
                    ps.setArray(1, ps.getConnection().createArrayOf("uuid", customerIds.toArray()));
                    ps.setObject(2, AppointmentStore.at(now));
                },
                rs -> {
                    out.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                            .add(new Held(rs.getObject(2, UUID.class), new Interval(instant(rs, 3), instant(rs, 4))));
                });
        return out;
    }

    /**
     * One offer to record.
     * @param bookedStart the start the booking had when it was read: if it was moved or closed since, nothing is sent
     */
    public record Offer(UUID id, Instant bookedStart, Instant start) {
    }

    /**
     * Records the offers, each only if it isn't the start that booking was offered last, and returns the bookings
     * whose offer was recorded: only they are told. So the same time is never sent twice in a row (a time offered,
     * taken by someone else and freed again is no news to them), while another time is (the one offered before may
     * have gone to whoever moved first). The rows are locked in id order first, so two offers running at once (two
     * cancels at one salon) wait for each other instead of deadlocking, and the second sees the first's offers. A
     * booking moved, cancelled, started or switched off since it was read (after the lock, as committed) is left out:
     * it would be told of a time against a start it no longer has.
     */
    public Set<UUID> recordOffers(List<Offer> offers) {
        Set<UUID> out = new HashSet<>();
        if (offers.isEmpty()) {
            return out;
        }
        List<Offer> sorted = offers.stream().sorted(Comparator.comparing(Offer::id)).toList();
        List<UUID> ids = sorted.stream().map(Offer::id).toList();
        // First lock the rows, in id order; then, in a statement of its own (READ COMMITTED: a fresh look at what is
        // committed by then), record against bookings still as they were read. One statement with both would judge
        // the bookings as they were before it waited for the locks
        jdbc.query("SELECT appointment_id FROM earlier_slot_alerts WHERE appointment_id = ANY(?) "
                        + "ORDER BY appointment_id FOR UPDATE",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", ids.toArray())),
                rs -> {
                });
        jdbc.query("""
                        UPDATE earlier_slot_alerts e SET offered_start = o.t
                        FROM unnest(?::uuid[], ?::timestamptz[], ?::timestamptz[]) AS o(id, booked, t), appointments a
                        WHERE e.appointment_id = o.id AND a.id = o.id
                            AND e.starts_at = o.booked AND a.starts_at = o.booked AND a.status = 'CONFIRMED'
                            AND e.offered_start IS DISTINCT FROM o.t
                        RETURNING e.appointment_id""",
                ps -> {
                    ps.setArray(1, ps.getConnection().createArrayOf("uuid", ids.toArray()));
                    ps.setArray(2, ps.getConnection().createArrayOf("text",
                            sorted.stream().map(o -> o.bookedStart().toString()).toArray()));
                    ps.setArray(3, ps.getConnection().createArrayOf("text",
                            sorted.stream().map(o -> o.start().toString()).toArray()));
                },
                rs -> {
                    out.add(rs.getObject(1, UUID.class));
                });
        return out;
    }

    /**
     * Deletes up to {@code limit} rows whose booking started before {@code before} (the automatic-end round, a day
     * after the start: nothing can be offered to them any more). Returns how many it deleted.
     */
    public int deleteStartedBefore(Instant before, int limit) {
        return jdbc.update("DELETE FROM earlier_slot_alerts WHERE appointment_id IN (SELECT appointment_id "
                + "FROM earlier_slot_alerts WHERE starts_at < ? LIMIT ?)", AppointmentStore.at(before), limit);
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
