package in.apnidukan.salon.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * The visit_follow_ups and salon_customer_prefs tables (V34): after each completed online booking, when to ask the
 * customer to rate it and when to remind them to come back; and whether they want those reminders.
 */
@Repository
public class VisitFollowUpStore {

    /** The rating request goes this long after the visit ended (SQL interval). */
    public static final String PROMPT_DELAY = "10 minutes";

    /**
     * When to remind the customer of appointment {@code a} to come back: 10:00 India time, the shortest come-back
     * interval of its services (leaving out those with 0, "no reminder") after the visit's day; null if every
     * service has 0. Uses the services' intervals as they are at the end of the visit. (One constant, so every SQL
     * text built from it is a constant too.)
     */
    static final String REMIND_AT = "(SELECT (date(a.ends_at AT TIME ZONE 'Asia/Kolkata') + min(ss.return_after_days) "
            + "+ time '10:00') AT TIME ZONE 'Asia/Kolkata' "
            + "FROM appointment_services aps JOIN salon_services ss ON ss.id = aps.service_id "
            + "WHERE aps.appointment_id = a.id AND ss.return_after_days > 0)";

    /** The columns written and the values read from appointment {@code a}: two parameters, the completion twice. */
    private static final String INSERT_FROM_A = "INSERT INTO visit_follow_ups (appointment_id, customer_id, shop_id, "
            + "completed_at, prompt_at, remind_at) SELECT a.id, a.customer_id, a.shop_id, ?, ?::timestamptz + interval '"
            + PROMPT_DELAY + "', " + REMIND_AT + " FROM appointments a ";

    /**
     * Rows for the completed online bookings among the ids of a CTE named {@code done} (written in the same statement
     * that completes them: AppointmentStore.completeEnded). Takes two parameters: the moment of completion, twice.
     * The appointment rows are read as they were before that statement (a statement's CTEs don't see each other's
     * changes), which is fine: completing changes neither the customer, the salon nor the planned end.
     */
    static final String FOR_DONE = INSERT_FROM_A + "WHERE a.id IN (SELECT id FROM done) AND a.kind = 'BOOKING' "
            + "ON CONFLICT (appointment_id) DO NOTHING";

    /** The row for one booking finished early: three parameters (the completion twice, the appointment id). */
    private static final String FOR_FINISHED = INSERT_FROM_A + "WHERE a.id = ? AND a.kind = 'BOOKING' "
            + "AND a.status = 'COMPLETED' ON CONFLICT (appointment_id) DO NOTHING";

    private final JdbcTemplate jdbc;

    public VisitFollowUpStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The row for one booking just finished early (Finish), after its status and end were written: nothing for a
     * walk-in, or if it is already there.
     */
    public void addFinished(UUID appointmentId, Instant now) {
        jdbc.update(FOR_FINISHED, AppointmentStore.at(now), AppointmentStore.at(now), appointmentId);
    }

    /**
     * A rating request due now, taken off the queue.
     * @param rated        the customer rated the visit already (then nothing is sent)
     * @param worksThere   the customer works in the salon: owner, helper or barber (they can't rate it: nothing is
     *                     sent)
     * @param salonLive    the salon is approved (a suspended one isn't asked about)
     * @param accountLive  the customer's account isn't deleted (a visit can complete just after a deletion)
     * @param services     the visit's services, in the order chosen
     */
    public record Prompt(UUID appointmentId, UUID customerId, UUID shopId, Instant completedAt, String salonName,
                         boolean rated, boolean worksThere, boolean salonLive, boolean accountLive,
                         List<String> services) {

        public Prompt {
            services = List.copyOf(services);
        }
    }

