package in.apnidukan.common.context;

import java.util.UUID;

/** Who is calling: the signed-in user and the shop they are acting for. */
public record CurrentUser(UUID userId, UUID shopId) {
}
