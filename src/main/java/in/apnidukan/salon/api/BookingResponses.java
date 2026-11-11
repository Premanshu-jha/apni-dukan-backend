package in.apnidukan.salon.api;

import in.apnidukan.common.context.ShopDirectory.ShopCard;
import in.apnidukan.salon.domain.Appointment;
import in.apnidukan.salon.domain.AppointmentCursor;
import in.apnidukan.salon.domain.AppointmentKind;
import in.apnidukan.salon.domain.AppointmentStatus;
import in.apnidukan.salon.domain.BlockType;
import in.apnidukan.salon.api.SalonOfferResponses.OfferView;
import in.apnidukan.salon.domain.BookedService;
import in.apnidukan.salon.domain.RatingTag;
import in.apnidukan.salon.domain.SalonTime;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What the booking, walk-in and daily-work endpoints return. Times are exact moments (ISO-8601, UTC). */
public final class BookingResponses {

    private BookingResponses() {
    }

    /**
     * A service of a booking or walk-in, as booked.
     * @param pricePaise     what the customer pays (with an offer: the offer price)
     * @param listPricePaise the price before the salon's offer; null if it was booked without one
     */
    public record ServiceLine(UUID serviceId, String name, int durationMinutes, long pricePaise,
                              boolean priceIsFrom, Long listPricePaise) {

        public static List<ServiceLine> from(List<BookedService> services) {
            return services.stream()
                    .map(s -> new ServiceLine(s.serviceId(), s.name(), s.durationMin(), s.pricePaise(),
                            s.priceIsFrom(), s.listPricePaise()))
                    .toList();
        }
    }

    // ---------- customer side ----------

    /**
     * A salon in the list near the customer: everything its card shows.
     * @param photoUrl      the salon's photo, a link valid for at least 15 more minutes; null if none
     * @param rating        its customers' ratings (average only from 5 ratings on: the card shows "New" before)
     * @param topServices   its first 3 services (the salon's order) that a barber does, with prices
     * @param serviceCount  how many such services in all (the card shows "+5 more")
     * @param today         today's opening hours; null if closed today or the salon hasn't set hours
     * @param openNow       true while open right now; false if closed now (or all day); null if no hours are set
     * @param closedToday   true on its weekly off or a closed day (holiday)
     * @param registered    true if the salon has its registration document (shops and establishments certificate,
     *                      trade licence or Udyam), checked by the field team; the card says "Registered", else
     *                      "Unregistered"
     * @param offer         the salon's offer running now or coming next (the card's badge); null if none
     */
    public record SalonSummary(UUID id, String name, String addressLine, String area, String city, double distanceKm,
                               String photoUrl, double distanceMetres, RatingSummary rating,
                               List<PriceLine> topServices, int serviceCount, DayHours today, Boolean openNow,
                               boolean closedToday, boolean registered, OfferBadge offer) {

        public SalonSummary {
            topServices = List.copyOf(topServices);
        }

        public static SalonSummary from(ShopCard card, String photoUrl, CardFacts facts) {
            return new SalonSummary(card.id(), card.name(), card.addressLine(), card.area(), card.city(),
                    card.distanceKm() == null ? 0 : card.distanceKm(), photoUrl,
                    card.distanceMetres() == null ? 0 : card.distanceMetres(), facts.rating(), facts.topServices(),
                    facts.serviceCount(), facts.today(), facts.openNow(), facts.closedToday(), facts.registered(),
                    facts.offer());
        }
    }

    /** A salon card's offer badge: "20% off · till 16 Nov" (or "from 14 Nov" while still to come). */
    public record OfferBadge(int percentOff, LocalDate startsOn, LocalDate endsOn) {
    }

    /** What a salon card shows besides the salon's own details (see SalonSummary). */
    public record CardFacts(RatingSummary rating, List<PriceLine> topServices, int serviceCount, DayHours today,
                            Boolean openNow, boolean closedToday, boolean registered, OfferBadge offer) {

        public CardFacts {
            topServices = List.copyOf(topServices);
        }
    }

    /** A service's name and price on a salon card ("from ₹150" when priceIsFrom). */
    public record PriceLine(String name, long pricePaise, boolean priceIsFrom) {
    }

