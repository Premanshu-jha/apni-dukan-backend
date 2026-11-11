package in.apnidukan.common.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Simple counters in Redis: "at most N per window". The window starts with the first hit.
 * Adding 1 and starting the timer happen in one Redis script, so a counter can never be left without a timer
 * (which would block that phone or IP forever), and parallel requests are counted correctly.
 */
@Component
public class RateLimiter {

    /** INCR, and on the first hit start the expiry timer: both in one atomic step. */
    private static final DefaultRedisScript<Long> INCREMENT_WITH_EXPIRY = new DefaultRedisScript<>("""
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return n
            """, Long.class);

    private final StringRedisTemplate redis;

    public RateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Counts one hit; returns false if this hit goes over the limit. */
    public boolean tryHit(String key, int max, Duration window) {
        return hit(key, window) <= max;
    }

    /** Counts one hit and returns the count so far in this window. */
    public long hit(String key, Duration window) {
        return incrementWithExpiry(redis, "rl:" + key, window);
    }

    /**
     * Takes back one hit counted by {@link #hit} or {@link #tryHit} for something that was refused further on (so a
     * refusal doesn't use up an allowance). Never below 0 (a counter that had expired meanwhile stays gone); the window stays as it was.
     */
    public void giveBack(String key) {
        Long left = redis.opsForValue().decrement("rl:" + key);
        if (left != null && left < 0) {
            redis.delete("rl:" + key);   // it had gone (expired meanwhile): don't leave a counter with no window
        }
    }

    /** Seconds until this counter's window ends (for Retry-After); 1 if unknown. */
    public long secondsLeft(String key) {
        Long ttl = redis.getExpire("rl:" + key);
        return ttl == null || ttl < 1 ? 1 : ttl;
    }

    /** The count so far, without adding a hit. */
    public long current(String key) {
        String value = redis.opsForValue().get("rl:" + key);
        return value == null ? 0 : Long.parseLong(value);
    }

    /**
     * Adds 1 to a Redis counter and, if it was just created, makes it expire after {@code window}.
     * If Redis gives no answer, returns Long.MAX_VALUE so the caller treats it as over the limit.
     */
    public static long incrementWithExpiry(StringRedisTemplate redis, String fullKey, Duration window) {
        Long count = redis.execute(INCREMENT_WITH_EXPIRY, List.of(fullKey), Long.toString(window.toMillis()));
        return count == null ? Long.MAX_VALUE : count;
    }
}
