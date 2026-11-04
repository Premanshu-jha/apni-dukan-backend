package in.apnidukan.common.context;

import java.util.List;
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
     * Adds a person to a shop with a role other than OWNER (a salon linking a barber's login).
     * 409 ALREADY_IN_A_SHOP if the person already works in a shop (this one or another): one shop per person.
     */
    void addMember(UUID shopId, UUID userId, String role);

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
