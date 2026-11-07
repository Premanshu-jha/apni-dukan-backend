package in.apnidukan.common.context;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * "Which shop does this person work in, and as what?" Implemented by the shops module; used by sign-in to put the
 * shop and role into the access token, and by the salon module to check the shop is a salon and who is asking,
 * without either depending on the shops module. Always read from the database, so it is never out of date.
 */
public interface ShopMemberships {

    Optional<Membership> find(UUID userId);

    /** Everyone working in this shop, owner first, then by when they joined. */
    List<Member> members(UUID shopId);

    /**
     * Invites the person with this phone to sign in as this barber profile of the salon (see ShopInvitesService): the
     * same answer whether or not the number has an account; he joins when he accepts. A waiting invitation for the
     * profile with another phone is replaced. The caller holds the profile's lock. Returns the phone as stored.
     * 400 INVALID_PHONE / VALIDATION_FAILED (own number); 409 ALREADY_A_MEMBER (already in this salon),
     * 409 INVITE_PENDING (this phone has another invitation waiting here); 429 after 20 invitations a day.
     */
    String inviteBarber(UUID shopId, UUID ownerId, UUID staffId, String phone);

    /** Cancels the barber profile's waiting sign-in invitation, if any (the caller holds the profile's lock). */
    void cancelBarberInvite(UUID shopId, UUID staffId, UUID by);

    /** The salon's waiting barber invitations: profile → the phone invited. */
    Map<UUID, String> waitingBarberInvites(UUID shopId);

    /** Removes a person from a shop (never the owner). Does nothing if they aren't a member of it. */
    void removeMember(UUID shopId, UUID userId);

    /**
     * @param role     OWNER, HELPER or BARBER
     * @param shopType KIRANA or SALON
     */
    record Membership(UUID shopId, String role, String shopType) {
    }

    /** @param role OWNER, HELPER or BARBER */
    record Member(UUID userId, String role) {
    }
}
