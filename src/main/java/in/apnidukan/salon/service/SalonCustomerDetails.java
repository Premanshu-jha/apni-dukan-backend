package in.apnidukan.salon.service;

import in.apnidukan.common.context.PersonDetailsListener;
import in.apnidukan.salon.repo.AppointmentStore;
import in.apnidukan.salon.repo.SalonVersionStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * A customer's name and phone show on the salons' screens they have appointments with: when they change, their
 * appointment rows are written again (one statement, in id order), before the person's row is locked (see
 * PersonDetailsListener for why the order matters). Those salons' counters then move at commit (V21), and the rows
 * show in the day boards' deltas with the new name.
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
        // committed already and is found below, or waits until this change committed and shows the new name itself
        appointments.lockCustomer(userId);
        versions.bumpForCustomer(userId);
    }
}
