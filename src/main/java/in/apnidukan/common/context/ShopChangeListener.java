package in.apnidukan.common.context;

import java.util.UUID;

/**
 * Told by the shops module about changes other modules must react to, inside the same transaction as the change.
 */
public interface ShopChangeListener {

    /**
     * The location changed (address, area, city, pincode or map pin). The salon module rewrites pending booking
     * reminders, which carry the address and the map link.
     */
    void locationChanged(UUID shopId);

    /**
     * Apni Dukan suspended the shop: customers can't find it any more. The salon module cancels its upcoming online
     * bookings and tells each customer. Called after the shop's row is saved (and locked) as SUSPENDED, so a booking
     * in progress has finished and new ones see the status.
     */
    void suspended(UUID shopId);

    /**
     * A helper or barber left the shop or was removed (their membership is already gone). The salon module takes
     * away their walk-in access and unlinks a barber's sign-in from his barber profile.
     */
    void memberRemoved(UUID shopId, UUID userId);
}
