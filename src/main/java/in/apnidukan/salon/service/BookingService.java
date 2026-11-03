package in.apnidukan.salon.service;

import in.apnidukan.common.context.ShopDirectory;
import in.apnidukan.common.context.ShopDirectory.ShopCard;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.files.FileRefs;
import in.apnidukan.common.text.TextCleaner.TextField;
import in.apnidukan.salon.api.BookingRequests.Book;
import in.apnidukan.salon.api.BookingResponses.BookingView;
import in.apnidukan.salon.api.BookingResponses.CancelResult;
import in.apnidukan.salon.api.BookingResponses.DayHours;
import in.apnidukan.salon.api.BookingResponses.SalonDetails;
import in.apnidukan.salon.api.BookingResponses.SalonService;
import in.apnidukan.salon.api.BookingResponses.SalonSummary;
import in.apnidukan.salon.api.BookingResponses.ServiceLine;
import in.apnidukan.salon.api.BookingResponses.Slot;
import in.apnidukan.salon.api.BookingResponses.Slots;
import in.apnidukan.salon.domain.Appointment;
import in.apnidukan.salon.domain.AppointmentKind;
import in.apnidukan.salon.domain.AppointmentStatus;
import in.apnidukan.salon.domain.Availability;
import in.apnidukan.salon.domain.Availability.BarberDay;
import in.apnidukan.salon.domain.BookedService;
import in.apnidukan.salon.domain.Interval;
import in.apnidukan.salon.domain.SalonServiceItem;
import in.apnidukan.salon.domain.SalonSettings;
import in.apnidukan.salon.domain.SalonStaff;
import in.apnidukan.salon.domain.SalonTime;
import in.apnidukan.salon.domain.Shift;
import in.apnidukan.salon.domain.StaffShift;
import in.apnidukan.salon.domain.StaffSkill;
import in.apnidukan.salon.domain.StrikeKind;
import in.apnidukan.salon.repo.AppointmentStore;
import in.apnidukan.salon.repo.SalonAccessStore;
import in.apnidukan.salon.repo.SalonServiceItemRepository;
import in.apnidukan.salon.repo.SalonStaffRepository;
import in.apnidukan.salon.repo.StaffShiftRepository;
import in.apnidukan.salon.repo.StaffSkillRepository;
import in.apnidukan.salon.repo.StrikeStore;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static in.apnidukan.common.error.DatabaseErrors.hasSqlState;
import static in.apnidukan.common.text.TextCleaner.clean;

/**
 * The customer side: salons near me, a salon's services and free times, booking, my bookings, cancel and
 * reschedule. Any signed-in person can book; the customer is always the caller (from the sign-in token).
 *
 * The customer picks services and a time only; the backend picks the barber (the least busy one that day who does
 * all the services and is free). Two people booking the last free barber at once can't both get him: the
 * database's no-overlap rule lets one insert through and the other moves on to the next barber or gets
 * 409 SLOT_TAKEN with the nearest free times.
 */
@Service
public class BookingService {

    /** Salons are listed within this distance of the customer. */
    static final double RADIUS_KM = 5.0;
    static final int MAX_SALONS = 50;
    /** Bookings a customer may hold at once (CONFIRMED, not yet started). */
    static final int MAX_ACTIVE_BOOKINGS = 2;
    /** Cancelling closer to the start than this counts as a late cancel (a strike). */
    static final Duration LATE_CANCEL = Duration.ofHours(1);
    /** 3 strikes within 60 days stop online booking until 30 days after the latest of them. */
    static final int STRIKES_TO_BLOCK = 3;
    static final Duration STRIKE_WINDOW = Duration.ofDays(60);
    static final Duration BLOCK_FOR = Duration.ofDays(30);
    static final int MY_BOOKINGS_LIMIT = 50;
    static final TextField BOOKING_FOR = new TextField("bookingFor", 2, 60);
    /** Booking numbers: no 0/O, 1/I/L, so they can be read out over the phone. */
    private static final String BOOKING_NO_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private final ShopDirectory shops;
    private final SalonCalendar calendar;
    private final AppointmentStore appointments;
    private final StrikeStore strikes;
    private final SalonAccessStore salonAccess;
    private final SalonServiceItemRepository services;
    private final SalonStaffRepository staff;
    private final StaffSkillRepository skills;
    private final StaffShiftRepository shifts;
    private final StartCodes codes;
    private final SalonNotices notices;
    private final FileRefs files;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public BookingService(ShopDirectory shops, SalonCalendar calendar, AppointmentStore appointments,
                          StrikeStore strikes, SalonAccessStore salonAccess, SalonServiceItemRepository services,
                          SalonStaffRepository staff, StaffSkillRepository skills, StaffShiftRepository shifts,
                          StartCodes codes, SalonNotices notices, FileRefs files, Clock clock) {
        this.shops = shops;
        this.calendar = calendar;
        this.appointments = appointments;
        this.strikes = strikes;
        this.salonAccess = salonAccess;
        this.services = services;
        this.staff = staff;
        this.skills = skills;
        this.shifts = shifts;
        this.codes = codes;
        this.notices = notices;
        this.files = files;
        this.clock = clock;
    }

