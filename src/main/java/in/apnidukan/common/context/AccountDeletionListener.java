package in.apnidukan.common.context;

import java.time.Instant;
import java.util.UUID;

/**
 * Told by the auth module when a person deletes their account, inside the same transaction, before their phone,
 * name and email are wiped. Each module cleans up what is still open for that person (upcoming salon bookings,
 * a barber's sign-in, waiting messages, devices) and keeps their past records, which stay linked to the now
 * nameless account. Shop owners can't delete their account (they close or hand over the shop first), so a
 * listener never has to deal with a shop losing its owner.
 */
public interface AccountDeletionListener {

    /**
     * Throws (an ApiException, 409) when the account can't be deleted now, for example a salon booking that is
     * about to start. Called before the delete code is sent; accountDeleted checks again under its locks.
     */
    default void checkCanDelete(UUID userId, Instant now) {
    }

    void accountDeleted(UUID userId, Instant now);
}
