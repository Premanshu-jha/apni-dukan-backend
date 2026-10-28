package in.apnidukan.common.context;

import java.util.Optional;
import java.util.UUID;

/**
 * "Which shop does this person work in, and as what?" Implemented by the shops module; used by sign-in to put the
 * shop and role into the access token without the auth module depending on the shops module.
 */
public interface ShopMemberships {

    Optional<Membership> find(UUID userId);

    /** @param role OWNER or HELPER */
    record Membership(UUID shopId, String role) {
    }
}
