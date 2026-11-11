package in.apnidukan.salon.api;

import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.common.context.ShopDirectory;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.salon.api.BookingRequests.RateVisit;
import in.apnidukan.salon.api.BookingRequests.SalonPreferences;
import in.apnidukan.salon.api.BookingResponses.OfferMessagesView;
import in.apnidukan.salon.api.BookingResponses.SalonPreferencesView;
import in.apnidukan.salon.api.SalonOfferRequests.OfferMessages;
import in.apnidukan.salon.api.BookingRequests.Book;
import in.apnidukan.salon.api.BookingRequests.EarlierAlert;
import in.apnidukan.salon.api.BookingRequests.Reschedule;
import in.apnidukan.salon.api.BookingResponses.BookingView;
import in.apnidukan.salon.api.BookingResponses.CancelResult;
import in.apnidukan.salon.api.BookingResponses.SalonDetails;
import in.apnidukan.salon.api.BookingResponses.SalonService;
import in.apnidukan.salon.api.BookingResponses.SalonSummary;
import in.apnidukan.salon.api.BookingResponses.Slots;
import in.apnidukan.salon.domain.AppointmentCursor;
import in.apnidukan.salon.service.BookingService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The customer app: find a salon, see free times, book, and manage my bookings. Sign-in needed. */
@RestController
public class BookingController {

    private final BookingService bookings;
    private final CurrentUserProvider currentUser;

    public BookingController(BookingService bookings, CurrentUserProvider currentUser) {
        this.bookings = bookings;
        this.currentUser = currentUser;
    }

    /**
     * Approved salons within 2 km of the point, nearest first, 30 at a time. For the next page send the last
     * salon's `distanceMetres` and `id` as `afterDistance` and `afterId` (both, or neither); a page shorter than 30
     * is the last one.
     */
    @GetMapping("/api/v1/salons")
    public List<SalonSummary> salons(@RequestParam BigDecimal lat, @RequestParam BigDecimal lng,
                                     @RequestParam(required = false) Double afterDistance,
                                     @RequestParam(required = false) UUID afterId) {
        if ((afterDistance == null) != (afterId == null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid",
                    Map.of("afterId", "send afterDistance and afterId together"));
        }
        if (afterDistance != null && !(afterDistance >= 0 && afterDistance <= 1_000_000)) {   // also NaN, Infinity
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid",
                    Map.of("afterDistance", "must be a distance in metres from the last salon of the page before"));
        }
        ShopDirectory.Cursor after = afterId == null ? null : new ShopDirectory.Cursor(afterDistance, afterId);
        return bookings.salonsNear(lat, lng, after);
    }

    /** A salon's page. Counts towards the person's salon reads (429 TOO_MANY_REQUESTS past 60 a minute). */
    @GetMapping("/api/v1/salons/{id}")
    public SalonDetails salon(@PathVariable UUID id) {
        return bookings.salon(me(), id);
    }

    @GetMapping("/api/v1/salons/{id}/services")
    public List<SalonService> services(@PathVariable UUID id) {
        return bookings.salonServices(me(), id);
    }

    /** Free start times for these services on this India date. */
    @GetMapping("/api/v1/salons/{id}/availability")
    public Slots availability(@PathVariable UUID id, @RequestParam List<UUID> services,
                              @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate date,
                              @RequestParam(required = false) UUID moving) {
        return bookings.availability(me(), id, services, date, moving);
    }

    /** Book (Idempotency-Key header required). 201. */
    @PostMapping("/api/v1/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingView book(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                            @Valid @RequestBody Book body) {
        return bookings.book(me(), key, body);
    }

    /**
     * My bookings: when=upcoming (default, all of them) or past (30 a page, latest first; the next page with the last
     * one's startsAt and id as afterStart and afterId, both or neither; fewer than 30 is the last page).
     */
    @GetMapping("/api/v1/me/bookings")
    public List<BookingView> mine(@RequestParam(defaultValue = "upcoming") String when,
                                  @RequestParam(required = false) Instant afterStart,
                                  @RequestParam(required = false) UUID afterId) {
        return bookings.myBookings(me(), !"past".equalsIgnoreCase(when), AppointmentCursor.of(afterStart, afterId));
    }

    @GetMapping("/api/v1/bookings/{id}")
    public BookingView booking(@PathVariable UUID id) {
        return bookings.booking(me(), id);
    }

    /** Cancel; countedAsLate says if it was less than 1 hour before (a strike). */
    @PostMapping("/api/v1/bookings/{id}/cancel")
    public CancelResult cancel(@PathVariable UUID id) {
        return bookings.cancel(me(), id);
    }

    /** Move to another free start. */
    @PostMapping("/api/v1/bookings/{id}/reschedule")
    public BookingView reschedule(@PathVariable UUID id, @Valid @RequestBody Reschedule body) {
        return bookings.reschedule(me(), id, body.startAt());
    }

    /**
     * "Tell me if an earlier slot opens" on or off. On needs a booking that can still be moved (409 WRONG_STATUS,
     * TOO_LATE_TO_MOVE); off works any time. Returns the booking.
     */
    @PutMapping("/api/v1/bookings/{id}/earlier-alert")
    public BookingView earlierAlert(@PathVariable UUID id, @Valid @RequestBody EarlierAlert body) {
        return bookings.earlierAlert(me(), id, body.on());
    }

    /**
     * Rate my completed visit, or change my rating: {stars 1-5, tags?, comment?}. Until rateUntil on the booking (14
     * days after the visit): then 409 RATING_CLOSED. 409 NOT_RATEABLE if the visit isn't completed. Returns the
     * booking with its rating.
     */
    @PostMapping("/api/v1/bookings/{id}/rating")
    public BookingView rate(@PathVariable UUID id, @Valid @RequestBody RateVisit body) {
        return bookings.rate(me(), id, body);
    }

    /**
     * My salon preferences: {comeBackReminders, salonOffers} ("Time for your next haircut?", and offer messages from
     * salons I visited; each on unless switched off).
     */
    @GetMapping("/api/v1/me/salon-preferences")
    public SalonPreferencesView preferences() {
        return bookings.preferences(me());
    }

    @PutMapping("/api/v1/me/salon-preferences")
    public SalonPreferencesView setPreferences(@Valid @RequestBody SalonPreferences body) {
        return bookings.setPreferences(me(), body.comeBackReminders(), body.salonOffers());
    }

    /**
     * Stops ({on: false}) or allows again one salon's offer messages for me; its offers still show on its card and
     * page. 404 SALON_NOT_FOUND if it isn't live.
     */
    @PutMapping("/api/v1/salons/{id}/offer-messages")
    public OfferMessagesView offerMessages(@PathVariable UUID id, @Valid @RequestBody OfferMessages body) {
        return bookings.setOfferMessages(me(), id, body.on());
    }

    private UUID me() {
        return currentUser.get().userId();
    }
}
