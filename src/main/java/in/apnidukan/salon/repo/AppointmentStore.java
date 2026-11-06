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
     * Every online booking of the salon still CONFIRMED that starts after {@code since} becomes CANCELLED_BY_SALON with
     * the reason, each with an event, in one statement (through ix_appointments_shop_confirmed); returns them.
     */
    public List<Appointment> cancelAllUpcoming(UUID shopId, Instant since, String reason, String detail, Instant now) {
        return jdbc.query("WITH closed AS (UPDATE appointments SET status = 'CANCELLED_BY_SALON', cancel_reason = ?, "
                        + "updated_at = ? WHERE shop_id = ? AND kind = 'BOOKING' AND status = 'CONFIRMED' "
                        + "AND starts_at > ? RETURNING " + COLUMNS + "), "
                        + "logged AS (INSERT INTO appointment_events (appointment_id, happened_at, by_user, event, detail) "
                        + "SELECT id, ?, NULL, 'CANCELLED_BY_SALON', ? FROM closed) "
                        + "SELECT " + COLUMNS + " FROM closed ORDER BY starts_at", ROW,
                reason, at(now), shopId, at(since), at(now), detail);
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
     * The automatic end: up to `limit` IN_SERVICE appointments whose planned end has passed become COMPLETED, each
     * with an event, in one statement (through ix_appointments_in_service). Safe on two servers at once: locked rows
     * are skipped and a row already completed no longer matches.
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
                            SELECT id, ?, NULL, 'COMPLETED' FROM done)
                        SELECT count(*) FROM done""",
                Integer.class, at(now), limit, at(now), at(now));
        return count == null ? 0 : count;
    }

    /** Appends to the appointment's history. by is null for the system (the automatic end). */
    /** The same history row for many appointments, in one statement. */
    public void eventAll(java.util.Collection<UUID> appointmentIds, Instant at, UUID by, String event, String detail) {
        if (appointmentIds.isEmpty()) {
            return;
        }
        jdbc.update(con -> {
            var ps = con.prepareStatement("INSERT INTO appointment_events (appointment_id, happened_at, by_user, "
                    + "event, detail) SELECT id, ?, ?, ?, ? FROM unnest(?::uuid[]) AS id");
            ps.setObject(1, at(at));
            ps.setObject(2, by);
            ps.setString(3, event);
            ps.setString(4, detail);
            ps.setArray(5, con.createArrayOf("uuid", appointmentIds.toArray()));
            return ps;
        });
    }

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
                + "AND event = 'REASSIGNED' AND detail LIKE ?)", Boolean.class, appointmentId, staffId + " -> %");
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

    /** Bookings and walk-ins starting in [from, to), latest first, at most limit (the owner's history). */
    public List<Appointment> history(UUID shopId, Instant from, Instant to, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM appointments WHERE shop_id = ? AND kind <> 'BLOCK' "
                + "AND starts_at >= ? AND starts_at < ? ORDER BY starts_at DESC, id LIMIT ?", ROW, shopId, at(from),
                at(to), limit);
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
        return jdbc.query("SELECT " + COLUMNS + " FROM appointments WHERE shop_id = ? AND status <> 'REMOVED' "
                + "AND time_range && tstzrange(?, ?, '[)') ORDER BY starts_at, id", ROW, shopId, at(from), at(to));
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

    /**
     * The salon's online bookings still open that start after {@code since} (CONFIRMED). Pass now for those still to
     * come, or a little earlier to include ones whose customer may still arrive (the 15 minutes they are held).
     */
    public List<Appointment> upcomingBookings(UUID shopId, Instant since) {
        return jdbc.query("SELECT " + COLUMNS + " FROM appointments WHERE shop_id = ? AND kind = 'BOOKING' "
                + "AND status = 'CONFIRMED' AND starts_at > ? ORDER BY starts_at", ROW, shopId, at(since));
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
