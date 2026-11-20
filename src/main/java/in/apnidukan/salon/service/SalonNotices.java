package in.apnidukan.salon.service;

import in.apnidukan.common.context.ShopDirectory.ShopCard;
import in.apnidukan.common.context.ShopMemberships;
import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.context.UserDirectory.Person;
import in.apnidukan.common.notify.Language;
import in.apnidukan.common.notify.MessageText;
import in.apnidukan.common.notify.NoticeTexts;
import in.apnidukan.common.notify.Notifications;
import in.apnidukan.common.notify.Notifications.DayTime;
import in.apnidukan.common.notify.Notifications.Notice;
import in.apnidukan.common.notify.Notifications.Text;
import in.apnidukan.salon.domain.Appointment;
import in.apnidukan.salon.domain.BookedService;
import in.apnidukan.salon.domain.SalonStaff;
import in.apnidukan.salon.domain.SalonTime;
import in.apnidukan.salon.repo.AppointmentStore;
import in.apnidukan.salon.repo.EarlierSlotAlertStore;
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
 *
 * Each is written in English here and also given as its text in notices/*.properties (Notice.in), so each reader gets
 * it in their language. Names, booking numbers, codes and times are data: the same in every language.
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
                        "BOOKING", a.bookingNo()))
                .in("BOOKING_CONFIRMED", Map.of("salon", salon.name(), "when", new DayTime(a.startsAt()),
                        "services", names(services), "booking", a.bookingNo())));
        if (Duration.between(now, a.startsAt()).compareTo(REMINDER_ONLY_IF_BOOKED_AHEAD) > 0) {
            out.add(reminder(a, salon));
        }
        Who who = customer(a);
        Optional<SalonSetups.Barber> his = setup.barber(a.staffId());
        Who barber = his.map(b -> Who.named(b.name())).orElseGet(() -> barber(a.staffId()));
        Set<UUID> logins = his.isPresent() ? loginOf(his.get()) : logins(List.of(a.staffId()));
        out.addAll(salonNotices(a, logins, "SALON_NEW_BOOKING", "New booking, " + TIME.format(a.startsAt()),
                who.english() + " booked " + names(services) + " with " + barber.english() + ", "
                        + DAY_TIME.format(a.startsAt()) + ". Booking " + a.bookingNo() + ".",
                salonSms(who.english(), a, barber.english()), a.customerId(), now,
                Text.of("SALON_NEW_BOOKING", Map.of("time", TIME.format(a.startsAt()), "who", who.value(),
                        "services", names(services), "barber", barber.value(), "when", new DayTime(a.startsAt()),
                        "booking", a.bookingNo()))));
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
                        "BOOKING", moved.bookingNo()))
                .in("BOOKING_RESCHEDULED", Map.of("salon", salon.name(), "when", new DayTime(moved.startsAt()),
                        "booking", moved.bookingNo())));
        remind(moved, salon, now);
        Who who = customer(moved);
        Who barber = barber(moved.staffId());
        tellSalon(moved, List.of(before.staffId(), moved.staffId()), "SALON_BOOKING_MOVED", "Booking moved",
                who.english() + " moved booking " + moved.bookingNo() + " from " + DAY_TIME.format(before.startsAt())
                        + " to " + DAY_TIME.format(moved.startsAt()) + ", with " + barber.english() + ".",
                salonSms(who.english(), moved, barber.english()), moved.customerId(), now,
                Text.of("SALON_BOOKING_MOVED", Map.of("who", who.value(), "booking", moved.bookingNo(),
                        "from", new DayTime(before.startsAt()), "to", new DayTime(moved.startsAt()),
                        "barber", barber.value())));
    }

    /** The customer cancelled: they and the salon are told. */
    public void cancelledByCustomer(Appointment a, ShopCard salon, boolean late, Instant now) {
        notifications.cancelPending(a.id());
        notifications.queue(new Notice(a.customerId(), "BOOKING_CANCELLED", a.id(), now, "Booking cancelled",
                "Your booking at " + salon.name() + " on " + DAY_TIME.format(a.startsAt()) + " is cancelled."
                        + (late ? " It was less than 1 hour before, so it counts as a late cancel." : ""),
                url(a), Map.of("SALON", salon.name(), "WHEN", DAY_TIME.format(a.startsAt())))
                .in("BOOKING_CANCELLED", Map.of("salon", salon.name(), "when", new DayTime(a.startsAt()),
                        "late", late ? Text.of("BOOKING_CANCELLED.late") : "")));
        salonToldOfCancel(a, customer(a), late ? LATE_NOTE : NO_NOTE, a.customerId(), now);
    }

    /**
     * Tells the salon a booking was cancelled without anyone at the salon doing it (the customer, or their account
     * being deleted). The time is free again.
     */
    public void salonToldOfCancel(Appointment a, String note, Instant now) {
        salonToldOfCancel(a, customer(a), new Note(note, note), a.customerId(), now);
    }

    /** The customer deleted their account: the salon is told the time is free, without the customer's name. */
    public void salonToldOfDeletedAccount(Appointment a, Instant now) {
        salonToldOfCancel(a, Who.A_CUSTOMER, DELETED_NOTE, null, now);
    }

    /** A sentence added to the salon's cancel message: its English and its value in the text ("" for none). */
    private record Note(String english, Object value) {
    }

    private static final Note NO_NOTE = new Note("", "");
    private static final Note LATE_NOTE = new Note(" Less than 1 hour before.",
            Text.of("SALON_BOOKING_CANCELLED.late"));
    private static final Note DELETED_NOTE = new Note(" They deleted their account.",
            Text.of("SALON_BOOKING_CANCELLED.deleted"));

    private void salonToldOfCancel(Appointment a, Who who, Note note, UUID about, Instant now) {
        Who barber = barber(a.staffId());
        tellSalon(a, List.of(a.staffId()), "SALON_BOOKING_CANCELLED", "Booking cancelled, " + TIME.format(a.startsAt()),
                who.english() + " cancelled " + DAY_TIME.format(a.startsAt()) + " with " + barber.english()
                        + ". Booking " + a.bookingNo() + "." + note.english() + " The time is free again.",
                salonSms(who.english(), a, barber.english()), about, now,
                Text.of("SALON_BOOKING_CANCELLED", Map.of("time", TIME.format(a.startsAt()), "who", who.value(),
                        "when", new DayTime(a.startsAt()), "barber", barber.value(), "booking", a.bookingNo(),
                        "note", note.value())));
    }

    /** The salon cancelled, with its reason. */
    public void cancelledBySalon(Appointment a, ShopCard salon, String reason, Instant now) {
        cancelledBySalon(List.of(a), salon, reason, now);
    }

    /** cancelledBySalon() for many bookings of one salon (it was suspended), written together. */
    public void cancelledBySalon(List<Appointment> bookings, ShopCard salon, String reason, Instant now) {
        notifications.cancelPendingAll(bookings.stream().map(Appointment::id).toList());
        Object reasonValue = reasonValue(reason);
        notifications.queueAll(bookings.stream().map(a -> new Notice(a.customerId(), "BOOKING_CANCELLED_BY_SALON",
                a.id(), now, "Cancelled by the salon",
                salon.name() + " cancelled your booking on " + DAY_TIME.format(a.startsAt()) + ": " + reason,
                url(a), Map.of("SALON", salon.name(), "WHEN", DAY_TIME.format(a.startsAt()), "REASON", reason))
                .in("BOOKING_CANCELLED_BY_SALON", Map.of("salon", salon.name(), "when", new DayTime(a.startsAt()),
                        "reason", reasonValue)))
                .toList());
    }

    /** The booking became a no-show (not started 10 minutes after its start): the customer is told. */
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
                    url(a), Map.of("SALON", salon.name(), "WHEN", DAY_TIME.format(a.startsAt())))
                    .in("BOOKING_NO_SHOW", Map.of("salon", salon.name(), "time", TIME.format(a.startsAt())));
        }).toList());
    }

    /**
     * An earlier time opened the same day (EarlierSlotAlerts): each customer who asked is told, in the app and by
     * push (no SMS template: it is an offer, not news about their booking). Tapping it opens their booking with Move
     * on that time (?earlier=); whoever moves first gets it. About the booking (refId), so a later change to it
     * cancels the message if it hasn't gone out yet. Written in one batch.
     * @param offers each booking told, with the start offered to it
     */
    public void earlierSlots(Map<EarlierSlotAlertStore.Candidate, Instant> offers, ShopCard salon, String barber,
                             Instant now) {
        notifications.queueAll(offers.entrySet().stream().map(e -> {
            EarlierSlotAlertStore.Candidate c = e.getKey();
            Instant t = e.getValue();
            return new Notice(c.customerId(), "BOOKING_EARLIER_SLOT", c.id(), now,
                    "Earlier time at " + salon.name(),
                    DAY_TIME.format(t) + " with " + barber + " just opened up. You're booked for "
                            + TIME.format(c.startsAt()) + ". Tap to move your booking; the first to move gets it.",
                    "/bookings/" + c.id() + "?earlier=" + t, Map.of())
                    .in("BOOKING_EARLIER_SLOT", Map.of("salon", salon.name(), "when", new DayTime(t),
                            "barber", barber, "time", TIME.format(c.startsAt())));
        }).toList());
    }

    /** The customer switched the alert off: an earlier-slot offer for this booking still waiting isn't sent. */
    public void withdrawEarlierSlot(UUID bookingId) {
        notifications.cancelPending(bookingId, "BOOKING_EARLIER_SLOT");
    }

    /** A no-show undone by the salon: the customer's "Missed booking" no longer counts. No SMS. */
    public void noShowUndone(Appointment a, ShopCard salon, Instant now) {
        notifications.queue(new Notice(a.customerId(), "BOOKING_NO_SHOW_UNDONE", a.id(), now, "Missed booking removed",
                salon.name() + " says you weren't a no-show on " + DAY_TIME.format(a.startsAt())
                        + ". It doesn't count against you.",
                url(a), Map.of())
                .in("BOOKING_NO_SHOW_UNDONE", Map.of("salon", salon.name(), "when", new DayTime(a.startsAt()))));
    }

    /**
     * Given to another barber by the owner or a helper: both barbers are told (not the person who did it), and the
     * customer of an online booking. No SMS: it is the same visit at the same salon.
     */
    public void reassigned(Appointment before, Appointment moved, ShopCard salon, UUID by, Instant now) {
        Who to = barber(moved.staffId());
        Who from = barber(before.staffId());
        Who who = moved.isBooking() ? customer(moved) : guest(moved);
        if (moved.isBooking()) {
            notifications.queue(new Notice(moved.customerId(), "BOOKING_BARBER_CHANGED", moved.id(), now,
                    "Your barber changed",
                    salon.name() + ", " + DAY_TIME.format(moved.startsAt()) + ": now with " + to.english()
                            + ". Booking " + moved.bookingNo() + ".",
                    url(moved), Map.of())
                    .in("BOOKING_BARBER_CHANGED", Map.of("salon", salon.name(), "when", new DayTime(moved.startsAt()),
                            "to", to.value(), "booking", moved.bookingNo())));
        }
        tellBarbers(moved, List.of(before.staffId(), moved.staffId()), "SALON_BOOKING_REASSIGNED",
                "Booking moved to " + to.english() + ", " + TIME.format(moved.startsAt()),
                who.english() + ", " + DAY_TIME.format(moved.startsAt()) + ": now with " + to.english()
                        + " instead of " + from.english() + ".",
                moved.isBooking() ? moved.customerId() : null, by, now,
                Text.of("SALON_BOOKING_REASSIGNED", Map.of("to", to.value(), "time", TIME.format(moved.startsAt()),
                        "who", who.value(), "when", new DayTime(moved.startsAt()), "from", from.value())));
    }

    /** The salon cancelled an online booking: the barber it was with is told (the customer already was). */
    public void barberToldOfSalonCancel(Appointment a, String reason, UUID by, Instant now) {
        Who who = customer(a);
        tellBarbers(a, List.of(a.staffId()), "SALON_CANCELLED_BY_SALON",
                "Booking cancelled by the salon, " + TIME.format(a.startsAt()),
                who.english() + ", " + DAY_TIME.format(a.startsAt()) + ": cancelled by the salon (" + reason
                        + "). The time is free again.",
                a.customerId(), by, now,
                Text.of("SALON_CANCELLED_BY_SALON", Map.of("time", TIME.format(a.startsAt()), "who", who.value(),
                        "when", new DayTime(a.startsAt()), "reason", reasonValue(reason))));
    }

    /**
     * A walk-in already in the chair was cancelled (added by mistake): the owner is told, in the app, unless he did it
     * himself, since it leaves the day's takings.
     */
    public void walkInCancelled(Appointment a, String reason, UUID by, Instant now) {
        Who barber = barber(a.staffId());
        Who guest = guest(a);
        Set<UUID> told = new LinkedHashSet<>();
        for (var m : memberships.members(a.shopId())) {
            if ("OWNER".equals(m.role()) && !m.userId().equals(by)) {
                told.add(m.userId());
                notifications.queue(new Notice(m.userId(), "SALON_WALK_IN_CANCELLED", null, now,
                        "Walk-in cancelled, " + TIME.format(a.startsAt()),
                        guest.english() + " with " + barber.english() + " at " + TIME.format(a.startsAt())
                                + " was cancelled while in the chair: " + reason,
                        dayUrl(a), Map.of(), null)
                        .in("SALON_WALK_IN_CANCELLED", Map.of("time", TIME.format(a.startsAt()),
                                "guest", guest.value(), "barber", barber.value(), "reason", reason)));
            }
        }
        // And the barber whose chair it was in (in the app; not if he did it himself, nor again if he is the owner)
        Set<UUID> barberLogin = logins(List.of(a.staffId()));
        barberLogin.removeAll(told);
        barberLogin.remove(by);
        for (UUID person : barberLogin) {
            notifications.queue(new Notice(person, "SALON_WALK_IN_CANCELLED", null, now,
                    "Walk-in cancelled, " + TIME.format(a.startsAt()),
                    guest.english() + " at " + TIME.format(a.startsAt()) + " was cancelled: " + reason, dayUrl(a),
                    Map.of(), null)
                    .in("SALON_WALK_IN_CANCELLED_CHAIR", Map.of("time", TIME.format(a.startsAt()),
                            "guest", guest.value(), "reason", reason)));
        }
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
     * again in one statement when the salon moves), once per language: the same words as reminder() and its text in
     * notices/*.properties, fitted to the stored sizes exactly as the notifications module fits every message
     * (MessageText). A language whose reminder text is missing or not in the order time, code, held-until gets the
     * English (as a message queued for it would).
     */
    List<AppointmentStore.ReminderText> reminderTexts(ShopCard salon) {
        List<AppointmentStore.ReminderText> out = new ArrayList<>();
        AppointmentStore.ReminderText english = reminderText(Language.EN, reminderTitle(salon),
                reminderAfterTime(salon), REMINDER_AFTER_CODE, reminderTail(salon), salon);
        out.add(english);
        for (Language language : Language.values()) {
            if (language != Language.EN) {
                out.add(localReminderText(language, salon).orElseGet(() -> english.as(language.code())));
            }
        }
        return out;
    }

    private static Optional<AppointmentStore.ReminderText> localReminderText(Language language, ShopCard salon) {
        Optional<String> body = NoticeTexts.template(language, "BOOKING_REMINDER.body");
        Optional<String> title = NoticeTexts.render(language, "BOOKING_REMINDER.title", Map.of("salon", salon.name()));
        if (body.isEmpty() || title.isEmpty() || !body.get().startsWith("{time}")) {
            return Optional.empty();
        }
        String t = body.get();
        int code = t.indexOf("{code}");
        int until = t.indexOf("{until}");
        if (code < 6 || until < code) {
            return Optional.empty();
        }
        Map<String, String> values = reminderPlace(salon);
        Optional<String> afterTime = NoticeTexts.fill(language, t.substring(6, code), values);
        Optional<String> afterCode = NoticeTexts.fill(language, t.substring(code + 6, until), Map.of());
        Optional<String> tail = NoticeTexts.fill(language, t.substring(until + 7), values);
        if (afterTime.isEmpty() || afterCode.isEmpty() || tail.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(reminderText(language, title.get(), afterTime.get(), afterCode.get(), tail.get(), salon));
    }

    private static AppointmentStore.ReminderText reminderText(Language language, String title, String afterTime,
                                                              String afterCode, String tail, ShopCard salon) {
        List<String> tails = new ArrayList<>();
        for (int times = 14; times <= 16; times++) {   // the two "h:mm AM" times: 7 + 7, 7 + 8 or 8 + 8 characters
            // Everything before the tail, as long as it will be (its own characters don't matter: the cut at 500
            // falls in the tail, after the name)
            String head = "x".repeat(times + afterTime.length() + 4 + afterCode.length());
            tails.add(MessageText.cut(head + tail, MessageText.MAX_BODY).substring(head.length()));
        }
        return new AppointmentStore.ReminderText(language.code(), MessageText.cut(title, MessageText.MAX_TITLE),
                afterTime, afterCode, tails,
                MessageText.smsQuery(Map.of("AREA", MessageText.cutPlain(salon.area(), MessageText.MAX_SMS_VARIABLE)))
                        + "&CODE=",
                "&" + MessageText.smsQuery(Map.of("SALON",
                        MessageText.cutPlain(salon.name(), MessageText.MAX_SMS_VARIABLE))) + "&TIME=");
    }

    /** The reminder's place values: the salon's name, address, area and map link. */
    private static Map<String, String> reminderPlace(ShopCard salon) {
        return Map.of("salon", salon.name(), "address", salon.addressLine(), "area", salon.area(),
                "map", mapLink(salon));
    }

    /**
     * To the salon's owner, its helpers and the given barbers' sign-ins, each person once, written in one batch. The
     * barbers also get the SMS (when a template is set for the type); the owner only when
     * app.notifications.owner-booking-sms is on (off by default: a busy salon's owner would get an SMS per booking, and
     * the app's push and list already tell them), also when he is one of the barbers; helpers in the app only (they run
     * the front desk).
     * @param about the customer the text names, so their account deletion blanks it (null when it names nobody)
     */
    private void tellSalon(Appointment a, List<UUID> barbers, String type, String title, String body,
                           Map<String, String> sms, UUID about, Instant now, Text text) {
        notifications.queueAll(salonNotices(a, logins(barbers), type, title, body, sms, about, now, text));
    }

    private List<Notice> salonNotices(Appointment a, Set<UUID> barberLogins, String type, String title, String body,
                                      Map<String, String> sms, UUID about, Instant now, Text text) {
        Set<UUID> withSms = new LinkedHashSet<>(barberLogins);
        Set<UUID> appOnly = new LinkedHashSet<>();
        for (var m : memberships.members(a.shopId())) {
            if ("OWNER".equals(m.role())) {
                if (ownerBookingSms) {
                    withSms.add(m.userId());
                } else {
                    withSms.remove(m.userId());   // his own chair too: the owner's setting decides
                    appOnly.add(m.userId());
                }
            } else if ("HELPER".equals(m.role())) {
                appOnly.add(m.userId());
            }
        }
        appOnly.removeAll(withSms);
        String day = dayUrl(a);
        List<Notice> out = new ArrayList<>();
        for (UUID person : withSms) {
            out.add(new Notice(person, type, null, now, title, body, day, sms, about, text));
        }
        for (UUID person : appOnly) {
            out.add(new Notice(person, type, null, now, title, body, day, Map.of(), about, text));
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
                             UUID by, Instant now, Text text) {
        Set<UUID> to = new LinkedHashSet<>();
        staff.findAllById(barbers).stream().map(SalonStaff::getUserId).filter(Objects::nonNull).forEach(to::add);
        to.remove(by);
        for (UUID person : to) {
            notifications.queue(new Notice(person, type, null, now, title, body, dayUrl(a), Map.of(), about, text));
        }
    }

    private static String dayUrl(Appointment a) {
        return "/salon/day?date=" + LocalDate.ofInstant(a.startsAt(), SalonTime.ZONE);
    }

    /**
     * A person a message names: the English words, and the value its text gets (their name as it is, or the words
     * for someone without one, in the reader's language).
     */
    private record Who(String english, Object value) {
        static final Who A_CUSTOMER = new Who("A customer", Text.of("who.customer"));
        static final Who A_BARBER = new Who("a barber", Text.of("who.barber"));
        static final Who A_WALK_IN = new Who("A walk-in", Text.of("who.walkIn"));

        static Who named(String name) {
            return new Who(name, name);
        }
    }

    private static Who guest(Appointment a) {
        return a.guestName() == null ? Who.A_WALK_IN : Who.named(a.guestName());
    }

    /** The salon's own words for its closing or suspension (SalonShopChanges) in the reader's language; else as is. */
    private static Object reasonValue(String reason) {
        if (SalonShopChanges.SUSPENDED_REASON.equals(reason)) {
            return Text.of("cancel.salonSuspended");
        }
        if (SalonShopChanges.CLOSED_REASON.equals(reason)) {
            return Text.of("cancel.salonClosed");
        }
        return reason;
    }

    private static Map<String, String> salonSms(String who, Appointment a, String barber) {
        return Map.of("CUSTOMER", who, "WHEN", DAY_TIME.format(a.startsAt()), "BARBER", barber,
                "BOOKING", a.bookingNo());
    }

    private Who customer(Appointment a) {
        Person p = users.find(Set.of(a.customerId())).get(a.customerId());
        return p == null || p.name() == null ? Who.A_CUSTOMER : Who.named(p.name());
    }

    private Who barber(UUID staffId) {
        return staff.findById(staffId).map(SalonStaff::getName).map(Who::named).orElse(Who.A_BARBER);
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
        Map<String, String> values = new java.util.HashMap<>(reminderPlace(salon));
        values.put("time", TIME.format(a.startsAt()));
        values.put("code", a.startCode());
        values.put("until", TIME.format(heldUntil));
        return new Notice(a.customerId(), "BOOKING_REMINDER", a.id(),
                a.startsAt().minus(REMINDER_BEFORE),
                reminderTitle(salon),
                // Code and held-until first: if a long name or address makes the text too long, only the end is cut
                TIME.format(a.startsAt()) + reminderAfterTime(salon) + a.startCode() + REMINDER_AFTER_CODE
                        + TIME.format(heldUntil) + reminderTail(salon),
                url(a), Map.of("SALON", salon.name(), "TIME", TIME.format(a.startsAt()), "AREA", salon.area(),
                        "CODE", a.startCode(), "UNTIL", TIME.format(heldUntil)))
                .in("BOOKING_REMINDER", values);
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
