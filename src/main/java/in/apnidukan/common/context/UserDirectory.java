package in.apnidukan.common.context;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Facts about people, for modules other than sign-in. Implemented by the auth module. Always read from the
 * database (never from the sign-in token, which can be up to 15 minutes old).
 */
public interface UserDirectory {

    /**
     * The person with this phone, creating the account if nobody has signed in with it yet (a salon inviting a
     * barber by phone: he then signs in with the normal OTP and finds his salon). 400 if the phone isn't valid.
     */
    UUID findOrCreateByPhone(String phone);

    /** The account with this phone, if any (for support looking a customer up). 400 if the phone isn't valid. */
    Optional<UUID> findByPhone(String phone);

    /** Name (null if not set) and phone of each person found; unknown ids are left out. */
    Map<UUID, Person> find(Collection<UUID> userIds);

    /** False once the person deleted their account (or for an unknown id). */
    boolean isActive(UUID userId);

    /**
     * Like isActive, and holds the person's row until the transaction ends, so deleting the account waits for it
     * (and a deletion in progress is waited for first). Must run inside a transaction.
     */
    boolean lockIfActive(UUID userId);

    /** True only for the Apni Dukan field team (users.admin). */
    boolean isAdmin(UUID userId);

    record Person(UUID id, String name, String phone) {
    }
}
