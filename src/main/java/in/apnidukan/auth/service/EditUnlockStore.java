package in.apnidukan.auth.service;

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
 * (AuthService.sendEditCode / unlockEditing), so a phone left signed in, or a stolen session, can't change them alone.
 * Kept in Redis (key edit-unlock:{userId}, the end as epoch milliseconds, expiring with it). Redis down: no unlock can
 * be made or read, and the change answers 503 like any call that needs Redis.
 */
@Service
public class EditUnlockStore implements EditUnlocks {

    /** How long one code unlocks editing. */
    public static final Duration FOR = Duration.ofMinutes(15);

    private final StringRedisTemplate redis;
    private final Clock clock;

    public EditUnlockStore(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /** Unlocked for FOR from now (a second unlock starts the time again). The caller checked the code. */
    Instant unlock(UUID userId) {
        Instant until = clock.instant().plus(FOR);
        redis.opsForValue().set(key(userId), Long.toString(until.toEpochMilli()), FOR);
        return until;
    }

    /** Locked again now (the owner tapped Done). */
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

    private static String key(UUID userId) {
        return "edit-unlock:" + userId;
    }
}
