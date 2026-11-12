package in.apnidukan.common.security;

import java.util.UUID;

/**
 * Who a valid sign-in token belongs to. Put into Spring Security's context by {@link JwtAuthFilter}.
 * @param shopId the shop the person is acting for; null until they have registered or joined a shop
 * @param shopRole OWNER, HELPER or BARBER in that shop; null without a shop
 * @param sessionId the device's sign-in the token was issued for ("sid"; the same through every refresh); null in a
 *                  token from before it was added
 */
public record AuthPrincipal(UUID userId, UUID shopId, String shopRole, boolean admin, UUID sessionId) {

    /** Without a device session (tests, and tokens issued before sessions were named). */
    public AuthPrincipal(UUID userId, UUID shopId, String shopRole, boolean admin) {
        this(userId, shopId, shopRole, admin, null);
    }
}
