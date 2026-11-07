package in.apnidukan.salon.service;

import in.apnidukan.common.context.PersonDetailsListener;
import in.apnidukan.salon.repo.AppointmentStore;
import in.apnidukan.salon.repo.SalonVersionStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * A customer's name and phone show on the salons' screens they have appointments with: when they change, those salons'
 * change counters move (one statement, in shop id order), before the person's row is locked (see
 * PersonDetailsListener for why the order matters).
 */
@Component
class SalonCustomerDetails implements PersonDetailsListener {

    private final SalonVersionStore versions;
    private final AppointmentStore appointments;

    SalonCustomerDetails(SalonVersionStore versions, AppointmentStore appointments) {
        this.versions = versions;
        this.appointments = appointments;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void detailsChanging(UUID userId) {
        // Their bookings first (the lock a booking takes before it adds a row): a booking at a new salon either
        // committed already and is found below, or waits until this change committed and then moves that salon's
        // counter itself
        appointments.lockCustomer(userId);
        versions.bumpForCustomer(userId);
    }
}