    /**
     * A salon's ratings as customers see them.
     * @param count   how many customers rated it
     * @param average average stars to one decimal; null until it has 5 ratings ("New"), so one early rating can't
     *                define it
     * @param tags    what customers said went well, most mentioned first; only tags someone ticked
     */
    public record RatingSummary(int count, Double average, List<TagCount> tags) {

        public RatingSummary {
            tags = List.copyOf(tags);
        }
    }

    public record TagCount(RatingTag tag, String label, int count) {
    }

    /**
     * One salon's page.
     * @param hours per weekday (1 = Monday), the salon's opening hours; a day left out is its weekly off. (A salon
     *              that hasn't set them yet: the earliest start and latest end of any barber that day)
     * @param closedDates its closed days (holidays) from today to the end of its booking window
     * @param rating      its customers' ratings
     * @param pausedUntil set while the salon has paused online bookings
     * @param photoUrl    the salon's photo, a link valid for at least 60 more minutes; null if none
     * @param maxDaysAhead how many days after today it takes online bookings (1 to 30, the owner's setting): the app
     *                     draws today plus this many day chips
     * @param offers       its offers running now or still to come, soonest first
     * @param offerMessages whether this customer gets its offer messages: null if they haven't completed a visit
     *                     there (they don't get them anyway); otherwise false once they stopped them for this salon
     */
    public record SalonDetails(UUID id, String name, String addressLine, String area, String city, String pincode,
                               BigDecimal latitude, BigDecimal longitude, String mapLink, List<DayHours> hours,
                               Instant pausedUntil, String photoUrl, int maxDaysAhead, List<LocalDate> closedDates,
                               RatingSummary rating, List<OfferView> offers, Boolean offerMessages) {

        public SalonDetails {
            hours = List.copyOf(hours);
            closedDates = List.copyOf(closedDates);
            offers = List.copyOf(offers);
        }
    }

    /** An open day: weekday 1 = Monday ... 7 = Sunday, "HH:mm" in salon time ("24:00" = midnight). */
    public record DayHours(int weekday, String open, String close) {
    }

    /**
     * A service as customers see it.
     * @param offerPricePaise its price in the salon's best offer for it that is running or still to come (only at
     *                        that offer's days and hours: the free times give each start's price); null if none
     */
    public record SalonService(UUID id, String name, int durationMinutes, long pricePaise, boolean priceIsFrom,
                               Long offerPricePaise) {
    }

    /**
     * Start times for the chosen services on one date.
     * @param freeCancelMinutes a booking can be cancelled without a strike for this long after it is made (10),
     *                          whatever its start
     * @param starts exact moments; each is also given as "HH:mm" in India time
     */
    public record Slots(LocalDate date, int durationMinutes, int freeCancelMinutes, List<Slot> starts) {
    }

    /**
     * @param lateCancelFrom 1 hour before the start: a cancel after this moment counts as late (a strike), unless
     *                       it comes within freeCancelMinutes of booking. The confirm screen warns when it is less
     *                       than freeCancelMinutes away (the booking can be cancelled free only for those minutes)
     * @param pricePaise     what the chosen services cost at this start (with the salon's offer, if one applies)
     * @param listPricePaise their price without the offer; null when no offer applies at this start
     */
    public record Slot(Instant startAt, String time, Instant lateCancelFrom, long pricePaise, Long listPricePaise) {

        public static Slot of(Instant t, Instant lateCancelFrom, List<BookedService> priced) {
            long price = priced.stream().mapToLong(BookedService::pricePaise).sum();
            boolean offer = priced.stream().anyMatch(s -> s.offerId() != null);
            long list = priced.stream()
                    .mapToLong(s -> s.listPricePaise() == null ? s.pricePaise() : s.listPricePaise()).sum();
            return new Slot(t, SalonTime.format(t.atZone(SalonTime.ZONE).toLocalTime().toSecondOfDay() / 60),
                    lateCancelFrom, price, offer ? list : null);
        }
    }

