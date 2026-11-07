package in.apnidukan.salon.service;

import in.apnidukan.common.context.ShopDirectory.ShopCard;
import in.apnidukan.common.context.ShopMemberships;
import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.context.UserDirectory.Person;
import in.apnidukan.common.notify.MessageText;
import in.apnidukan.common.notify.Notifications;
import in.apnidukan.common.notify.Notifications.Notice;
import in.apnidukan.salon.domain.Appointment;
import in.apnidukan.salon.domain.BookedService;
import in.apnidukan.salon.domain.SalonStaff;
import in.apnidukan.salon.domain.SalonTime;
import in.apnidukan.salon.repo.AppointmentStore;
import in.apnidukan.salon.repo.SalonStaffRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The messages about a booking: to the customer, and to the salon (the owner, and the barber it is with if he has a
 * sign-in) when a customer books, cancels or moves online, so nobody at the salon has to keep watching the day
 * screen. Each is a Web Push, plus an SMS from its template once a template id is set for that type (see
 * SmsTemplates and the README). Queued in the same transaction as the change (see Notifications), so a message
 * exists only if the change was saved.
 *
 * The salon's messages are about something that already happened, so they have no subject (refId): a later change
 * to the booking never cancels them; it sends its own.
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
    private final ShopMemberships memberships;
    private final SalonStaffRepository staff;
    private final UserDirectory users;
    private final boolean ownerBookingSms;

    /**
     * @param memberships @Lazy: the shops module (which implements it) calls back into the salon module when a shop
     *                    changes (SalonShopChanges uses these notices), so it is looked up on first use instead
     * @param ownerBookingSms app.notifications.owner-booking-sms: the owner also gets the SMS of each booking, move and
     *                        cancel (off by default; barbers keep theirs when the template is set)
     */
    public SalonNotices(Notifications notifications, @Lazy ShopMemberships memberships, SalonStaffRepository staff,
                        UserDirectory users,
                        @Value("${app.notifications.owner-booking-sms:false}") boolean ownerBookingSms) {
        this.ownerBookingSms = ownerBookingSms;
        this.notifications = notifications;
        this.memberships = memberships;
        this.staff = staff;
        this.users = users;
    }

    /**
     * Booking saved: the confirmation now, the reminder 60 minutes before if it was booked well ahead, and the salon
     * told. The barber's name and sign-in come from the setup the booking was made with (in memory), so it costs three
     * statements: the customer's name, the salon's members, and every message in one batch.
     */
    public void booked(Appointment a, ShopCard salon, List<BookedService> services, SalonSetups.Setup setup,
                       Instant now) {
        List<Notice> out = new ArrayList<>();
        out.add(new Notice(a.customerId(), "BOOKING_CONFIRMED", a.id(), now,
                "Booked at " + salon.name(),
                salon.name() + ", " + DAY_TIME.format(a.startsAt()) + ", " + names(services) + ". Booking "
                        + a.bookingNo() + ". Pay at the salon.",
                url(a), Map.of("SALON", salon.name(), "WHEN", DAY_TIME.format(a.startsAt()),
                        "BOOKING", a.bookingNo())));
        if (Duration.between(now, a.startsAt()).compareTo(REMINDER_ONLY_IF_BOOKED_AHEAD) > 0) {
            out.add(reminder(a, salon));
        }
        String who = customerName(a);
        Optional<SalonSetups.Barber> his = setup.barber(a.staffId());
        String barber = his.map(SalonSetups.Barber::name).orElseGet(() -> barberName(a.staffId()));
        Set<UUID> logins = his.isPresent() ? loginOf(his.get()) : logins(List.of(a.staffId()));
        out.addAll(salonNotices(a, logins, "SALON_NEW_BOOKING", "New booking, " + TIME.format(a.startsAt()),
                who + " booked " + names(services) + " with " + barber + ", " + DAY_TIME.format(a.startsAt())
                        + ". Booking " + a.bookingNo() + ".",
                salonSms(who, a, barber), a.customerId(), now));
        notifications.queueAll(out);
    }

    private static Set<UUID> loginOf(SalonSetups.Barber barber) {
        return barber.login() == null ? Set.of() : Set.of(barber.login());
    }

    /**
     * Booking moved: cancels its pending messages, tells the customer, sets a new reminder, and tells the salon
     * (both barbers when it moved from one to another).
     */
    public void rescheduled(Appointment before, Appointment moved, ShopCard salon, Instant now) {
        notifications.cancelPending(moved.id());
        notifications.queue(new Notice(moved.customerId(), "BOOKING_RESCHEDULED", moved.id(), now,
                "Booking moved",
                salon.name() + ", now " + DAY_TIME.format(moved.startsAt()) + ". Booking " + moved.bookingNo() + ".",
                url(moved), Map.of("SALON", salon.name(), "WHEN", DAY_TIME.format(moved.startsAt()),
                        "BOOKING", moved.bookingNo())));
        remind(moved, salon, now);
        String who = customerName(moved);
        String barber = barberName(moved.staffId());
        tellSalon(moved, List.of(before.staffId(), moved.staffId()), "SALON_BOOKING_MOVED", "Booking moved",
                who + " moved booking " + moved.bookingNo() + " from " + DAY_TIME.format(before.startsAt()) + " to "
                        + DAY_TIME.format(moved.startsAt()) + ", with " + barber + ".",
                salonSms(who, moved, barber), moved.customerId(), now);
    }

    /** The customer cancelled: they and the salon are told. */
    public void cancelledByCustomer(Appointment a, ShopCard salon, boolean late, Instant now) {
        notifications.cancelPending(a.id());
        notifications.queue(new Notice(a.customerId(), "BOOKING_CANCELLED", a.id(), now, "Booking cancelled",
                "Your booking at " + salon.name() + " on " + DAY_TIME.format(a.startsAt()) + " is cancelled."
                        + (late ? " It was less than 1 hour before, so it counts as a late cancel." : ""),
                url(a), Map.of("SALON", salon.name(), "WHEN", DAY_TIME.format(a.startsAt()))));
        salonToldOfCancel(a, late ? " Less than 1 hour before." : "", now);
    }

    /**
     * Tells the salon a booking was cancelled without anyone at the salon doing it (the customer, or their account
     * being deleted). The time is free again.
     */
    public void salonToldOfCancel(Appointment a, String note, Instant now) {
        salonToldOfCancel(a, customerName(a), note, a.customerId(), now);
    }

    /** The customer deleted their account: the salon is told the time is free, without the customer's name. */
    public void salonToldOfDeletedAccount(Appointment a, Instant now) {
        salonToldOfCancel(a, "A customer", " They deleted their account.", null, now);
    }

    private void salonToldOfCancel(Appointment a, String who, String note, UUID about, Instant now) {
        String barber = barberName(a.staffId());
        tellSalon(a, List.of(a.staffId()), "SALON_BOOKING_CANCELLED", "Booking cancelled, " + TIME.format(a.startsAt()),
                who + " cancelled " + DAY_TIME.format(a.startsAt()) + " with " + barber + ". Booking " + a.bookingNo()
                        + "." + note + " The time is free again.",
                salonSms(who, a, barber), about, now);
    }

    /** The salon cancelled, with its reason. */
    public void cancelledBySalon(Appointment a, ShopCard salon, String reason, Instant now) {
        cancelledBySalon(List.of(a), salon, reason, now);
    }

    /** cancelledBySalon() for many bookings of one salon (it was suspended), written together. */
    public void cancelledBySalon(List<Appointment> bookings, ShopCard salon, String reason, Instant now) {
        notifications.cancelPendingAll(bookings.stream().map(Appointment::id).toList());
        notifications.queueAll(bookings.stream().map(a -> new Notice(a.customerId(), "BOOKING_CANCELLED_BY_SALON",
                a.id(), now, "Cancelled by the salon",
                salon.name() + " cancelled your booking on " + DAY_TIME.format(a.startsAt()) + ": " + reason,
                url(a), Map.of("SALON", salon.name(), "WHEN", DAY_TIME.format(a.startsAt()), "REASON", reason)))
                .toList());
    }

    /** The booking became a no-show (not started 15 minutes after its start): the customer is told. */
    public void noShow(Appointment a, ShopCard salon, Instant now) {
        noShows(List.of(a), shopId -> salon, now);
    }

    /** noShow() for a whole batch of the automatic no-show round, written together. */
    public void noShows(List<Appointment> bookings, java.util.function.Function<UUID, ShopCard> salonOf,
                        Instant now) {
        notifications.cancelPendingAll(bookings.stream().map(Appointment::id).toList());
        notifications.queueAll(bookings.stream().map(a -> {
            ShopCard salon = salonOf.apply(a.shopId());
            return new Notice(a.customerId(), "BOOKING_NO_SHOW", a.id(), now, "Missed booking",
                    "You didn't come to " + salon.name() + " at " + TIME.format(a.startsAt()) + ". It counts as a "
                            + "no-show; 3 within 60 days pause online booking for 30 days.",
                    url(a), Map.of("SALON", salon.name(), "WHEN", DAY_TIME.format(a.startsAt())));
        }).toList());
    }

    /** A no-show undone by the salon: the customer's "Missed booking" no longer counts. No SMS. */
    public void noShowUndone(Appointment a, ShopCard salon, Instant now) {
        notifications.queue(new Notice(a.customerId(), "BOOKING_NO_SHOW_UNDONE", a.id(), now, "Missed booking removed",
                salon.name() + " says you weren't a no-show on " + DAY_TIME.format(a.startsAt())
                        + ". It doesn't count against you.",
                url(a), Map.of()));
    }

    /**
     * Given to another barber by the owner or a helper: both barbers are told (not the person who did it), and the
     * customer of an online booking. No SMS: it is the same visit at the same salon.
     */
    public void reassigned(Appointment before, Appointment moved, ShopCard salon, UUID by, Instant now) {
        String to = barberName(moved.staffId());
        String from = barberName(before.staffId());
        String who = moved.isBooking() ? customerName(moved) : guestName(moved);
        if (moved.isBooking()) {
            notifications.queue(new Notice(moved.customerId(), "BOOKING_BARBER_CHANGED", moved.id(), now,
                    "Your barber changed",
                    salon.name() + ", " + DAY_TIME.format(moved.startsAt()) + ": now with " + to + ". Booking "
                            + moved.bookingNo() + ".",
                    url(moved), Map.of()));
        }
        tellBarbers(moved, List.of(before.staffId(), moved.staffId()), "SALON_BOOKING_REASSIGNED",
                "Booking moved to " + to + ", " + TIME.format(moved.startsAt()),
                who + ", " + DAY_TIME.format(moved.startsAt()) + ": now with " + to + " instead of " + from + ".",
                moved.isBooking() ? moved.customerId() : null, by, now);
    }

    /** The salon cancelled an online booking: the barber it was with is told (the customer already was). */
    public void barberToldOfSalonCancel(Appointment a, String reason, UUID by, Instant now) {
        tellBarbers(a, List.of(a.staffId()), "SALON_CANCELLED_BY_SALON",
                "Booking cancelled by the salon, " + TIME.format(a.startsAt()),
                customerName(a) + ", " + DAY_TIME.format(a.startsAt()) + ": cancelled by the salon (" + reason
                        + "). The time is free again.",
                a.customerId(), by, now);
    }

    /**
     * A walk-in already in the chair was cancelled (added by mistake): the owner is told, in the app, unless he did it
     * himself, since it leaves the day's takings.
     */
    public void walkInCancelled(Appointment a, String reason, UUID by, Instant now) {
        String barber = barberName(a.staffId());
        for (var m : memberships.members(a.shopId())) {
            if ("OWNER".equals(m.role()) && !m.userId().equals(by)) {
                notifications.queue(new Notice(m.userId(), "SALON_WALK_IN_CANCELLED", null, now,
                        "Walk-in cancelled, " + TIME.format(a.startsAt()),
                        guestName(a) + " with " + barber + " at " + TIME.format(a.startsAt())
                                + " was cancelled while in the chair: " + reason,
                        dayUrl(a), Map.of(), null));
            }
        }
        // And the barber whose chair it was in (in the app; not if he did it himself)
        tellBarbers(a, List.of(a.staffId()), "SALON_WALK_IN_CANCELLED", "Walk-in cancelled, " + TIME.format(a.startsAt()),
                guestName(a) + " at " + TIME.format(a.startsAt()) + " was cancelled: " + reason, null, by, now);
    }

    /** Someone left the salon or lost their sign-in: their salon messages go. */
    public void forgetSalonMessages(UUID userId) {
        notifications.forgetSalonMessages(userId);
    }

    /** forgetSalonMessages for several people at once (the salon closed). */
    public void forgetSalonMessages(java.util.Collection<UUID> userIds) {
        notifications.forgetSalonMessages(userIds);
    }

    /** Started, no-show: nothing more to remind about. */
    public void closed(Appointment a) {
        notifications.cancelPending(a.id());
    }

    /**
     * What every booking reminder of this salon shares (see AppointmentStore.rewriteReminders, which writes them all
     * again in one statement when the salon moves): the same words as reminder(), fitted to the stored sizes exactly
     * as the notifications module fits every message (MessageText).
     */
    AppointmentStore.ReminderText reminderText(ShopCard salon) {
        String afterTime = reminderAfterTime(salon);
        String tail = reminderTail(salon);
        List<String> tails = new ArrayList<>();
        for (int times = 14; times <= 16; times++) {   // the two "h:mm AM" times: 7 + 7, 7 + 8 or 8 + 8 characters
            // Everything before the tail, as long as it will be (its own characters don't matter: the cut at 500
            // falls in the tail, after the name)
            String head = "x".repeat(times + afterTime.length() + 4 + REMINDER_AFTER_CODE.length());
            tails.add(MessageText.cut(head + tail, MessageText.MAX_BODY).substring(head.length()));
        }
        return new AppointmentStore.ReminderText(MessageText.cut(reminderTitle(salon), MessageText.MAX_TITLE),
                afterTime, REMINDER_AFTER_CODE, tails,
                MessageText.smsQuery(Map.of("AREA", MessageText.cutPlain(salon.area(), MessageText.MAX_SMS_VARIABLE)))
                        + "&CODE=",
                "&" + MessageText.smsQuery(Map.of("SALON",
                        MessageText.cutPlain(salon.name(), MessageText.MAX_SMS_VARIABLE))) + "&TIME=");
    }

    /**
     * To the salon's owner, its helpers and the given barbers' sign-ins, each person once, written in one batch. The
     * barbers also get the SMS (when a template is set for the type); the owner only when
     * app.notifications.owner-booking-sms is on (off by default: a busy salon's owner would get an SMS per booking, and
     * the app's push and list already tell them); helpers in the app only (they run the front desk).
     * @param about the customer the text names, so their account deletion blanks it (null when it names nobody)
     */
    private void tellSalon(Appointment a, List<UUID> barbers, String type, String title, String body,
                           Map<String, String> sms, UUID about, Instant now) {
        notifications.queueAll(salonNotices(a, logins(barbers), type, title, body, sms, about, now));
    }

    private List<Notice> salonNotices(Appointment a, Set<UUID> barberLogins, String type, String title, String body,
                                      Map<String, String> sms, UUID about, Instant now) {
        Set<UUID> withSms = new LinkedHashSet<>(barberLogins);
        Set<UUID> appOnly = new LinkedHashSet<>();
        for (var m : memberships.members(a.shopId())) {
            if ("OWNER".equals(m.role())) {
                (ownerBookingSms ? withSms : appOnly).add(m.userId());
            } else if ("HELPER".equals(m.role())) {
                appOnly.add(m.userId());
            }
        }
        appOnly.removeAll(withSms);
        String day = dayUrl(a);
        List<Notice> out = new ArrayList<>();
        for (UUID person : withSms) {
            out.add(new Notice(person, type, null, now, title, body, day, sms, about));
        }
        for (UUID person : appOnly) {
            out.add(new Notice(person, type, null, now, title, body, day, Map.of(), about));
        }
        return out;
    }

    /** The sign-ins of these barbers (those who have one), in one query. */
    private Set<UUID> logins(List<UUID> barbers) {
        Set<UUID> out = new LinkedHashSet<>();
        staff.findAllById(barbers).stream().map(SalonStaff::getUserId).filter(Objects::nonNull).forEach(out::add);
        return out;
    }

    /** To the given barbers' sign-ins only, in the app (no SMS), never to the person who made the change. */
    private void tellBarbers(Appointment a, List<UUID> barbers, String type, String title, String body, UUID about,
                             UUID by, Instant now) {
        Set<UUID> to = new LinkedHashSet<>();
        staff.findAllById(barbers).stream().map(SalonStaff::getUserId).filter(Objects::nonNull).forEach(to::add);
        to.remove(by);
        for (UUID person : to) {
            notifications.queue(new Notice(person, type, null, now, title, body, dayUrl(a), Map.of(), about));
        }
    }

    private static String dayUrl(Appointment a) {
        return "/salon/day?date=" + LocalDate.ofInstant(a.startsAt(), SalonTime.ZONE);
    }

    private static String guestName(Appointment a) {
        return a.guestName() == null ? "A walk-in" : a.guestName();
    }

    private static Map<String, String> salonSms(String who, Appointment a, String barber) {
        return Map.of("CUSTOMER", who, "WHEN", DAY_TIME.format(a.startsAt()), "BARBER", barber,
                "BOOKING", a.bookingNo());
    }

    private String customerName(Appointment a) {
        Person p = users.find(Set.of(a.customerId())).get(a.customerId());
        return p == null || p.name() == null ? "A customer" : p.name();
    }

    private String barberName(UUID staffId) {
        return staff.findById(staffId).map(SalonStaff::getName).orElse("a barber");
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

    /** The reminder's words between its parts. Kept in one place: AppointmentStore.rewriteReminders uses them too. */
    private static final String REMINDER_AFTER_CODE = ". Held until ";

    private static String reminderTitle(ShopCard salon) {
        return "In 1 hour at " + salon.name();
    }

    private static String reminderAfterTime(ShopCard salon) {
        return " at " + salon.name() + ". Start code ";
    }

    private static String reminderTail(ShopCard salon) {
        return ". Can't come? Cancel in the app. " + salon.addressLine() + ", " + salon.area() + ". Map: "
                + mapLink(salon);
    }

    static Notice reminder(Appointment a, ShopCard salon) {
        Instant heldUntil = a.startsAt().plus(SalonDayService.LATE_ALLOWANCE);
        return new Notice(a.customerId(), "BOOKING_REMINDER", a.id(),
                a.startsAt().minus(REMINDER_BEFORE),
                reminderTitle(salon),
                // Code and held-until first: if a long name or address makes the text too long, only the end is cut
                TIME.format(a.startsAt()) + reminderAfterTime(salon) + a.startCode() + REMINDER_AFTER_CODE
                        + TIME.format(heldUntil) + reminderTail(salon),
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
