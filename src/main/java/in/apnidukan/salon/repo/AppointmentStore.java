package in.apnidukan.salon.repo;

import in.apnidukan.salon.domain.Appointment;
import in.apnidukan.salon.domain.AppointmentCursor;
import in.apnidukan.salon.domain.AppointmentKind;
import in.apnidukan.salon.domain.AppointmentStatus;
import in.apnidukan.salon.domain.BlockType;
import in.apnidukan.salon.domain.BookedService;
import in.apnidukan.salon.domain.Interval;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
    /**
     * What holds these barbers' time in [from, to). Joined barber by barber with an overlap test on time_range, so
     * the no-overlap index (ex_appointments_no_overlap: staff_id, time_range) answers it from the few rows around
     * that time, not from each barber's whole history.
     */
    private static final String HOLDING_SQL = "SELECT a.id, a.shop_id, a.staff_id, a.kind, a.block_type, "
            + "a.closure_id, a.customer_id, a.booking_no, a.guest_name, a.start_code, a.starts_at, a.ends_at, a.status, "
            + "a.started_at, a.started_with_code, a.cancel_reason, a.created_by, a.client_request_id, a.created_at, "
            + "a.updated_at FROM unnest(?::uuid[]) AS b(id) "
            + "JOIN appointments a ON a.staff_id = b.id AND a.time_range && tstzrange(?, ?, '[)') "
            + "WHERE a." + HOLDING + " AND a.id <> ? ORDER BY a.starts_at";
    /** What free-time checks read of the same rows: the barber, the times and whether it is a block (4 columns). */
    private static final String BUSY_SQL = "SELECT a.staff_id, a.starts_at, a.ends_at, a.kind = 'BLOCK' "
            + "FROM unnest(?::uuid[]) AS b(id) "
            + "JOIN appointments a ON a.staff_id = b.id AND a.time_range && tstzrange(?, ?, '[)') "
            + "WHERE a." + HOLDING + " AND a.id <> ?";
    /** The same for every barber of the salon (through ix_appointments_shop_range, that day's rows only). */
    private static final String SALON_BUSY_SQL = "SELECT staff_id, starts_at, ends_at, kind = 'BLOCK' FROM appointments "
            + "WHERE shop_id = ? AND " + HOLDING + " AND time_range && tstzrange(?, ?, '[)')";
    /** Upcoming: still open (CONFIRMED or IN_SERVICE) and not over yet. Past: everything else. */
    private static final String UPCOMING_SQL = "SELECT " + COLUMNS + " FROM appointments WHERE customer_id = ? "
            + "AND status IN ('CONFIRMED', 'IN_SERVICE') AND ends_at > ? ORDER BY starts_at, id LIMIT ?";
    /*
     * Pages (keyset): soonest-first lists go on after the last (starts_at, id); latest-first lists are ordered
     * starts_at DESC, id ASC (as they always were), so the next page is an earlier start, or the same start and a
     * later id. Each page is one range of an index on (shop_id or customer_id, starts_at), however deep.
     */
    private static final String AFTER_SOONEST = " AND (starts_at, id) > (?, ?)";
    private static final String AFTER_LATEST = " AND (starts_at < ? OR (starts_at = ? AND id > ?))";
    /** The salon's bookings still to come or still able to start (CONFIRMED, not over yet). */
    private static final String SALON_UPCOMING = "SELECT " + COLUMNS + " FROM appointments WHERE shop_id = ? "
            + "AND kind <> 'BLOCK' AND status = 'CONFIRMED' AND ends_at > ? AND starts_at < ?";
    private static final String BARBER_UPCOMING = "SELECT " + COLUMNS + " FROM appointments WHERE shop_id = ? "
            + "AND staff_id = ? AND kind <> 'BLOCK' AND status = 'CONFIRMED' AND ends_at > ? AND starts_at < ?";
    private static final String SOONEST = " ORDER BY starts_at, id LIMIT ?";
    private static final String LATEST = " ORDER BY starts_at DESC, id LIMIT ?";
    private static final String SALON_UPCOMING_SQL = SALON_UPCOMING + SOONEST;
    private static final String SALON_UPCOMING_AFTER_SQL = SALON_UPCOMING + AFTER_SOONEST + SOONEST;
    private static final String BARBER_UPCOMING_SQL = BARBER_UPCOMING + SOONEST;
    private static final String BARBER_UPCOMING_AFTER_SQL = BARBER_UPCOMING + AFTER_SOONEST + SOONEST;
    private static final String PAST = "SELECT " + COLUMNS + " FROM appointments WHERE customer_id = ? "
            + "AND NOT (status IN ('CONFIRMED', 'IN_SERVICE') AND ends_at > ?)";
    private static final String PAST_SQL = PAST + LATEST;
    private static final String PAST_AFTER_SQL = PAST + AFTER_LATEST + LATEST;
    private static final String HISTORY = "SELECT " + COLUMNS + " FROM appointments WHERE shop_id = ? "
            + "AND kind <> 'BLOCK' AND starts_at >= ? AND starts_at < ?";
    private static final String HISTORY_SQL = HISTORY + LATEST;
    private static final String HISTORY_AFTER_SQL = HISTORY + AFTER_LATEST + LATEST;
    private static final String DAY = "SELECT " + COLUMNS + " FROM appointments WHERE shop_id = ? "
            + "AND status <> 'REMOVED' AND time_range && tstzrange(?, ?, '[)')";
    private static final String DAY_SQL = DAY + " ORDER BY starts_at, id";
    private static final String BARBER_DAY_SQL = DAY + " AND staff_id = ? ORDER BY starts_at, id";

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

    /** The unique key on booking numbers (V4: the column's own UNIQUE, named by Postgres). */
    public static final String BOOKING_NO_KEY = "appointments_booking_no_key";

    /**
     * insertIfFree for an online booking, whose random booking number could, rarely, already be taken: the insert
     * runs inside a savepoint, and a clash on the booking number (23505 on BOOKING_NO_KEY) undoes only that insert,
     * so the same transaction tries again with the next number from {@code numbers}, up to {@code tries} numbers in
     * all. Any other refusal is passed on.
     * @return the booking as saved (with the number it got), or empty when the barber's time is taken
     */
    public Optional<Appointment> insertBookingIfFree(Appointment a, java.util.function.Supplier<String> numbers,
                                                     int tries) {
        Appointment next = a;
        for (int i = 1; ; i++) {
            jdbc.execute("SAVEPOINT booking_no");
            try {
                boolean saved = insertIfFree(next);
                jdbc.execute("RELEASE SAVEPOINT booking_no");
                return saved ? Optional.of(next) : Optional.empty();
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                jdbc.execute("ROLLBACK TO SAVEPOINT booking_no");
                jdbc.execute("RELEASE SAVEPOINT booking_no");
                if (i >= tries || !in.apnidukan.common.error.DatabaseErrors.violatedUnique(e, BOOKING_NO_KEY)) {
                    throw e;
                }
                next = next.withBookingNo(numbers.get());
            }
        }
    }

    public void insertServices(UUID appointmentId, List<BookedService> services) {
        for (int i = 0; i < services.size(); i++) {
            BookedService s = services.get(i);
            jdbc.update("INSERT INTO appointment_services (appointment_id, position, service_id, name, duration_min, "
                            + "price_paise, price_is_from, offer_id, list_price_paise) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    appointmentId, i, s.serviceId(), s.name(), s.durationMin(), s.pricePaise(), s.priceIsFrom(),
                    s.offerId(), s.listPricePaise());
        }
    }

    /** New prices for a booking's services (moved into or out of an offer), in their booking order. */
    public void reprice(UUID appointmentId, List<BookedService> services) {
        for (int i = 0; i < services.size(); i++) {
            BookedService s = services.get(i);
            jdbc.update("UPDATE appointment_services SET price_paise = ?, offer_id = ?, list_price_paise = ? "
                    + "WHERE appointment_id = ? AND position = ?",
                    s.pricePaise(), s.offerId(), s.listPricePaise(), appointmentId, i);
        }
    }

    /**
     * CONFIRMED → IN_SERVICE, and its time becomes [startsAt, endsAt): the same as planned, or earlier when it starts
     * before its planned start (the customer is in the chair from now on, so the barber is busy from now). If that
     * earlier time overlaps someone else, the database refuses it with SQL state 23P01 (the caller turns that into a
     * 409). withCode: null for walk-ins (they have no code).
     */
    public boolean start(UUID id, Boolean withCode, Instant startsAt, Instant endsAt, Instant now) {
        return jdbc.update("UPDATE appointments SET status = 'IN_SERVICE', started_at = ?, started_with_code = ?, "
                        + "starts_at = ?, ends_at = ?, updated_at = ? WHERE id = ? AND status = 'CONFIRMED'",
                at(now), withCode, at(startsAt), at(endsAt), at(now), id) == 1;
    }

    /** IN_SERVICE → COMPLETED now (done before the planned end): the rest of the barber's time is free again. */
    public boolean finish(UUID id, Instant endsAt, Instant now) {
        return jdbc.update("UPDATE appointments SET status = 'COMPLETED', ends_at = ?, updated_at = ? "
                + "WHERE id = ? AND status = 'IN_SERVICE'", at(endsAt), at(now), id) == 1;
    }

    /** A walk-in already in the chair, cancelled (added by mistake): IN_SERVICE → CANCELLED_BY_SALON. */
    public boolean cancelWalkInInService(UUID id, String reason, Instant now) {
        return jdbc.update("UPDATE appointments SET status = 'CANCELLED_BY_SALON', cancel_reason = ?, updated_at = ? "
                + "WHERE id = ? AND status = 'IN_SERVICE' AND kind = 'WALK_IN'", reason, at(now), id) == 1;
    }

    /**
     * A walk-in already in the chair moves to another barber (added to the wrong one): the rest of its time is with
     * him from now. started_at stays: the customer has been in the salon's chair since then. 23P01 if his time was
     * taken in between.
     */
    public boolean moveWalkInInService(UUID id, UUID staffId, Instant startsAt, Instant endsAt, Instant now) {
        return jdbc.update("UPDATE appointments SET staff_id = ?, starts_at = ?, ends_at = ?, updated_at = ? "
                        + "WHERE id = ? AND status = 'IN_SERVICE' AND kind = 'WALK_IN'",
                staffId, at(startsAt), at(endsAt), at(now), id) == 1;
    }

    /** Undoing a no-show: NO_SHOW → the given status (CANCELLED_BY_SALON) with the reason. */
    public boolean undoNoShow(UUID id, AppointmentStatus to, String reason, Instant now) {
        return jdbc.update("UPDATE appointments SET status = ?, cancel_reason = ?, updated_at = ? "
                + "WHERE id = ? AND status = 'NO_SHOW'", to.name(), reason, at(now), id) == 1;
    }

    /**
     * The automatic no-show: up to `limit` bookings still CONFIRMED whose start was at or before {@code startedBy}
     * become NO_SHOW, oldest first; returns the rows it changed. Found through ix_appointments_confirmed_start (V13),
     * so it reads only bookings waiting to start, never the whole table. Rows another server or request holds are
     * skipped (SKIP LOCKED) and picked up next time, and each row changes only once.
     */
    public List<Appointment> markNoShows(Instant startedBy, Instant now, int limit) {
        // MATERIALIZED: picked once; as a plain subquery Postgres may run it again per row and pass the limit
        return jdbc.query("WITH due AS MATERIALIZED (SELECT id FROM appointments WHERE status = 'CONFIRMED' "
                + "AND kind <> 'BLOCK' AND starts_at <= ? ORDER BY starts_at LIMIT ? FOR UPDATE SKIP LOCKED) "
                + "UPDATE appointments SET status = 'NO_SHOW', updated_at = ? WHERE id IN (SELECT id FROM due) "
                + "AND status = 'CONFIRMED' RETURNING " + COLUMNS, ROW, at(startedBy), limit, at(now));
    }

    /**
     * Every online booking of the salon still CONFIRMED that starts after {@code since} becomes CANCELLED_BY_SALON
     * with the reason, each with an event: at most {@code max} (soonest first) in one statement, so a closed or
     * suspended salon's bookings are cancelled a slice at a time (SalonClosingJob). Rows another transaction holds right now (a customer
     * cancelling) are skipped (SKIP LOCKED) and come in a later slice if still CONFIRMED.
     */
    public List<Appointment> cancelUpcomingSlice(UUID shopId, Instant since, String reason, String detail, Instant now,
                                                 int max) {
        return jdbc.query("WITH pick AS MATERIALIZED (SELECT id FROM appointments WHERE shop_id = ? AND kind = 'BOOKING' "
                        + "AND status = 'CONFIRMED' AND starts_at > ? ORDER BY starts_at, id LIMIT ? "
                        + "FOR UPDATE SKIP LOCKED), "
                        + "closed AS (UPDATE appointments SET status = 'CANCELLED_BY_SALON', cancel_reason = ?, "
                        + "updated_at = ? WHERE id IN (SELECT id FROM pick) AND status = 'CONFIRMED' RETURNING "
                        + COLUMNS + "), "
                        + "logged AS (INSERT INTO appointment_events (appointment_id, happened_at, by_user, event, detail) "
                        + "SELECT id, ?, NULL, 'CANCELLED_BY_SALON', ? FROM closed) "
                        + "SELECT " + COLUMNS + " FROM closed ORDER BY starts_at", ROW,
                shopId, at(since), max, reason, at(now), at(now), detail);
    }

    /** Whether the salon still has a CONFIRMED booking starting after {@code since} (one held elsewhere a moment ago). */
    public boolean hasUpcoming(UUID shopId, Instant since) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM appointments WHERE shop_id = ? "
                + "AND kind = 'BOOKING' AND status = 'CONFIRMED' AND starts_at > ?)", Boolean.class, shopId, at(since)));
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

    /**
     * Opens a closed day that hasn't ended yet again: its row goes (false if there is none) and so do its barbers'
     * blocks (removeClosure).
     */
    public boolean removeClosedDay(UUID shopId, UUID closureId, Instant now) {
        return jdbc.update("DELETE FROM salon_closed_days WHERE shop_id = ? AND id = ? AND ends_at > ?", shopId,
                closureId, at(now)) == 1;
    }

    /**
     * CLOSED blocks of that whole day already there under another closure id with no day row (written by an older
     * server copy during a deploy): they join this closed day. Returns the barbers they belong to.
     */
    public Set<UUID> adoptClosedBlocks(UUID shopId, UUID closureId, Instant start, Instant end, Instant now) {
        return new HashSet<>(jdbc.query("UPDATE appointments a SET closure_id = ?, updated_at = ? WHERE a.shop_id = ? "
                        + "AND a.status = 'BLOCKED' AND a.block_type = 'CLOSED' AND a.closure_id IS NOT NULL "
                        + "AND a.closure_id <> ? AND a.starts_at = ? AND a.ends_at = ? AND NOT EXISTS (SELECT 1 FROM "
                        + "salon_closed_days d WHERE d.id = a.closure_id) RETURNING a.staff_id",
                (rs, i) -> rs.getObject(1, UUID.class), closureId, at(now), shopId, closureId, at(start), at(end)));
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
     * The automatic end: up to `limit` IN_SERVICE appointments whose planned end has passed become COMPLETED, each
     * with an event and, for an online booking, its follow-ups (the rating request and the come-back reminder:
     * VisitFollowUpStore), in one statement (through ix_appointments_in_service). Safe on two servers at once: locked
     * rows are skipped and a row already completed no longer matches.
     * @return how many were completed
     */
    public int completeEnded(Instant now, int limit) {
        Integer count = jdbc.queryForObject("""
                        WITH due AS MATERIALIZED (
                            SELECT id FROM appointments WHERE status = 'IN_SERVICE' AND ends_at <= ?
                            ORDER BY ends_at LIMIT ? FOR UPDATE SKIP LOCKED),
                        done AS (
                            UPDATE appointments SET status = 'COMPLETED', updated_at = ?
                            WHERE id IN (SELECT id FROM due) AND status = 'IN_SERVICE'
                            RETURNING id),
                        logged AS (
                            INSERT INTO appointment_events (appointment_id, happened_at, by_user, event)
                            SELECT id, ?, NULL, 'COMPLETED' FROM done),
                        followed AS (
                            """ + VisitFollowUpStore.FOR_DONE + """
                        )
                        SELECT count(*) FROM done""",
                Integer.class, at(now), limit, at(now), at(now), at(now), at(now));
        return count == null ? 0 : count;
    }

    /** The same history row for many appointments, in one statement (see event()). */
    public void eventAll(java.util.Collection<UUID> appointmentIds, Instant at, UUID by, String event, String detail) {
        if (appointmentIds.isEmpty()) {
            return;
        }
        jdbc.update("INSERT INTO appointment_events (appointment_id, happened_at, by_user, event, detail) "
                + "SELECT id, ?, ?, ?, ? FROM unnest(?::uuid[]) AS id", ps -> {
                    ps.setObject(1, at(at));
                    ps.setObject(2, by);
                    ps.setString(3, event);
                    ps.setString(4, detail);
                    ps.setArray(5, ps.getConnection().createArrayOf("uuid", appointmentIds.toArray()));
                });
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
     * Locks this customer's open bookings (CONFIRMED or IN_SERVICE, not over yet), in id order, until the transaction
     * ends. Taken before their salons' counters: a salon acting on one of them (cancel, reassign) locks the row first
     * and moves the counter after, so the same order here means the two wait for each other, never deadlock.
     */
    public void lockOpenForCustomer(UUID customerId, Instant now) {
        jdbc.query("SELECT id FROM appointments WHERE customer_id = ? AND status IN ('CONFIRMED', 'IN_SERVICE') "
                + "AND ends_at > ? ORDER BY id FOR UPDATE", rs -> null, customerId, at(now));
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

    /** Minutes of breaks this person added for this barber that are still on, within [from, to). */
    public long ownBreakMinutes(UUID staffId, UUID createdBy, Instant from, Instant to) {
        Long minutes = jdbc.queryForObject("SELECT COALESCE(SUM(EXTRACT(EPOCH FROM (LEAST(ends_at, ?) - "
                        + "GREATEST(starts_at, ?))) / 60), 0)::bigint FROM appointments WHERE staff_id = ? "
                        + "AND created_by = ? AND kind = 'BLOCK' AND block_type = 'BREAK' AND status = 'BLOCKED' "
                        + "AND starts_at < ? AND ends_at > ?",
                Long.class, at(to), at(from), staffId, createdBy, at(to), at(from));
        return minutes == null ? 0 : minutes;
    }

    /** Whether this appointment was given away from this barber (its REASSIGNED events read "from -> to"). */
    public boolean wasReassignedFrom(UUID appointmentId, UUID staffId) {
        Boolean found = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM appointment_events WHERE appointment_id = ? "
                + "AND event IN ('REASSIGNED', 'BARBER_CHANGED') AND detail LIKE ?)", Boolean.class, appointmentId,
                staffId + " -> %");
        return Boolean.TRUE.equals(found);
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
            ps.setObject(2, at(from));
            ps.setObject(3, at(to));
            ps.setObject(4, except == null ? new UUID(0, 0) : except);
        }, ROW);
    }

    /** Time held on a barber, as free-time checks need it. @param block a break, leave or closed day */
    public record Busy(UUID staffId, Interval time, boolean block) {
    }

    private static final RowMapper<Busy> BUSY_ROW = (rs, i) -> new Busy(rs.getObject(1, UUID.class),
            new Interval(rs.getObject(2, OffsetDateTime.class).toInstant(),
                    rs.getObject(3, OffsetDateTime.class).toInstant()), rs.getBoolean(4));

    /** holding() with only what free-time checks need (4 columns instead of 20). */
    public List<Busy> busy(Collection<UUID> staffIds, Instant from, Instant to, UUID except) {
        if (staffIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query(BUSY_SQL, ps -> {
            ps.setArray(1, ps.getConnection().createArrayOf("uuid", staffIds.toArray()));
            ps.setObject(2, at(from));
            ps.setObject(3, at(to));
            ps.setObject(4, except == null ? new UUID(0, 0) : except);
        }, BUSY_ROW);
    }

    /** busy() for every barber of the salon, [from, to): what SalonCalendar keeps in memory per salon and day. */
    public List<Busy> salonBusy(UUID shopId, Instant from, Instant to) {
        return jdbc.query(SALON_BUSY_SQL, BUSY_ROW, shopId, at(from), at(to));
    }

    /**
     * Bookings and walk-ins starting in [from, to), latest first, at most limit, after the cursor (null: from the
     * latest) (the owner's history).
     */
    public List<Appointment> history(UUID shopId, Instant from, Instant to, AppointmentCursor after, int limit) {
        return after == null
                ? jdbc.query(HISTORY_SQL, ROW, shopId, at(from), at(to), limit)
                : jdbc.query(HISTORY_AFTER_SQL, ROW, shopId, at(from), at(to), at(after.afterStart()),
                        at(after.afterStart()), after.afterId(), limit);
    }

    /** How many bookings and walk-ins starting in [from, to) are in each status. */
    public Map<String, Long> historyCounts(UUID shopId, Instant from, Instant to) {
        Map<String, Long> counts = new java.util.TreeMap<>();
        jdbc.query("SELECT status, count(*) FROM appointments WHERE shop_id = ? AND kind <> 'BLOCK' AND starts_at >= ? "
                        + "AND starts_at < ? GROUP BY status", rs -> {
                    counts.put(rs.getString(1), rs.getLong(2));
                }, shopId, at(from), at(to));
        return counts;
    }

    /** The list prices of the services of everything COMPLETED that started in [from, to). */
    public long completedPaise(UUID shopId, Instant from, Instant to) {
        Long sum = jdbc.queryForObject("SELECT COALESCE(SUM(s.price_paise), 0) FROM appointment_services s "
                        + "JOIN appointments a ON a.id = s.appointment_id WHERE a.shop_id = ? AND a.kind <> 'BLOCK' "
                        + "AND a.status = 'COMPLETED' AND a.starts_at >= ? AND a.starts_at < ?", Long.class,
                shopId, at(from), at(to));
        return sum == null ? 0 : sum;
    }

    /** Everything in the salon overlapping [from, to) except removed blocks, for the day screen. */
    public List<Appointment> forDay(UUID shopId, Instant from, Instant to) {
        // Overlap on time_range: answered by ix_appointments_shop_range (V13) from that day's rows only
        return jdbc.query(DAY_SQL, ROW, shopId, at(from), at(to));
    }

    /**
     * The salon's appointments written by transaction {@code fromXid} or later (change_xid, V21), at most
     * {@code limit}, in the order they were written (change_seq): what may have changed on its day boards and upcoming
     * list since an answer read while {@code fromXid} was the oldest transaction still running. Every day and every
     * barber: the caller sorts out which are on the answer asked for and which left it.
     *
     * Bounded however old the cursor: the rows are read in the index's own order (ix_appointments_shop_change, V24)
     * and the read stops after {@code limit}, so a cursor held low by a long transaction somewhere (OldSnapshotWatch)
     * costs at most {@code limit} rows; only those few are then put in write order.
     */
    public List<Appointment> changedSince(UUID shopId, long fromXid, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM (SELECT " + COLUMNS + ", change_seq FROM appointments "
                + "WHERE shop_id = ? AND change_xid >= ? ORDER BY change_xid LIMIT ?) c ORDER BY change_seq",
                ROW, shopId, fromXid, limit);
    }

    /**
     * Which of these appointments were ever taken away from this barber, in one query: given to another barber by the
     * salon (REASSIGNED, "his id -> ...") or moved to another barber by the customer (BARBER_CHANGED, the same
     * detail). The rows that could have been on his own board or list before they left it.
     */
    public java.util.Set<UUID> reassignedFrom(Collection<UUID> appointmentIds, UUID staffId) {
        if (appointmentIds.isEmpty()) {
            return java.util.Set.of();
        }
        Object[] ids = appointmentIds.toArray();
        return new java.util.HashSet<>(jdbc.query("SELECT DISTINCT appointment_id FROM appointment_events "
                + "WHERE appointment_id = ANY (?) AND event IN ('REASSIGNED', 'BARBER_CHANGED') AND detail LIKE ?",
                ps -> {
                    ps.setArray(1, ps.getConnection().createArrayOf("uuid", ids));
                    ps.setString(2, staffId + " -> %");
                }, (rs, i) -> rs.getObject(1, UUID.class)));
    }

    /**
     * Online bookings of the salon still waiting (CONFIRMED) whose end passed in (after, upTo]: time alone took them
     * off the upcoming list since an earlier answer (ix_appointments_shop_confirmed; none lasts a day). Only this
     * barber's when staffId is set (a barber who sees only his own list).
     */
    public List<UUID> endedBetween(UUID shopId, UUID staffId, Instant after, Instant upTo) {
        Object from = at(after.minus(java.time.Duration.ofDays(1)));
        return staffId == null
                ? jdbc.queryForList(ENDED_SQL, UUID.class, shopId, from, at(after), at(upTo))
                : jdbc.queryForList(BARBER_ENDED_SQL, UUID.class, shopId, from, at(after), at(upTo), staffId);
    }

    private static final String ENDED_SQL = "SELECT id FROM appointments WHERE shop_id = ? AND status = 'CONFIRMED' "
            + "AND kind <> 'BLOCK' AND starts_at > ? AND ends_at > ? AND ends_at <= ?";
    private static final String BARBER_ENDED_SQL = ENDED_SQL + " AND staff_id = ?";

    /**
     * Bookings and walk-ins of the salon waiting to start (CONFIRMED) whose start passed in (after, upTo] and that are
     * on [from, to): time alone turned them "not started" since an earlier answer (ix_appointments_shop_confirmed).
     */
    public List<Appointment> startedBetween(UUID shopId, Instant after, Instant upTo, Instant from, Instant to) {
        return jdbc.query("SELECT " + COLUMNS + " FROM appointments WHERE shop_id = ? AND status = 'CONFIRMED' "
                        + "AND starts_at > ? AND starts_at <= ? AND time_range && tstzrange(?, ?, '[)') "
                        + "ORDER BY starts_at, id", ROW, shopId, at(after), at(upTo), at(from), at(to));
    }

    /**
     * The first moment after {@code after} that time alone changes a day board or the upcoming list on [from, to): a
     * waiting booking's start (it turns "not started"), and with {@code ends} also an end (it leaves the upcoming list).
     * For the whole salon (a barber's own column can only change at one of these). Through
     * ix_appointments_shop_confirmed.
     */
    public Optional<Instant> nextChange(UUID shopId, Instant after, Instant from, Instant to, boolean ends) {
        OffsetDateTime next = jdbc.queryForObject("SELECT LEAST(min(starts_at) FILTER (WHERE starts_at > ?), "
                        + "CASE WHEN ? THEN min(ends_at) FILTER (WHERE ends_at > ?) END) FROM appointments "
                        + "WHERE shop_id = ? AND status = 'CONFIRMED' AND kind <> 'BLOCK' AND ends_at > ? "
                        + "AND starts_at < ? AND ends_at > ?", OffsetDateTime.class, at(after), ends, at(after), shopId,
                at(after), at(to), at(from));
        return Optional.ofNullable(next).map(OffsetDateTime::toInstant);
    }

    /** forDay for one barber only (a barber who sees just his own column): the others' rows are never read. */
    public List<Appointment> forDay(UUID shopId, UUID staffId, Instant from, Instant to) {
        return jdbc.query(BARBER_DAY_SQL, ROW, shopId, at(from), at(to), staffId);
    }

    /**
     * A customer's bookings. Upcoming: still CONFIRMED or IN_SERVICE, soonest first. Past: everything else, latest
     * first, after the cursor (null: from the latest; upcoming lists take none).
     */
    public List<Appointment> forCustomer(UUID customerId, boolean upcoming, Instant now, AppointmentCursor after,
                                         int limit) {
        if (upcoming || after == null) {
            return jdbc.query(upcoming ? UPCOMING_SQL : PAST_SQL, ROW, customerId, at(now), limit);
        }
        return jdbc.query(PAST_AFTER_SQL, ROW, customerId, at(now), at(after.afterStart()), at(after.afterStart()),
                after.afterId(), limit);
    }

    /**
     * Holds the barber's row in shared mode (SELECT ... FOR SHARE) until the transaction ends and says whether he
     * is still active. Booking, moving, reassigning and walk-ins take this before writing; switching a barber off and
     * saving his hours or services lock the same row for update, so they run one after the other. This checks only
     * "active": whether he still works then and does the services is checked next, by SalonCalendar.stillFits (a
     * change saved meanwhile has committed by now and moved the salon's setup_version).
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
     * {@code until}; only this barber's when staffId is set; at most limit, after the cursor (null: the first page).
     */
    public List<Appointment> upcomingInSalon(UUID shopId, UUID staffId, Instant now, Instant until,
                                             AppointmentCursor after, int limit) {
        if (after == null) {
            return staffId == null
                    ? jdbc.query(SALON_UPCOMING_SQL, ROW, shopId, at(now), at(until), limit)
                    : jdbc.query(BARBER_UPCOMING_SQL, ROW, shopId, staffId, at(now), at(until), limit);
        }
        return staffId == null
                ? jdbc.query(SALON_UPCOMING_AFTER_SQL, ROW, shopId, at(now), at(until), at(after.afterStart()),
                        after.afterId(), limit)
                : jdbc.query(BARBER_UPCOMING_AFTER_SQL, ROW, shopId, staffId, at(now), at(until),
                        at(after.afterStart()), after.afterId(), limit);
    }

    /**
     * The parts of a booking reminder that are the same for every booking of a salon (its name and address), as
     * SalonNotices writes them, already fitted to the stored sizes. Times are "h:mm AM" (7 or 8 characters), so the
     * text before {@code tails} is one of three lengths, and the body's cut at 500 characters (in the tail: the address
     * comes last) is worked out in Java for each: tails[0] when both times are 7 characters, [1] for 15, [2] for 16.
     */
    public record ReminderText(String title, String afterTime, String afterCode, List<String> tails, String smsHead,
                               String smsMiddle) {

        public ReminderText {
            tails = List.copyOf(tails);
        }
    }

    /**
     * Rewrites the pending reminders of every online booking of the salon still to come (CONFIRMED, starting after
     * {@code since}) with the salon's new name and address, in one statement: each booking's own parts (its time,
     * start code and held-until time, in India time) are put in by the database, so a salon with thousands of
     * bookings ahead is never loaded into memory. Only reminders still waiting (PENDING) change; they become unread
     * again, like any rewritten message. Returns how many rows changed.
     * Found through ix_appointments_shop_confirmed and the pending messages' ref index (ix_notification_jobs_ref).
     */
    public int rewriteReminders(UUID shopId, Instant since, java.time.Duration heldFor, ReminderText t) {
        return jdbc.update("""
                        WITH due AS (
                            SELECT id, start_code,
                                to_char(starts_at AT TIME ZONE 'Asia/Kolkata', 'FMHH12:MI AM') AS t,
                                to_char((starts_at + make_interval(mins => ?)) AT TIME ZONE 'Asia/Kolkata',
                                        'FMHH12:MI AM') AS u
                            FROM appointments
                            WHERE shop_id = ? AND kind = 'BOOKING' AND status = 'CONFIRMED' AND starts_at > ?)
                        UPDATE notification_jobs j SET title = ?, url = '/bookings/' || d.id, read_at = NULL,
                            body = d.t || ? || d.start_code || ? || d.u
                                || (ARRAY[?, ?, ?]::text[])[length(d.t) + length(d.u) - 13],
                            sms_variables = CASE WHEN j.channel = 'SMS' THEN ? || d.start_code || ?
                                || replace(replace(d.t, ':', '%3A'), ' ', '+') || '&UNTIL='
                                || replace(replace(d.u, ':', '%3A'), ' ', '+') ELSE NULL END
                        FROM due d
                        WHERE j.ref_id = d.id AND j.type = 'BOOKING_REMINDER' AND j.status = 'PENDING'""",
                (int) heldFor.toMinutes(), shopId, at(since), t.title(), t.afterTime(), t.afterCode(),
                t.tails().get(0), t.tails().get(1), t.tails().get(2), t.smsHead(), t.smsMiddle());
    }

    /**
     * Whether this customer already holds a booking (CONFIRMED or IN_SERVICE) overlapping [from, to), apart from
     * the one being moved. One person can't be in two chairs at once.
     */
    public boolean customerHasOverlap(UUID customerId, Instant from, Instant to, UUID exceptId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM appointments WHERE customer_id = ? "
                        + "AND status IN ('CONFIRMED', 'IN_SERVICE') AND starts_at < ? AND ends_at > ? "
                        + "AND (?::uuid IS NULL OR id <> ?::uuid)",
                Integer.class, customerId, at(to), at(from), exceptId, exceptId);
        return n != null && n > 0;
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
        jdbc.query("SELECT appointment_id, service_id, name, duration_min, price_paise, price_is_from, offer_id, "
                        + "list_price_paise FROM appointment_services WHERE appointment_id = ANY(?) "
                        + "ORDER BY appointment_id, position",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", appointmentIds.toArray())),
                rs -> {
                    out.computeIfAbsent(rs.getObject("appointment_id", UUID.class), k -> new ArrayList<>())
                            .add(new BookedService(rs.getObject("service_id", UUID.class), rs.getString("name"),
                                    rs.getInt("duration_min"), rs.getLong("price_paise"),
                                    rs.getBoolean("price_is_from"), rs.getObject("offer_id", UUID.class),
                                    (Long) rs.getObject("list_price_paise")));
                });
        return out;
    }

    /**
     * A closed day of the salon: its time (the same for every barber's row) and how many barbers' rows it has.
     */
    public record Closure(UUID closureId, Instant startsAt, Instant endsAt, int barbers) {
    }

    /** The salon's own closed days (salon_closed_days, V34), each with how many barbers' CLOSED blocks it has. */
    private static final String CLOSURES = "SELECT d.id AS closure_id, d.starts_at, d.ends_at, "
            + "(SELECT count(*) FROM appointments a WHERE a.shop_id = d.shop_id AND a.closure_id = d.id "
            + "AND a.status = 'BLOCKED') AS barbers FROM salon_closed_days d WHERE d.shop_id = ? AND d.ends_at > ?";

    private static Closure closure(ResultSet rs, int row) throws SQLException {
        return new Closure(rs.getObject("closure_id", UUID.class), instant(rs, "starts_at"), instant(rs, "ends_at"),
                rs.getInt("barbers"));
    }

    /**
     * The salon's closed days not over yet, earliest first (ix_salon_closed_days_shop), with or without barbers.
     */
    public List<Closure> closures(UUID shopId, Instant from) {
        return jdbc.query(CLOSURES + " ORDER BY 2, 1", AppointmentStore::closure, shopId, at(from));
    }

    /** closures() that this barber has no CLOSED row in yet (he is joining the salon's closed days). */
    public List<Closure> closuresWithout(UUID shopId, UUID staffId, Instant from) {
        return jdbc.query(CLOSURES + " AND NOT EXISTS (SELECT 1 FROM appointments b WHERE b.shop_id = d.shop_id "
                        + "AND b.closure_id = d.id AND b.staff_id = ? AND b.status = 'BLOCKED') ORDER BY 2, 1",
                AppointmentStore::closure, shopId, at(from), staffId);
    }

    /**
     * Records a closed day of the salon (the caller holds the salon's calendar lock); false if that day is already
     * closed.
     */
    public boolean addClosedDay(UUID id, UUID shopId, java.time.LocalDate day, Instant start, Instant end, UUID by,
                                Instant now) {
        return jdbc.update("INSERT INTO salon_closed_days (id, shop_id, day, starts_at, ends_at, created_by, "
                + "created_at) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT ON CONSTRAINT ux_salon_closed_days_day "
                + "DO NOTHING", id, shopId, day, at(start), at(end), by, at(now)) == 1;
    }

    /** One time range (of a list) and what holds the barber's time in it. */
    public record Hit(int range, Appointment appointment) {
    }

    /**
     * What holds this barber's time in any of these ranges, in one query: each hit with the index of its range (one
     * appointment overlapping two ranges comes once for each), in range order, then by start.
     */
    public List<Hit> holdingIn(UUID staffId, List<Instant[]> ranges) {
        if (ranges.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT r.i, a.id, a.shop_id, a.staff_id, a.kind, a.block_type, a.closure_id, a.customer_id, "
                        + "a.booking_no, a.guest_name, a.start_code, a.starts_at, a.ends_at, a.status, a.started_at, "
                        + "a.started_with_code, a.cancel_reason, a.created_by, a.client_request_id, a.created_at, "
                        + "a.updated_at FROM unnest(?::text[], ?::text[]) WITH ORDINALITY AS r(s, e, i) "
                        + "JOIN appointments a ON a.staff_id = ? "
                        + "AND a.time_range && tstzrange(r.s::timestamptz, r.e::timestamptz, '[)') "
                        + "WHERE a." + HOLDING + " ORDER BY r.i, a.starts_at", ps -> {
                    ps.setArray(1, ps.getConnection().createArrayOf("text",
                            ranges.stream().map(r -> r[0].toString()).toArray()));
                    ps.setArray(2, ps.getConnection().createArrayOf("text",
                            ranges.stream().map(r -> r[1].toString()).toArray()));
                    ps.setObject(3, staffId);
                }, (rs, i) -> new Hit(rs.getInt("i") - 1, map(rs, i)));
    }

    /** BLOCKED → REMOVED for all of these, in one statement; returns how many changed. */
    public int removeBlocks(Collection<UUID> ids, Instant now) {
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbc.update("UPDATE appointments SET status = 'REMOVED', updated_at = ? WHERE id = ANY (?) "
                + "AND status = 'BLOCKED'", ps -> {
                    ps.setObject(1, at(now));
                    ps.setArray(2, ps.getConnection().createArrayOf("uuid", ids.toArray()));
                });
    }

    /**
     * Inserts a CLOSED block for this barber in each closed day (closure id and time), in one statement, skipping any
     * that would overlap something holding his time (like insertIfFree); returns the ids saved.
     */
    public List<UUID> insertClosedDays(UUID shopId, UUID staffId, List<Closure> days, List<UUID> ids, UUID by,
                                       Instant now) {
        if (days.isEmpty()) {
            return List.of();
        }
        return jdbc.query("INSERT INTO appointments (id, shop_id, staff_id, kind, block_type, closure_id, starts_at, "
                        + "ends_at, status, created_by, created_at, updated_at) "
                        + "SELECT r.id, ?, ?, 'BLOCK', 'CLOSED', r.closure_id, r.s::timestamptz, r.e::timestamptz, "
                        + "'BLOCKED', ?, ?, ? FROM unnest(?::uuid[], ?::uuid[], ?::text[], ?::text[]) "
                        + "AS r(id, closure_id, s, e) "
                        + "ON CONFLICT ON CONSTRAINT ex_appointments_no_overlap DO NOTHING RETURNING id", ps -> {
                    ps.setObject(1, shopId);
                    ps.setObject(2, staffId);
                    ps.setObject(3, by);
                    ps.setObject(4, at(now));
                    ps.setObject(5, at(now));
                    ps.setArray(6, ps.getConnection().createArrayOf("uuid", ids.toArray()));
                    ps.setArray(7, ps.getConnection().createArrayOf("uuid",
                            days.stream().map(Closure::closureId).toArray()));
                    ps.setArray(8, ps.getConnection().createArrayOf("text",
                            days.stream().map(d -> d.startsAt().toString()).toArray()));
                    ps.setArray(9, ps.getConnection().createArrayOf("text",
                            days.stream().map(d -> d.endsAt().toString()).toArray()));
                }, (rs, i) -> rs.getObject(1, UUID.class));
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
