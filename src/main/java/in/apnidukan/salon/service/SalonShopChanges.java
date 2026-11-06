package in.apnidukan.salon.service;

import in.apnidukan.common.context.ShopChangeListener;
import in.apnidukan.common.context.ShopDirectory;
import in.apnidukan.common.context.ShopDirectory.ShopCard;
import in.apnidukan.salon.domain.Appointment;
import in.apnidukan.salon.repo.AppointmentStore;
import in.apnidukan.salon.repo.SalonAccessStore;
import in.apnidukan.salon.repo.SalonStaffRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the salon does when its shop changes:
 * <ul>
 *   <li>moved (new address or map pin): pending booking reminders are rewritten with the new place;</li>
 *   <li>suspended by Apni Dukan: upcoming online bookings are cancelled and each customer is told;</li>
 *   <li>someone left or was removed: their walk-in access goes, and a barber's sign-in is unlinked (his name,
 *       hours and bookings stay with the salon).</li>
 * </ul>
 */
@Component
class SalonShopChanges implements ShopChangeListener {

    /** What customers are told; the field team's own reason stays between them and the owner. */
    static final String SUSPENDED_REASON = "The salon isn't taking online bookings for now.";

    private final AppointmentStore appointments;
    private final ShopDirectory shops;
    private final SalonNotices notices;
    private final SalonAccessStore access;
    private final SalonStaffRepository staff;
    private final Clock clock;

    SalonShopChanges(AppointmentStore appointments, ShopDirectory shops, SalonNotices notices,
                     SalonAccessStore access, SalonStaffRepository staff, Clock clock) {
        this.appointments = appointments;
        this.shops = shops;
        this.notices = notices;
        this.access = access;
        this.staff = staff;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void memberRemoved(UUID shopId, UUID userId) {
        access.revokeWalkIns(shopId, userId);
        notices.forgetSalonMessages(userId);
        staff.findByUserId(userId).filter(b -> b.getShopId().equals(shopId)).ifPresent(barber -> {
            barber.unlinkLogin(clock.instant());
            staff.saveAndFlush(barber);
        });
    }

    /** Runs inside the shop change's transaction: if that rolls back, the old reminders stay. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void locationChanged(UUID shopId) {
        shops.find(shopId).ifPresent(card -> notices.locationChanged(
                appointments.upcomingBookings(shopId, clock.instant()), card));
    }

    /**
     * Runs inside the suspension's transaction, after the shop's row was locked for it: a booking or move in
     * progress (they hold that row in shared mode first) has finished, and later ones see SUSPENDED.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void suspended(UUID shopId) {
        Instant now = clock.instant();
        ShopCard card = shops.find(shopId).orElseThrow();
        // Also those whose customer may still be on the way (held 15 minutes): left open, they'd become no-shows.
        // One statement closes them all and logs each (a busy salon can have thousands ahead); then each customer is
        // told (queued messages, sent by the sender job).
        List<Appointment> closed = appointments.cancelAllUpcoming(shopId, now.minus(SalonDayService.LATE_ALLOWANCE),
                SUSPENDED_REASON, "salon suspended by Apni Dukan", now);
        for (Appointment a : closed) {
            notices.cancelledBySalon(a, card, SUSPENDED_REASON, now);
        }
    }
}
