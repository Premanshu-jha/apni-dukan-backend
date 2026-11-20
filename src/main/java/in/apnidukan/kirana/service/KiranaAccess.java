package in.apnidukan.kirana.service;

import in.apnidukan.common.context.ShopMemberships;
import in.apnidukan.common.context.ShopMemberships.Membership;
import in.apnidukan.common.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Who is asking, for the kirana's counter screens. Looked up in the database on every call (one query), never taken
 * from the sign-in token or the request. The owner and his helpers bill, take khata payments, add stock and products;
 * prices, credit limits, the UPI ID and cancelling a bill are the owner's.
 */
@Component
public class KiranaAccess {

    private final ShopMemberships memberships;

    public KiranaAccess(ShopMemberships memberships) {
        this.memberships = memberships;
    }

    /** @param role OWNER or HELPER */
    public record Caller(UUID userId, UUID shopId, String role) {

        public boolean isOwner() {
            return "OWNER".equals(role);
        }
    }

    /**
     * The owner or a helper of a kirana. 404 NO_SHOP without a shop; 409 NOT_A_KIRANA for a salon (salon people never
     * see kirana screens); 403 NOT_ALLOWED for anyone else (a salon's barber can't be here, but the check stays).
     */
    public Caller caller(UUID userId) {
        Membership m = memberships.find(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NO_SHOP",
                "You haven't registered or joined a shop yet."));
        if (!"KIRANA".equals(m.shopType())) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_A_KIRANA", "This is only for kirana shops.");
        }
        if (!"OWNER".equals(m.role()) && !"HELPER".equals(m.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_ALLOWED", "You can't do this in this shop.");
        }
        return new Caller(userId, m.shopId(), m.role());
    }

    /** 403 OWNER_ONLY unless the caller is the kirana's owner. */
    public Caller owner(UUID userId) {
        Caller c = caller(userId);
        if (!c.isOwner()) {
            throw ownerOnly();
        }
        return c;
    }

    static ApiException ownerOnly() {
        return new ApiException(HttpStatus.FORBIDDEN, "OWNER_ONLY", "Only the shop owner can do this.");
    }
}
