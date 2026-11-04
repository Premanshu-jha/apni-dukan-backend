package in.apnidukan.salon.service;

import in.apnidukan.common.context.ShopChangeListener;
import in.apnidukan.common.context.ShopDirectory;
import in.apnidukan.salon.repo.AppointmentStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/** A salon moved (new address or map pin): its pending booking reminders are rewritten with the new place. */
@Component
class SalonLocationChanges implements ShopChangeListener {

    private final AppointmentStore appointments;
    private final ShopDirectory shops;
    private final SalonNotices notices;
    private final Clock clock;

    SalonLocationChanges(AppointmentStore appointments, ShopDirectory shops, SalonNotices notices, Clock clock) {
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
}