    // ---------- finding a salon ----------

    /** Approved salons within 5 km, nearest first, leaving out those that paused online bookings. */
    @Transactional(readOnly = true)
    public List<SalonSummary> salonsNear(BigDecimal latitude, BigDecimal longitude) {
        if (latitude.doubleValue() < 6 || latitude.doubleValue() > 37.6
                || longitude.doubleValue() < 68 || longitude.doubleValue() > 97.5) {
            throw SalonCalendar.invalid("lat", "the point must be in India");
        }
        Instant now = now();
        return shops.nearby("SALON", latitude, longitude, RADIUS_KM, MAX_SALONS).stream()
                .filter(card -> !paused(card.id(), now))
                .map(card -> SalonSummary.from(card, photo(card)))
                .toList();
    }

    /** A salon's page: address, map link and opening hours (from its barbers' weekly hours). */
    @Transactional(readOnly = true)
    public SalonDetails salon(UUID salonId) {
        ShopCard card = visible(salonId);
        List<UUID> barbers = staff.findByShopIdOrderByCreatedAtAscIdAsc(salonId).stream()
                .filter(SalonStaff::isActive).map(SalonStaff::getId).toList();
        Map<Integer, int[]> byDay = new HashMap<>();
        for (StaffShift s : shifts.findByStaffIdIn(barbers)) {
            Shift sh = s.shift();
            byDay.merge(sh.weekday(), new int[] {sh.startMin(), sh.endMin()},
                    (a, b) -> new int[] {Math.min(a[0], b[0]), Math.max(a[1], b[1])});
        }
        List<DayHours> hours = byDay.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new DayHours(e.getKey(), SalonTime.format(e.getValue()[0]), SalonTime.format(e.getValue()[1])))
                .toList();
        Instant now = now();
        return new SalonDetails(card.id(), card.name(), card.addressLine(), card.area(), card.city(), card.pincode(),
                card.latitude(), card.longitude(), SalonNotices.mapLink(card), hours,
                salonAccess.pausedUntil(salonId).filter(u -> u.isAfter(now)).orElse(null), photo(card));
    }

    /** The salon's active services that at least one active barber does, in the salon's order. */
    @Transactional(readOnly = true)
    public List<SalonService> salonServices(UUID salonId) {
        visible(salonId);
        List<UUID> barbers = staff.findByShopIdOrderByCreatedAtAscIdAsc(salonId).stream()
                .filter(SalonStaff::isActive).map(SalonStaff::getId).toList();
        Set<UUID> offered = skills.findByStaffIdIn(barbers).stream().map(StaffSkill::getServiceId)
                .collect(Collectors.toSet());
        return services.findByShopIdOrderBySortOrderAscNameAsc(salonId).stream()
                .filter(s -> s.isActive() && offered.contains(s.getId()))
                .map(BookingService::serviceView)
                .toList();
    }

    /**
     * The start times free for these services on this date (India), each on a quarter hour, inside the salon's
     * booking window. 409 NO_BARBER_FOR_SERVICES if no single barber does them all.
     */
    @Transactional(readOnly = true)
    public Slots availability(UUID salonId, List<UUID> serviceIds, LocalDate date) {
        bookable(salonId);
        Instant now = now();
        List<BookedService> chosen = calendar.chosenServices(salonId, serviceIds);
        Duration length = SalonCalendar.length(chosen);
        List<SalonStaff> barbers = calendar.barbersFor(salonId, SalonDayService.ids(chosen));
        Interval window = calendar.window(calendar.settings(salonId, now), now);
        Interval day = dayInWindow(date, window, now);
        List<Instant> starts = day == null ? List.of()
                : Availability.starts(calendar.days(barbers, date, null), length, day.start(), day.end());
        return new Slots(date, (int) length.toMinutes(), starts.stream().map(Slot::of).toList());
    }

    // ---------- booking ----------

    /**
     * Books the chosen services at startAt. Checks, in order: the same Idempotency-Key (returns the booking already
     * made), strikes (403 BOOKING_BLOCKED), the 2-booking limit (409 TOO_MANY_BOOKINGS), the salon (409
     * SALON_NOT_BOOKABLE), the services, the time (400 OUTSIDE_WINDOW), then tries the free barbers in order.
     * 201 with the booking, or 409 SLOT_TAKEN with the nearest free starts that day.
     */
    @Transactional
    public BookingView book(UUID customerId, String idempotencyKey, Book in) {
        UUID requestId = SalonDayService.requestId(idempotencyKey);
        appointments.lockCustomer(customerId);   // this person's bookings run one after the other
        Optional<Appointment> earlier = appointments.findByRequest(customerId, requestId);
        if (earlier.isPresent()) {
            requireSameBooking(earlier.get(), in);
            return view(earlier.get(), now());
        }
        Instant now = now();
        requireNotBlocked(customerId, now);
        if (appointments.activeBookings(customerId, now) >= MAX_ACTIVE_BOOKINGS) {
            throw new ApiException(HttpStatus.CONFLICT, "TOO_MANY_BOOKINGS",
                    "You already have " + MAX_ACTIVE_BOOKINGS + " upcoming bookings. Cancel one to book another.");
        }
        ShopCard salon = bookable(in.salonId(), true);
        List<BookedService> chosen = calendar.chosenServices(salon.id(), in.serviceIds());
        Duration length = SalonCalendar.length(chosen);
        List<SalonStaff> barbers = calendar.barbersFor(salon.id(), SalonDayService.ids(chosen));
        Interval window = calendar.window(calendar.settings(salon.id(), now), now);
        Instant start = in.startAt();
        requireInWindow(start, window);
        Interval slot = Interval.of(start, length);
        LocalDate date = LocalDate.ofInstant(start, SalonTime.ZONE);
        List<BarberDay> days = calendar.days(barbers, date, null);
        String bookingFor = in.bookingFor() == null || in.bookingFor().isBlank() ? null
                : clean(BOOKING_FOR, in.bookingFor());
        for (UUID barberId : Availability.barbersFor(days, slot)) {
            if (!appointments.lockActiveBarber(barberId)) {
                continue;   // switched off a moment ago
            }
            Appointment a = new Appointment(UUID.randomUUID(), salon.id(), barberId, AppointmentKind.BOOKING, null,
                    null, customerId, bookingNo(), bookingFor, null, codes.newCode(), slot.start(), slot.end(),
                    AppointmentStatus.CONFIRMED, null, null, null, customerId, requestId, now, now);
            if (appointments.insertIfFree(a)) {
                appointments.insertServices(a.id(), chosen);
                appointments.event(a.id(), now, customerId, "BOOKED", null);
                notices.booked(a, salon, chosen, now);
                return view(a, now);
            }
        }
        throw slotTaken(days, length, start, window, date, now);
    }

    /** My bookings: upcoming (soonest first) or past (latest first), up to 50. */
    @Transactional(readOnly = true)
    public List<BookingView> myBookings(UUID customerId, boolean upcoming) {
        Instant now = now();
        return views(appointments.forCustomer(customerId, upcoming, now, MY_BOOKINGS_LIMIT), now);
    }

    /** One of my bookings; 404 for anyone else's. */
    @Transactional(readOnly = true)
    public BookingView booking(UUID customerId, UUID bookingId) {
        return view(mine(customerId, appointments.find(bookingId)), now());
    }

    /**
     * Cancels my booking, any time before its start. Less than 1 hour before counts as a late cancel (a strike),
     * which the reply says.
     */
    @Transactional
    public CancelResult cancel(UUID customerId, UUID bookingId) {
        appointments.lockCustomer(customerId);
        Appointment a = lockMine(customerId, bookingId);
        Instant now = now();
        requireChangeable(a, now);
        boolean late = Duration.between(now, a.startsAt()).compareTo(LATE_CANCEL) < 0;
        if (!appointments.close(a.id(), AppointmentStatus.CANCELLED_BY_CUSTOMER, null, now)) {
            throw SalonDayService.wrongStatus("It was changed in the meantime. Reload.");
        }
        if (late) {
            strikes.add(customerId, a.id(), StrikeKind.LATE_CANCEL, now);
        }
        appointments.event(a.id(), now, customerId, "CANCELLED_BY_CUSTOMER", late ? "late" : null);
        notices.cancelledByCustomer(a, shops.find(a.shopId()).orElseThrow(), late, now);
        return new CancelResult(view(appointments.find(a.id()).orElseThrow(), now), late);
    }

    /**
     * Moves my booking to another start. The same row moves (it can't clash with itself): to the same barber if
     * he is free then, otherwise to another free barber who does the services. 409 SLOT_TAKEN with the nearest
     * free starts if nobody is free.
     */
    @Transactional
    public BookingView reschedule(UUID customerId, UUID bookingId, Instant newStart) {
        appointments.lockCustomer(customerId);
        // The shop row (FOR SHARE) before the booking's row, the same order as booking and suspending a shop
        UUID shopId = mine(customerId, appointments.find(bookingId)).shopId();
        ShopCard salon = shops.findForBooking(shopId).orElseThrow();   // see ShopDirectory.findForBooking
        Appointment a = lockMine(customerId, bookingId);
        Instant now = now();
        requireChangeable(a, now);
        if (!"APPROVED".equals(salon.status()) || paused(salon.id(), now)) {
            throw new ApiException(HttpStatus.CONFLICT, "SALON_NOT_BOOKABLE",
                    "The salon isn't taking online changes right now (paused, or its details are being checked "
                            + "again). Your booking stays; you can still cancel it.");
        }
        if (newStart.equals(a.startsAt())) {
            return view(a, now);
        }
        List<BookedService> booked = appointments.services(List.of(a.id())).getOrDefault(a.id(), List.of());
        Duration length = SalonCalendar.length(booked);
        List<SalonStaff> barbers = calendar.barbersFor(a.shopId(), SalonDayService.ids(booked));
        Interval window = calendar.window(calendar.settings(a.shopId(), now), now);
        requireInWindow(newStart, window);
        Interval slot = Interval.of(newStart, length);
        LocalDate date = LocalDate.ofInstant(newStart, SalonTime.ZONE);
        List<BarberDay> days = calendar.days(barbers, date, a.id());
        List<UUID> candidates = new ArrayList<>(Availability.barbersFor(days, slot));
        if (candidates.remove(a.staffId())) {
            candidates.add(0, a.staffId());   // keep the same barber when he is free
        }
        // Lock only the barber it goes to: the first one who is still active (his own barber needs no lock, since
        // a barber with upcoming bookings can't be switched off)
        while (!candidates.isEmpty() && !candidates.get(0).equals(a.staffId())
                && !appointments.lockActiveBarber(candidates.get(0))) {
            candidates.remove(0);
        }
        if (candidates.isEmpty()) {
            throw slotTaken(days, length, newStart, window, date, now);
        }
        try {
            if (!appointments.move(a.id(), candidates.get(0), slot.start(), slot.end(), now)) {
                throw SalonDayService.wrongStatus("It was changed in the meantime. Reload.");
            }
        } catch (DataIntegrityViolationException e) {
            if (hasSqlState(e, SalonDayService.OVERLAP)) {   // taken in between: everything here is undone
                throw slotTaken(days, length, newStart, window, date, now);   // worked out in memory, no SQL
            }
            throw e;
        } catch (PessimisticLockingFailureException e) {
            throw SalonDayService.tryAgain();   // two moves into each other's time: the database broke the tie
        }
        Appointment moved = appointments.find(a.id()).orElseThrow();
        appointments.event(a.id(), now, customerId, "RESCHEDULED", a.startsAt() + " -> " + newStart);
        notices.rescheduled(a, moved, salon, now);
        return view(moved, now);
    }

    // ---------- rules ----------

    /**
     * 403 BOOKING_BLOCKED if 3 strikes fall within 60 days of each other and the latest of them was less than
     * 30 days ago; details.until says when booking opens again. Walking in is never blocked.
     */
    void requireNotBlocked(UUID customerId, Instant now) {
        blockedUntil(strikes.since(customerId, now.minus(STRIKE_WINDOW).minus(BLOCK_FOR)), now)
                .ifPresent(until -> {
                    throw new ApiException(HttpStatus.FORBIDDEN, "BOOKING_BLOCKED",
                            "Online booking is paused for you after 3 missed or late-cancelled bookings. You can "
                                    + "still walk in.", Map.of("until", until.toString()));
                });
    }

    /** @param strikesLatestFirst the customer's strikes in the last 90 days, latest first */
    static Optional<Instant> blockedUntil(List<Instant> strikesLatestFirst, Instant now) {
        for (int i = 0; i + STRIKES_TO_BLOCK - 1 < strikesLatestFirst.size(); i++) {
            Instant latest = strikesLatestFirst.get(i);
            Instant oldest = strikesLatestFirst.get(i + STRIKES_TO_BLOCK - 1);
            if (Duration.between(oldest, latest).compareTo(STRIKE_WINDOW) <= 0) {
                Instant until = latest.plus(BLOCK_FOR);
                return until.isAfter(now) ? Optional.of(until) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** The salon, if customers can book it now: approved, a salon, not paused. 409 SALON_NOT_BOOKABLE otherwise. */
    private ShopCard bookable(UUID salonId) {
        return bookable(salonId, false);
    }

    /** @param forBooking hold the shop's row until the booking is saved (see ShopDirectory.findForBooking) */
    private ShopCard bookable(UUID salonId, boolean forBooking) {
        ShopCard card = forBooking ? visibleSalon(shops.findForBooking(salonId)) : visible(salonId);
        if (paused(salonId, now())) {
            throw new ApiException(HttpStatus.CONFLICT, "SALON_NOT_BOOKABLE",
                    "This salon isn't taking online bookings right now. Try again later.");
        }
        return card;
    }

    /** An approved salon; anything else (including other shop types and unknown ids) is 404. */
    private ShopCard visible(UUID salonId) {
        return visibleSalon(shops.find(salonId));
    }

    private static ShopCard visibleSalon(Optional<ShopCard> found) {
        return found
                .filter(c -> "SALON".equals(c.type()) && "APPROVED".equals(c.status()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SALON_NOT_FOUND", "No such salon."));
    }

    private String photo(ShopCard card) {
        return card.shopPhotoFileId() == null ? null : files.link(card.shopPhotoFileId());
    }

    private boolean paused(UUID salonId, Instant now) {
        return salonAccess.pausedUntil(salonId).map(u -> u.isAfter(now)).orElse(false);
    }

    /** On the quarter-hour grid and inside the booking window, else 400 OUTSIDE_WINDOW. */
    private static void requireInWindow(Instant start, Interval window) {
        if (!Availability.onGrid(start)) {
            throw outsideWindow("Times go in steps of 15 minutes (:00, :15, :30, :45).");
        }
        if (start.isBefore(window.start()) || !start.isBefore(window.end())) {
            throw outsideWindow("That time is too soon or too far ahead for this salon.");
        }
    }

    /**
     * The part of this date inside the window, or null if nothing is left of it (late tonight). 400 OUTSIDE_WINDOW
     * for a date before today or after the salon's last bookable day.
     */
    private static Interval dayInWindow(LocalDate date, Interval window, Instant now) {
        Instant from = Availability.dayStart(date);
        Instant to = Availability.dayStart(date.plusDays(1));
        if (date.isBefore(LocalDate.ofInstant(now, SalonTime.ZONE)) || !from.isBefore(window.end())) {
            throw outsideWindow("Pick a date from today up to the salon's last bookable day.");
        }
        Instant start = from.isAfter(window.start()) ? from : window.start();
        Instant end = to.isBefore(window.end()) ? to : window.end();
        return end.isAfter(start) ? new Interval(start, end) : null;
    }

    private ApiException slotTaken(List<BarberDay> days, Duration length, Instant wanted, Interval window,
                                   LocalDate date, Instant now) {
        Interval day = dayInWindow(date, window, now);
        List<Instant> free = day == null ? List.of() : Availability.starts(days, length, day.start(), day.end());
        List<String> nearest = Availability.nearest(free, wanted, 3).stream().map(Instant::toString).toList();
        return new ApiException(HttpStatus.CONFLICT, "SLOT_TAKEN",
                "That time isn't free any more. Pick one of the nearest free times.", Map.of("nearest", nearest));
    }

    private static void requireChangeable(Appointment a, Instant now) {
        if (a.status() != AppointmentStatus.CONFIRMED || !now.isBefore(a.startsAt())) {
            throw SalonDayService.wrongStatus("This booking can't be changed any more.");
        }
    }

    /**
     * The same Idempotency-Key must mean the same booking: same salon, start and services. A retry gets the booking
     * already made; a different request with a used key gets 409 IDEMPOTENCY_KEY_REUSED (it was not booked).
     */
    private void requireSameBooking(Appointment earlier, Book in) {
        List<UUID> bookedIds = SalonDayService.ids(appointments.services(List.of(earlier.id()))
                .getOrDefault(earlier.id(), List.of()));
        // A booking moved since keeps its key: its original start is no longer known, so salon and services decide
        boolean sameTime = earlier.startsAt().equals(in.startAt())
                || appointments.hasEvent(earlier.id(), "RESCHEDULED");
        boolean same = earlier.isBooking() && earlier.shopId().equals(in.salonId()) && sameTime
                && bookedIds.equals(new ArrayList<>(new java.util.LinkedHashSet<>(in.serviceIds())));
        if (!same) {
            throw SalonDayService.keyReused();
        }
    }

    /** Checks the booking is the caller's own before locking it, so nobody can lock other people's rows. */
    private Appointment lockMine(UUID customerId, UUID bookingId) {
        mine(customerId, appointments.find(bookingId));
        return mine(customerId, appointments.lock(bookingId));
    }

    /** The booking if it is the caller's own; 404 otherwise, so other people's ids can't be probed. */
    private static Appointment mine(UUID customerId, Optional<Appointment> found) {
        return found.filter(a -> a.isBooking() && customerId.equals(a.customerId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "No such booking."));
    }

    private String bookingNo() {
        StringBuilder sb = new StringBuilder("S");
        for (int i = 0; i < 7; i++) {
            sb.append(BOOKING_NO_CHARS.charAt(random.nextInt(BOOKING_NO_CHARS.length())));
        }
        return sb.toString();
    }

    // ---------- views ----------

    private BookingView view(Appointment a, Instant now) {
        return views(List.of(a), now).get(0);
    }

    private List<BookingView> views(List<Appointment> list, Instant now) {
        Map<UUID, List<BookedService>> booked = appointments.services(list.stream().map(Appointment::id).toList());
        Map<UUID, ShopCard> salons = new LinkedHashMap<>();
        for (Appointment a : list) {
            salons.computeIfAbsent(a.shopId(), id -> shops.find(id).orElseThrow());
        }
        return list.stream().map(a -> {
            ShopCard s = salons.get(a.shopId());
            List<BookedService> lines = booked.getOrDefault(a.id(), List.of());
            boolean changeable = a.status() == AppointmentStatus.CONFIRMED && now.isBefore(a.startsAt());
            boolean movable = changeable && "APPROVED".equals(s.status()) && !paused(s.id(), now);
            return new BookingView(a.id(), a.bookingNo(), s.id(), s.name(),
                    s.addressLine() + ", " + s.area() + ", " + s.city() + " " + s.pincode(), SalonNotices.mapLink(s),
                    ServiceLine.from(lines), lines.stream().mapToLong(BookedService::pricePaise).sum(),
                    lines.stream().anyMatch(BookedService::priceIsFrom), a.startsAt(), a.endsAt(),
                    a.startsAt().plus(SalonDayService.LATE_ALLOWANCE), a.status(),
                    a.status() == AppointmentStatus.CONFIRMED ? a.startCode() : null, a.bookingFor(),
                    a.cancelReason(), changeable, movable, a.createdAt());
        }).toList();
    }

    private static SalonService serviceView(SalonServiceItem s) {
        return new SalonService(s.getId(), s.details().name(), s.details().durationMin(), s.details().pricePaise(),
                s.details().priceIsFrom());
    }

    private static ApiException outsideWindow(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "OUTSIDE_WINDOW", message);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
