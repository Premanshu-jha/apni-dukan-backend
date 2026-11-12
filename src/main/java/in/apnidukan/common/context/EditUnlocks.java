package in.apnidukan.common.context;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Whether a person confirmed with a code to their own phone, a few minutes ago, that they mean to change their
 * verified shop's details (the auth module keeps it; the shops module asks before every such change).
 */
public interface EditUnlocks {

    /**
     * Until when editing is unlocked for this person on the device making this request; empty when it isn't (never,
     * on another device only, or the time ran out). Only for the caller.
     */
    Optional<Instant> unlockedUntil(UUID userId);
}
