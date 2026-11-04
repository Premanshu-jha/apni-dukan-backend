package in.apnidukan.salon.repo;

import in.apnidukan.salon.domain.Appointment;
import in.apnidukan.salon.domain.AppointmentKind;
import in.apnidukan.salon.domain.AppointmentStatus;
import in.apnidukan.salon.domain.BlockType;
import in.apnidukan.salon.domain.BookedService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The appointments, appointment_services and appointment_events tables, in plain SQL. Every status change is a
 * conditional UPDATE ("... WHERE status = the status we expect"): if someone else changed the row first, nothing is
 * updated and the caller reports it, so two people can never both act on one appointment.
 */
@Repository
public class AppointmentStore {

    private static final String COLUMNS = "id, shop_id, staff_id, kind, block_type, closure_id, customer_id, "
            + "booking_no, guest_name, start_code, starts_at, ends_at, status, started_at, "
            + "started_with_code, cancel_reason, created_by, client_request_id, created_at, updated_at";
    private static final String HOLDING = "status IN ('CONFIRMED', 'IN_SERVICE', 'BLOCKED')";
    // Whole statements as constants: no SQL is ever built from values (they always go in as ? parameters)
    private static final String HOLDING_SQL = "SELECT " + COLUMNS + " FROM appointments WHERE staff_id = ANY(?) AND "
            + HOLDING + " AND starts_at < ? AND ends_at > ? AND id <> ? ORDER BY starts_at";
    /** Upcoming: still open (CONFIRMED or IN_SERVICE) and not over yet. Past: everything else. */
    private static final String UPCOMING_SQL = "SELECT " + COLUMNS + " FROM appointments WHERE customer_id = ? "
            + "AND status IN ('CONFIRMED', 'IN_SERVICE') AND ends_at > ? ORDER BY starts_at, id LIMIT ?";
    /** The salon's bookings still to come or still able to start (CONFIRMED, not over yet). */
    private static final String SALON_UPCOMING_SQL = "SELECT " + COLUMNS + " FROM appointments WHERE shop_id = ? "
            + "AND kind <> 'BLOCK' AND status = 'CONFIRMED' AND ends_at > ? AND starts_at < ? "
            + "ORDER BY starts_at, id LIMIT ?";
    private static final String BARBER_UPCOMING_SQL = "SELECT " + COLUMNS + " FROM appointments WHERE shop_id = ? "
            + "AND staff_id = ? AND kind <> 'BLOCK' AND status = 'CONFIRMED' AND ends_at > ? AND starts_at < ? "
            + "ORDER BY starts_at, id LIMIT ?";
    private static final String PAST_SQL = "SELECT " + COLUMNS + " FROM appointments WHERE customer_id = ? "
            + "AND NOT (status IN ('CONFIRMED', 'IN_SERVICE') AND ends_at > ?) ORDER BY starts_at DESC, id LIMIT ?";

    private final JdbcTemplate jdbc;

    public AppointmentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- writes ----------

    /**
     * Inserts the appointment unless it would overlap one holding the same barber's time: ON CONFLICT ON CONSTRAINT
     * ex_appointments_no_overlap DO NOTHING turns exactly that clash into "no row" instead of an error, so the caller
     * can simply try the next barber in the same transaction. Any other refusal (a repeated request id, a booking
     * number already used) is still an error, never mistaken for "busy".
     * If another transaction is inserting an overlapping row at the same moment, this waits for it to finish first.
     * @return true if it was saved
     */
    public boolean insertIfFree(Appointment a) {
        List<UUID> saved = jdbc.query("INSERT INTO appointments (" + COLUMNS + ") VALUES "
                        + "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT ON CONSTRAINT ex_appointments_no_overlap DO NOTHING RETURNING id",
                (rs, i) -> rs.getObject(1, UUID.class),
                a.id(), a.shopId(), a.staffId(), a.kind().name(), a.blockType() == null ? null : a.blockType().name(),
                a.closureId(), a.customerId(), a.bookingNo(), a.guestName(), a.startCode(),
                at(a.startsAt()), at(a.endsAt()), a.status().name(), at(a.startedAt()), a.startedWithCode(),
                a.cancelReason(), a.createdBy(), a.clientRequestId(), at(a.createdAt()), at(a.updatedAt()));
        return !saved.isEmpty();
    }