    /**
     * Up to {@code limit} rating requests due by {@code now}, oldest first, each taken off the queue (prompt_at set to
     * NULL) in the caller's transaction, with what the message needs. Rows another server holds are skipped.
     */
    public List<Prompt> takePrompts(Instant now, int limit) {
        return jdbc.query("""
                        WITH due AS MATERIALIZED (
                            SELECT appointment_id FROM visit_follow_ups WHERE prompt_at <= ?
                            ORDER BY prompt_at LIMIT ? FOR UPDATE SKIP LOCKED),
                        taken AS (
                            UPDATE visit_follow_ups f SET prompt_at = NULL FROM due
                            WHERE f.appointment_id = due.appointment_id
                            RETURNING f.appointment_id, f.customer_id, f.shop_id, f.completed_at)
                        SELECT t.appointment_id, t.customer_id, t.shop_id, t.completed_at, s.name AS salon_name,
                            EXISTS (SELECT 1 FROM salon_ratings r WHERE r.appointment_id = t.appointment_id) AS rated,
                            EXISTS (SELECT 1 FROM shop_members m WHERE m.user_id = t.customer_id
                                    AND m.shop_id = t.shop_id) AS works_there,
                            s.status = 'APPROVED' AS salon_live,
                            EXISTS (SELECT 1 FROM users u WHERE u.id = t.customer_id AND u.deleted_at IS NULL)
                                AS account_live,
                            ARRAY(SELECT aps.name FROM appointment_services aps
                                  WHERE aps.appointment_id = t.appointment_id ORDER BY aps.position) AS services
                        FROM taken t JOIN shops s ON s.id = t.shop_id""",
                (rs, i) -> new Prompt(rs.getObject("appointment_id", UUID.class),
                        rs.getObject("customer_id", UUID.class), rs.getObject("shop_id", UUID.class),
                        rs.getObject("completed_at", OffsetDateTime.class).toInstant(), rs.getString("salon_name"),
                        rs.getBoolean("rated"), rs.getBoolean("works_there"), rs.getBoolean("salon_live"),
                        rs.getBoolean("account_live"), strings(rs.getArray("services"))),
                AppointmentStore.at(now), limit);
    }

    /**
     * A come-back reminder due now, taken off the queue, with every reason not to send it.
     * @param salonApproved   the salon is still live and taking online bookings (approved, not paused)
     * @param wantsReminders  the customer hasn't switched the reminders off, their account isn't deleted, and they
     *                        don't work in the salon (an owner, helper or barber who booked it as a customer)
     * @param hasUpcoming     the customer already has a booking coming up (any salon)
     * @param visitedSince    the customer completed another online booking (any salon) after this one
     * @param serviceIds      the visit's services the reminder is about (see dueAmong) that the salon still offers
     *                        (active), in the order chosen
     * @param serviceNames    their names, in the same order
     */
    public record Reminder(UUID appointmentId, UUID customerId, UUID shopId, Instant completedAt, String salonName,
                           boolean salonApproved, boolean wantsReminders, boolean hasUpcoming, boolean visitedSince,
                           List<UUID> serviceIds, List<String> serviceNames) {

        public Reminder {
            serviceIds = List.copyOf(serviceIds);
            serviceNames = List.copyOf(serviceNames);
        }
    }

    /**
     * Up to {@code limit} come-back reminders due by {@code now}, oldest first, each taken off the queue (remind_at
     * set to NULL) in the caller's transaction, with what deciding and the message need. Rows another server holds
     * are skipped.
     */
    public List<Reminder> takeReminders(Instant now, int limit) {
        return jdbc.query("""
                        WITH due AS MATERIALIZED (
                            SELECT appointment_id, remind_at FROM visit_follow_ups WHERE remind_at <= ?
                            ORDER BY remind_at LIMIT ? FOR UPDATE SKIP LOCKED),
                        taken AS (
                            UPDATE visit_follow_ups f SET remind_at = NULL FROM due
                            WHERE f.appointment_id = due.appointment_id
                            RETURNING f.appointment_id, f.customer_id, f.shop_id, f.completed_at,
                                due.remind_at AS was_due)
                        SELECT t.appointment_id, t.customer_id, t.shop_id, t.completed_at, s.name AS salon_name,
                            COALESCE(p.come_back_reminders, true)
                                AND EXISTS (SELECT 1 FROM users u WHERE u.id = t.customer_id AND u.deleted_at IS NULL)
                                AND NOT EXISTS (SELECT 1 FROM shop_members m WHERE m.user_id = t.customer_id
                                                AND m.shop_id = t.shop_id) AS wants,
                            EXISTS (SELECT 1 FROM appointments b WHERE b.customer_id = t.customer_id
                                    AND b.status IN ('CONFIRMED', 'IN_SERVICE') AND b.ends_at > ?) AS has_upcoming,
                            s.status = 'APPROVED' AND NOT EXISTS (SELECT 1 FROM salon_pauses sp
                                WHERE sp.shop_id = t.shop_id AND sp.paused_until > ?) AS salon_approved,
                            EXISTS (SELECT 1 FROM visit_follow_ups n WHERE n.customer_id = t.customer_id
                                    AND n.completed_at > t.completed_at) AS visited_since,
                            ARRAY(SELECT aps.service_id FROM appointment_services aps
                                  JOIN salon_services ss ON ss.id = aps.service_id AND ss.active
                                  WHERE aps.appointment_id = t.appointment_id AND ss.return_after_days > 0
                                  ORDER BY aps.position) AS service_ids,
                            ARRAY(SELECT ss.name FROM appointment_services aps
                                  JOIN salon_services ss ON ss.id = aps.service_id AND ss.active
                                  WHERE aps.appointment_id = t.appointment_id AND ss.return_after_days > 0
                                  ORDER BY aps.position) AS service_names,
                            ARRAY(SELECT ss.return_after_days FROM appointment_services aps
                                  JOIN salon_services ss ON ss.id = aps.service_id AND ss.active
                                  WHERE aps.appointment_id = t.appointment_id AND ss.return_after_days > 0
                                  ORDER BY aps.position) AS service_days,
                            -- the interval the reminder was scheduled with (REMIND_AT: from the visit's day)
                            date(t.was_due AT TIME ZONE 'Asia/Kolkata')
                                - date(a.ends_at AT TIME ZONE 'Asia/Kolkata') AS scheduled_days
                        FROM taken t JOIN shops s ON s.id = t.shop_id
                        JOIN appointments a ON a.id = t.appointment_id
                        LEFT JOIN salon_customer_prefs p ON p.user_id = t.customer_id""",
                (rs, i) -> {
                    List<Integer> due = dueAmong(ints(rs.getArray("service_days")), rs.getInt("scheduled_days"));
                    List<UUID> ids = uuids(rs.getArray("service_ids"));
                    List<String> names = strings(rs.getArray("service_names"));
                    return new Reminder(rs.getObject("appointment_id", UUID.class),
                            rs.getObject("customer_id", UUID.class), rs.getObject("shop_id", UUID.class),
                            rs.getObject("completed_at", OffsetDateTime.class).toInstant(), rs.getString("salon_name"),
                            rs.getBoolean("salon_approved"), rs.getBoolean("wants"), rs.getBoolean("has_upcoming"),
                            rs.getBoolean("visited_since"), due.stream().map(ids::get).toList(),
                            due.stream().map(names::get).toList());
                },
                AppointmentStore.at(now), limit, AppointmentStore.at(now), AppointmentStore.at(now));
    }

