package in.apnidukan.salon.service;

import in.apnidukan.common.context.AccountDeletionListener;
import in.apnidukan.common.context.ShopMemberships;
import in.apnidukan.salon.domain.Appointment;
import in.apnidukan.salon.domain.AppointmentStatus;
import in.apnidukan.salon.repo.AppointmentStore;
import in.apnidukan.salon.repo.SalonAccessStore;
import in.apnidukan.salon.repo.SalonStaffRepository;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Someone deleted their account (see AccountDeletionListener):
 * <ul>
 *   <li>as a customer: every booking still waiting (CONFIRMED) is cancelled, with no strike, and the salon is
 *       told the time is free;</li>
 *   <li>as a barber with a sign-in: the sign-in is unlinked (his name, hours and history stay for the salon);</li>
 *   <li>any walk-in access goes;</li>
 *   <li>the "for" names they typed on their bookings are cleared.</li>
 * </ul>
 * Past bookings and strikes stay as they are.
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

    SalonAccountDeletion(AppointmentStore appointments, SalonStaffRepository staff, SalonAccessStore access,
                         ShopMemberships memberships, SalonNotices notices) {
        this.appointments = appointments;
        this.staff = staff;
        this.access = access;
        this.memberships = memberships;
        this.notices = notices;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void accountDeleted(UUID userId, Instant now) {
        // Waits for a booking or move of theirs in progress; one waiting behind this sees the account deleted
        // (BookingService checks that after taking the same lock) and is refused
        appointments.lockCustomer(userId);
        for (Appointment a : appointments.forCustomer(userId, true, now, PAGE)) {
            if (a.status() == AppointmentStatus.CONFIRMED
                    && appointments.close(a.id(), AppointmentStatus.CANCELLED_BY_CUSTOMER, null, now)) {
                appointments.event(a.id(), now, userId, "CANCELLED_BY_CUSTOMER", "account deleted");
                notices.closed(a);
                notices.salonToldOfDeletedAccount(a, now);
            }
        }
        appointments.forgetBookingFor(userId);   // the free-text "for" names they typed
        staff.findByUserId(userId).ifPresent(barber -> {
            memberships.removeMember(barber.getShopId(), userId);
            barber.unlinkLogin(now);
            staff.saveAndFlush(barber);
        });
        access.revokeAllWalkIns(userId);
    }
}