    public void insertServices(UUID appointmentId, List<BookedService> services) {
        for (int i = 0; i < services.size(); i++) {
            BookedService s = services.get(i);
            jdbc.update("INSERT INTO appointment_services (appointment_id, position, service_id, name, duration_min, "
                            + "price_paise, price_is_from) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    appointmentId, i, s.serviceId(), s.name(), s.durationMin(), s.pricePaise(), s.priceIsFrom());
        }
    }

    /** CONFIRMED → IN_SERVICE. withCode: null for walk-ins (they have no code). */
    public boolean start(UUID id, Boolean withCode, Instant now) {
        return jdbc.update("UPDATE appointments SET status = 'IN_SERVICE', started_at = ?, started_with_code = ?, "
                + "updated_at = ? WHERE id = ? AND status = 'CONFIRMED'", at(now), withCode, at(now), id) == 1;
    }

    /** Undoing a no-show: NO_SHOW → the given status (CANCELLED_BY_SALON) with the reason. */
    public boolean undoNoShow(UUID id, AppointmentStatus to, String reason, Instant now) {
        return jdbc.update("UPDATE appointments SET status = ?, cancel_reason = ?, updated_at = ? "
                + "WHERE id = ? AND status = 'NO_SHOW'", to.name(), reason, at(now), id) == 1;
    }

    /**
     * The automatic no-show: every booking still CONFIRMED whose start was at or before
     * {@code startedBy} becomes NO_SHOW, in one statement; returns the rows it changed (each only once, even with
     * two servers running this).
     */
    public List<Appointment> markNoShows(Instant startedBy, Instant now) {
        return jdbc.query("UPDATE appointments SET status = 'NO_SHOW', updated_at = ? WHERE status = 'CONFIRMED' "
                + "AND kind <> 'BLOCK' AND starts_at <= ? RETURNING " + COLUMNS, ROW, at(now), at(startedBy));
    }

    /** CONFIRMED → NO_SHOW, CANCELLED_BY_CUSTOMER or CANCELLED_BY_SALON; frees the barber's time. */
    public boolean close(UUID id, AppointmentStatus to, String reason, Instant now) {
        return jdbc.update("UPDATE appointments SET status = ?, cancel_reason = ?, updated_at = ? "
                + "WHERE id = ? AND status = 'CONFIRMED'", to.name(), reason, at(now), id) == 1;
    }

    /** BLOCKED → REMOVED; frees the time. */
    public boolean removeBlock(UUID id, Instant now) {
        return jdbc.update("UPDATE appointments SET status = 'REMOVED', updated_at = ? "
                + "WHERE id = ? AND status = 'BLOCKED'", at(now), id) == 1;
    }

    /** Removes every block of a closed day that hasn't ended yet (past days keep their history); returns their ids. */
    public List<UUID> removeClosure(UUID shopId, UUID closureId, Instant now) {
        return jdbc.query("UPDATE appointments SET status = 'REMOVED', updated_at = ? WHERE shop_id = ? "
                        + "AND closure_id = ? AND status = 'BLOCKED' AND ends_at > ? RETURNING id",
                (rs, i) -> rs.getObject(1, UUID.class), at(now), shopId, closureId, at(now));
    }

    /**
     * Moves a CONFIRMED appointment to another barber and/or time (reschedule, reassign). The same row moves, so it
     * can't clash with itself. Callers check the barber is free first; if someone took the time in between, the
     * database refuses it with SQL state 23P01, and the caller turns that into a 409 (the whole change is undone).
     */
    public boolean move(UUID id, UUID staffId, Instant startsAt, Instant endsAt, Instant now) {
        return jdbc.update("UPDATE appointments SET staff_id = ?, starts_at = ?, ends_at = ?, updated_at = ? "
                + "WHERE id = ? AND status = 'CONFIRMED'", staffId, at(startsAt), at(endsAt), at(now), id) == 1;
    }

    /**
     * The automatic end: every IN_SERVICE appointment whose planned end has passed becomes COMPLETED, and each gets
     * an event, in one statement. Safe to run on two servers at once: a row already completed no longer matches.
     * @return how many were completed
     */
    public int completeEnded(Instant now) {
        Integer count = jdbc.queryForObject("""
                        WITH done AS (
                            UPDATE appointments SET status = 'COMPLETED', updated_at = ?
                            WHERE status = 'IN_SERVICE' AND ends_at <= ?
                            RETURNING id),
                        logged AS (
                            INSERT INTO appointment_events (appointment_id, happened_at, by_user, event)
                            SELECT id, ?, NULL, 'COMPLETED' FROM done)
                        SELECT count(*) FROM done""",
                Integer.class, at(now), at(now), at(now));
        return count == null ? 0 : count;
    }

    /** Appends to the appointment's history. by is null for the system (the automatic end). */
    public void event(UUID appointmentId, Instant at, UUID by, String event, String detail) {
        jdbc.update("INSERT INTO appointment_events (appointment_id, happened_at, by_user, event, detail) "
                        + "VALUES (?, ?, ?, ?, ?)",
                appointmentId, at(at), by, event, detail);
    }

    /**
     * Holds a customer's bookings still for this transaction: two bookings or reschedules by the same person run
     * one after the other, so the 2-booking limit and the strike check can't be slipped past by tapping twice.
     * (A transaction-level advisory lock: released automatically at commit or rollback.)
     */
    public void lockCustomer(UUID customerId) {
        jdbc.query("SELECT pg_advisory_xact_lock(7101, hashtext(?))", rs -> null, customerId.toString());
    }

    /**
     * One salon's barber list and its closed days change one at a time: closing a day and adding (or switching on) a
     * barber take this lock, so neither can miss the other's new rows. Released at commit or rollback.
     * Always taken before any barber row lock, so it can't deadlock with them.
     */
    public void lockSalonCalendar(UUID shopId) {
        jdbc.query("SELECT pg_advisory_xact_lock(7102, hashtext(?))", rs -> null, shopId.toString());
    }

    // ---------- reads ----------

    public Optional<Appointment> find(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM appointments WHERE id = ?", ROW, id).stream().findFirst();
    }

    /** Like find, and locks the row (FOR UPDATE) until the transaction ends. */
    public Optional<Appointment> lock(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM appointments WHERE id = ? FOR UPDATE", ROW, id)
                .stream().findFirst();
    }

    public boolean hasEvent(UUID appointmentId, String event) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM appointment_events WHERE appointment_id = ? AND event = ?",
                Integer.class, appointmentId, event);
        return n != null && n > 0;
    }

    /** The appointment made earlier with the same Idempotency-Key by the same person, if any. */
    public Optional<Appointment> findByRequest(UUID createdBy, UUID requestId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM appointments WHERE created_by = ? AND client_request_id = ?",
                ROW, createdBy, requestId).stream().findFirst();
    }

    /** Appointments holding these barbers' time that overlap [from, to), except one (the one being moved). */
    public List<Appointment> holding(Collection<UUID> staffIds, Instant from, Instant to, UUID except) {
        if (staffIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query(HOLDING_SQL, ps -> {
            ps.setArray(1, ps.getConnection().createArrayOf("uuid", staffIds.toArray()));
            ps.setObject(2, at(to));
            ps.setObject(3, at(from));
            ps.setObject(4, except == null ? new UUID(0, 0) : except);
        }, ROW);
    }

    /** Everything in the salon overlapping [from, to) except removed blocks, for the day screen. */
    public List<Appointment> forDay(UUID shopId, Instant from, Instant to) {
        return jdbc.query("SELECT " + COLUMNS + " FROM appointments WHERE shop_id = ? AND status <> 'REMOVED' "
                + "AND starts_at < ? AND ends_at > ? ORDER BY starts_at, id", ROW, shopId, at(to), at(from));
    }

    /**
     * A customer's bookings. Upcoming: still CONFIRMED or IN_SERVICE, soonest first. Past: everything else, latest
     * first.
     */
    public List<Appointment> forCustomer(UUID customerId, boolean upcoming, Instant now, int limit) {
        return jdbc.query(upcoming ? UPCOMING_SQL : PAST_SQL, ROW, customerId, at(now), limit);
    }

    /**
     * Holds the barber's row in shared mode (SELECT ... FOR SHARE) until the transaction ends and says whether he
     * is still active. Booking takes this before inserting; switching a barber off locks the same row for update,
     * so the two run one after the other and a booking can never land on a barber being switched off.
     */
    public boolean lockActiveBarber(UUID staffId) {
        List<Boolean> active = jdbc.query("SELECT active FROM salon_staff WHERE id = ? FOR SHARE",
                (rs, i) -> rs.getBoolean(1), staffId);
        return !active.isEmpty() && active.get(0);
    }

    /** Bookings and walk-ins of this barber that still hold his time (not yet ended). */
    public int upcomingForStaff(UUID staffId, Instant now) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM appointments WHERE staff_id = ? "
                + "AND status IN ('CONFIRMED', 'IN_SERVICE') AND ends_at > ?", Integer.class, staffId, at(now));
        return n == null ? 0 : n;
    }

    /**
     * What's coming up in the salon, soonest first: CONFIRMED bookings and walk-ins not over yet that start before
     * {@code until}; only this barber's when staffId is set.
     */
    public List<Appointment> upcomingInSalon(UUID shopId, UUID staffId, Instant now, Instant until, int limit) {
        return staffId == null
                ? jdbc.query(SALON_UPCOMING_SQL, ROW, shopId, at(now), at(until), limit)
                : jdbc.query(BARBER_UPCOMING_SQL, ROW, shopId, staffId, at(now), at(until), limit);
    }

    /** The salon's online bookings still to come (CONFIRMED, start in the future). */
    public List<Appointment> upcomingBookings(UUID shopId, Instant now) {
        return jdbc.query("SELECT " + COLUMNS + " FROM appointments WHERE shop_id = ? AND kind = 'BOOKING' "
                + "AND status = 'CONFIRMED' AND starts_at > ? ORDER BY starts_at", ROW, shopId, at(now));
    }

    /** Bookings of this customer that are CONFIRMED and haven't started yet (the 2-booking limit). */
    public int activeBookings(UUID customerId, Instant now) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM appointments WHERE customer_id = ? "
                + "AND status = 'CONFIRMED' AND ends_at > ?", Integer.class, customerId, at(now));
        return n == null ? 0 : n;
    }

    /** The services in each appointment, in booking order. */
    public Map<UUID, List<BookedService>> services(Collection<UUID> appointmentIds) {
        Map<UUID, List<BookedService>> out = new LinkedHashMap<>();
        if (appointmentIds.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT appointment_id, service_id, name, duration_min, price_paise, price_is_from "
                        + "FROM appointment_services WHERE appointment_id = ANY(?) ORDER BY appointment_id, position",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", appointmentIds.toArray())),
                rs -> {
                    out.computeIfAbsent(rs.getObject("appointment_id", UUID.class), k -> new ArrayList<>())
                            .add(new BookedService(rs.getObject("service_id", UUID.class), rs.getString("name"),
                                    rs.getInt("duration_min"), rs.getLong("price_paise"),
                                    rs.getBoolean("price_is_from")));
                });
        return out;
    }

    /** Appointment ids by closure (for the closed-days list). */
    public Map<UUID, List<Appointment>> closures(UUID shopId, Instant from) {
        return jdbc.query("SELECT " + COLUMNS + " FROM appointments WHERE shop_id = ? AND closure_id IS NOT NULL "
                        + "AND status = 'BLOCKED' AND ends_at > ? ORDER BY starts_at", ROW, shopId, at(from))
                .stream()
                .collect(Collectors.groupingBy(Appointment::closureId, LinkedHashMap::new, Collectors.toList()));
    }

    // ---------- mapping ----------

    private static final RowMapper<Appointment> ROW = AppointmentStore::map;

    private static Appointment map(ResultSet rs, int row) throws SQLException {
        String blockType = rs.getString("block_type");
        Object withCode = rs.getObject("started_with_code");
        return new Appointment(rs.getObject("id", UUID.class), rs.getObject("shop_id", UUID.class),
                rs.getObject("staff_id", UUID.class), AppointmentKind.valueOf(rs.getString("kind")),
                blockType == null ? null : BlockType.valueOf(blockType), rs.getObject("closure_id", UUID.class),
                rs.getObject("customer_id", UUID.class), rs.getString("booking_no"),
                rs.getString("guest_name"), rs.getString("start_code"), instant(rs, "starts_at"),
                instant(rs, "ends_at"), AppointmentStatus.valueOf(rs.getString("status")),
                instant(rs, "started_at"), withCode == null ? null : (Boolean) withCode,
                rs.getString("cancel_reason"), rs.getObject("created_by", UUID.class),
                rs.getObject("client_request_id", UUID.class), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    static OffsetDateTime at(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
