package in.apnidukan.salon.service;

import in.apnidukan.common.context.ShopDirectory.ShopCard;
import in.apnidukan.common.notify.Notifications;
import in.apnidukan.common.notify.Notifications.Notice;
import in.apnidukan.salon.domain.Appointment;
import in.apnidukan.salon.domain.BookedService;
import in.apnidukan.salon.domain.SalonTime;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The messages a customer gets about a booking: the Web Push text, and the variables for its SMS template (the
 * SMS goes out only once a template id is set for that type, see SmsTemplates and the README). Queued in the same transaction as the change (see Notifications),
 * so a message exists only if the change was saved.
 */
@Component
public class SalonNotices {

    /** The reminder goes out this long before the start... */
    static final Duration REMINDER_BEFORE = Duration.ofMinutes(60);
    /** ...but only for bookings made more than this ahead; a later booking's confirmation already says it all. */
    static final Duration REMINDER_ONLY_IF_BOOKED_AHEAD = Duration.ofMinutes(75);

    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH)
            .withZone(SalonTime.ZONE);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
            .withZone(SalonTime.ZONE);

    private final Notifications notifications;

    public SalonNotices(Notifications notifications) {
        this.notifications = notifications;
    }

    /** Booking saved: the confirmation now, and the reminder 60 minutes before if it was booked well ahead. */
    public void booked(Appointment a, ShopCard salon, List<BookedService> services, Instant now) {
        notifications.queue(new Notice(a.customerId(), "BOOKING_CONFIRMED", a.id(), now,
                "Booked at " + salon.name(),
                salon.name() + ", " + DAY_TIME.format(a.startsAt()) + ", " + names(services) + ". Booking "
                        + a.bookingNo() + ". Pay at the salon.",
                url(a), Map.of("SALON", salon.name(), "WHEN", DAY_TIME.format(a.startsAt()),
                        "BOOKING", a.bookingNo())));
        remind(a, salon, now);
    }

    /** Booking moved: cancels its pending messages, tells the customer, and sets a new reminder. */
    public void rescheduled(Appointment moved, ShopCard salon, Instant now) {
        notifications.cancelPending(moved.id());
        notifications.queue(new Notice(moved.customerId(), "BOOKING_RESCHEDULED", moved.id(), now,
                "Booking moved",
                salon.name() + ", now " + DAY_TIME.format(moved.startsAt()) + ". Booking " + moved.bookingNo() + ".",
                url(moved), Map.of("SALON", salon.name(), "WHEN", DAY_TIME.format(moved.startsAt()),
                        "BOOKING", moved.bookingNo())));
        remind(moved, salon, now);
    }

    /** The customer cancelled. */
    public void cancelledByCustomer(Appointment a, ShopCard salon, boolean late, Instant now) {
        notifications.cancelPending(a.id());
        notifications.queue(new Notice(a.customerId(), "BOOKING_CANCELLED", a.id(), now, "Booking cancelled",
                "Your booking at " + salon.name() + " on " + DAY_TIME.format(a.startsAt()) + " is cancelled."
                        + (late ? " It was less than 1 hour before, so it counts as a late cancel." : ""),
                url(a), Map.of("SALON", salon.name(), "WHEN", DAY_TIME.format(a.startsAt()))));
    }

    /** The salon cancelled, with its reason. */
    public void cancelledBySalon(Appointment a, ShopCard salon, String reason, Instant now) {
        notifications.cancelPending(a.id());
        notifications.queue(new Notice(a.customerId(), "BOOKING_CANCELLED_BY_SALON", a.id(), now,
                "Cancelled by the salon",
                salon.name() + " cancelled your booking on " + DAY_TIME.format(a.startsAt()) + ": " + reason,
                url(a), Map.of("SALON", salon.name(), "WHEN", DAY_TIME.format(a.startsAt()), "REASON", reason)));
    }

    /** Started, no-show: nothing more to remind about. */
    public void closed(Appointment a) {
        notifications.cancelPending(a.id());
    }

    /**
     * The salon's location changed: every booking's reminder that hasn't gone out yet is written again with the new
     * address and map link (bookings whose reminder already went out, or never had one, are left alone).
     */
    public void locationChanged(List<Appointment> upcoming, ShopCard salon) {
        for (Appointment a : upcoming) {
            notifications.replacePending(reminder(a, salon));   // only channels still waiting are rewritten
        }
    }

    private void remind(Appointment a, ShopCard salon, Instant now) {
        if (Duration.between(now, a.startsAt()).compareTo(REMINDER_ONLY_IF_BOOKED_AHEAD) <= 0) {
            return;
        }
        queueReminder(a, salon);
    }

    private void queueReminder(Appointment a, ShopCard salon) {
        notifications.queue(reminder(a, salon));
    }

    private static Notice reminder(Appointment a, ShopCard salon) {
        Instant heldUntil = a.startsAt().plus(SalonDayService.LATE_ALLOWANCE);
        return new Notice(a.customerId(), "BOOKING_REMINDER", a.id(),
                a.startsAt().minus(REMINDER_BEFORE),
                "In 1 hour at " + salon.name(),
                // Code and held-until first: if a long name or address makes the text too long, only the end is cut
                TIME.format(a.startsAt()) + " at " + salon.name() + ". Start code " + a.startCode() + ". Held until "
                        + TIME.format(heldUntil) + ". Can't come? Cancel in the app. " + salon.addressLine() + ", "
                        + salon.area() + ". Map: " + mapLink(salon),
                url(a), Map.of("SALON", salon.name(), "TIME", TIME.format(a.startsAt()), "AREA", salon.area(),
                        "CODE", a.startCode(), "UNTIL", TIME.format(heldUntil)));
    }

    static String mapLink(ShopCard salon) {
        return "https://www.google.com/maps/search/?api=1&query=" + salon.latitude().toPlainString() + ","
                + salon.longitude().toPlainString();
    }

    private static String names(List<BookedService> services) {
        return services.stream().map(BookedService::name).collect(Collectors.joining(" + "));
    }

    private static String url(Appointment a) {
        return "/bookings/" + a.id();
    }
}
