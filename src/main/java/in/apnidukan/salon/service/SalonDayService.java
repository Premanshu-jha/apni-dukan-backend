package in.apnidukan.salon.service;

import in.apnidukan.common.context.CurrentUser;
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
import in.apnidukan.salon.api.BookingResponses.HistoryView;
import in.apnidukan.salon.api.BookingResponses.HoursBlock;
import in.apnidukan.salon.api.BookingResponses.MemberView;
import in.apnidukan.salon.api.BookingResponses.PauseView;
import in.apnidukan.salon.api.BookingResponses.UpcomingChanges;
import in.apnidukan.salon.api.BookingResponses.WalkInOption;
import in.apnidukan.salon.api.BookingResponses.WalkInPrice;
import in.apnidukan.salon.domain.Appointment;
import in.apnidukan.salon.domain.AppointmentCursor;
import in.apnidukan.salon.domain.AppointmentKind;
import in.apnidukan.salon.domain.AppointmentStatus;
import in.apnidukan.salon.domain.Availability;
import in.apnidukan.salon.domain.Availability.BarberDay;
import in.apnidukan.salon.domain.Availability.WalkInSlot;
import in.apnidukan.salon.domain.BlockType;
import in.apnidukan.salon.domain.BookedService;
import in.apnidukan.salon.domain.DayCursor;
import in.apnidukan.salon.domain.Interval;
import in.apnidukan.salon.domain.SalonOffer;
import in.apnidukan.salon.domain.SalonStaff;
import in.apnidukan.salon.domain.SalonTime;
import in.apnidukan.salon.domain.StaffShift;
import in.apnidukan.salon.domain.StrikeKind;
import in.apnidukan.salon.repo.AppointmentStore;
import in.apnidukan.salon.repo.SalonAccessStore;
import in.apnidukan.salon.repo.SalonStaffRepository;
import in.apnidukan.salon.repo.SalonVersionStore;
import in.apnidukan.salon.repo.StaffShiftRepository;
import in.apnidukan.salon.repo.StrikeStore;
import in.apnidukan.salon.repo.VisitFollowUpStore;
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
import java.util.Locale;
import java.util.HashMap;
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
    public static final Duration LATE_ALLOWANCE = Duration.ofMinutes(10);
    /** Start is allowed from this long before the planned start. */
    static final Duration EARLY_START = Duration.ofMinutes(15);
    /** No-show can be undone at the salon until this long after the start; after that only support can. */
    static final Duration UNDO_NO_SHOW_WITHIN = Duration.ofHours(24);
    static final Duration MAX_BREAK = Duration.ofHours(12);
    /**
     * A barber's own breaks (not the owner's or a helper's): short, and today or tomorrow only. Longer time off is
     * leave, which only the owner gives; without this a barber could block his whole month with back-to-back breaks.
     */
    static final Duration MAX_OWN_BREAK = Duration.ofHours(4);
    /** A walk-in already in the chair can be cancelled as a mistake only this soon after it started. */
    static final Duration MISTAKE_WINDOW = Duration.ofMinutes(15);
    /** A walk-in moved to another barber keeps at least this long with him. */
    static final Duration MIN_LEFT = Duration.ofMinutes(5);
    static final Duration MAX_LEAVE = Duration.ofDays(30);
    static final Duration MAX_PAUSE = Duration.ofDays(30);
    static final Duration MAX_CONFLICT_RANGE = Duration.ofDays(31);
    static final int CLOSURE_DAYS_AHEAD = 365;
    /**
     * The upcoming list covers the longest booking window a salon can set (today and 30 days): it reads bookings
     * starting before the end of the 31st day from today, a bound that moves only at midnight.
     */
    static final int UPCOMING_DAYS = 31;
    /** Appointments per page of the upcoming list and of the history (the next page with afterStart and afterId). */
    public static final int UPCOMING_PAGE = 100;
    static final int HISTORY_MAX_DAYS = 31;
    public static final int HISTORY_PAGE = 100;
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
    private final SalonEtags etags;
    private final SalonSetups setups;
    private final SalonVersionStore versions;
    private final VisitFollowUpStore followUps;
    private final Clock clock;

    public SalonDayService(SalonAccess access, SalonCalendar calendar, AppointmentStore appointments,
                           StrikeStore strikes, SalonStaffRepository staff, StaffShiftRepository shifts,
                           SalonAccessStore salonAccess, StartCodes codes, SalonNotices notices, ShopDirectory shops,
                           ShopMemberships memberships, UserDirectory users, SalonEtags etags, SalonSetups setups,
                           SalonVersionStore versions, VisitFollowUpStore followUps, Clock clock) {
        this.followUps = followUps;
        this.etags = etags;
        this.setups = setups;
        this.versions = versions;
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
     * With its ETag (null when the sign-in token doesn't name the salon: then the answer is
     * fingerprinted by EtagConfig as before). The salon's change counter is read before anything else, so a change
     * made while the answer is put together gives the next request a new tag, never an old answer. The answer carries
     * {@code seq}, the cursor for asking only for what changed after it (dayChanges).
     */
    @Transactional(readOnly = true)
    public Tagged<DayView> day(CurrentUser me, LocalDate date) {
        Optional<SalonEtags.Version> version = etags.version(me);
        Caller c = access.caller(me.userId());
        Instant now = now();
        LocalDate day = date != null ? date : today(now);
        Built built = wholeDay(c, day, now, versions.board(c.shopId()));
        // The answer changes by itself only when a waiting appointment's start passes (notStarted) and, for "today",
        // at midnight; until then the same tag stands
        Instant validUntil = earliest(now, built.all().stream().filter(a -> a.status() == AppointmentStatus.CONFIRMED)
                .map(Appointment::startsAt), date == null ? Availability.dayStart(day.plusDays(1)) : null);
        return new Tagged<>(built.view(), version.flatMap(v -> etags.tag(v, c, SalonEtags.DAY, dayParams(date),
                validUntil)).orElse(null));
    }

    /** Changed rows a delta may carry; more than this, and the whole day is cheaper to send. */
    static final int DELTA_MAX = 300;
    /** A cursor older than this gets the whole day (a phone back from a long sleep). */
    static final Duration DELTA_MAX_AGE = Duration.ofHours(1);

    /**
     * Only what changed on the day board since an earlier answer (its {@code seq}, sent back as {@code since}): the
     * appointments of that day written since, in the order they were written ({@code changed}, each as the board
     * shows it, to replace by id), the ids of those that left it ({@code removed}: moved to another day or barber,
     * a removed block), and the bookings whose start passed since (now "not started"). The whole day instead
     * ({@code full: true}) when the setup changed (barbers, their hours and services, who works there or may add
     * walk-ins), when the cursor is unknown, older than an hour or for another date ("today" after midnight), or when
     * more than DELTA_MAX rows changed. A busy salon's board polled every 30 s then costs a few small reads and a few
     * hundred bytes instead of the whole day.
     */
    @Transactional(readOnly = true)
    public DayView dayChanges(CurrentUser me, LocalDate date, String since) {
        Caller c = access.caller(me.userId());   // one or two small reads (SalonAccess)
        Instant now = now();
        LocalDate day = date != null ? date : today(now);
        SalonVersionStore.Board board = versions.board(c.shopId());   // before any row: see V21
        Optional<DayCursor> cursor = DayCursor.parse(since, false)
                .filter(from -> deltaPossible(from, board, day, now));
        if (cursor.isPresent() && nothingSince(cursor.get(), board, now)) {
            // The counter unchanged and no waiting start passed: nothing at all changed, and nothing else is read
            return new DayView(day, null, c.staffId(), false, cursor(false, day, board, now,
                    cursor.get().validUntil()), board.setupVersion(), List.of(), List.of());
        }
        Instant dayFrom = Availability.dayStart(day);
        Instant dayTo = Availability.dayStart(day.plusDays(1));
        Optional<Delta> delta = cursor.flatMap(cur -> delta(c, cur, board, a -> onBoard(a, c, dayFrom, dayTo)));
        if (delta.isEmpty()) {
            return wholeDay(c, day, now, board).view();
        }
        Map<UUID, Appointment> changed = delta.get().changed();
        // Time alone: bookings whose start passed since the last answer show "not started" now
        if (now.isAfter(cursor.get().at())) {
            for (Appointment a : appointments.startedBetween(c.shopId(), cursor.get().at(), now, dayFrom, dayTo)) {
                if (onBoard(a, c, dayFrom, dayTo)) {
                    changed.putIfAbsent(a.id(), a);
                }
            }
        }
        List<AppointmentView> views = changed.isEmpty() ? List.of() : views(c, List.copyOf(changed.values()));
        Instant validUntil = appointments.nextChange(c.shopId(), now, dayFrom, dayTo, false).orElse(dayTo);
        return new DayView(day, null, c.staffId(), false, cursor(false, day, board, now, validUntil),
                board.setupVersion(), views, delta.get().removed());
    }

    /**
     * Whether nothing at all can have changed since the cursor: the salon's counter is the same (no appointment of
     * it was written since: it moves at each commit) and no waiting booking's start (or, for the upcoming list, end)
     * has come since. Then a delta needs no other read.
     */
    private static boolean nothingSince(DayCursor from, SalonVersionStore.Board board, Instant now) {
        return board.version() == from.version() && now.isBefore(from.validUntil());
    }

    /**
     * Only what changed on the upcoming list since an earlier answer of this call (see dayChanges): {@code full: true}
     * means "load the list's pages again and go on from this seq" (an unknown cursor gives that too, so the app asks
     * once with any since to get its first seq); otherwise {@code changed} (bookings now on the list, new or
     * changed, or turned "not started") and {@code removed} (ids no longer on it: cancelled, started, moved out of
     * the window or to another barber, or over).
     */
    @Transactional(readOnly = true)
    public UpcomingChanges upcomingChanges(CurrentUser me, String since) {
        Caller c = access.caller(me.userId());
        Instant now = now();
        LocalDate today = today(now);
        Instant until = Availability.dayStart(today.plusDays(UPCOMING_DAYS + 1L));
        SalonVersionStore.Board board = versions.board(c.shopId());
        Optional<DayCursor> cursor = DayCursor.parse(since, true)
                .filter(from -> deltaPossible(from, board, today, now));
        UUID onlyStaff = c.seesAll() ? null : c.staffId();
        if (cursor.isPresent() && nothingSince(cursor.get(), board, now)) {
            // Nothing written and no start or end came since: an empty delta from the counters alone
            return new UpcomingChanges(false, cursor(true, today, board, now, cursor.get().validUntil()),
                    board.setupVersion(), List.of(), List.of());
        }
        Optional<Delta> delta = cursor.flatMap(cur -> delta(c, cur, board, a -> onUpcoming(a, onlyStaff, now, until)));
        String seq = cursor(true, today, board, now, appointments.nextChange(c.shopId(), now, now, until, true)
                .orElse(Availability.dayStart(today.plusDays(1))));
        if (delta.isEmpty()) {
            return new UpcomingChanges(true, seq, board.setupVersion(), null, null);
        }
        Map<UUID, Appointment> changed = delta.get().changed();
        List<UUID> removed = new ArrayList<>(delta.get().removed());
        Instant from = cursor.get().at();
        if (now.isAfter(from)) {
            // Time alone: a start passed ("not started" now) or an end passed (off the list)
            for (Appointment a : appointments.startedBetween(c.shopId(), from, now, from, until)) {
                if (onUpcoming(a, onlyStaff, now, until)) {
                    changed.putIfAbsent(a.id(), a);
                }
            }
            for (UUID id : appointments.endedBetween(c.shopId(), onlyStaff, from, now)) {
                if (!changed.containsKey(id) && !removed.contains(id)) {
                    removed.add(id);   // the app drops ids it doesn't have
                }
            }
        }
        List<AppointmentView> views = changed.isEmpty() ? List.of() : views(c, List.copyOf(changed.values()));
        return new UpcomingChanges(false, seq, board.setupVersion(), views, removed);
    }

    /** What was written since a cursor, split by whether it is on the answer now. */
    private record Delta(Map<UUID, Appointment> changed, List<UUID> removed) {
    }

    /**
     * The rows written since the cursor, split by {@code shown}; empty when there are too many (more than DELTA_MAX:
     * the whole answer is cheaper). {@code removed} holds only rows that could have been in this caller's answer: for
     * a barber who sees only himself, his own rows and rows given away from him, never other barbers' ids.
     */
    private Optional<Delta> delta(Caller c, DayCursor from, SalonVersionStore.Board board,
                                  java.util.function.Predicate<Appointment> shown) {
        // The counter unchanged: no appointment of the salon was written since (it moves at the commit of each one)
        List<Appointment> written = board.version() == from.version() ? List.of()
                : appointments.changedSince(c.shopId(), from.oldestRunningXid(), DELTA_MAX + 1);
        if (written.size() > DELTA_MAX) {
            return Optional.empty();
        }
        Map<UUID, Appointment> changed = new LinkedHashMap<>();
        List<Appointment> left = new ArrayList<>();
        for (Appointment a : written) {
            if (shown.test(a)) {
                changed.put(a.id(), a);
            } else {
                left.add(a);
            }
        }
        List<UUID> removed;
        if (c.seesAll()) {
            removed = left.stream().map(Appointment::id).toList();
        } else {
            Set<UUID> givenAway = appointments.reassignedFrom(left.stream()
                    .filter(a -> !a.staffId().equals(c.staffId())).map(Appointment::id).toList(), c.staffId());
            removed = left.stream().filter(a -> a.staffId().equals(c.staffId()) || givenAway.contains(a.id()))
                    .map(Appointment::id).toList();
        }
        return Optional.of(new Delta(changed, removed));
    }

    /** Whether a delta from this cursor can be trusted; otherwise the whole answer goes. */
    private static boolean deltaPossible(DayCursor from, SalonVersionStore.Board board, LocalDate day, Instant now) {
        return from.date().equals(day)   // another date's board (or yesterday's "today", or upcoming list)
                && from.setupVersion() == board.setupVersion()
                && from.oldestRunningXid() <= board.oldestRunningXid()   // never ahead of the database: unknown
                && from.version() <= board.version()
                && !from.at().isAfter(now)
                && Duration.between(from.at(), now).compareTo(DELTA_MAX_AGE) <= 0;
    }

    /** On the board this caller sees for [from, to): that day, not a removed block, and his own if he sees one column. */
    private static boolean onBoard(Appointment a, Caller c, Instant from, Instant to) {
        return a.status() != AppointmentStatus.REMOVED && a.startsAt().isBefore(to) && a.endsAt().isAfter(from)
                && (c.seesAll() || a.staffId().equals(c.staffId()));
    }

    /** On the upcoming list (as AppointmentStore.upcomingInSalon reads it): a waiting booking not over yet. */
    private static boolean onUpcoming(Appointment a, UUID onlyStaff, Instant now, Instant until) {
        return a.kind() != AppointmentKind.BLOCK && a.status() == AppointmentStatus.CONFIRMED
                && a.endsAt().isAfter(now) && a.startsAt().isBefore(until)
                && (onlyStaff == null || a.staffId().equals(onlyStaff));
    }

    private static String cursor(boolean upcoming, LocalDate day, SalonVersionStore.Board board, Instant now,
                                 Instant validUntil) {
        return new DayCursor(upcoming, day, board.oldestRunningXid(), board.setupVersion(), board.version(), now,
                validUntil).text();
    }

    /** A whole day board and the appointments on it. */
    private record Built(DayView view, List<Appointment> all) {
    }

    private Built wholeDay(Caller c, LocalDate day, Instant now, SalonVersionStore.Board board) {
        Instant from = Availability.dayStart(day);
        Instant to = Availability.dayStart(day.plusDays(1));
        List<Appointment> all;
        List<SalonStaff> barbers;
        if (c.seesAll()) {
            all = appointments.forDay(c.shopId(), from, to);
            // Active barbers, plus any switched-off barber who had appointments that day (history stays visible)
            List<SalonStaff> active = staff.findByShopIdAndActiveTrueOrderByCreatedAtAscIdAsc(c.shopId());
            Set<UUID> activeIds = active.stream().map(SalonStaff::getId).collect(Collectors.toSet());
            Set<UUID> gone = all.stream().map(Appointment::staffId).filter(id -> !activeIds.contains(id))
                    .collect(Collectors.toSet());
            barbers = gone.isEmpty() ? active : java.util.stream.Stream.concat(active.stream(),
                            staff.findAllById(gone).stream())
                    .sorted(Comparator.comparing(SalonStaff::getCreatedAt).thenComparing(SalonStaff::getId))
                    .toList();
        } else {
            // His own column only: the other barbers' rows are never read (SalonAccess: he is active)
            all = appointments.forDay(c.shopId(), c.staffId(), from, to);
            barbers = staff.findById(c.staffId()).stream().toList();
        }
        Set<UUID> shown = barbers.stream().map(SalonStaff::getId).collect(Collectors.toSet());
        Map<UUID, List<BookedService>> services = appointments.services(all.stream().map(Appointment::id).toList());
        Map<UUID, Person> customers = users.find(all.stream().map(Appointment::customerId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet()));
        Map<UUID, List<StaffShift>> weeks = shifts.findByStaffIdIn(shown).stream()
                .collect(Collectors.groupingBy(StaffShift::getStaffId));
        List<BarberColumn> columns = barbers.stream().map(b -> new BarberColumn(b.getId(), b.getName(),
                Availability.working(weeks.getOrDefault(b.getId(), List.of()).stream().map(StaffShift::shift)
                        .toList(), day).stream().map(i -> hours(i, day)).toList(),
                all.stream().filter(a -> a.staffId().equals(b.getId()))
                        .map(a -> view(a, services.getOrDefault(a.id(), List.of()), customers, now, c))
                        .toList()))
                .toList();
        // Until when time alone can't change it: the first start still to come of a booking waiting on it
        Instant validUntil = all.stream().filter(a -> a.status() == AppointmentStatus.CONFIRMED)
                .map(Appointment::startsAt).filter(t -> t.isAfter(now)).min(Comparator.naturalOrder()).orElse(to);
        return new Built(new DayView(day, columns, c.staffId(), true, cursor(false, day, board, now, validUntil),
                board.setupVersion(),
                null, null), all);
    }

    /** What the day screen's tag says about the request: the date asked for (none = today). */
    public static String dayParams(LocalDate date) {
        return "date=" + (date == null ? "" : date.toString());
    }

    /**
     * An answer and its ETag (null: no tag of our own).
     */
    public record Tagged<T>(T body, String etag) {
    }

    /**
     * The first moment after now that the answer changes by time alone: the earliest of these moments still to come
     * (a start, an end, midnight), at most a day ahead.
     */
    private static Instant earliest(Instant now, java.util.stream.Stream<Instant> moments, Instant also) {
        Instant first = now.plus(Duration.ofDays(1));
        if (also != null && also.isAfter(now) && also.isBefore(first)) {
            first = also;
        }
        for (Instant m : (Iterable<Instant>) moments::iterator) {
            if (m.isAfter(now) && m.isBefore(first)) {
                first = m;
            }
        }
        return first;
    }

    // ---------- walk-ins ----------

    /**
     * For the chosen services, one suggestion per barber who does them all: free now, or (for information: a walk-in
     * always starts now) when he is next free today (exact minutes). Free-now barbers first (least booked first), then by earliest start; barbers who can't fit
     * it in today last.
     */
    @Transactional(readOnly = true)
    public List<WalkInOption> walkInOptions(UUID userId, List<UUID> serviceIds) {
        Caller c = access.walkIns(userId);
        Instant now = now();
        // The barbers, what they do and their weeks from memory while the setup is unchanged (SalonSetups)
        SalonSetups.Setup setup = setups.of(c.shopId());
        List<BookedService> chosen = calendar.chosenServices(setup, serviceIds);
        Duration length = SalonCalendar.length(chosen);
        List<SalonSetups.Barber> barbers = calendar.barbersFor(setup, ids(chosen));
        Map<UUID, SalonSetups.Barber> byId = barbers.stream()
                .collect(Collectors.toMap(SalonSetups.Barber::id, Function.identity()));
        LocalDate today = today(now);
        Instant dayEnd = Availability.dayStart(today.plusDays(1));
        List<BarberDay> days = calendar.setupDays(barbers, today, null);
        List<WalkInOption> options = new ArrayList<>();
        for (BarberDay d : days) {
            Optional<WalkInSlot> slot = Availability.walkIn(d, length, now, dayEnd);
            SalonSetups.Barber b = byId.get(d.staffId());
            options.add(new WalkInOption(b.id(), b.name(), slot.map(WalkInSlot::freeNow).orElse(false),
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
     * Every active service's price for a walk-in starting now (owner and people with walk-in access): with the salon's
     * offer where one applies now, as {@link #addWalkIn} prices it. The walk-in screen reads it again every minute.
     */
    @Transactional(readOnly = true)
    public List<WalkInPrice> walkInPrices(UUID userId) {
        Caller c = access.walkIns(userId);
        Instant start = now().truncatedTo(ChronoUnit.MINUTES);
        SalonSetups.Setup setup = setups.of(c.shopId());
        List<SalonOffer> offers = setup.offersFrom(today(start));
        Map<UUID, SalonOffer> byId = offers.stream().collect(Collectors.toMap(SalonOffer::id, o -> o));
        return SalonOffer.price(setup.services(), offers, start)
                .stream()
                .map(s -> new WalkInPrice(s.serviceId(), s.pricePaise(),
                        s.priceIsFrom(), s.listPricePaise(), s.offerId() == null ? null : until(byId.get(s.offerId()))))
                .toList();
    }

    /** When the offer's hours end today, as "14:00" (India time); null when it runs all day. */
    private static String until(SalonOffer o) {
        if (o == null || o.toMin() == null || o.toMin() >= SalonTime.END_OF_DAY) {
            return null;
        }
        return String.format(Locale.ROOT, "%02d:%02d", o.toMin() / 60, o.toMin() % 60);
    }

    /**
     * Adds a walk-in with the chosen barber, starting now (IN_SERVICE at once; the customer is in the chair, so no
     * code). Walk-ins are never booked for later. Priced like a booking starting now: the salon's offer applies when
     * it covers this moment and the service (its price kept on the walk-in whatever happens to the offer later). When
     * the request says the total it showed and the price now differs: 409 PRICE_CHANGED, nothing added. If he isn't
     * free now (taken in between): 409 BARBER_BUSY with his next free start. The same Idempotency-Key again returns
     * the walk-in already added.
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
        // The shop's row held in shared mode until the walk-in is saved, as a booking holds it (ShopDirectory
        // .findForBooking): publishing or ending an offer (holdAgainstBookings) waits for it, and one that committed
        // first is in the setup read below, so a walk-in is never priced with an offer that just ended
        if (shops.findForBooking(c.shopId()).isEmpty()) {
            throw staffNotFound();
        }
        // From the setup in memory (SalonSetups), with the same checks; once his row is locked below, that he is still
        // active, and (if the setup changed meanwhile) that he still does these services, against the database
        SalonSetups.Setup setup = setups.of(c.shopId());
        Instant start = now.truncatedTo(ChronoUnit.MINUTES);
        List<BookedService> chosen = SalonOffer.price(
                calendar.chosenServices(setup, in.serviceIds()), setup.offersFrom(today(start)), start);
        long total = chosen.stream().mapToLong(BookedService::pricePaise).sum();
        if (in.expectedPricePaise() != null && in.expectedPricePaise() != total) {
            // An offer started or ended (or a price was edited) after the screen read the prices: nothing is added
            throw new ApiException(HttpStatus.CONFLICT, "PRICE_CHANGED",
                    "The price changed just now. Check the new total with the customer, then add again.",
                    Map.of("pricePaise", total));
        }
        Duration length = SalonCalendar.length(chosen);
        SalonSetups.Barber barber = setup.barber(in.staffId()).orElseThrow(SalonDayService::staffNotFound);
        if (calendar.barbersFor(setup, ids(chosen)).stream().noneMatch(b -> b.id().equals(barber.id()))) {
            throw SalonCalendar.invalid("staffId", "this barber doesn't do all of the chosen services");
        }
        String guest = in.name() == null || in.name().isBlank() ? null : clean(GUEST_NAME, in.name());
        Appointment a = new Appointment(UUID.randomUUID(), c.shopId(), barber.id(), AppointmentKind.WALK_IN, null,
                null, null, null, guest, null, start, start.plus(length), AppointmentStatus.IN_SERVICE, now,
                null, null, c.userId(), requestId, now, now);
        if (!appointments.lockActiveBarber(barber.id())) {
            throw staffNotFound();
        }
        if (!calendar.stillFits(c.shopId(), setup.version(), barber.id(), ids(chosen), null)) {
            throw setupChanged();
        }
        if (!appointments.insertIfFree(a)) {
            BarberDay day = calendar.setupDays(List.of(barber), today(now), null).get(0);
            Optional<WalkInSlot> next = Availability.walkIn(day, length, now,
                    Availability.dayStart(today(now).plusDays(1)));
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("freeNow", false);
            details.put("freeAt", next.map(s -> (Object) s.start().toString()).orElse("not today"));
            throw new ApiException(HttpStatus.CONFLICT, "BARBER_BUSY",
                    barber.name() + " isn't free right now. Pick a barber who is free now.", details);
        }
        appointments.insertServices(a.id(), chosen);
        appointments.event(a.id(), now, c.userId(), "WALK_IN_STARTED", null);
        return salonView(c, a);
    }

    /**
     * Everything still to come, soonest first, across days: online bookings that are CONFIRMED and not over yet (so
     * a customer running late is still on it, until the automatic no-show). Owner and helpers see the whole
     * salon; a barber sees his own unless he has walk-in access. One page of UPCOMING_PAGE after the cursor (null:
     * the first page), with its ETag (see day()).
     */
    @Transactional(readOnly = true)
    public Tagged<List<AppointmentView>> upcoming(CurrentUser me, AppointmentCursor after) {
        Optional<SalonEtags.Version> version = etags.version(me);
        Caller c = access.caller(me.userId());
        Instant now = now();
        Instant until = Availability.dayStart(today(now).plusDays(UPCOMING_DAYS + 1L));
        List<Appointment> page = appointments.upcomingInSalon(c.shopId(), c.seesAll() ? null : c.staffId(), now,
                until, after, UPCOMING_PAGE);
        // It changes by time alone when one of these starts (notStarted) or ends (it leaves the list), and at
        // midnight (the 31-day bound moves)
        Instant validUntil = earliest(now, page.stream().flatMap(a -> java.util.stream.Stream.of(a.startsAt(),
                a.endsAt())), Availability.dayStart(today(now).plusDays(1)));
        return new Tagged<>(views(c, page), version.flatMap(v -> etags.tag(v, c, SalonEtags.UPCOMING,
                upcomingParams(after), validUntil)).orElse(null));
    }

    /** What the upcoming list's tag says about the request: the page asked for. */
    public static String upcomingParams(AppointmentCursor after) {
        return after == null ? "" : "after=" + after.afterStart() + "/" + after.afterId();
    }

    /**
     * The owner's history: bookings and walk-ins on the India dates from..to (at most 31 days), latest first, a page
     * of HISTORY_PAGE after the cursor (null: the first page), with how many ended in each status and what the
     * completed ones were worth at list price (over the whole range, on the first page only). Owner or helper.
     */
    @Transactional(readOnly = true)
    public HistoryView history(UUID userId, LocalDate from, LocalDate to, AppointmentCursor after) {
        Caller c = access.ownerOrHelper(userId);
        if (to.isBefore(from)) {
            throw SalonCalendar.invalid("to", "must be on or after from");
        }
        if (from.plusDays(HISTORY_MAX_DAYS).isBefore(to.plusDays(1))) {
            throw SalonCalendar.invalid("to", "is too far from from (at most " + HISTORY_MAX_DAYS + " days)");
        }
        Instant start = Availability.dayStart(from);
        Instant end = Availability.dayStart(to.plusDays(1));
        List<Appointment> list = appointments.history(c.shopId(), start, end, after, HISTORY_PAGE + 1);
        boolean more = list.size() > HISTORY_PAGE;
        List<Appointment> shown = more ? list.subList(0, HISTORY_PAGE) : list;
        AppointmentCursor next = more ? AppointmentCursor.after(shown.get(HISTORY_PAGE - 1)) : null;
        // Counts and the amount over the whole range, on the first page only: they don't change from page to page,
        // and each is a scan of the whole range (a busy salon has 60,000 appointments in 31 days). Later pages carry
        // null for both; the app keeps the first page's.
        boolean first = after == null;
        return new HistoryView(from, to, first ? appointments.historyCounts(c.shopId(), start, end) : null,
                first ? appointments.completedPaise(c.shopId(), start, end) : null, views(c, shown), more, next);
    }

    // ---------- start, no-show, reassign, cancel ----------

    /**
     * CONFIRMED → IN_SERVICE, from 15 minutes before the start until 10 minutes after it (then it is a no-show).
     * An online booking needs the customer's start code, or withoutCode (owner or helper only). A barber can start only his own appointments.
     */
    @Transactional
    public AppointmentView start(UUID userId, UUID appointmentId, Start in) {
        Caller c = access.caller(userId);
        Appointment a = lockedFor(c, appointmentId);
        Instant now = now();
        requireStatus(a, AppointmentStatus.CONFIRMED);
        Instant startFrom = a.startsAt().minus(EARLY_START);
        if (now.isBefore(startFrom)) {
            throw wrongStatus("Too early: Start is allowed from 15 minutes before " + clockTime(a.startsAt()) + ".",
                    Map.of("reason", "TOO_EARLY", "startFrom", startFrom.toString()));
        }
        Instant heldUntil = a.startsAt().plus(LATE_ALLOWANCE);
        if (!now.isBefore(heldUntil)) {
            throw wrongStatus("It was held until " + clockTime(heldUntil) + " and is a no-show now. Add the customer "
                    + "as a walk-in.", Map.of("reason", "HELD_OVER", "heldUntil", heldUntil.toString()));
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
        // The customer is in the chair from now: the barber is busy from now for the visit's length. Started early,
        // the booking moves earlier. Started late, it runs later when the barber is free for that (else it keeps its
        // planned end, so the next booking isn't pushed: the barber hurries or reassigns).
        Instant minute = now.truncatedTo(ChronoUnit.MINUTES);
        Duration length = Duration.between(a.startsAt(), a.endsAt());
        Instant from = a.startsAt();
        Instant to = a.endsAt();
        boolean early = minute.isBefore(from);
        if (early) {
            from = minute;
            to = minute.plus(length);
        } else if (minute.isAfter(from)) {
            Instant later = minute.plus(length);
            SalonStaff barber = staff.findById(a.staffId()).orElseThrow();
            if (calendar.day(barber, LocalDate.ofInstant(from, SalonTime.ZONE), a.id()).isFree(new Interval(from, later))) {
                to = later;
            }
        }
        // Read before the UPDATE: a refused UPDATE aborts the transaction, and no query can run after it
        String barberName = barberName(a.staffId());
        boolean started;
        try {
            started = appointments.start(a.id(), withCode, from, to, now);
        } catch (DataIntegrityViolationException e) {
            if (hasSqlState(e, OVERLAP) && !early) {
                // A late start whose longer time was just taken by someone else: the next try keeps the planned end
                throw tryAgain();
            }
            if (hasSqlState(e, OVERLAP)) {
                // Someone (or a break) still holds his chair before this booking's time
                throw new ApiException(HttpStatus.CONFLICT, "BARBER_BUSY",
                        barberName + " is busy (another customer or a break) until " + clockTime(a.startsAt())
                                + ". Start this at " + clockTime(a.startsAt()) + ", or finish the one before first.",
                        Map.of("reason", "STILL_BUSY", "startAt", a.startsAt().toString()));
            }
            throw e;
        } catch (PessimisticLockingFailureException e) {
            throw tryAgain();
        }
        if (!started) {
            throw changed();
        }
        List<String> notes = new ArrayList<>();
        if (Boolean.FALSE.equals(withCode)) {
            notes.add("without code");
        }
        if (early) {
            notes.add("early");
        }
        appointments.event(a.id(), now, c.userId(), "STARTED", notes.isEmpty() ? null : String.join(", ", notes));
        notices.closed(a);
        return salonView(c, appointments.find(a.id()).orElseThrow());
    }

    /**
     * IN_SERVICE → COMPLETED now: the customer is done before the planned end, so the rest of the barber's time is
     * free for the next walk-in or booking. Anyone who may act on the barber's appointments.
     */
    @Transactional
    public AppointmentView finish(UUID userId, UUID appointmentId) {
        Caller c = access.caller(userId);
        // As for reassign and cancel, whoever added a walk-in still in the chair may finish it too
        Appointment a = c.ownerOrHelper() ? lockedFor(c, appointmentId) : lockedForOwnOrAdded(c, appointmentId);
        requireStatus(a, AppointmentStatus.IN_SERVICE);
        Instant now = now();
        // Never before its own start (the database wants a time range that isn't empty)
        Instant end = now.isAfter(a.startsAt()) ? now : a.startsAt().plus(Duration.ofMinutes(1));
        if (end.isAfter(a.endsAt())) {
            end = a.endsAt();
        }
        if (!appointments.finish(a.id(), end, now)) {
            throw changed();
        }
        appointments.event(a.id(), now, c.userId(), "FINISHED", null);
        followUps.addFinished(a.id(), now);   // an online booking: its rating request and come-back reminder
        return salonView(c, appointments.find(a.id()).orElseThrow());
    }

    /**
     * CONFIRMED → NO_SHOW, from 10 minutes after the start (the automatic no-show does the same within a minute).
     * Frees the barber's time at once; the customer gets a strike and the same "missed booking" message.
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
                    Map.of("reason", "TOO_EARLY", "noShowFrom", from.toString()));
        }
        if (!appointments.close(a.id(), AppointmentStatus.NO_SHOW, null, now)) {
            throw changed();
        }
        if (a.isBooking()) {
            // Dated at the booking's start, not when staff pressed the button (it may be pressed late)
            strikes.add(a.customerId(), a.id(), StrikeKind.NO_SHOW, a.startsAt());
            notices.noShow(a, salonCard(c.shopId()), now);
        }
        appointments.event(a.id(), now, c.userId(), "NO_SHOW", null);
        return salonView(c, appointments.find(a.id()).orElseThrow());
    }

    /**
     * Undo a No-show (owner or helper, within 24 hours of the start): the customer's strike is taken back, the booking
     * is closed as cancelled by the salon ("No-show undone"), and the customer is told the missed booking no longer
     * counts. It never waits again: its 10 minutes to start are over by the time it can be a no-show.
     */
    @Transactional
    public AppointmentView undoNoShow(UUID userId, UUID appointmentId) {
        Caller c = access.ownerOrHelper(userId);
        Appointment a = lockedFor(c, appointmentId);
        Instant now = now();
        requireStatus(a, AppointmentStatus.NO_SHOW);
        if (now.isAfter(a.startsAt().plus(UNDO_NO_SHOW_WITHIN))) {
            throw wrongStatus("Too late to undo here (more than 24 hours). Clozest support can still remove the "
                    + "customer's strike.", Map.of("reason", "TOO_LATE"));
        }
        if (!appointments.undoNoShow(a.id(), AppointmentStatus.CANCELLED_BY_SALON, "No-show undone", now)) {
            throw changed();
        }
        boolean hadStrike = a.isBooking() && strikes.remove(a.id());
        appointments.event(a.id(), now, c.userId(), "NO_SHOW_UNDONE", hadStrike ? "strike removed" : null);
        if (a.isBooking() && users.isActive(a.customerId())) {
            // The "Missed booking" message they got is taken back (not for an account deleted since)
            notices.noShowUndone(a, salonCard(c.shopId()), now);
        }
        return salonView(c, appointments.find(a.id()).orElseThrow());
    }

    /**
     * The automatic no-show (AutoEndJob, every minute): a booking still not started 10 minutes
     * after its start becomes NO_SHOW, which frees the barber's time. Start needs the customer's code (or the
     * owner's "without code"), so a customer being served is always started by then. An online booking gives the
     * customer a strike, dated at its start, and a message saying so. Safe on two servers at once: each row is
     * changed by one UPDATE only. One batch of at most `batch` bookings per call, in its own transaction (the job
     * calls again while a batch comes back full), so a busy minute never makes one huge transaction.
     * @return how many became no-shows in this batch
     */
    @Transactional
    public int markNoShows(Instant now, int batch) {
        List<Appointment> missed = appointments.markNoShows(now.minus(LATE_ALLOWANCE), now, batch);
        Instant recent = now.minus(Duration.ofDays(1));
        // The whole batch in a few statements: history rows, strikes and messages each written together
        appointments.eventAll(missed.stream().map(Appointment::id).toList(), now, null, "NO_SHOW", "automatic");
        // A booking from more than a day ago (left from before the automatic no-show existed, or the job was
        // stopped for a day) is closed quietly: no strike or message that late
        List<Appointment> struck = missed.stream().filter(a -> a.isBooking() && a.startsAt().isAfter(recent))
                .toList();
        strikes.addAll(struck, StrikeKind.NO_SHOW);
        Map<UUID, ShopCard> salons = new HashMap<>();   // once per salon in the batch
        notices.noShows(struck, shopId -> salons.computeIfAbsent(shopId, this::salonCard), now);
        return missed.size();
    }

    /**
     * Gives an appointment to another barber who does its services. Owner, helper, or whoever added a walk-in still in the chair. A CONFIRMED one keeps its time;
     * a walk-in already in the chair (added to the wrong barber) continues with him from now for the rest of its time
     * (at least 5 minutes), keeping when it was first put in a chair. The
     * barbers it moved between are told (and the customer, for an online booking).
     */
    @Transactional
    public AppointmentView reassign(UUID userId, UUID appointmentId, Reassign in) {
        Caller c = access.caller(userId);
        Appointment a = lockedForFixing(c, appointmentId);
        boolean walkInInChair = a.kind() == AppointmentKind.WALK_IN && a.status() == AppointmentStatus.IN_SERVICE;
        if (!walkInInChair) {
            requireStatus(a, AppointmentStatus.CONFIRMED);
        }
        if (a.staffId().equals(in.staffId())) {
            return salonView(c, a);
        }
        SalonSetups.Setup setup = setups.of(c.shopId());
        SalonSetups.Barber target = setup.barber(in.staffId()).orElseThrow(SalonDayService::staffNotFound);
        List<UUID> serviceIds = ids(appointments.services(List.of(a.id())).getOrDefault(a.id(), List.of()));
        if (calendar.barbersFor(setup, serviceIds).stream().noneMatch(b -> b.id().equals(target.id()))) {
            throw SalonCalendar.invalid("staffId", "this barber doesn't do all of its services");
        }
        Instant now = now();
        Instant start = a.startsAt();
        Instant end = a.endsAt();
        if (walkInInChair) {
            // The rest of the visit, with him, from now (at least 5 minutes)
            start = now.truncatedTo(ChronoUnit.MINUTES);
            Duration left = Duration.between(start, a.endsAt());
            end = start.plus(left.compareTo(MIN_LEFT) < 0 ? MIN_LEFT : left);
        }
        if (!calendar.setupDays(List.of(target), LocalDate.ofInstant(start, SalonTime.ZONE), a.id()).get(0)
                .isFree(new Interval(start, end))) {
            throw barberBusy(target.name());
        }
        if (!appointments.lockActiveBarber(target.id())) {
            throw staffNotFound();
        }
        // New hours or services saved for him meanwhile (under the lock we now hold) are checked here
        if (!calendar.stillFits(c.shopId(), setup.version(), target.id(), serviceIds, new Interval(start, end))) {
            throw setupChanged();
        }
        if (walkInInChair) {
            moveWalkInOrBusy(a, target, start, end, now);
        } else {
            moveOrBusy(a, target, start, end, now);
        }
        appointments.event(a.id(), now, c.userId(), "REASSIGNED", a.staffId() + " -> " + target.id());
        Appointment moved = appointments.find(a.id()).orElseThrow();
        notices.reassigned(a, moved, salonCard(c.shopId()), c.userId(), now);
        return salonView(c, moved);
    }

    /**
     * The salon cancels a CONFIRMED booking or walk-in, or a walk-in already in the chair (added by mistake), with a
     * reason. An online booking's customer gets the reason; the barber is told. No strike.
     */
    @Transactional
    public AppointmentView cancel(UUID userId, UUID appointmentId, SalonCancel in) {
        Caller c = access.caller(userId);
        Appointment a = lockedForFixing(c, appointmentId);
        boolean walkInInChair = a.kind() == AppointmentKind.WALK_IN && a.status() == AppointmentStatus.IN_SERVICE;
        if (!walkInInChair) {
            requireStatus(a, AppointmentStatus.CONFIRMED);
        }
        String reason = clean(REASON, in.reason());
        Instant now = now();
        // From when it was first put in a chair (started_at), which a move to another barber doesn't reset
        Instant inChairSince = a.startedAt() != null ? a.startedAt() : a.startsAt();
        if (walkInInChair && now.isAfter(inChairSince.plus(MISTAKE_WINDOW))) {
            // Served for a while: it happened, so it stays in the day's takings; Finish ends it
            throw wrongStatus("It has been in the chair for more than " + MISTAKE_WINDOW.toMinutes()
                    + " minutes. Finish it instead.", Map.of("reason", "IN_CHAIR_TOO_LONG"));
        }
        boolean closed = walkInInChair
                ? appointments.cancelWalkInInService(a.id(), reason, now)
                : appointments.close(a.id(), AppointmentStatus.CANCELLED_BY_SALON, reason, now);
        if (!closed) {
            throw changed();
        }
        if (a.isBooking()) {
            notices.cancelledBySalon(a, salonCard(c.shopId()), reason, now);
            notices.barberToldOfSalonCancel(a, reason, c.userId(), now);
        } else if (walkInInChair) {
            notices.walkInCancelled(a, reason, c.userId(), now);   // the owner hears of it (it leaves the takings)
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
        boolean own = !c.ownerOrHelper();   // a barber's break for himself
        Interval time = range(in.startAt().truncatedTo(ChronoUnit.MINUTES), in.endAt().truncatedTo(ChronoUnit.MINUTES),
                in.type() == BlockType.LEAVE ? MAX_LEAVE : own ? MAX_OWN_BREAK : MAX_BREAK);
        if (!time.end().isAfter(now)) {
            throw SalonCalendar.invalid("endAt", "is in the past");
        }
        if (own) {
            if (!time.start().isBefore(Availability.dayStart(today(now).plusDays(2)))) {
                throw SalonCalendar.invalid("startAt", "must be today or tomorrow for your own break; ask the owner "
                        + "for time off further ahead");
            }
            // At most 4 hours of his own breaks a day. His requests run one after the other (the same per-person
            // lock his bookings use), so two adds at once can't both fit under it
            appointments.lockCustomer(c.userId());
            LocalDate day = LocalDate.ofInstant(time.start(), SalonTime.ZONE);
            long used = appointments.ownBreakMinutes(barber.getId(), c.userId(), Availability.dayStart(day),
                    Availability.dayStart(day.plusDays(1)));
            if (used + Duration.between(time.start(), time.end()).toMinutes() > MAX_OWN_BREAK.toMinutes()) {
                throw SalonCalendar.invalid("endAt", "is past your 4 hours of breaks for that day ("
                        + used + " minutes used); ask the owner for longer");
            }
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
        if (!c.ownerOrHelper() && !c.userId().equals(a.createdBy())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_ALLOWED",
                    "The owner or a helper added this break; ask them to remove it.");
        }
        Instant now = now();
        if (!c.ownerOrHelper() && !now.isBefore(a.startsAt())) {
            // Taken (or being taken): it stays on the record, and it keeps counting towards his 4 hours that day
            throw wrongStatus("This break has started; it stays. Ask the owner if it was a mistake.",
                    Map.of("reason", "STARTED"));
        }
        if (!appointments.removeBlock(a.id(), now)) {
            throw wrongStatus("It was already removed.");
        }
        appointments.event(a.id(), now, c.userId(), "BLOCK_REMOVED", null);
    }

    // ---------- closed days ----------

    /**
     * Closes the salon for a whole day (a festival holiday): the salon's closed day, and one CLOSED block per active
     * barber sharing its id, all or nothing. Works before any barber exists (during registration); barbers added or
     * switched on later get it too (SalonSetupService.joinClosedDays). Anything already on that day (bookings,
     * walk-ins, breaks): 409 BLOCK_CONFLICTS listing it. The day already closed: 409 ALREADY_CLOSED.
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
        Interval day = new Interval(Availability.dayStart(in.date()), Availability.dayStart(in.date().plusDays(1)));
        // The day first: an already closed one would otherwise show up below as its own blocks in the way
        UUID closureId = UUID.randomUUID();
        if (!appointments.addClosedDay(closureId, c.shopId(), in.date(), day.start(), day.end(), c.userId(), now)) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_CLOSED", "The salon is already closed that day.");
        }
        // Blocks an older server copy wrote for this day without its row join it (and aren't "in the way")
        Set<UUID> covered = appointments.adoptClosedBlocks(c.shopId(), closureId, day.start(), day.end(), now);
        barbers = barbers.stream().filter(b -> !covered.contains(b.getId())).toList();
        List<UUID> ids = barbers.stream().map(SalonStaff::getId).toList();
        List<Appointment> inTheWay = ids.isEmpty() ? List.of()
                : appointments.holding(ids, day.start(), day.end(), null);
        if (!inTheWay.isEmpty()) {
            throw blockConflicts(inTheWay);   // rolls the day back too
        }
        for (SalonStaff b : barbers) {
            Appointment block = block(c, b.getId(), BlockType.CLOSED, closureId, day, now);
            if (!appointments.insertIfFree(block)) {   // something was added in between: undo all of it
                throw blockConflicts(appointments.holding(ids, day.start(), day.end(), null));
            }
            appointments.event(block.id(), now, c.userId(), "CLOSED_DAY_ADDED", in.date().toString());
        }
        return new ClosureView(closureId, in.date(), barbers.size() + covered.size());
    }

    /** Upcoming closed days. */
    @Transactional(readOnly = true)
    public List<ClosureView> closures(UUID userId) {
        Caller c = access.caller(userId);
        // Grouped in the database: one row per closed day, not one per barber
        return appointments.closures(c.shopId(), now()).stream()
                .map(d -> new ClosureView(d.closureId(), LocalDate.ofInstant(d.startsAt(), SalonTime.ZONE),
                        d.barbers()))
                .toList();
    }

    /** Opens a closed day again (owner). */
    @Transactional
    public void removeClosure(UUID userId, UUID closureId) {
        Caller c = access.owner(userId);
        appointments.lockSalonCalendar(c.shopId());   // a barber joining this closed day right now can't be missed
        Instant now = now();
        boolean day = appointments.removeClosedDay(c.shopId(), closureId, now);
        // Blocks without a day row too (closed by an older server copy during a deploy): they still go
        List<UUID> removed = appointments.removeClosure(c.shopId(), closureId, now);
        if (!day && removed.isEmpty()) {
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
        // The shop's row against bookings first: one in progress (it holds the row FOR SHARE and read "not paused")
        // finishes before the pause, and one after it reads the pause
        shops.holdAgainstBookings(c.shopId());
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
        java.util.function.Predicate<Appointment> ours = a -> a.shopId().equals(c.shopId())
                && a.kind() != AppointmentKind.BLOCK;
        java.util.function.Predicate<Appointment> mine = a -> ours.test(a) && c.actsFor(a.staffId());
        // Checked before locking too, so nobody can lock another salon's rows by guessing ids
        Optional<Appointment> seen = appointments.find(id).filter(ours);
        if (seen.isEmpty()) {
            throw notFound();
        }
        if (!mine.test(seen.get())) {
            throw notMine(c, id);
        }
        Appointment a = appointments.lock(id).filter(ours).orElseThrow(SalonDayService::notFound);
        if (!mine.test(a)) {
            throw notMine(c, id);
        }
        return a;
    }

    /**
     * For reassign and cancel: the owner and helpers, any appointment; anyone else (a barber with walk-in access), only
     * a walk-in he added himself and that is still in the chair, so a walk-in put on the wrong barber can be fixed by
     * whoever added it. Otherwise 403 (his own column) or 404 (another barber's).
     */
    private Appointment lockedForFixing(Caller c, UUID id) {
        if (c.ownerOrHelper()) {
            return lockedFor(c, id);
        }
        Appointment a = lockedForOwnOrAdded(c, id);
        if (!addedInChairBy(a, c)) {
            throw SalonAccess.notAllowed();   // his own column, but not a walk-in he added that is still in the chair
        }
        return a;
    }

    /** A barber's own appointment, or a walk-in he added that is still in the chair (on any column). */
    private Appointment lockedForOwnOrAdded(Caller c, UUID id) {
        Optional<Appointment> seen = appointments.find(id)
                .filter(a -> a.shopId().equals(c.shopId()) && a.kind() != AppointmentKind.BLOCK);
        if (seen.isPresent() && addedInChairBy(seen.get(), c)) {
            Appointment a = appointments.lock(id).orElseThrow(SalonDayService::notFound);
            if (addedInChairBy(a, c)) {
                return a;
            }
        }
        return lockedFor(c, id);   // his own; 404 / REASSIGNED for another barber's
    }

    /** A walk-in he added, still in the chair, while he still has walk-in access. */
    private static boolean addedInChairBy(Appointment a, Caller c) {
        return c.walkIns() && a.kind() == AppointmentKind.WALK_IN && a.status() == AppointmentStatus.IN_SERVICE
                && c.userId().equals(a.createdBy());
    }

    /**
     * Another barber's appointment: 404 as if it didn't exist, unless it was this barber's and was just given to
     * someone else (then he is told so, rather than "no such appointment" while it is on his screen).
     */
    private ApiException notMine(Caller c, UUID id) {
        if (c.staffId() != null && appointments.wasReassignedFrom(id, c.staffId())) {
            return new ApiException(HttpStatus.CONFLICT, "WRONG_STATUS", "It was given to another barber. Reload.",
                    Map.of("reason", "REASSIGNED"));
        }
        return notFound();
    }

    /** Like moveOrBusy, for a walk-in already in the chair. */
    private void moveWalkInOrBusy(Appointment a, SalonSetups.Barber target, Instant start, Instant end,
                                  Instant now) {
        try {
            if (!appointments.moveWalkInInService(a.id(), target.id(), start, end, now)) {
                throw changed();
            }
        } catch (DataIntegrityViolationException e) {
            if (hasSqlState(e, OVERLAP)) {
                throw barberBusy(target.name());
            }
            throw e;
        } catch (PessimisticLockingFailureException e) {
            throw tryAgain();
        }
    }

    private String barberName(UUID staffId) {
        return staff.findById(staffId).map(SalonStaff::getName).orElse("The barber");
    }

    private SalonStaff activeBarber(Caller c, UUID staffId) {
        return staff.findByIdAndShopId(staffId, c.shopId()).filter(SalonStaff::isActive)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND", "No such barber."));
    }

    /**
     * Moves the appointment; if the database's no-overlap rule refuses it (someone took the time in between),
     * 409 BARBER_BUSY and the whole change is undone.
     */
    private void moveOrBusy(Appointment a, SalonSetups.Barber target, Instant start, Instant end, Instant now) {
        try {
            if (!appointments.move(a.id(), target.id(), start, end, now)) {
                throw changed();
            }
        } catch (DataIntegrityViolationException e) {
            if (hasSqlState(e, OVERLAP)) {
                throw barberBusy(target.name());
            }
            throw e;
        } catch (PessimisticLockingFailureException e) {
            throw tryAgain();   // two moves into each other's time at once: the database broke the tie
        }
    }

    private Appointment block(Caller c, UUID staffId, BlockType type, UUID closureId, Interval time, Instant now) {
        return new Appointment(UUID.randomUUID(), c.shopId(), staffId, AppointmentKind.BLOCK, type, closureId,
                null, null, null, null, time.start(), time.end(), AppointmentStatus.BLOCKED, null, null, null,
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
        return list.stream().map(a -> view(a, services.getOrDefault(a.id(), List.of()), customers, now, c))
                .toList();
    }

    /**
     * As the caller sees it. The customer's phone, so the salon can call someone running late, goes to the owner and
     * helpers only, never barbers.
     */
    private static AppointmentView view(Appointment a, List<BookedService> services, Map<UUID, Person> customers,
                                        Instant now, Caller c) {
        boolean showPhone = c.ownerOrHelper();
        String name = null;
        String phone = null;
        if (a.customerId() != null) {
            Person p = customers.get(a.customerId());
            name = p == null || p.name() == null ? "Customer" : p.name();
            phone = showPhone && p != null ? p.phone() : null;
        }
        // A walk-in he added counts as his (to fix) only while he may still add walk-ins; a break he added, always
        boolean hisToFix = a.kind() == AppointmentKind.BLOCK || c.ownerOrHelper() || c.walkIns();
        return AppointmentView.of(a, services, name, phone, now, a.startsAt().plus(LATE_ALLOWANCE),
                hisToFix ? c.userId() : null);
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
            throw wrongStatus("It is " + a.status() + " now. Reload.", Map.of("reason", "CHANGED", "status", a.status().name()));
        }
    }

    /** The barber's hours or services changed while this was on its way: 409 TRY_AGAIN, the screen reloads. */
    static ApiException setupChanged() {
        return new ApiException(HttpStatus.CONFLICT, "TRY_AGAIN",
                "This barber's hours or services just changed. Reload and try again.");
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

    /** WRONG_STATUS with details.reason (TOO_EARLY, HELD_OVER, TOO_LATE, CHANGED, REASSIGNED), so apps needn't parse words. */
    static ApiException wrongStatus(String message, Map<String, Object> details) {
        return new ApiException(HttpStatus.CONFLICT, "WRONG_STATUS", message, details);
    }

    /** Someone else changed it first. */
    static ApiException changed() {
        return wrongStatus("It was changed in the meantime. Reload.", Map.of("reason", "CHANGED"));
    }

    private static ApiException barberBusy(String name) {
        return new ApiException(HttpStatus.CONFLICT, "BARBER_BUSY", name + " is busy at that time.");
    }

    private static ApiException staffNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND", "No such barber.");
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