    /**
     * A booking as its customer sees it.
     * @param startCode    the 4 digits to show at the salon; only while the booking is CONFIRMED
     * @param heldUntil    the salon keeps the booking until this moment (10 minutes after the start)
     * @param canChange    true while it can still be cancelled (CONFIRMED, before the start)
     * @param canMove      true while it can also be moved to another time (the salon is approved and not paused,
     *                     and it is more than 1 hour away)
     * @param lateCancelFrom a cancel after this moment counts as late (a strike): 1 hour before the start, or 10
     *                     minutes after booking if that is later
     * @param earlierAlert true if the customer asked to be told when an earlier time opens that day
     * @param rating       the customer's rating of this visit; null if not rated
     * @param rateUntil    while set, the customer can rate the visit (or change the rating) until this moment:
     *                     completed online bookings, for 14 days after the visit; null otherwise
     * @param totalListPricePaise the total before the salon's offer; null if booked without one
     */
    public record BookingView(UUID id, String bookingNo, UUID salonId, String salonName, String salonAddress,
                              String mapLink, List<ServiceLine> services, long totalPricePaise,
                              Long totalListPricePaise, boolean priceIsFrom,
                              Instant startsAt, Instant endsAt, Instant heldUntil, AppointmentStatus status,
                              String startCode, String cancelReason, boolean canChange,
                              boolean canMove, Instant createdAt, Instant lateCancelFrom, boolean earlierAlert,
                              MyRating rating, Instant rateUntil) {
    }

    /**
     * A customer's rating of one visit.
     * @param comment only the salon sees it; null if none
     */
    public record MyRating(int stars, List<RatingTag> tags, String comment, Instant ratedAt, Instant updatedAt) {

        public MyRating {
            tags = List.copyOf(tags);
        }
    }

    /**
     * The customer's salon preferences.
     * @param comeBackReminders "Time for your next haircut?" after a visit
     * @param salonOffers       offer messages from salons they visited (each salon can also be muted on its page)
     */
    public record SalonPreferencesView(boolean comeBackReminders, boolean salonOffers) {
    }

    /** Whether the customer gets this salon's offer messages. */
    public record OfferMessagesView(boolean on) {
    }

    // ---------- ratings on the salon's side ----------

    /**
     * A page of the salon's ratings, newest first.
     * @param summary all of the salon's ratings (with the average from the first rating on); first page only (null on
     *                later ones: keep the first page's)
     * @param next    the next page's query values (afterAt, afterId); null on the last page
     */
    public record RatingsPage(RatingSummary summary, List<SalonRating> items, RatingsNext next) {

        public RatingsPage {
            items = List.copyOf(items);
        }
    }

    /**
     * One customer's rating of a visit, as the salon sees it.
     * @param customerName who rated it ("Customer" if they have no name: never set, or the account was deleted)
     * @param comment      null if none (or the account was deleted)
     * @param visitAt      when the booking started
     */
    public record SalonRating(UUID bookingId, String bookingNo, int stars, List<RatingTag> tags, String comment,
                              List<String> services, String customerName, Instant visitAt, Instant ratedAt) {

        public SalonRating {
            tags = List.copyOf(tags);
            services = List.copyOf(services);
        }
    }

    public record RatingsNext(Instant afterAt, UUID afterId) {
    }

    /**
     * @param countedAsLate true if it was less than 1 hour before the start and more than 10 minutes after booking
     *                      (a strike)
     */
    public record CancelResult(BookingView booking, boolean countedAsLate) {
    }

    // ---------- salon side ----------

    /**
     * An appointment on the salon's screens. Never carries the customer's start code.
     * @param customerName the customer's name (or "Customer" if they haven't set one); bookings only
     * @param customerPhone the customer's phone, to call someone running late; bookings only, and only for the
     *                      owner and helpers (null for barbers)
     * @param notStarted   true if it is CONFIRMED and its start has passed: the screen shows "not started" so staff
     *                     press Start or No-show
     * @param noShowFrom   bookings and walk-ins: No-show is allowed from this moment
     * @param addedByMe    whether the person asking added it (a barber may remove only the breaks he added)
     */
    public record AppointmentView(UUID id, AppointmentKind kind, BlockType blockType, UUID staffId,
                                  AppointmentStatus status, Instant startsAt, Instant endsAt, Instant startedAt,
                                  Boolean startedWithCode, List<ServiceLine> services, String bookingNo,
                                  String customerName, String customerPhone, String guestName,
                                  String cancelReason, UUID closureId, boolean notStarted, Instant noShowFrom,
                                  boolean addedByMe) {

        public static AppointmentView of(Appointment a, List<BookedService> services, String customerName,
                                         String customerPhone, Instant now, Instant noShowFrom, UUID viewer) {
            boolean notStarted = a.status() == AppointmentStatus.CONFIRMED && !now.isBefore(a.startsAt());
            return new AppointmentView(a.id(), a.kind(), a.blockType(), a.staffId(), a.status(), a.startsAt(),
                    a.endsAt(), a.startedAt(), a.startedWithCode(), ServiceLine.from(services), a.bookingNo(),
                    customerName, customerPhone, a.guestName(), a.cancelReason(), a.closureId(),
                    notStarted, a.kind() == AppointmentKind.BLOCK ? null : noShowFrom,
                    viewer != null && viewer.equals(a.createdBy()));
        }
    }

