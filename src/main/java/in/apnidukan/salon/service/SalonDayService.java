package in.apnidukan.salon.service;

import in.apnidukan.common.context.ShopDirectory;
import in.apnidukan.common.context.ShopDirectory.ShopCard;
import in.apnidukan.common.context.ShopMemberships;
import in.apnidukan.common.context.ShopMemberships.Member;
import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.context.UserDirectory.Person;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.text.TextCleaner.TextField;
import in.apnidukan.salon.api.BookingRequests.AddBlock;
import in.apnidukan.salon.api.BookingRequests.AddClosure;
import in.apnidukan.salon.api.BookingRequests.Reassign;
import in.apnidukan.salon.api.BookingRequests.SalonCancel;
import in.apnidukan.salon.api.BookingRequests.Start;
import in.apnidukan.salon.api.BookingRequests.WalkIn;
import in.apnidukan.salon.api.BookingResponses.AppointmentView;
import in.apnidukan.salon.api.BookingResponses.BarberColumn;
import in.apnidukan.salon.api.BookingResponses.ClosureView;
import in.apnidukan.salon.api.BookingResponses.DayView;
import in.apnidukan.salon.api.BookingResponses.HoursBlock;
import in.apnidukan.salon.api.BookingResponses.MemberView;
import in.apnidukan.salon.api.BookingResponses.PauseView;
import in.apnidukan.salon.api.BookingResponses.WalkInOption;
import in.apnidukan.salon.domain.Appointment;
import in.apnidukan.salon.domain.AppointmentKind;
import in.apnidukan.salon.domain.AppointmentStatus;
import in.apnidukan.salon.domain.Availability;
import in.apnidukan.salon.domain.Availability.BarberDay;
import in.apnidukan.salon.domain.Availability.WalkInSlot;
import in.apnidukan.salon.domain.BlockType;
import in.apnidukan.salon.domain.BookedService;
import in.apnidukan.salon.domain.Interval;
import in.apnidukan.salon.domain.SalonStaff;
import in.apnidukan.salon.domain.SalonTime;
import in.apnidukan.salon.domain.StaffShift;
import in.apnidukan.salon.domain.StrikeKind;
import in.apnidukan.salon.repo.AppointmentStore;
import in.apnidukan.salon.repo.SalonAccessStore;
import in.apnidukan.salon.repo.SalonStaffRepository;
import in.apnidukan.salon.repo.StaffShiftRepository;
import in.apnidukan.salon.repo.StrikeStore;
import in.apnidukan.salon.service.SalonAccess.Caller;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static in.apnidukan.common.error.DatabaseErrors.hasSqlState;
import static in.apnidukan.common.text.TextCleaner.clean;

/**
 * The salon's daily work: the day screen, walk-ins, Start, No-show, reassign, salon cancel, breaks, leave,
 * closed days, pausing online bookings, and who may add walk-ins. Who may do what: see {@link SalonAccess}.
 *
 * Every change runs in one transaction, locks the appointment row first, and changes the status only from the
 * expected one, so two people tapping at once can't both act on it. The database's no-overlap rule has the last
 * word on double-booking a barber.
 */
@Service
public class SalonDayService {

    /** A customer may arrive this late and is still served; No-show is allowed only after it. */
    public static final Duration LATE_ALLOWANCE = Duration.ofMinutes(15);
    /** Start is allowed from this long before the planned start. */
    static final Duration EARLY_START = Duration.ofMinutes(15);
    static final Duration MAX_BREAK = Duration.ofHours(12);
    static final Duration MAX_LEAVE = Duration.ofDays(30);
    static final Duration MAX_PAUSE = Duration.ofDays(30);
    static final Duration MAX_CONFLICT_RANGE = Duration.ofDays(31);
    static final int CLOSURE_DAYS_AHEAD = 365;
    /** SQL state of an exclusion-rule violation (the no-overlap rule). */
    static final String OVERLAP = "23P01";

    static final TextField GUEST_NAME = new TextField("name", 2, 60);
    static final TextField REASON = new TextField("reason", 2, 200);

    private final SalonAccess access;
    private final SalonCalendar calendar;
    private final AppointmentStore appointments;
    private final StrikeStore strikes;
    private final SalonStaffRepository staff;
    private final StaffShiftRepository shifts;
    private final SalonAccessStore salonAccess;
    private final StartCodes codes;
    private final SalonNotices notices;
    private final ShopDirectory shops;
    private final ShopMemberships memberships;
    private final UserDirectory users;
    private final Clock clock;

