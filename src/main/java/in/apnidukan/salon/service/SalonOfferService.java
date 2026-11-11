package in.apnidukan.salon.service;

import in.apnidukan.common.context.ShopDirectory;
import in.apnidukan.common.context.ShopDirectory.ShopCard;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.notify.Notifications;
import in.apnidukan.common.notify.Notifications.Notice;
import in.apnidukan.salon.api.SalonOfferRequests.NewOffer;
import in.apnidukan.salon.api.SalonOfferResponses.OfferPreview;
import in.apnidukan.salon.api.SalonOfferResponses.OfferState;
import in.apnidukan.salon.api.SalonOfferResponses.OfferView;
import in.apnidukan.salon.api.SalonOfferResponses.OwnerOffer;
import in.apnidukan.salon.api.SalonOfferResponses.PricedService;
import in.apnidukan.salon.domain.Availability;
import in.apnidukan.salon.domain.SalonOffer;
import in.apnidukan.salon.domain.BookedService;
import in.apnidukan.salon.domain.SalonTime;
import in.apnidukan.salon.domain.Shift;
import in.apnidukan.salon.repo.AppointmentStore;
import in.apnidukan.salon.repo.SalonOfferStore;
import in.apnidukan.salon.repo.SalonOpenDayRepository;
import in.apnidukan.salon.service.SalonAccess.Caller;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Salon offers on the owner's side: a discount for some days (and hours) that he publishes whenever he likes; the
 * message goes at once to the salon's past customers. Customers' side (prices, the switches): BookingService.
 */
@Service
public class SalonOfferService {

    /** How far ahead an offer can last: its last day at most this many days after today. */
    static final int MAX_DAYS_AHEAD = 90;

    /** The owner's list shows this many offers: every running or coming one first (so each can be ended), then the
     * newest ended ones. */
    static final int LIST_SIZE = 100;

