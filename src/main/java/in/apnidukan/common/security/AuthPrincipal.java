package in.apnidukan.common.security;

import java.util.UUID;

/**
 * Who a valid sign-in token belongs to. Put into Spring Security's context by {@link JwtAuthFilter}.
 * @param shopId the shop the person is acting for; null until they have registered or joined a shop
 * @param shopRole OWNER, HELPER or BARBER in that shop; null without a shop
 */
public record AuthPrincipal(UUID userId, UUID shopId, String shopRole, boolean admin) {
}
