package in.apnidukan.salon.service;

import in.apnidukan.common.context.ShopDirectory;
import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.context.ShopDirectory.ShopCard;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.files.FileRefs;
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
import in.apnidukan.salon.domain.AppointmentCursor;
import in.apnidukan.salon.domain.AppointmentKind;
import in.apnidukan.salon.domain.AppointmentStatus;
import in.apnidukan.salon.domain.Availability;
import in.apnidukan.salon.domain.Availability.BarberDay;
import in.apnidukan.salon.domain.BookedService;
import in.apnidukan.salon.domain.Interval;
import in.apnidukan.salon.domain.SalonTime;
import in.apnidukan.salon.domain.Shift;
import in.apnidukan.salon.domain.StrikeKind;
import in.apnidukan.salon.repo.AppointmentStore;
import in.apnidukan.salon.repo.SalonAccessStore;
import in.apnidukan.salon.repo.StrikeStore;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import in.apnidukan.common.security.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static in.apnidukan.common.error.DatabaseErrors.hasSqlState;

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
    static final int RADIUS_METRES = 2_000;
    /** Salons per page of the list; the app asks for the next page with the last card's distance and id. */
    static final int SALONS_PAGE = 30;
    /** Bookings a customer may hold at once (CONFIRMED, not yet started). */
    static final int MAX_ACTIVE_BOOKINGS = 2;
    /** Cancelling closer to the start than this counts as a late cancel (a strike). */
    static final Duration LATE_CANCEL = Duration.ofHours(1);
    /**
     * A cancel this soon after booking never counts as late, whatever the start: someone who booked for 40 minutes
     * from now and at once sees the wrong time or salon must not get a strike for putting it right.
     */
    static final Duration FREE_CANCEL_AFTER_BOOKING = Duration.ofMinutes(10);
    /** 3 strikes within 60 days stop online booking until 30 days after the latest of them. */
    static final int STRIKES_TO_BLOCK = 3;
    static final Duration STRIKE_WINDOW = Duration.ofDays(60);
    static final Duration BLOCK_FOR = Duration.ofDays(30);
    /** Upcoming bookings are at most a few (2 waiting, plus any in the chair): one list, at most this many. */
    static final int MY_BOOKINGS_LIMIT = 50;
    /** Past bookings per page (the next page with the last one's startsAt and id). */
    public static final int PAST_PAGE = 30;
    /**
     * New bookings and moves per customer per day. Each one messages the salon (push, and SMS when set up), so a
     * book-and-cancel loop would otherwise use up the salon's and everyone's SMS budget.
     */
    static final int CHANGES_PER_DAY = 10;
    /**
     * Booking numbers tried for one booking when the random one is already taken (each try is a fresh random number;
     * with 31^7 numbers a clash is already rare, five in a row never happens).
     */
    static final int BOOKING_NO_TRIES = 5;

    private final ShopDirectory shops;
    private final SalonCalendar calendar;
    private final AppointmentStore appointments;
    private final StrikeStore strikes;
    private final SalonAccessStore salonAccess;
    private final StartCodes codes;
    private final SalonNotices notices;
    private final FileRefs files;
    private final UserDirectory users;
    private final Clock clock;
    private final RateLimiter limits;
    private final SalonSetups setups;
    private final BookingNumbers numbers;
    private final int readsPerMinute;

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    public BookingService(ShopDirectory shops, SalonCalendar calendar, AppointmentStore appointments,
                          StrikeStore strikes, SalonAccessStore salonAccess,
                          StartCodes codes, SalonNotices notices, FileRefs files, UserDirectory users, Clock clock,
                          RateLimiter limits, SalonSetups setups, BookingNumbers numbers,
                          @Value("${app.salon.reads-per-minute:60}") int readsPerMinute) {
        this.limits = limits;
        this.setups = setups;
        this.numbers = numbers;
        this.readsPerMinute = readsPerMinute;
        this.shops = shops;
        this.calendar = calendar;
        this.appointments = appointments;
        this.strikes = strikes;
        this.salonAccess = salonAccess;
        this.codes = codes;
        this.notices = notices;
        this.files = files;
        this.users = users;
        this.clock = clock;
    }

    // ---------- finding a salon ----------

    /**
     * Approved salons within 2 km, nearest first, leaving out those that paused online bookings, one page at a time:
     * `after` is the last salon of the previous page (null for the first). Two database reads per page however
     * many salons there are: the page (PostGIS), and the photos' links.
     */
    @Transactional(readOnly = true)
    public List<SalonSummary> salonsNear(BigDecimal latitude, BigDecimal longitude, ShopDirectory.Cursor after) {
        if (latitude.doubleValue() < 6 || latitude.doubleValue() > 37.6
                || longitude.doubleValue() < 68 || longitude.doubleValue() > 97.5) {
            throw SalonCalendar.invalid("lat", "the point must be in India");
        }
        List<ShopCard> page = shops.nearby("SALON", latitude, longitude, RADIUS_METRES, SALONS_PAGE, after, true);
        Map<UUID, String> photos = files.links(page.stream().map(ShopCard::shopPhotoFileId)
                .filter(java.util.Objects::nonNull).toList());
        return page.stream()
                .map(card -> SalonSummary.from(card, card.shopPhotoFileId() == null ? null
                        : photos.get(card.shopPhotoFileId())))
                .toList();
    }

    /**
     * A salon's page: address, map link, opening hours (from its barbers' weekly hours, from the setup kept in
     * memory: SalonSetups, read with the pause) and how many days ahead it takes bookings (the app's day chips).
     */
    @Transactional(readOnly = true)
    public SalonDetails salon(UUID customerId, UUID salonId) {
        requireReadsLeft(customerId);
        ShopCard card = visible(salonId);
        SalonSetups.Current current = setups.current(salonId);
        Map<Integer, int[]> byDay = new HashMap<>();
        for (SalonSetups.Barber b : current.setup().barbers()) {
            for (Shift sh : b.week()) {
                byDay.merge(sh.weekday(), new int[] {sh.startMin(), sh.endMin()},
                        (x, y) -> new int[] {Math.min(x[0], y[0]), Math.max(x[1], y[1])});
            }
        }
        List<DayHours> hours = byDay.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new DayHours(e.getKey(), SalonTime.format(e.getValue()[0]), SalonTime.format(e.getValue()[1])))
                .toList();
        Instant now = now();
        return new SalonDetails(card.id(), card.name(), card.addressLine(), card.area(), card.city(), card.pincode(),
                card.latitude(), card.longitude(), SalonNotices.mapLink(card), hours,
                current.pausedAt(now) ? current.pausedUntil() : null, photo(card), current.setup().maxDaysAhead());
    }

    /** The salon's active services that at least one active barber does, in the salon's order. */
    @Transactional(readOnly = true)
    public List<SalonService> salonServices(UUID customerId, UUID salonId) {
        requireReadsLeft(customerId);
        visible(salonId);
        SalonSetups.Setup setup = setups.of(salonId);
        Set<UUID> offered = setup.barbers().stream().flatMap(b -> b.serviceIds().stream())
                .collect(Collectors.toSet());
        return setup.services().stream()
                .filter(s -> offered.contains(s.serviceId()))
                .map(s -> new SalonService(s.serviceId(), s.name(), s.durationMin(), s.pricePaise(), s.priceIsFrom()))
                .toList();
    }

    /**
     * The start times free for these services on this date (India), each on a quarter hour, inside the salon's
     * booking window. 409 NO_BARBER_FOR_SERVICES if no single barber does them all.
     * @param moving one of the customer's own bookings being moved (or null): its own time counts as free, so a
     *               move by a few minutes onto it is offered. 404 if it isn't theirs.
     */
    @Transactional(readOnly = true)
    public Slots availability(UUID customerId, UUID salonId, List<UUID> serviceIds, LocalDate date, UUID moving) {
        requireReadsLeft(customerId);
        UUID except = moving == null ? null : mine(customerId, appointments.find(moving)).id();
        // Three reads: the salon, its counters with its pause, and that day's busy time. The barbers, what they do,
        // their weeks and the booking settings come from memory while the salon's setup is unchanged
        visible(salonId);
        SalonSetups.Current current = setups.current(salonId);
        Instant now = now();
        requireNotPaused(current, now);
        SalonSetups.Setup setup = current.setup();
        List<BookedService> chosen = calendar.chosenServices(setup, serviceIds);
        Duration length = SalonCalendar.length(chosen);
        List<SalonSetups.Barber> barbers = calendar.barbersFor(setup, SalonDayService.ids(chosen));
        Interval window = calendar.window(setup, now);
        Interval day = dayInWindow(date, window, now);
        // The busy time from memory while the salon is unchanged (a booking being moved: read fresh, without it)
        List<Instant> starts = day == null ? List.of()
                : Availability.starts(except == null ? calendar.setupDays(current, barbers, date)
                        : calendar.setupDays(barbers, date, except), length, day.start(), day.end());
        return new Slots(date, (int) length.toMinutes(), (int) FREE_CANCEL_AFTER_BOOKING.toMinutes(),
                starts.stream().map(t -> Slot.of(t, t.minus(LATE_CANCEL))).toList());
    }

    /**
     * 429 TOO_MANY_REQUESTS (with Retry-After) after app.salon.reads-per-minute salon pages, service lists and
     * free-time checks by one person in a minute: each check reads every barber's day, and nobody browsing needs that
     * many. If Redis doesn't answer, the read goes ahead.
     */
    private void requireReadsLeft(UUID customerId) {
        String key = "salon-reads:" + customerId;
        try {
            if (!limits.tryHit(key, readsPerMinute, Duration.ofMinutes(1))) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS",
                        "Too many salon pages at once. Wait a few seconds and try again.")
                        .retryAfter(limits.secondsLeft(key));
            }
        } catch (DataAccessException redisDown) {
            log.warn("Redis didn't answer the salon-read count ({}); letting it through",
                    redisDown.getClass().getSimpleName());
        }
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
        requireLiveAccount(customerId);
        users.requireCompleteProfile(customerId);   // verified email (for recovery) and a name for the salon
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
        requireChangesLeft(customerId);
        // The shop's row held until the booking is saved (see ShopDirectory.findForBooking); the setup from memory
        ShopCard salon = visibleSalon(shops.findForBooking(in.salonId()));
        SalonSetups.Current current = setups.current(salon.id());
        requireNotPaused(current, now);
        SalonSetups.Setup setup = current.setup();
        List<BookedService> chosen = calendar.chosenServices(setup, in.serviceIds());
        Duration length = SalonCalendar.length(chosen);
        List<SalonSetups.Barber> barbers = calendar.barbersFor(setup, SalonDayService.ids(chosen));
        Interval window = calendar.window(setup, now);
        Instant start = in.startAt();
        requireInWindow(start, window);
        Interval slot = Interval.of(start, length);
        requireNoOverlap(customerId, slot, null);
        LocalDate date = LocalDate.ofInstant(start, SalonTime.ZONE);
        List<BarberDay> days = calendar.setupDays(current, barbers, date);
        List<UUID> serviceIds = SalonDayService.ids(chosen);
        for (UUID barberId : Availability.barbersFor(days, slot)) {
            if (!appointments.lockActiveBarber(barberId)
                    || !calendar.stillFits(salon.id(), setup.version(), barberId, serviceIds, slot)) {
                // Switched off, or new hours or services saved for him a moment ago (the setup in memory is checked
                // against the database here, under his lock): the next barber
                continue;
            }
            Appointment fresh = new Appointment(UUID.randomUUID(), salon.id(), barberId, AppointmentKind.BOOKING,
                    null, null, customerId, numbers.next(), null, codes.newCode(), slot.start(), slot.end(),
                    AppointmentStatus.CONFIRMED, null, null, null, customerId, requestId, now, now);
            // A booking number already taken is tried again with a new one (BOOKING_NO_TRIES), never a 500
            Optional<Appointment> saved = appointments.insertBookingIfFree(fresh, numbers::next, BOOKING_NO_TRIES);
            if (saved.isPresent()) {
                Appointment a = saved.get();
                countChange(customerId);
                appointments.insertServices(a.id(), chosen);
                appointments.event(a.id(), now, customerId, "BOOKED", null);
                notices.booked(a, salon, chosen, setup, now);
                return view(a, now);
            }
        }
        throw slotTaken(days, length, start, window, date, now);
    }

    /**
     * My bookings: upcoming (soonest first, up to 50: there are only a few), or past (latest first, PAST_PAGE a page
     * after the cursor; null: the first page).
     */
    @Transactional(readOnly = true)
    public List<BookingView> myBookings(UUID customerId, boolean upcoming, AppointmentCursor after) {
        if (upcoming && after != null) {
            throw SalonCalendar.invalid("afterStart", "pages are only for when=past");
        }
        Instant now = now();
        return views(appointments.forCustomer(customerId, upcoming, now, after,
                upcoming ? MY_BOOKINGS_LIMIT : PAST_PAGE), now);
    }

    /** One of my bookings; 404 for anyone else's. */
    @Transactional(readOnly = true)
    public BookingView booking(UUID customerId, UUID bookingId) {
        return view(mine(customerId, appointments.find(bookingId)), now());
    }

    /**
     * Cancels my booking, any time before its start. Less than 1 hour before counts as a late cancel (a strike),
     * which the reply says, except within 10 minutes of booking (see lateCancelFrom).
     */
    @Transactional
    public CancelResult cancel(UUID customerId, UUID bookingId) {
        appointments.lockCustomer(customerId);
        Appointment a = lockMine(customerId, bookingId);
        Instant now = now();
        requireChangeable(a, now);
        boolean late = now.isAfter(lateCancelFrom(a));
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
        requireLiveAccount(customerId);
        // The shop row (FOR SHARE) before the booking's row, the same order as booking and suspending a shop
        UUID shopId = mine(customerId, appointments.find(bookingId)).shopId();
        ShopCard salon = shops.findForBooking(shopId).orElseThrow();   // see ShopDirectory.findForBooking
        Appointment a = lockMine(customerId, bookingId);
        Instant now = now();
        requireChangeable(a, now);
        if (Duration.between(now, a.startsAt()).compareTo(LATE_CANCEL) < 0) {
            // Otherwise moving first, then cancelling more than an hour ahead, would dodge the late-cancel strike
            throw new ApiException(HttpStatus.CONFLICT, "TOO_LATE_TO_MOVE",
                    "Less than 1 hour to go: this booking can't be moved any more. You can still cancel it.");
        }
        SalonSetups.Current current = setups.current(a.shopId());
        if (!"APPROVED".equals(salon.status()) || current.pausedAt(now)) {
            throw new ApiException(HttpStatus.CONFLICT, "SALON_NOT_BOOKABLE",
                    "The salon isn't taking online changes right now (paused, or its details are being checked "
                            + "again). Your booking stays; you can still cancel it.");
        }
        if (newStart.equals(a.startsAt())) {
            return view(a, now);
        }
        requireChangesLeft(customerId);
        List<BookedService> booked = appointments.services(List.of(a.id())).getOrDefault(a.id(), List.of());
        Duration length = SalonCalendar.length(booked);
        List<SalonSetups.Barber> barbers = calendar.barbersFor(current.setup(), SalonDayService.ids(booked));
        Interval window = calendar.window(current.setup(), now);
        requireInWindow(newStart, window);
        Interval slot = Interval.of(newStart, length);
        requireNoOverlap(customerId, slot, a.id());
        LocalDate date = LocalDate.ofInstant(newStart, SalonTime.ZONE);
        List<BarberDay> days = calendar.setupDays(barbers, date, a.id());
        List<UUID> candidates = new ArrayList<>(Availability.barbersFor(days, slot));
        if (candidates.remove(a.staffId())) {
            candidates.add(0, a.staffId());   // keep the same barber when he is free
        }
        // Lock the barber it goes to, his own one too: new hours or services saved for a barber take his row's lock,
        // so once we hold it, a change made meanwhile is seen (stillFits) and the move can't land outside them. The
        // first one still active who still fits under the setup as it is now.
        List<UUID> serviceIds = SalonDayService.ids(booked);
        while (!candidates.isEmpty() && (!appointments.lockActiveBarber(candidates.get(0))
                || !calendar.stillFits(a.shopId(), current.setup().version(), candidates.get(0), serviceIds, slot))) {
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
        if (!moved.staffId().equals(a.staffId())) {
            // Another barber now: his own-only screens drop it (AppointmentStore.reassignedFrom), and he can't act on it
            appointments.event(a.id(), now, customerId, "BARBER_CHANGED", a.staffId() + " -> " + moved.staffId());
        }
        countChange(customerId);
        notices.rescheduled(a, moved, salon, now);
        return view(moved, now);
    }

    // ---------- rules ----------

    /**
     * Checked after taking the customer's lock: an account deleted while this request waited (or used with a
     * token from before the deletion while Redis is down) can't book or move. 401, like any signed-out call.
     */
    private void requireLiveAccount(UUID customerId) {
        if (!users.isActive(customerId)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "NOT_SIGNED_IN", "Sign in first.");
        }
    }

    private static String changesKey(UUID customerId) {
        return "booking-changes:" + customerId;
    }

    /**
     * 429 TOO_MANY_CHANGES once CHANGES_PER_DAY bookings and moves were made in 24 hours. Only changes that
     * happened count (countChange): a refused attempt (a taken time, a closed day) never uses one up. If Redis
     * doesn't answer, the change goes ahead: the other limits (2 upcoming bookings, strikes) still hold.
     */
    private void requireChangesLeft(UUID customerId) {
        String key = changesKey(customerId);
        try {
            if (limits.current(key) >= CHANGES_PER_DAY) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_CHANGES",
                        "You've booked or moved " + CHANGES_PER_DAY + " times today. Try again tomorrow.")
                        .retryAfter(limits.secondsLeft(key));
            }
        } catch (DataAccessException redisDown) {
            log.warn("Redis didn't answer the booking-change count ({}); letting it through",
                    redisDown.getClass().getSimpleName());
        }
    }

    /** Counts one booking or move that was made (this customer's calls run one at a time, under their lock). */
    private void countChange(UUID customerId) {
        try {
            limits.hit(changesKey(customerId), Duration.ofHours(24));
        } catch (DataAccessException redisDown) {
            log.warn("Redis didn't take the booking-change count ({})", redisDown.getClass().getSimpleName());
        }
    }

    /** 409 OVERLAPS_YOUR_BOOKING if this person already holds a booking at that time (except the one moved). */
    private void requireNoOverlap(UUID customerId, Interval slot, UUID exceptId) {
        if (appointments.customerHasOverlap(customerId, slot.start(), slot.end(), exceptId)) {
            throw new ApiException(HttpStatus.CONFLICT, "OVERLAPS_YOUR_BOOKING",
                    "You already have a booking at that time. Pick another time, or cancel the other booking.");
        }
    }

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

    /**
     * From when cancelling this booking counts as late (a strike): 1 hour before its start, but never within 10
     * minutes of it being made. Cancelling exactly at this moment is still on time.
     */
    static Instant lateCancelFrom(Appointment a) {
        Instant beforeStart = a.startsAt().minus(LATE_CANCEL);
        Instant afterBooking = a.createdAt().plus(FREE_CANCEL_AFTER_BOOKING);
        return beforeStart.isAfter(afterBooking) ? beforeStart : afterBooking;
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

    /** 409 SALON_NOT_BOOKABLE while the salon has paused online bookings (read with its counters: SalonSetups). */
    private static void requireNotPaused(SalonSetups.Current current, Instant now) {
        if (current.pausedAt(now)) {
            throw new ApiException(HttpStatus.CONFLICT, "SALON_NOT_BOOKABLE",
                    "This salon isn't taking online bookings right now. Try again later.");
        }
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

    // ---------- views ----------

    private BookingView view(Appointment a, Instant now) {
        return views(List.of(a), now).get(0);
    }

    private List<BookingView> views(List<Appointment> list, Instant now) {
        Map<UUID, List<BookedService>> booked = appointments.services(list.stream().map(Appointment::id).toList());
        // Every salon of the page and its pause in one query each (a customer's list holds a few salons, many bookings)
        Set<UUID> shopIds = list.stream().map(Appointment::shopId).collect(Collectors.toSet());
        Map<UUID, ShopCard> salons = shops.findAll(shopIds);
        Map<UUID, Instant> pauses = salonAccess.pausedUntil(shopIds);
        Map<UUID, Boolean> pausedNow = new HashMap<>();
        for (UUID id : shopIds) {
            if (!salons.containsKey(id)) {
                throw new IllegalStateException("A booking's salon is missing: " + id);
            }
            Instant until = pauses.get(id);
            pausedNow.put(id, until != null && until.isAfter(now));
        }
        return list.stream().map(a -> {
            ShopCard s = salons.get(a.shopId());
            List<BookedService> lines = booked.getOrDefault(a.id(), List.of());
            boolean changeable = a.status() == AppointmentStatus.CONFIRMED && now.isBefore(a.startsAt());
            boolean movable = changeable && "APPROVED".equals(s.status()) && !pausedNow.get(s.id())
                    && Duration.between(now, a.startsAt()).compareTo(LATE_CANCEL) >= 0;
            return new BookingView(a.id(), a.bookingNo(), s.id(), s.name(),
                    s.addressLine() + ", " + s.area() + ", " + s.city() + " " + s.pincode(), SalonNotices.mapLink(s),
                    ServiceLine.from(lines), lines.stream().mapToLong(BookedService::pricePaise).sum(),
                    lines.stream().anyMatch(BookedService::priceIsFrom), a.startsAt(), a.endsAt(),
                    a.startsAt().plus(SalonDayService.LATE_ALLOWANCE), a.status(),
                    a.status() == AppointmentStatus.CONFIRMED ? a.startCode() : null,
                    a.cancelReason(), changeable, movable, a.createdAt(), lateCancelFrom(a));
        }).toList();
    }

    private static ApiException outsideWindow(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "OUTSIDE_WINDOW", message);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