    /**
     * The owner's look back over a range of days, one page at a time.
     * @param counts            how many appointments ended in each status (COMPLETED, NO_SHOW, ...), whole range;
     *                          on the first page only (null on later pages: the app keeps the first page's)
     * @param completedPaise    the list prices of everything COMPLETED ("from" prices count at their base), whole range;
     *                          first page only, like counts
     * @param appointments      bookings and walk-ins in the range, latest first, at most 100 on this page
     * @param truncated         true when there is another page (same as next != null; kept for apps that show a
     *                          "pick fewer days" note instead of loading more)
     * @param next              the next page's afterStart and afterId; null on the last page
     */
    public record HistoryView(LocalDate from, LocalDate to, Map<String, Long> counts, Long completedPaise,
                              List<AppointmentView> appointments, boolean truncated, AppointmentCursor next) {
    }

    /**
     * The day screen: one column per barber, or (GET /salon/day?since=) only what changed since an earlier answer.
     * @param barbers      the whole day (full answers); null in a delta
     * @param myStaffId    the caller's own barber profile (a barber), so the app knows which column is his; else null
     * @param full         true: barbers is the whole day (replace what the app holds); false: a delta
     * @param seq          send this back as since= to get only what changed after this answer (opaque)
     * @param setupVersion the salon's setup this answer was made under (for information: a change makes the next
     *                     delta a full answer by itself)
     * @param changed      a delta's appointments that are new or changed on this board, in the order they were
     *                     written: replace by id (or add to the barber's column by staffId); null in full answers
     * @param removed      a delta's appointment ids that are no longer on this board (moved to another day or barber,
     *                     a removed block): drop them if shown; null in full answers
     */
    public record DayView(LocalDate date, List<BarberColumn> barbers, UUID myStaffId, boolean full, String seq,
                          long setupVersion, List<AppointmentView> changed, List<UUID> removed) {
    }

    /**
     * The upcoming list's delta (GET /salon/upcoming?since=).
     * @param full    true: load the pages again (GET /salon/upcoming), then go on with seq
     * @param changed bookings new or changed on the list, in the order they were written (null when full)
     * @param removed ids no longer on the list (null when full)
     */
    public record UpcomingChanges(boolean full, String seq, long setupVersion, List<AppointmentView> changed,
                                  List<UUID> removed) {
    }

    /** @param hours his working hours that day, "HH:mm" in India time */
    public record BarberColumn(UUID staffId, String name, List<HoursBlock> hours, List<AppointmentView> appointments) {
    }

    public record HoursBlock(String start, String end) {
    }

    /**
     * A walk-in suggestion for one barber.
     * @param freeNow true if he can start now
     * @param startAt now, or his earliest later start today; null if he can't fit it in today
     */
    public record WalkInOption(UUID staffId, String name, boolean freeNow, Instant startAt, String time,
                               long bookedMinutesToday) {
    }

    /**
     * Someone in the salon, for the owner's walk-in access screen.
     * @param staffId his barber row if he is a barber with a sign-in
     */
    public record MemberView(UUID userId, String name, String phone, String role, UUID staffId,
                             boolean walkInAccess) {
    }

    public record PauseView(Instant pausedUntil) {
    }

    /** A closed day: one block per barber, all sharing closureId. */
    public record ClosureView(UUID closureId, LocalDate date, int barbers) {
    }
}
