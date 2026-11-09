package in.apnidukan.salon.service;

import in.apnidukan.common.context.ShopDirectory;
import in.apnidukan.common.context.ShopDirectory.ShopCard;
import in.apnidukan.salon.domain.Availability;
import in.apnidukan.salon.domain.Availability.BarberDay;
import in.apnidukan.salon.domain.BookedService;
import in.apnidukan.salon.domain.Interval;
import in.apnidukan.salon.domain.SalonTime;
import in.apnidukan.salon.repo.AppointmentStore;
import in.apnidukan.salon.repo.EarlierSlotAlertStore;
import in.apnidukan.salon.repo.EarlierSlotAlertStore.Candidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * "Tell me if an earlier slot opens": when a customer cancels or moves an online booking, the customers of the same
 * salon and day who switched the alert on and are booked later are offered the time that just opened, in the app and
 * by push. Nobody is moved: tapping the message opens their booking with Move on that time, and the first to move gets
 * it (the move itself is the usual one, with its checks; a time already taken answers SLOT_TAKEN with the nearest).
 *
 * <p>Who is offered what, for a time freed on one barber (the booking's own time, before it was cancelled or moved):
 * <ul>
 *   <li>bookings at that salon with the alert on, still waiting, starting after the freed time on the same day and
 *       at least 1 hour from now (a booking can't be moved closer to its start than that: BookingService.LATE_CANCEL),
 *       soonest first: the first {@link #MAX_OFFERS} the time suits (of the {@link #CANDIDATES} soonest);</li>
 *   <li>each is offered the earliest quarter-hour start inside the freed time (and the salon's booking window: its
 *       notice, e.g. 30 minutes from now), before its own, where that barber does all its services and is free for its
 *       whole length, no other barber who does them was free already (that time is no news), and which doesn't clash
 *       with the customer's other booking;</li>
 *   <li>only if that start isn't the one offered to that booking last time (EarlierSlotAlertStore.recordOffers): the
 *       same time is never sent twice in a row, but another one is, since the time offered before may have gone to
 *       whoever moved first.</li>
 * </ul>
 * Nothing is offered while the salon isn't taking online bookings (paused, or not approved), or if the barber is no
 * longer active.
 *
 * <p>When: once the cancel or move commits ({@link #freed}), on a small worker of its own (at most
 * {@link #WORKERS} at once, in a transaction of its own each), never on the customer's request: a change that rolls
 * back offers nothing, the offers see it (and any other change committed by then), and the customer's answer never
 * waits for them or holds a second database connection meanwhile. If the offers fail (the database is down a
 * moment), or more than {@link #QUEUE} freed times are waiting (a burst), the change stands and that offer is lost;
 * it is logged. A copy of the backend that stops loses the offers still waiting, the same way.
 *
 * <p>Cost: one read through ix_earlier_slot_alerts_shop_start, which ends it when nobody qualifies (most cancels).
 * Otherwise: the salon and its counters (SalonSetups), the barbers' busy time that day (one read, kept in memory while
 * the salon is unchanged: SalonCalendar), the candidates' services and bookings, one statement for the offers and one
 * batch of messages.
 */
@Component
public class EarlierSlotAlerts {

    /** At most this many bookings are offered one freed time: the soonest after it that it suits. */
    static final int MAX_OFFERS = 10;
    /** Bookings looked at for one freed time (soonest first) to find those MAX_OFFERS. */
    static final int CANDIDATES = 50;
    /** Freed times worked on at once, each with one database connection. */
    static final int WORKERS = 2;
    /** Freed times that may wait for a worker; beyond this a burst's offers are dropped (logged). */
    static final int QUEUE = 1_000;

    private static final Logger log = LoggerFactory.getLogger(EarlierSlotAlerts.class);

    private final EarlierSlotAlertStore alerts;
    private final AppointmentStore appointments;
    private final ShopDirectory shops;
    private final SalonSetups setups;
    private final SalonCalendar calendar;
    private final SalonNotices notices;
    private final Clock clock;
    private final TransactionTemplate ownTransaction;
    private final ThreadPoolExecutor worker;
    /** Freed times handed to the worker and not finished yet (tests wait for 0). */
    private final AtomicInteger inFlight = new AtomicInteger();

    public EarlierSlotAlerts(EarlierSlotAlertStore alerts, AppointmentStore appointments, ShopDirectory shops,
                             SalonSetups setups, SalonCalendar calendar, SalonNotices notices, Clock clock,
                             PlatformTransactionManager transactions) {
        this.alerts = alerts;
        this.appointments = appointments;
        this.shops = shops;
        this.setups = setups;
        this.calendar = calendar;
        this.notices = notices;
        this.clock = clock;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.worker = new ThreadPoolExecutor(WORKERS, WORKERS, 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(QUEUE), Thread.ofPlatform().name("earlier-slot-", 0).daemon().factory(),
                (task, pool) -> {
                    inFlight.decrementAndGet();
                    log.warn("Earlier-slot offers dropped: {} freed times already waiting", QUEUE);
                });
    }

    @PreDestroy
    void stop() {
        worker.shutdown();
    }

    /**
     * A booking's time on this barber was freed (cancelled or moved away by its customer). Called inside that change's
     * transaction: the offers are made once it commits (on the worker), and never if it rolls back.
     * @param booking  the booking itself, never offered its own time
     * @param customer whose it is: their other bookings aren't offered the time they just gave up
     */
    public void freed(UUID shopId, UUID staffId, Interval time, UUID booking, UUID customer) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            submit(shopId, staffId, time, booking, customer);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submit(shopId, staffId, time, booking, customer);
            }
        });
    }

    private void submit(UUID shopId, UUID staffId, Interval time, UUID booking, UUID customer) {
        inFlight.incrementAndGet();
        worker.execute(() -> {
            try {
                ownTransaction.executeWithoutResult(status -> offer(shopId, staffId, time, booking, customer));
            } catch (RuntimeException e) {
                // The cancel or move already stands; only the offer is lost
                log.warn("Earlier-slot offers for salon {} failed ({}); the change itself stands", shopId,
                        e.getClass().getSimpleName());
            } finally {
                inFlight.decrementAndGet();
            }
        });
    }

    /**
     * Waits until every freed time handed over so far has been offered (or the time is up). For tests: offers are made
     * after the customer's answer.
     * @return true if nothing is left
     */
    public boolean awaitIdle(Duration atMost) throws InterruptedException {
        long until = System.nanoTime() + atMost.toNanos();
        while (inFlight.get() > 0) {
            if (System.nanoTime() > until) {
                return false;
            }
            Thread.sleep(10);
        }
        return true;
    }

    /**
     * Works out and sends the offers for one freed time, in the caller's transaction. Returns how many bookings were
     * told. Called by tests too.
     */
    int offer(UUID shopId, UUID staffId, Interval freed, UUID booking, UUID customer) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Optional<ShopCard> salon = shops.find(shopId)
                .filter(c -> "SALON".equals(c.type()) && "APPROVED".equals(c.status()));
        if (salon.isEmpty()) {
            return 0;
        }
        SalonSetups.Current current = setups.current(shopId);
        if (current.pausedAt(now)) {
            return 0;
        }
        SalonSetups.Setup setup = current.setup();
        Optional<SalonSetups.Barber> barber = setup.barber(staffId);
        Interval window = calendar.window(setup, now);
        if (barber.isEmpty() || !freed.end().isAfter(window.start()) || !freed.start().isBefore(window.end())) {
            // Switched off since, or no part of the freed time can be booked online (too soon, or beyond the last
            // bookable day after the salon shortened it)
            return 0;
        }
        LocalDate date = LocalDate.ofInstant(freed.start(), SalonTime.ZONE);
        Instant dayEnd = Availability.dayStart(date.plusDays(1));
        List<Candidate> candidates = alerts.candidates(shopId, freed.start(), now.plus(BookingService.LATE_CANCEL),
                dayEnd, booking, CANDIDATES);
        if (candidates.isEmpty()) {
            return 0;
        }
        // Every barber's day (one read, or none while the salon is unchanged: SalonCalendar keeps it), to know which
        // times were free before: with another barber, a time is no news
        List<BarberDay> days = calendar.setupDays(current, setup.barbers(), date);
        List<UUID> ids = candidates.stream().map(Candidate::id).toList();
        Map<UUID, List<BookedService>> services = appointments.services(ids);
        Map<UUID, List<EarlierSlotAlertStore.Held>> held = alerts.heldBy(
                candidates.stream().map(Candidate::customerId).distinct().toList(), now);
        Instant from = window.start().isAfter(freed.start()) ? window.start() : freed.start();
        List<EarlierSlotAlertStore.Offer> offers = new ArrayList<>();
        Map<UUID, Instant> offered = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            if (offers.size() == MAX_OFFERS) {
                break;
            }
            if (c.customerId().equals(customer)) {
                continue;   // they just gave this time up themselves
            }
            List<Interval> others = held.getOrDefault(c.customerId(), List.of()).stream()
                    .filter(h -> !h.id().equals(c.id()))
                    .map(EarlierSlotAlertStore.Held::time)
                    .toList();
            Optional<Instant> t = earliest(c, services.getOrDefault(c.id(), List.of()), barber.get(),
                    setup.barbers(), days, freed, from, others);
            // The time offered to it last is no news: it doesn't take one of the MAX_OFFERS places
            if (t.isPresent() && !t.get().equals(c.offeredStart())) {
                offers.add(new EarlierSlotAlertStore.Offer(c.id(), c.startsAt(), t.get()));
                offered.put(c.id(), t.get());
            }
        }
        Set<UUID> recorded = alerts.recordOffers(offers);
        Map<Candidate, Instant> told = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            if (recorded.contains(c.id())) {
                told.put(c, offered.get(c.id()));
            }
        }
        if (!told.isEmpty()) {
            notices.earlierSlots(told, salon.get(), barber.get().name(), now);
        }
        return told.size();
    }

    /**
     * The earliest start offered to this booking, if any: on the quarter hour from {@code from} (the freed time's
     * start, or the salon's notice if later) until the freed time ends and before the booking's own start, where
     * <ul>
     *   <li>the freed barber does all its services and is free for its whole length (its own time on him counts as
     *       free: moving would leave it);</li>
     *   <li>no other barber who does them was free for it already (then the time is no news: it could have been
     *       booked or moved to before);</li>
     *   <li>and it doesn't clash with the customer's other bookings.</li>
     * </ul>
     * Whether it was offered to them last time is decided when it is recorded (EarlierSlotAlertStore.recordOffers).
     */
    static Optional<Instant> earliest(Candidate c, List<BookedService> services, SalonSetups.Barber freedBarber,
                                      List<SalonSetups.Barber> barbers, List<BarberDay> days, Interval freed,
                                      Instant from, List<Interval> otherBookings) {
        List<UUID> serviceIds = SalonDayService.ids(services);
        if (services.isEmpty() || !freedBarber.serviceIds().containsAll(serviceIds)) {
            return Optional.empty();
        }
        Duration length = SalonCalendar.length(services);
        Set<UUID> doTheServices = new HashSet<>();
        for (SalonSetups.Barber b : barbers) {
            if (b.serviceIds().containsAll(serviceIds)) {
                doTheServices.add(b.id());
            }
        }
        BarberDay freedDay = null;
        List<BarberDay> otherDays = new ArrayList<>();
        for (BarberDay d : days) {
            if (d.staffId().equals(freedBarber.id())) {
                freedDay = withoutOwnTime(d, c);
            } else if (doTheServices.contains(d.staffId())) {
                otherDays.add(withoutOwnTime(d, c));
            }
        }
        if (freedDay == null) {
            return Optional.empty();
        }
        Instant until = c.startsAt();
        if (freed.end().isBefore(until)) {
            until = freed.end();   // only starts inside the freed time
        }
        for (Instant t : Availability.starts(List.of(freedDay), length, from, until)) {
            Interval slot = Interval.of(t, length);
            if (otherDays.stream().noneMatch(d -> d.fits(slot)) && otherBookings.stream().noneMatch(slot::overlaps)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    /** The barber's day without this booking's own time, if it is with him (moving it would free that time). */
    private static BarberDay withoutOwnTime(BarberDay day, Candidate c) {
        if (!day.staffId().equals(c.staffId())) {
            return day;
        }
        List<Interval> busy = new ArrayList<>(day.busy());
        busy.remove(c.time());
        return new BarberDay(day.staffId(), day.working(), busy, day.bookedMinutes(), day.addedAt());
    }
}
