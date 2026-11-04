package in.apnidukan.common.context;

import java.util.UUID;

/**
 * Who is calling: the signed-in user, the shop they are acting for and their role there (OWNER, HELPER or BARBER).
 * shopId and role are null until the person has registered or joined a shop.
 *
 * shopId and role come from the access token, so they can be up to 15 minutes old. Owner-only actions must check
 * the role in the database (as PATCH /shops/me does), not this field. When memberships can change (helpers PR),
 * changing one must also end that person's current tokens (SessionRevocations), so a removed helper's token stops
 * working for the shop at once.
 */
public record CurrentUser(UUID userId, UUID shopId, String role) {

    public CurrentUser(UUID userId, UUID shopId) {
        this(userId, shopId, null);
    }
}
