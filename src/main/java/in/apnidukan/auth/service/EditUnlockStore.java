package in.apnidukan.auth.service;

import in.apnidukan.common.context.CurrentUser;
import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.common.context.EditUnlocks;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Editing a verified shop's details is unlocked for {@link #FOR} after the owner enters a code sent to his own phone
 * (AuthService.sendEditCode / unlockEditing), on that device only: another sign-in to the same account (a phone left
 * signed in at the salon, another computer) needs its own code. (A copied token shares its device's sign-in, until
 * reuse detection ends every session.) Kept in Redis per device sign-in (key
 * edit-unlock:{userId}:{sessionId}, the end as epoch milliseconds, expiring with it). A token from before sessions
 * were named (at most 15 minutes after the deploy) shares one key, "-". Redis down: no unlock can be made or read,
 * and the change answers 503 like any call that needs Redis.
 */
@Service
public class EditUnlockStore implements EditUnlocks {

    /** How long one code unlocks editing. */
    public static final Duration FOR = Duration.ofMinutes(15);

    private final StringRedisTemplate redis;
    private final CurrentUserProvider currentUser;
    private final Clock clock;

    public EditUnlockStore(StringRedisTemplate redis, CurrentUserProvider currentUser, Clock clock) {
        this.redis = redis;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    /** Unlocked for FOR from now (a second unlock starts the time again). The caller checked the code. */
    Instant unlock(UUID userId) {
        Instant until = clock.instant().plus(FOR);
        redis.opsForValue().set(key(userId), Long.toString(until.toEpochMilli()), FOR);
        return until;
    }

    /** Locked again now on this device (the owner tapped Done). */
    public void lock(UUID userId) {
        redis.delete(key(userId));
    }

    /** Seconds from now until then (0 once past): the app counts down from this, not from the phone's own clock. */
    public long secondsLeft(Instant until) {
        return until == null ? 0 : Math.max(0, Duration.between(clock.instant(), until).toSeconds());
    }

    @Override
    public Optional<Instant> unlockedUntil(UUID userId) {
        String value = redis.opsForValue().get(key(userId));
        if (value == null) {
            return Optional.empty();
        }
        try {
            Instant until = Instant.ofEpochMilli(Long.parseLong(value));
            return until.isAfter(clock.instant()) ? Optional.of(until) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** The person on the device making this request (the caller is always the signed-in person themselves). */
    private String key(UUID userId) {
        CurrentUser me = currentUser.get();
        if (!me.userId().equals(userId)) {
            throw new IllegalStateException("An edit unlock is only ever read or set for the caller");
        }
        return "edit-unlock:" + userId + ":" + (me.sessionId() == null ? "-" : me.sessionId());
    }
}