    /**
     * The message's type: BOOKING_ so the customer app's list shows it (Audience.CUSTOMER), and not SALON_ (those are
     * the salon's own work messages, forgotten when someone leaves it).
     */
    static final String TYPE = "BOOKING_OFFER";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter PAUSE = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH)
            .withZone(SalonTime.ZONE);

    private final SalonAccess access;
    private final SalonOfferStore offers;
    private final AppointmentStore appointments;
    private final SalonOpenDayRepository openDays;
    private final SalonSetups setups;
    private final ShopDirectory shops;
    private final Notifications notifications;
    private final Clock clock;

    public SalonOfferService(SalonAccess access, SalonOfferStore offers, AppointmentStore appointments,
                             SalonOpenDayRepository openDays, SalonSetups setups, ShopDirectory shops,
                             Notifications notifications, Clock clock) {
        this.access = access;
        this.offers = offers;
        this.appointments = appointments;
        this.openDays = openDays;
        this.setups = setups;
        this.shops = shops;
        this.notifications = notifications;
        this.clock = clock;
    }

    /** The salon's offers, newest first (owner and helpers). */
    @Transactional(readOnly = true)
    public List<OwnerOffer> list(UUID userId) {
        Caller c = access.ownerOrHelper(userId);
        LocalDate today = today(clock.instant());
        return offers.list(c.shopId(), today, LIST_SIZE).stream()
                .map(l -> ownerView(l.offer(), l.bookings(), today))
                .toList();
    }

    /** What publishing this offer would do: both prices of each service, and how many customers it would tell. */
    @Transactional(readOnly = true)
    public OfferPreview preview(UUID userId, NewOffer in) {
        Caller c = access.owner(userId);
        Checked checked = check(c, in, clock.instant());
        return new OfferPreview(checked.priced(), offers.countRecipients(c.shopId()));
    }

    /**
     * Publishes the offer (owner only) and queues its message to the salon's past customers at once. Checks, in
     * order: the request's fields (400, before anything is read), the salon is live (409 SALON_NOT_LIVE), the times
     * and dates (400), the salon has services (409 NO_SERVICES) and these are among them (400), the first day inside
     * the booking window (409 OFFER_TOO_FAR), bookings not paused over its start (409 SALON_PAUSED), opening hours
     * set (409 NO_SALON_HOURS), its hours inside the salon's on every open day (409 OFFER_OUTSIDE_HOURS, naming the
     * days), some opening time left in its dates (409 NO_OPEN_TIME), and no running or coming offer on the same
     * services at the same time (409 OFFER_OVERLAPS). A double tap: the second is refused as an overlap, so nobody is
     * told twice.
     */
    @Transactional
    public OwnerOffer publish(UUID userId, NewOffer in) {
        Caller c = access.owner(userId);
        // Who to tell, read before the locks below (a big salon's read doesn't hold up its day): someone switching
        // offers off at this very moment may still get this one
        List<UUID> to = offers.recipients(c.shopId());
        appointments.lockSalonCalendar(c.shopId());   // two offers published at once are checked one after the other
        // Bookings in progress finish first, and those after read the new offer (see ShopDirectory.holdAgainstBookings)
        shops.holdAgainstBookings(c.shopId());
        Instant now = clock.instant();
        Checked checked = check(c, in, now);
        SalonOffer offer = new SalonOffer(UUID.randomUUID(), c.shopId(), in.percentOff(), checked.all(),
                checked.all() ? Set.of() : checked.serviceIds(), in.startsOn(), in.endsOn(), checked.fromMin(),
                checked.toMin(), null, to.size(), now);
        offers.insert(offer, userId);
        if (!to.isEmpty()) {
            String title = cut(offer.percentOff() + "% off at " + checked.salon().name(), 100);
            String body = cut(what(checked) + " · " + when(offer) + ". Book in the app.", 500);
            String url = "/salons/" + c.shopId() + "?offer=" + offer.id();
            notifications.queueAll(to.stream()
                    .map(u -> new Notice(u, TYPE, offer.id(), now, title, body, url, Map.of()))
                    .toList());
        }
        return ownerView(offer, 0, today(now));
    }

    /**
     * Ends the offer now (owner only): bookings made from now on are at the normal price; those already made keep
     * theirs, and messages about it still waiting aren't sent. 404 if not this salon's; 409 OFFER_OVER if it already
     * ended (early, or its last day is past).
     */
    @Transactional
    public OwnerOffer end(UUID userId, UUID offerId) {
        Caller c = access.owner(userId);
        // A booking in progress (it read the offer) finishes first; one after this reads it ended. The same lock as a
        // pause (ShopDirectory.holdAgainstBookings), taken before the offer's row
        shops.holdAgainstBookings(c.shopId());
        SalonOffer offer = offers.lock(c.shopId(), offerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "OFFER_NOT_FOUND", "No such offer."));
        Instant now = clock.instant();
        LocalDate today = today(now);
        if (offer.ended() || offer.endsOn().isBefore(today)) {
            throw new ApiException(HttpStatus.CONFLICT, "OFFER_OVER", "This offer has already ended.");
        }
        offers.end(offer.id(), now);
        notifications.cancelPending(offer.id(), TYPE);
        int bookings = offers.bookings(offer.id());
        return ownerView(new SalonOffer(offer.id(), offer.shopId(), offer.percentOff(), offer.allServices(),
                offer.serviceIds(), offer.startsOn(), offer.endsOn(), offer.fromMin(), offer.toMin(), now,
                offer.notified(), offer.createdAt()), bookings, today);
    }

    // ---------- the checks ----------

    /** An offer that passed every check, with what the preview and the message need. */
    record Checked(ShopCard salon, boolean all, Set<UUID> serviceIds, Integer fromMin, Integer toMin,
                   List<PricedService> priced, List<String> names) {
    }

    private Checked check(Caller c, NewOffer in, Instant now) {
        ShopCard salon = shops.find(c.shopId()).filter(s -> "APPROVED".equals(s.status()))
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "SALON_NOT_LIVE",
                        "Offers can be published once your salon is live."));
        if ((in.from() == null) != (in.until() == null)) {
            throw SalonCalendar.invalid(in.from() == null ? "from" : "until", "set both times, or neither");
        }
        Integer fromMin = in.from() == null ? null : SalonTime.minutes("from", in.from());
        Integer toMin = in.until() == null ? null : SalonTime.minutes("until", in.until());
        if (fromMin != null && fromMin >= toMin) {
            throw SalonCalendar.invalid("until", "must be after the start time");
        }
        LocalDate today = today(now);
        if (in.startsOn().isBefore(today)) {
            throw SalonCalendar.invalid("startsOn", "must be today or later");
        }
        if (in.endsOn().isBefore(in.startsOn())) {
            throw SalonCalendar.invalid("endsOn", "must be on or after the first day");
        }
        if (in.endsOn().isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
            throw SalonCalendar.invalid("endsOn", "must be at most " + MAX_DAYS_AHEAD + " days from today");
        }
        // The services: the salon's active ones (as bookings see them), all of them or those picked
        SalonSetups.Current current = setups.current(c.shopId());
        List<BookedService> active = current.setup().services();
        if (active.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_SERVICES", "Add your services before an offer.");
        }
        boolean all = in.serviceIds().isEmpty();
        Set<UUID> picked = new HashSet<>(in.serviceIds());
        Set<UUID> known = active.stream().map(BookedService::serviceId).collect(Collectors.toSet());
        if (!known.containsAll(picked)) {
            throw SalonCalendar.invalid("serviceIds", "must be your salon's services that are switched on");
        }
        List<BookedService> covered = active.stream().filter(s -> all || picked.contains(s.serviceId())).toList();
        // Customers can book only so far ahead: a message about days nobody can book yet would only disappoint
        LocalDate lastBookable = today.plusDays(current.setup().maxDaysAhead());
        if (in.startsOn().isAfter(lastBookable)) {
            throw new ApiException(HttpStatus.CONFLICT, "OFFER_TOO_FAR", "Customers can book up to "
                    + current.setup().maxDaysAhead() + " days ahead, so start the offer by "
                    + DAY.format(lastBookable) + ".", Map.of("lastStart", lastBookable.toString()));
        }
        Instant firstMoment = Availability.dayStart(in.startsOn()).plusSeconds(60L * (fromMin == null ? 0 : fromMin));
        if (current.pausedAt(now) && current.pausedUntil().isAfter(firstMoment)) {
            throw new ApiException(HttpStatus.CONFLICT, "SALON_PAUSED", "Online bookings are paused until "
                    + PAUSE.format(current.pausedUntil()) + ". Resume them first, or start the offer after that.");
        }
        requireInsideHours(c.shopId(), in.startsOn(), in.endsOn(), fromMin, toMin, now);
        for (SalonOffer other : offers.current(c.shopId(), in.startsOn())) {
            // An offer whose services are all switched off since covers nothing any more: not in the way
            Set<UUID> stillOn = other.allServices() ? known
                    : other.serviceIds().stream().filter(known::contains).collect(Collectors.toSet());
            if (!stillOn.isEmpty() && other.overlaps(in.startsOn(), in.endsOn(), fromMin, toMin, all, picked)
                    && (other.allServices() || all || picked.stream().anyMatch(stillOn::contains))) {
                throw new ApiException(HttpStatus.CONFLICT, "OFFER_OVERLAPS", "It overlaps your " + other.percentOff()
                        + "% offer (" + when(other) + ") on the same services. Pick other days, hours or services, "
                        + "or end that offer first.", Map.of("offerId", other.id()));
            }
        }
        List<PricedService> priced = covered.stream()
                .map(s -> new PricedService(s.serviceId(), s.name(), s.pricePaise(),
                        SalonOffer.priceOf(s.pricePaise(), in.percentOff()), s.priceIsFrom()))
                .toList();
        return new Checked(salon, all, picked, fromMin, toMin, priced, covered.stream().map(BookedService::name).toList());
    }

    /**
     * The offer's hours must lie inside the salon's opening hours on every day it is open in the offer's dates
     * (weekly offs and closed days are skipped: nobody books then), and some of that time must still be ahead.
     */
    private void requireInsideHours(UUID shopId, LocalDate starts, LocalDate ends, Integer fromMin, Integer toMin,
                                    Instant now) {
        Map<Integer, Shift> week = new LinkedHashMap<>();
        openDays.findByShopIdOrderByWeekdayAsc(shopId).forEach(d -> week.put(d.day().weekday(), d.day()));
        if (week.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_SALON_HOURS", "Set your opening hours first.");
        }
        Set<LocalDate> closed = appointments.closures(shopId, Availability.dayStart(starts)).stream()
                .map(cl -> LocalDate.ofInstant(cl.startsAt(), SalonTime.ZONE))
                .collect(Collectors.toSet());
        LocalDate today = today(now);
        int minuteNow = LocalTime.ofInstant(now, SalonTime.ZONE).toSecondOfDay() / 60;
        Map<Integer, Shift> outside = new LinkedHashMap<>();
        boolean timeLeft = false;
        for (LocalDate d = starts; !d.isAfter(ends); d = d.plusDays(1)) {
            Shift open = week.get(d.getDayOfWeek().getValue());
            if (open == null || closed.contains(d)) {
                continue;
            }
            if (fromMin != null && (fromMin < open.startMin() || toMin > open.endMin())) {
                outside.putIfAbsent(open.weekday(), open);
                continue;
            }
            int endsAt = toMin != null ? toMin : open.endMin();
            timeLeft |= d.isAfter(today) || endsAt > minuteNow;
        }
        if (!outside.isEmpty()) {
            List<Map<String, Object>> days = new ArrayList<>();
            List<String> said = new ArrayList<>();
            for (Shift s : outside.values()) {
                String name = DayOfWeek.of(s.weekday()).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
                said.add(name + " " + SalonTime.format(s.startMin()) + "–" + SalonTime.format(s.endMin()));
                days.add(Map.of("weekday", s.weekday(), "open", SalonTime.format(s.startMin()),
                        "close", SalonTime.format(s.endMin())));
            }
            throw new ApiException(HttpStatus.CONFLICT, "OFFER_OUTSIDE_HOURS", "The offer's hours must be inside "
                    + "your opening hours: " + String.join(", ", said) + ".", Map.of("days", days));
        }
        if (!timeLeft) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_OPEN_TIME",
                    "Your salon isn't open at any time left in these dates.");
        }
    }

    // ---------- views and texts ----------

    static OwnerOffer ownerView(SalonOffer o, int bookings, LocalDate today) {
        OfferState state = o.ended() || o.endsOn().isBefore(today) ? OfferState.ENDED
                : o.startsOn().isAfter(today) ? OfferState.UPCOMING : OfferState.RUNNING;
        return new OwnerOffer(OfferView.of(o), state, o.createdAt(), o.endedAt(), o.notified(), bookings);
    }

    /** "12 Nov – 16 Nov, 12:00–16:00", "12 Nov" for one day; the hours only when set. */
    static String when(SalonOffer o) {
        String days = o.startsOn().equals(o.endsOn()) ? DAY.format(o.startsOn())
                : DAY.format(o.startsOn()) + " – " + DAY.format(o.endsOn());
        return o.fromMin() == null ? days
                : days + ", " + SalonTime.format(o.fromMin()) + "–" + SalonTime.format(o.toMin());
    }

    /** "All services", or up to 3 names and how many more. */
    private static String what(Checked c) {
        if (c.all()) {
            return "All services";
        }
        List<String> names = c.names();
        return names.size() <= 3 ? String.join(", ", names)
                : String.join(", ", names.subList(0, 3)) + " and " + (names.size() - 3) + " more";
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static LocalDate today(Instant now) {
        return LocalDate.ofInstant(now, SalonTime.ZONE);
    }
}
