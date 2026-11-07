package in.apnidukan.common.context;

import java.util.Collection;
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

    /**
     * The owner closed the shop for good (its row is saved, and locked, as CLOSED; memberships end right after). The
     * salon module cancels its upcoming online bookings and tells each customer, as for a suspension.
     */
    void closed(UUID shopId);

    /**
     * A helper or barber of the shop took it over (hand-over accepted; their membership is now OWNER). The salon
     * module takes away walk-in access they no longer need and unlinks a barber's sign-in from his barber profile
     * (the profile, his hours and bookings stay with the salon), since the owner sees every barber.
     */
    void becameOwner(UUID shopId, UUID userId);

    /**
     * Several people left at once (the shop closed; their memberships are already gone): memberRemoved for each, done
     * together. The salon module answers it in a few statements however many there are.
     */
    default void membersRemoved(UUID shopId, Collection<UUID> userIds) {
        userIds.forEach(userId -> memberRemoved(shopId, userId));
    }

    /**
     * A barber profile's sign-in invitation is about to be cancelled by the owner: the salon module takes the
     * profile's lock first, the order accepting takes them in (see ShopInvitesService).
     */
    default void barberInviteChanging(UUID shopId, UUID staffId) {
    }

    /**
     * Someone is accepting an invitation to sign in as this barber profile (before it is marked accepted): the salon
     * module locks the profile and refuses (409 INVITE_GONE) if it was switched off or got a sign-in meanwhile.
     */
    default void barberJoining(UUID shopId, UUID staffId) {
    }

    /** They joined as this barber (their BARBER membership is saved): the salon module links the profile to them. */
    default void barberJoined(UUID shopId, UUID staffId, UUID userId) {
    }
}