    public SalonDayService(SalonAccess access, SalonCalendar calendar, AppointmentStore appointments,
                           StrikeStore strikes, SalonStaffRepository staff, StaffShiftRepository shifts,
                           SalonAccessStore salonAccess, StartCodes codes, SalonNotices notices, ShopDirectory shops,
                           ShopMemberships memberships, UserDirectory users, Clock clock) {
        this.access = access;
        this.calendar = calendar;
        this.appointments = appointments;
        this.strikes = strikes;
        this.staff = staff;
        this.shifts = shifts;
        this.salonAccess = salonAccess;
        this.codes = codes;
        this.notices = notices;
        this.shops = shops;
        this.memberships = memberships;
        this.users = users;
        this.clock = clock;
    }

    // ---------- the day screen ----------

    /**
     * One column per active barber with his hours and everything on his day (removed blocks left out). A barber
     * without walk-in access gets only his own column.
     */
    @Transactional(readOnly = true)
    public DayView day(UUID userId, LocalDate date) {
        Caller c = access.caller(userId);
        Instant now = now();
        LocalDate day = date != null ? date : today(now);
        List<Appointment> onDay = appointments.forDay(c.shopId(), Availability.dayStart(day),
                Availability.dayStart(day.plusDays(1)));
        Set<UUID> busyThatDay = onDay.stream().map(Appointment::staffId).collect(Collectors.toSet());
        // Active barbers, plus any switched-off barber who had appointments that day (history stays visible)
        List<SalonStaff> barbers = staff.findByShopIdOrderByCreatedAtAscIdAsc(c.shopId()).stream()
                .filter(b -> b.isActive() || busyThatDay.contains(b.getId()))
                .filter(b -> c.seesAll() || b.getId().equals(c.staffId()))
                .toList();
        Set<UUID> shown = barbers.stream().map(SalonStaff::getId).collect(Collectors.toSet());
        List<Appointment> all = onDay.stream().filter(a -> shown.contains(a.staffId())).toList();
        Map<UUID, List<BookedService>> services = appointments.services(all.stream().map(Appointment::id).toList());
        Map<UUID, Person> customers = users.find(all.stream().map(Appointment::customerId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet()));
        Map<UUID, List<StaffShift>> weeks = shifts.findByStaffIdIn(shown).stream()
                .collect(Collectors.groupingBy(StaffShift::getStaffId));
        List<BarberColumn> columns = barbers.stream().map(b -> new BarberColumn(b.getId(), b.getName(),
                Availability.working(weeks.getOrDefault(b.getId(), List.of()).stream().map(StaffShift::shift)
                        .toList(), day).stream().map(i -> hours(i, day)).toList(),
                all.stream().filter(a -> a.staffId().equals(b.getId()))
                        .map(a -> view(a, services.getOrDefault(a.id(), List.of()), customers, now,
                                c.ownerOrHelper()))
                        .toList()))
                .toList();
        return new DayView(day, columns);
    }

    // ---------- walk-ins ----------

    /**
     * For the chosen services, one suggestion per barber who does them all: free now, or his earliest start today
     * (exact minutes). Free-now barbers first (least booked first), then by earliest start; barbers who can't fit
     * it in today last.
     */
    @Transactional(readOnly = true)
    public List<WalkInOption> walkInOptions(UUID userId, List<UUID> serviceIds) {
        Caller c = access.walkIns(userId);
        Instant now = now();
        List<BookedService> chosen = calendar.chosenServices(c.shopId(), serviceIds);
        Duration length = SalonCalendar.length(chosen);
        List<SalonStaff> barbers = calendar.barbersFor(c.shopId(), ids(chosen));
        Map<UUID, SalonStaff> byId = barbers.stream().collect(Collectors.toMap(SalonStaff::getId, Function.identity()));
        LocalDate today = today(now);
        Instant dayEnd = Availability.dayStart(today.plusDays(1));
        List<BarberDay> days = calendar.days(barbers, today, null);
        List<WalkInOption> options = new ArrayList<>();
        for (BarberDay d : days) {
            Optional<WalkInSlot> slot = Availability.walkIn(d, length, now, dayEnd);
            SalonStaff b = byId.get(d.staffId());
            options.add(new WalkInOption(b.getId(), b.getName(), slot.map(WalkInSlot::freeNow).orElse(false),
                    slot.map(WalkInSlot::start).orElse(null), slot.map(s -> clockTime(s.start())).orElse(null),
                    d.bookedMinutes()));
        }
        Map<UUID, BarberDay> dayOf = days.stream().collect(Collectors.toMap(BarberDay::staffId, Function.identity()));
        options.sort(Comparator.comparing((WalkInOption o) -> o.startAt() == null)
                .thenComparing(o -> !o.freeNow())
                .thenComparing(o -> o.startAt() == null ? Instant.MAX : o.startAt())
                .thenComparingLong(WalkInOption::bookedMinutesToday)
                .thenComparing(o -> dayOf.get(o.staffId()).addedAt()));
        return options;
    }

