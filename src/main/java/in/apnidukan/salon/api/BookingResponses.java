package in.apnidukan.salon.api;

import in.apnidukan.common.context.ShopDirectory.ShopCard;
import in.apnidukan.salon.domain.Appointment;
import in.apnidukan.salon.domain.AppointmentKind;
import in.apnidukan.salon.domain.AppointmentStatus;
import in.apnidukan.salon.domain.BlockType;
import in.apnidukan.salon.domain.BookedService;
import in.apnidukan.salon.domain.SalonTime;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** What the booking, walk-in and daily-work endpoints return. Times are exact moments (ISO-8601, UTC). */
public final class BookingResponses {

    private BookingResponses() {
    }

    public record ServiceLine(UUID serviceId, String name, int durationMinutes, long pricePaise,
                              boolean priceIsFrom) {

        public static List<ServiceLine> from(List<BookedService> services) {
            return services.stream()
                    .map(s -> new ServiceLine(s.serviceId(), s.name(), s.durationMin(), s.pricePaise(),
                            s.priceIsFrom()))
                    .toList();
        }
    }

    // ---------- customer side ----------

    /**
     * A salon in the list near the customer.
     * @param photoUrl the salon's photo, a link valid for a few minutes; null if none
     */
    public record SalonSummary(UUID id, String name, String area, String city, double distanceKm, String photoUrl) {

        public static SalonSummary from(ShopCard card, String photoUrl) {
            return new SalonSummary(card.id(), card.name(), card.area(), card.city(),
                    card.distanceKm() == null ? 0 : card.distanceKm(), photoUrl);
        }
    }

    /**
     * One salon's page.
     * @param hours per weekday (1 = Monday), the earliest start and latest end of any barber; a day without
     *              barbers is left out
     * @param pausedUntil set while the salon has paused online bookings
     * @param photoUrl    the salon's photo, a link valid for a few minutes; null if none
     */
    public record SalonDetails(UUID id, String name, String addressLine, String area, String city, String pincode,
                               BigDecimal latitude, BigDecimal longitude, String mapLink, List<DayHours> hours,
                               Instant pausedUntil, String photoUrl) {
    }

    public record DayHours(int weekday, String open, String close) {
    }

    /** A service as customers see it. */
    public record SalonService(UUID id, String name, int durationMinutes, long pricePaise, boolean priceIsFrom) {
    }

    /**
     * Start times for the chosen services on one date.
     * @param starts exact moments; each is also given as "HH:mm" in India time
     */
    public record Slots(LocalDate date, int durationMinutes, List<Slot> starts) {
    }

    public record Slot(Instant startAt, String time) {

        public static Slot of(Instant t) {
            return new Slot(t, SalonTime.format(t.atZone(SalonTime.ZONE).toLocalTime().toSecondOfDay() / 60));
        }
    }

    /**
     * A booking as its customer sees it.
     * @param startCode    the 4 digits to show at the salon; only while the booking is CONFIRMED
     * @param heldUntil    the salon keeps the booking until this moment (15 minutes after the start)
     * @param canChange    true while it can still be cancelled (CONFIRMED, before the start)
     * @param canMove      true while it can also be moved to another time (the salon is approved and not paused)
     */
    public record BookingView(UUID id, String bookingNo, UUID salonId, String salonName, String salonAddress,
                              String mapLink, List<ServiceLine> services, long totalPricePaise, boolean priceIsFrom,
                              Instant startsAt, Instant endsAt, Instant heldUntil, AppointmentStatus status,
                              String startCode, String cancelReason, boolean canChange,
                              boolean canMove, Instant createdAt) {
    }

    /** @param countedAsLate true if it was less than 1 hour before the start (a strike) */
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
     */
    public record AppointmentView(UUID id, AppointmentKind kind, BlockType blockType, UUID staffId,
                                  AppointmentStatus status, Instant startsAt, Instant endsAt, Instant startedAt,
                                  Boolean startedWithCode, List<ServiceLine> services, String bookingNo,
                                  String customerName, String customerPhone, String guestName,
                                  String cancelReason, UUID closureId, boolean notStarted, Instant noShowFrom) {

        public static AppointmentView of(Appointment a, List<BookedService> services, String customerName,
                                         String customerPhone, Instant now, Instant noShowFrom) {
            boolean notStarted = a.status() == AppointmentStatus.CONFIRMED && !now.isBefore(a.startsAt());
            return new AppointmentView(a.id(), a.kind(), a.blockType(), a.staffId(), a.status(), a.startsAt(),
                    a.endsAt(), a.startedAt(), a.startedWithCode(), ServiceLine.from(services), a.bookingNo(),
                    customerName, customerPhone, a.guestName(), a.cancelReason(), a.closureId(),
                    notStarted, a.kind() == AppointmentKind.BLOCK ? null : noShowFrom);
        }
    }

    /** The day screen: one column per barber. */
    public record DayView(LocalDate date, List<BarberColumn> barbers) {
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
