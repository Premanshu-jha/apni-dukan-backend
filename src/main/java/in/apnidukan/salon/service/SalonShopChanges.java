package in.apnidukan.salon.service;

import in.apnidukan.common.context.ShopChangeListener;
import in.apnidukan.common.context.ShopDirectory;
import in.apnidukan.common.context.ShopDirectory.ShopCard;
import in.apnidukan.salon.domain.Appointment;
import in.apnidukan.salon.domain.AppointmentStatus;
import in.apnidukan.salon.repo.AppointmentStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * What the salon does when its shop changes:
 * <ul>
 *   <li>moved (new address or map pin): pending booking reminders are rewritten with the new place;</li>
 *   <li>suspended by Apni Dukan: upcoming online bookings are cancelled and each customer is told.</li>
 * </ul>
 */
@Component
class SalonShopChanges implements ShopChangeListener {

    /** What customers are told; the field team's own reason stays between them and the owner. */
    static final String SUSPENDED_REASON = "The salon isn't taking online bookings for now.";

    private final AppointmentStore appointments;
    private final ShopDirectory shops;
    private final SalonNotices notices;
    private final Clock clock;

    SalonShopChanges(AppointmentStore appointments, ShopDirectory shops, SalonNotices notices, Clock clock) {
        this.appointments = appointments;
        this.shops = shops;
        this.notices = notices;
        this.clock = clock;
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
        for (Appointment a : appointments.upcomingBookings(shopId, now)) {
            if (appointments.close(a.id(), AppointmentStatus.CANCELLED_BY_SALON, SUSPENDED_REASON, now)) {
                appointments.event(a.id(), now, null, "CANCELLED_BY_SALON", "salon suspended by Apni Dukan");
                notices.cancelledBySalon(a, card, SUSPENDED_REASON, now);
            }
        }
    }
}
