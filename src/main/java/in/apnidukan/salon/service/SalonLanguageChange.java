package in.apnidukan.salon.service;

import in.apnidukan.common.context.LanguageChangeListener;
import in.apnidukan.common.context.ShopDirectory;
import in.apnidukan.salon.repo.AppointmentStore;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * A customer chose another language: the reminders of his online bookings still to come, queued in the old one, are
 * written again in the new one, as a salon's move rewrites them (AppointmentStore.rewriteReminders, here for his
 * bookings only): one statement per salon he has such a booking at (at most two, the booking limit), each booking's
 * time, start code and held-until put in by the database. His other booking messages go out as soon as they are
 * queued, so none waits long enough to matter. A booking made at the very moment of the change may keep the
 * language it was made in.
 */
@Component
class SalonLanguageChange implements LanguageChangeListener {

    private final AppointmentStore appointments;
    private final ShopDirectory shops;
    private final SalonNotices notices;
    private final Clock clock;

    /** @param shops @Lazy: the shops module calls back into the salon module (see SalonNotices) */
    SalonLanguageChange(AppointmentStore appointments, @Lazy ShopDirectory shops, SalonNotices notices,
                        Clock clock) {
        this.appointments = appointments;
        this.shops = shops;
        this.notices = notices;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void languageChanged(UUID userId) {
        java.time.Instant now = clock.instant();
        for (UUID shopId : appointments.shopsWithPendingReminders(userId, now)) {
            shops.find(shopId).ifPresent(card -> appointments.rewriteReminders(shopId, now,
                    SalonDayService.LATE_ALLOWANCE, notices.reminderTexts(card), userId));
        }
    }
}