    /**
     * Adds a walk-in with the chosen barber: now (IN_SERVICE at once) or at a later time today (CONFIRMED, holding
     * his time so online slots skip it). If his time was taken in between: 409 BARBER_BUSY with his next free start.
     * The same Idempotency-Key again returns the walk-in already added.
     */
    @Transactional
    public AppointmentView addWalkIn(UUID userId, String idempotencyKey, WalkIn in) {
        Caller c = access.walkIns(userId);
        UUID requestId = requestId(idempotencyKey);
        appointments.lockCustomer(c.userId());   // the same person's retries run one after the other
        Optional<Appointment> earlier = appointments.findByRequest(c.userId(), requestId);
        if (earlier.isPresent()) {
            Appointment e = earlier.get();
            List<UUID> hadServices = ids(appointments.services(List.of(e.id())).getOrDefault(e.id(), List.of()));
            if (e.kind() != AppointmentKind.WALK_IN || !e.shopId().equals(c.shopId())
                    || !e.staffId().equals(in.staffId())
                    || !hadServices.equals(new ArrayList<>(new java.util.LinkedHashSet<>(in.serviceIds())))) {
                throw keyReused();   // the same key for a different walk-in: nothing was added
            }
            return salonView(c, e);
        }
        Instant now = now();
        List<BookedService> chosen = calendar.chosenServices(c.shopId(), in.serviceIds());
        Duration length = SalonCalendar.length(chosen);
        SalonStaff barber = activeBarber(c, in.staffId());
        if (calendar.barbersFor(c.shopId(), ids(chosen)).stream().noneMatch(b -> b.getId().equals(barber.getId()))) {
            throw SalonCalendar.invalid("staffId", "this barber doesn't do all of the chosen services");
        }
        Instant nowMinute = now.truncatedTo(ChronoUnit.MINUTES);
        boolean startNow = in.startAt() == null;
        Instant start = startNow ? nowMinute : in.startAt().truncatedTo(ChronoUnit.MINUTES);
        if (start.isBefore(nowMinute)) {
            throw SalonCalendar.invalid("startAt", "is in the past; leave it out to start now");
        }
        if (!start.isBefore(Availability.dayStart(today(now).plusDays(1)))) {
            throw SalonCalendar.invalid("startAt", "must be today");
        }
        String guest = in.name() == null || in.name().isBlank() ? null : clean(GUEST_NAME, in.name());
        Appointment a = new Appointment(UUID.randomUUID(), c.shopId(), barber.getId(), AppointmentKind.WALK_IN, null,
                null, null, null, null, guest, null, start, start.plus(length),
                startNow ? AppointmentStatus.IN_SERVICE : AppointmentStatus.CONFIRMED, startNow ? now : null, null,
                null, c.userId(), requestId, now, now);
        if (!appointments.lockActiveBarber(barber.getId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND", "No such barber.");
        }
        if (!appointments.insertIfFree(a)) {
            BarberDay day = calendar.day(barber, today(now), null);
            Optional<WalkInSlot> next = Availability.walkIn(day, length, now,
                    Availability.dayStart(today(now).plusDays(1)));
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("freeNow", false);
            details.put("freeAt", next.map(s -> (Object) s.start().toString()).orElse("not today"));
            throw new ApiException(HttpStatus.CONFLICT, "BARBER_BUSY",
                    barber.getName() + " is busy at that time now. Pick another suggestion.", details);
        }
        appointments.insertServices(a.id(), chosen);
        appointments.event(a.id(), now, c.userId(), startNow ? "WALK_IN_STARTED" : "WALK_IN_HELD", null);
        return salonView(c, a);
    }

    // ---------- start, no-show, reassign, cancel ----------

    /**
     * CONFIRMED → IN_SERVICE, from 15 minutes before the start. An online booking needs the customer's start code,
     * or withoutCode (owner or helper only). A barber can start only his own appointments.
     */
    @Transactional
    public AppointmentView start(UUID userId, UUID appointmentId, Start in) {
        Caller c = access.caller(userId);
        Appointment a = lockedFor(c, appointmentId);
        Instant now = now();
        requireStatus(a, AppointmentStatus.CONFIRMED);
        if (now.isBefore(a.startsAt().minus(EARLY_START))) {
            throw wrongStatus("Too early: Start is allowed from 15 minutes before " + clockTime(a.startsAt()) + ".");
        }
        if (!now.isBefore(a.endsAt())) {
            throw wrongStatus("Its time is over (it ended at " + clockTime(a.endsAt()) + "). Mark No-show, or add "
                    + "the customer as a walk-in.");
        }
        Boolean withCode = null;
        if (a.isBooking()) {
            if (in != null && Boolean.TRUE.equals(in.withoutCode())) {
                if (!c.ownerOrHelper()) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "NOT_ALLOWED",
                            "Only the owner or a helper can start without the customer's code.");
                }
                withCode = false;
            } else {
                codes.check(a.id(), a.startCode(), in == null ? null : in.code());
                withCode = true;
            }
        }
        if (!appointments.start(a.id(), withCode, now)) {
            throw wrongStatus("It was changed in the meantime. Reload.");
        }
        appointments.event(a.id(), now, c.userId(), "STARTED", Boolean.FALSE.equals(withCode) ? "without code" : null);
        notices.closed(a);
        return salonView(c, appointments.find(a.id()).orElseThrow());
    }

    /**
     * CONFIRMED → NO_SHOW, from 15 minutes after the start. Frees the barber's time at once. An online booking
     * gives the customer a strike; a walk-in doesn't (there is no account).
     */
    @Transactional
    public AppointmentView noShow(UUID userId, UUID appointmentId) {
        Caller c = access.caller(userId);
        Appointment a = lockedFor(c, appointmentId);
        Instant now = now();
        requireStatus(a, AppointmentStatus.CONFIRMED);
        Instant from = a.startsAt().plus(LATE_ALLOWANCE);
        if (now.isBefore(from)) {
            throw new ApiException(HttpStatus.CONFLICT, "WRONG_STATUS",
                    "The customer may still come: No-show is allowed from " + clockTime(from) + ".",
                    Map.of("noShowFrom", from.toString()));
        }
        if (!appointments.close(a.id(), AppointmentStatus.NO_SHOW, null, now)) {
            throw wrongStatus("It was changed in the meantime. Reload.");
        }
        if (a.isBooking()) {
            // Dated at the booking's start, not when staff pressed the button (it may be pressed late)
            strikes.add(a.customerId(), a.id(), StrikeKind.NO_SHOW, a.startsAt());
            notices.closed(a);
        }
        appointments.event(a.id(), now, c.userId(), "NO_SHOW", null);
        return salonView(c, appointments.find(a.id()).orElseThrow());
    }

    /** Gives a CONFIRMED appointment to another barber who does its services, same time. Owner or helper. */
    @Transactional
    public AppointmentView reassign(UUID userId, UUID appointmentId, Reassign in) {
        Caller c = access.ownerOrHelper(userId);
        Appointment a = lockedFor(c, appointmentId);
        requireStatus(a, AppointmentStatus.CONFIRMED);
        if (a.staffId().equals(in.staffId())) {
            return salonView(c, a);
        }
        SalonStaff target = activeBarber(c, in.staffId());
        List<UUID> serviceIds = ids(appointments.services(List.of(a.id())).getOrDefault(a.id(), List.of()));
        if (calendar.barbersFor(c.shopId(), serviceIds).stream().noneMatch(b -> b.getId().equals(target.getId()))) {
            throw SalonCalendar.invalid("staffId", "this barber doesn't do all of its services");
        }
        if (!calendar.day(target, LocalDate.ofInstant(a.startsAt(), SalonTime.ZONE), a.id()).isFree(a.time())) {
            throw barberBusy(target);
        }
        Instant now = now();
        if (!appointments.lockActiveBarber(target.getId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND", "No such barber.");
        }
        moveOrBusy(a, target, a.startsAt(), a.endsAt(), now);
        appointments.event(a.id(), now, c.userId(), "REASSIGNED", a.staffId() + " -> " + target.getId());
        return salonView(c, appointments.find(a.id()).orElseThrow());
    }

    /** The salon cancels a CONFIRMED booking or walk-in, with a reason sent to the customer. No strike. */
    @Transactional
    public AppointmentView cancel(UUID userId, UUID appointmentId, SalonCancel in) {
        Caller c = access.ownerOrHelper(userId);
        Appointment a = lockedFor(c, appointmentId);
        requireStatus(a, AppointmentStatus.CONFIRMED);
        String reason = clean(REASON, in.reason());
        Instant now = now();
        if (!appointments.close(a.id(), AppointmentStatus.CANCELLED_BY_SALON, reason, now)) {
            throw wrongStatus("It was changed in the meantime. Reload.");
        }
        if (a.isBooking()) {
            notices.cancelledBySalon(a, salonCard(c.shopId()), reason, now);
        }
        appointments.event(a.id(), now, c.userId(), "CANCELLED_BY_SALON", reason);
        return salonView(c, appointments.find(a.id()).orElseThrow());
    }

    // ---------- breaks and leave ----------

    /** The bookings and walk-ins a block for this barber would hit (to reassign or cancel them first). */
    @Transactional(readOnly = true)
    public List<AppointmentView> conflicts(UUID userId, UUID staffId, Instant from, Instant to) {
        Caller c = access.caller(userId);
        SalonStaff barber = activeBarber(c, staffId);
        if (!c.actsFor(barber.getId())) {
            throw noStaff();   // another barber's day: as if he didn't exist
        }
        Interval range = range(from, to, MAX_CONFLICT_RANGE);
        return views(c, appointments.holding(List.of(barber.getId()), range.start(), range.end(), null));
    }

    /**
     * Adds a break (owner, helper, or the barber himself) or leave (owner). If bookings or walk-ins are in the
     * way: 409 BLOCK_CONFLICTS listing them, and nothing is saved.
     */
    @Transactional
    public AppointmentView addBlock(UUID userId, UUID staffId, AddBlock in) {
        Caller c = access.caller(userId);
        SalonStaff barber = activeBarber(c, staffId);
        if (in.type() == BlockType.CLOSED) {
            throw SalonCalendar.invalid("type", "use closed days (/salon/closures) to close the whole salon");
        }
        if (!c.actsFor(barber.getId())) {
            throw noStaff();   // another barber's day: as if he didn't exist
        }
        if (in.type() == BlockType.LEAVE && !c.isOwner()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER_ONLY", "Only the salon owner can add leave.");
        }
        Instant now = now();
        Interval time = range(in.startAt().truncatedTo(ChronoUnit.MINUTES), in.endAt().truncatedTo(ChronoUnit.MINUTES),
                in.type() == BlockType.BREAK ? MAX_BREAK : MAX_LEAVE);
        if (!time.end().isAfter(now)) {
            throw SalonCalendar.invalid("endAt", "is in the past");
        }
        Appointment block = block(c, barber.getId(), in.type(), null, time, now);
        if (!appointments.insertIfFree(block)) {
            throw blockConflicts(appointments.holding(List.of(barber.getId()), time.start(), time.end(), null));
        }
        appointments.event(block.id(), now, c.userId(), "BLOCK_ADDED", in.type().name());
        return salonView(c, block);
    }

    /** Removes a break (owner, helper, the barber himself) or leave (owner): his time is free again. */
    @Transactional
    public void removeBlock(UUID userId, UUID staffId, UUID blockId) {
        Caller c = access.caller(userId);
        java.util.function.Predicate<Appointment> ours = b -> b.shopId().equals(c.shopId())
                && b.staffId().equals(staffId) && b.kind() == AppointmentKind.BLOCK && b.blockType() != BlockType.CLOSED
                && c.actsFor(b.staffId());
        if (appointments.find(blockId).filter(ours).isEmpty()) {   // checked before locking
            throw notFound();
        }
        Appointment a = appointments.lock(blockId).filter(ours).orElseThrow(SalonDayService::notFound);
        if (a.blockType() == BlockType.LEAVE && !c.isOwner()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER_ONLY", "Only the salon owner can remove leave.");
        }
        Instant now = now();
        if (!appointments.removeBlock(a.id(), now)) {
            throw wrongStatus("It was already removed.");
        }
        appointments.event(a.id(), now, c.userId(), "BLOCK_REMOVED", null);
    }

    // ---------- closed days ----------

    /**
     * Closes the salon for a whole day: one CLOSED block per active barber, sharing a closure id, all or nothing.
     * Anything already on that day (bookings, walk-ins, breaks): 409 BLOCK_CONFLICTS listing it.
     */
    @Transactional
    public ClosureView addClosure(UUID userId, AddClosure in) {
        Caller c = access.owner(userId);
        appointments.lockSalonCalendar(c.shopId());   // a barber added at this moment can't miss the closed day
        Instant now = now();
        LocalDate today = today(now);
        if (in.date().isBefore(today) || in.date().isAfter(today.plusDays(CLOSURE_DAYS_AHEAD))) {
            throw SalonCalendar.invalid("date", "must be from today up to a year ahead");
        }
        List<SalonStaff> barbers = staff.findByShopIdOrderByCreatedAtAscIdAsc(c.shopId()).stream()
                .filter(SalonStaff::isActive).toList();
        if (barbers.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_ACTIVE_BARBERS", "Add a barber first.");
        }
        Interval day = new Interval(Availability.dayStart(in.date()), Availability.dayStart(in.date().plusDays(1)));
        List<UUID> ids = barbers.stream().map(SalonStaff::getId).toList();
        List<Appointment> inTheWay = appointments.holding(ids, day.start(), day.end(), null);
        if (!inTheWay.isEmpty()) {
            throw blockConflicts(inTheWay);
        }
        UUID closureId = UUID.randomUUID();
        for (SalonStaff b : barbers) {
            Appointment block = block(c, b.getId(), BlockType.CLOSED, closureId, day, now);
            if (!appointments.insertIfFree(block)) {   // something was added in between: undo all of it
                throw blockConflicts(appointments.holding(ids, day.start(), day.end(), null));
            }
            appointments.event(block.id(), now, c.userId(), "CLOSED_DAY_ADDED", in.date().toString());
        }
        return new ClosureView(closureId, in.date(), barbers.size());
    }

    /** Upcoming closed days. */
    @Transactional(readOnly = true)
    public List<ClosureView> closures(UUID userId) {
        Caller c = access.caller(userId);
        return appointments.closures(c.shopId(), now()).entrySet().stream()
                .map(e -> new ClosureView(e.getKey(),
                        LocalDate.ofInstant(e.getValue().get(0).startsAt(), SalonTime.ZONE), e.getValue().size()))
                .toList();
    }

    /** Opens a closed day again (owner). */
    @Transactional
    public void removeClosure(UUID userId, UUID closureId) {
        Caller c = access.owner(userId);
        appointments.lockSalonCalendar(c.shopId());   // a barber joining this closed day right now can't be missed
        Instant now = now();
        List<UUID> removed = appointments.removeClosure(c.shopId(), closureId, now);
        if (removed.isEmpty()) {
            throw notFound();
        }
        for (UUID id : removed) {
            appointments.event(id, now, c.userId(), "CLOSED_DAY_REMOVED", null);
        }
    }

    // ---------- pause online bookings ----------

    @Transactional(readOnly = true)
    public PauseView pauseStatus(UUID userId) {
        Caller c = access.caller(userId);
        Instant now = now();
        return new PauseView(salonAccess.pausedUntil(c.shopId()).filter(u -> u.isAfter(now)).orElse(null));
    }

    /**
     * Stops new online bookings until the given moment (default: the end of today). Bookings already made and
     * walk-ins carry on. Owner or helper.
     */
    @Transactional
    public PauseView pause(UUID userId, Instant until) {
        Caller c = access.ownerOrHelper(userId);
        Instant now = now();
        Instant end = until != null ? until : Availability.dayStart(today(now).plusDays(1));
        if (!end.isAfter(now) || end.isAfter(now.plus(MAX_PAUSE))) {
            throw SalonCalendar.invalid("until", "must be in the next 30 days");
        }
        salonAccess.pause(c.shopId(), end, c.userId(), now);
        return new PauseView(end);
    }

    @Transactional
    public PauseView resume(UUID userId) {
        Caller c = access.ownerOrHelper(userId);
        salonAccess.resume(c.shopId());
        return new PauseView(null);
    }

    // ---------- who may add walk-ins ----------

    /** Everyone in the salon, with whether they may add walk-ins. Owner only. */
    @Transactional(readOnly = true)
    public List<MemberView> members(UUID userId) {
        Caller c = access.owner(userId);
        List<Member> members = memberships.members(c.shopId());
        Map<UUID, Person> people = users.find(members.stream().map(Member::userId).toList());
        Map<UUID, UUID> staffOf = new LinkedHashMap<>();
        for (SalonStaff b : staff.findByShopIdOrderByCreatedAtAscIdAsc(c.shopId())) {
            if (b.getUserId() != null) {
                staffOf.put(b.getUserId(), b.getId());
            }
        }
        Set<UUID> allowed = salonAccess.walkInAccess(c.shopId());
        return members.stream().map(m -> {
            Person p = people.get(m.userId());
            return new MemberView(m.userId(), p == null ? null : p.name(), p == null ? null : p.phone(), m.role(),
                    staffOf.get(m.userId()), "OWNER".equals(m.role()) || allowed.contains(m.userId()));
        }).toList();
    }

    /** Gives or takes back walk-in access for a helper or a barber of this salon. Owner only. */
    @Transactional
    public List<MemberView> setWalkInAccess(UUID userId, UUID memberId, boolean allowed) {
        Caller c = access.owner(userId);
        Member m = memberships.members(c.shopId()).stream()
                .filter(x -> x.userId().equals(memberId) && !"OWNER".equals(x.role()))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND",
                        "No helper or barber with this id in your salon."));
        if (allowed) {
            salonAccess.grantWalkIns(c.shopId(), m.userId(), c.userId(), now());
        } else {
            salonAccess.revokeWalkIns(c.shopId(), m.userId());
        }
        return members(userId);
    }

    // ---------- helpers ----------

    /**
     * The appointment, locked, if it is a booking or walk-in of the caller's salon that the caller may act on
     * (a barber: his own only). Otherwise 404, so ids of other salons or barbers can't be probed.
     */
    private Appointment lockedFor(Caller c, UUID id) {
        java.util.function.Predicate<Appointment> mine = a -> a.shopId().equals(c.shopId())
                && a.kind() != AppointmentKind.BLOCK && c.actsFor(a.staffId());
        // Checked before locking too, so nobody can lock another salon's rows by guessing ids
        if (appointments.find(id).filter(mine).isEmpty()) {
            throw notFound();
        }
        return appointments.lock(id).filter(mine).orElseThrow(SalonDayService::notFound);
    }

    private SalonStaff activeBarber(Caller c, UUID staffId) {
        return staff.findByIdAndShopId(staffId, c.shopId()).filter(SalonStaff::isActive)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND", "No such barber."));
    }

    /**
     * Moves the appointment; if the database's no-overlap rule refuses it (someone took the time in between),
     * 409 BARBER_BUSY and the whole change is undone.
     */
    private void moveOrBusy(Appointment a, SalonStaff target, Instant start, Instant end, Instant now) {
        try {
            if (!appointments.move(a.id(), target.getId(), start, end, now)) {
                throw wrongStatus("It was changed in the meantime. Reload.");
            }
        } catch (DataIntegrityViolationException e) {
            if (hasSqlState(e, OVERLAP)) {
                throw barberBusy(target);
            }
            throw e;
        } catch (PessimisticLockingFailureException e) {
            throw tryAgain();   // two moves into each other's time at once: the database broke the tie
        }
    }

    private Appointment block(Caller c, UUID staffId, BlockType type, UUID closureId, Interval time, Instant now) {
        return new Appointment(UUID.randomUUID(), c.shopId(), staffId, AppointmentKind.BLOCK, type, closureId,
                null, null, null, null, null, time.start(), time.end(), AppointmentStatus.BLOCKED, null, null, null,
                c.userId(), null, now, now);
    }

    private static Interval range(Instant from, Instant to, Duration max) {
        if (!to.isAfter(from)) {
            throw SalonCalendar.invalid("endAt", "must be after the start");
        }
        if (Duration.between(from, to).compareTo(max) > 0) {
            throw SalonCalendar.invalid("endAt", "is too far from the start (at most " + max.toHours() + " hours)");
        }
        return new Interval(from, to);
    }

    /** 409 BLOCK_CONFLICTS listing what's in the way: {id, kind, staffId, startsAt, endsAt} each. */
    static ApiException blockConflicts(List<Appointment> inTheWay) {
        List<Map<String, Object>> list = inTheWay.stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.id().toString());
            m.put("kind", a.kind().name());
            m.put("staffId", a.staffId().toString());
            m.put("startsAt", a.startsAt().toString());
            m.put("endsAt", a.endsAt().toString());
            return m;
        }).toList();
        return new ApiException(HttpStatus.CONFLICT, "BLOCK_CONFLICTS",
                "Some appointments are in the way. Reassign or cancel them first.", Map.of("appointments", list));
    }

    /** One appointment as salon screens show it (never with the start code). */
    AppointmentView salonView(Caller c, Appointment a) {
        return views(c, List.of(a)).get(0);
    }

    private List<AppointmentView> views(Caller c, List<Appointment> list) {
        Map<UUID, List<BookedService>> services = appointments.services(list.stream().map(Appointment::id).toList());
        Set<UUID> customerIds = new HashSet<>();
        list.stream().map(Appointment::customerId).filter(java.util.Objects::nonNull).forEach(customerIds::add);
        Map<UUID, Person> customers = users.find(customerIds);
        Instant now = now();
        return list.stream().map(a -> view(a, services.getOrDefault(a.id(), List.of()), customers, now,
                c.ownerOrHelper())).toList();
    }

    /**
     * @param showPhone the customer's phone, so the salon can call someone running late: owner and helpers only,
     *                  never barbers
     */
    private static AppointmentView view(Appointment a, List<BookedService> services, Map<UUID, Person> customers,
                                        Instant now, boolean showPhone) {
        String name = null;
        String phone = null;
        if (a.customerId() != null) {
            Person p = customers.get(a.customerId());
            name = p == null || p.name() == null ? "Customer" : p.name();
            phone = showPhone && p != null ? p.phone() : null;
        }
        return AppointmentView.of(a, services, name, phone, now, a.startsAt().plus(LATE_ALLOWANCE));
    }

    private ShopCard salonCard(UUID shopId) {
        return shops.find(shopId).orElseThrow();
    }

    private static HoursBlock hours(Interval i, LocalDate day) {
        Instant midnight = Availability.dayStart(day);
        return new HoursBlock(SalonTime.format((int) Duration.between(midnight, i.start()).toMinutes()),
                SalonTime.format((int) Duration.between(midnight, i.end()).toMinutes()));
    }

    static UUID requestId(String key) {
        if (key == null || key.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "Send an Idempotency-Key header: a new UUID for each new request, the same one when retrying.");
        }
        try {
            return UUID.fromString(key.strip());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "The Idempotency-Key header must be a UUID.");
        }
    }

    static ApiException keyReused() {
        return new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                "This Idempotency-Key was already used for something else. Send a new one.");
    }

    static List<UUID> ids(List<BookedService> services) {
        return services.stream().map(BookedService::serviceId).toList();
    }

    static String clockTime(Instant t) {
        return SalonTime.format(t.atZone(SalonTime.ZONE).toLocalTime().toSecondOfDay() / 60);
    }

    private static void requireStatus(Appointment a, AppointmentStatus expected) {
        if (a.status() != expected) {
            throw wrongStatus("It is " + a.status() + " now. Reload.");
        }
    }

    static ApiException tryAgain() {
        return new ApiException(HttpStatus.CONFLICT, "TRY_AGAIN", "Someone else changed the same times. Try again.");
    }

    private static ApiException noStaff() {
        return new ApiException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND", "No such barber.");
    }

    static ApiException wrongStatus(String message) {
        return new ApiException(HttpStatus.CONFLICT, "WRONG_STATUS", message);
    }

    private static ApiException barberBusy(SalonStaff target) {
        return new ApiException(HttpStatus.CONFLICT, "BARBER_BUSY", target.getName() + " is busy at that time.");
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "No such appointment.");
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static LocalDate today(Instant now) {
        return LocalDate.ofInstant(now, SalonTime.ZONE);
    }
}