    /**
     * Which of a visit's services (still offered, with a come-back interval) the reminder is about: those whose
     * interval had passed by the day it was scheduled for (a haircut every 3 weeks and a beard trim every week: at
     * one week, the beard trim). If none (the owner lengthened an interval since), all of them, so the reminder isn't
     * lost. Returns their positions in the list.
     */
    public static List<Integer> dueAmong(List<Integer> days, int scheduledDays) {
        List<Integer> due = new ArrayList<>();
        for (int i = 0; i < days.size(); i++) {
            if (days.get(i) <= scheduledDays) {
                due.add(i);
            }
        }
        if (due.isEmpty()) {
            for (int i = 0; i < days.size(); i++) {
                due.add(i);
            }
        }
        return due;
    }

    /** Whether this customer wants come-back reminders (no choice saved: yes). */
    public boolean wantsReminders(UUID customerId) {
        List<Boolean> saved = jdbc.query("SELECT come_back_reminders FROM salon_customer_prefs WHERE user_id = ?",
                (rs, i) -> rs.getBoolean(1), customerId);
        return saved.isEmpty() || saved.get(0);
    }

    /**
     * The visit was just rated: its rating request is taken off the queue. Waits for a follow-up round that holds the
     * row (it may be about to queue the request), so the caller's cancelling of a queued request comes after it.
     */
    public void ratedNow(UUID appointmentId) {
        jdbc.update("UPDATE visit_follow_ups SET prompt_at = NULL WHERE appointment_id = ?", appointmentId);
    }

    /** Saves the customer's choice. */
    public void setWantsReminders(UUID customerId, boolean on, Instant now) {
        jdbc.update("INSERT INTO salon_customer_prefs (user_id, come_back_reminders, updated_at) VALUES (?, ?, ?) "
                        + "ON CONFLICT (user_id) DO UPDATE SET come_back_reminders = EXCLUDED.come_back_reminders, "
                        + "updated_at = EXCLUDED.updated_at",
                customerId, on, AppointmentStore.at(now));
    }

    /** A deleted account: nothing more is sent about their visits, and their choice goes. */
    public void forget(UUID customerId) {
        jdbc.update("DELETE FROM visit_follow_ups WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM salon_customer_prefs WHERE user_id = ?", customerId);
    }

    private static List<String> strings(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return new ArrayList<>(Arrays.asList((String[]) array.getArray()));
    }

    private static List<Integer> ints(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return new ArrayList<>(Arrays.asList((Integer[]) array.getArray()));
    }

    private static List<UUID> uuids(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return new ArrayList<>(Arrays.asList((UUID[]) array.getArray()));
    }
}
