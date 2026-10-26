package in.apnidukan.common.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Simple counters in Redis: "at most N per window". The window starts with the first hit.
 * Redis INCR is atomic, so parallel requests are counted correctly.
 */
@Component
public class RateLimiter {

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
        Long count = redis.opsForValue().increment("rl:" + key);
        if (count == null) {
            return Long.MAX_VALUE;
        }
        if (count == 1) {
            redis.expire("rl:" + key, window);
        }
        return count;
    }

    /** The count so far, without adding a hit. */
    public long current(String key) {
        String value = redis.opsForValue().get("rl:" + key);
        return value == null ? 0 : Long.parseLong(value);
    }
}
