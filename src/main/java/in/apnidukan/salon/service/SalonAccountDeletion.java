package in.apnidukan.salon.service;

import in.apnidukan.common.context.AccountDeletionListener;
import in.apnidukan.common.context.ShopMemberships;
import in.apnidukan.salon.domain.Appointment;
import in.apnidukan.salon.domain.AppointmentStatus;
import in.apnidukan.salon.repo.AppointmentStore;
import in.apnidukan.salon.repo.SalonAccessStore;
import in.apnidukan.salon.repo.SalonStaffRepository;
import in.apnidukan.salon.repo.SalonVersionStore;
import in.apnidukan.salon.repo.StrikeStore;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.salon.domain.SalonTime;
import org.springframework.http.HttpStatus;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Someone deleted their account (see AccountDeletionListener):
 * <ul>
 *   <li>as a customer: every booking still waiting (CONFIRMED) is cancelled, with no strike, and the salon is
 *       told the time is free;</li>
 *   <li>as a barber with a sign-in: the sign-in is unlinked (his name, hours and history stay for the salon);</li>
 *   <li>any walk-in access goes.</li>
 * </ul>
 * Past bookings and strikes stay as they are.
 *
 * Deleting is refused (409) while online booking is blocked, or when a booking starts within the hour or is in
 * progress: otherwise deleting and signing up again would be a way round a block or a late-cancel strike.
 */
@Component
@Order(10)
class SalonAccountDeletion implements AccountDeletionListener {

    /** Bookings are fetched in pages of this size (a customer holds at most 2 waiting ones). */
    private static final int PAGE = 50;

    private final AppointmentStore appointments;
    private final SalonStaffRepository staff;
    private final SalonAccessStore access;
    private final ShopMemberships memberships;
    private final SalonNotices notices;
    private final StrikeStore strikes;
    private final SalonVersionStore versions;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
            .withZone(SalonTime.ZONE);

    SalonAccountDeletion(AppointmentStore appointments, SalonStaffRepository staff, SalonAccessStore access,
                         ShopMemberships memberships, SalonNotices notices, StrikeStore strikes,
                         SalonVersionStore versions) {
        this.strikes = strikes;
        this.versions = versions;
        this.appointments = appointments;
        this.staff = staff;
        this.access = access;
        this.memberships = memberships;
        this.notices = notices;
    }

    @Override
    @Transactional(readOnly = true)
    public void checkCanDelete(UUID userId, Instant now) {
        BookingService.blockedUntil(strikes.since(userId, now.minus(BookingService.STRIKE_WINDOW)
                .minus(BookingService.BLOCK_FOR)), now).ifPresent(until -> {
                    throw new ApiException(HttpStatus.CONFLICT, "BOOKING_BLOCKED",
                            "You can delete your account after your booking pause ends on " + DAY.format(until) + ".",
                            Map.of("until", until.toString()));
                });
        for (Appointment a : appointments.forCustomer(userId, true, now, null, PAGE)) {
            boolean soon = a.status() == AppointmentStatus.CONFIRMED
                    && Duration.between(now, a.startsAt()).compareTo(BookingService.LATE_CANCEL) < 0;
            if (soon || a.status() == AppointmentStatus.IN_SERVICE) {
                throw new ApiException(HttpStatus.CONFLICT, "BOOKING_SOON",
                        "You have a booking starting within the hour or in progress (" + a.bookingNo()
                                + "). Cancel it or wait until it's over, then delete your account.",
                        Map.of("bookingId", a.id().toString()));
            }
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void accountDeleted(UUID userId, Instant now) {
        // Waits for a booking or move of theirs in progress; one waiting behind this sees the account deleted
        // (BookingService checks that after taking the same lock) and is refused
        appointments.lockCustomer(userId);
        // Locks in the order every other writer takes them: their open booking rows (a salon cancelling one holds the
        // row, then its counter), then every salon's counter at once in shop id order (their name goes from those
        // screens, and the cancellations below take no new counter one by one), then, in AuthService, the person's
        // row (see PersonDetailsListener)
        appointments.lockOpenForCustomer(userId, now);
        versions.bumpForCustomer(userId);
        checkCanDelete(userId, now);   // again, under the lock: nothing changed since the code was sent
        for (Appointment a : appointments.forCustomer(userId, true, now, null, PAGE)) {
            if (a.status() == AppointmentStatus.CONFIRMED
                    && appointments.close(a.id(), AppointmentStatus.CANCELLED_BY_CUSTOMER, null, now)) {
                appointments.event(a.id(), now, userId, "CANCELLED_BY_CUSTOMER", "account deleted");
                notices.closed(a);
                notices.salonToldOfDeletedAccount(a, now);
            }
        }
        staff.findByUserId(userId).ifPresent(barber -> {
            memberships.removeMember(barber.getShopId(), userId);
            barber.unlinkLogin(now);
            staff.saveAndFlush(barber);
        });
        access.revokeAllWalkIns(userId);
    }
}
