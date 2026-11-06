package in.apnidukan.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Makes "signed out everywhere" instant. Access tokens can't be cancelled one by one, so each person has a
 * session generation number in Redis, and every access token carries the generation it was issued in.
 * Ending all sessions (account recovery, a copied refresh token) adds 1: older tokens are refused on their next
 * call, while tokens issued afterwards carry the new number and keep working.
 *
 * Each key (session-gen:{user}) expires {@link #ttl} after its last change, so keys of people who signed out
 * everywhere once don't stay in Redis forever (lakhs of people).
 *
 * The number never goes back, even across that expiry: ending all sessions sets it to the larger of "current + 1"
 * and Redis's own clock in milliseconds. A plain counter would restart at 0 after expiry, and a token issued at,
 * say, 5 just before would survive the next sign-out everywhere (which would only reach 1). With the clock, the
 * next number is always above anything issued before, while the key is missing a token carries 0 (older than
 * anything set later), and the servers' own clocks don't matter.
 */
@Component
public class SessionRevocations {

    private static final Logger log = LoggerFactory.getLogger(SessionRevocations.class);

    /**
     * Raise to max(current + 1, Redis time in ms) and (re)start the expiry, in one step: never lower than before,
     * and never left without a timer.
     */
    private static final DefaultRedisScript<Long> RAISE_AND_EXPIRE = new DefaultRedisScript<>("""
            local t = redis.call('TIME')
            local ms = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local n = math.max(tonumber(redis.call('GET', KEYS[1]) or '0') + 1, ms)
            redis.call('SET', KEYS[1], string.format('%d', n), 'PX', ARGV[1])
            return n
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public SessionRevocations(StringRedisTemplate redis, JwtProperties jwt) {
        this.redis = redis;
        this.ttl = Duration.ofDays(jwt.refreshDays() + 1L);
    }

    public long currentGeneration(UUID userId) {
        String value = redis.opsForValue().get(key(userId));
        return value == null ? 0 : Long.parseLong(value);
    }

    /**
     * The generation to put in a new access token. If Redis doesn't answer, 0: signing in and refreshing keep
     * working (the check fails open too, see isRevoked); once Redis is back, a token older than a sign-out
     * everywhere is refused and the app refreshes it, which the database decides.
     */
    public long generationForNewToken(UUID userId) {
        try {
            return currentGeneration(userId);
        } catch (DataAccessException redisDown) {
            log.warn("Redis didn't answer the session generation ({}); issuing with 0",
                    redisDown.getClass().getSimpleName());
            return 0;
        }
    }

    public void revokeAll(UUID userId) {
        redis.execute(RAISE_AND_EXPIRE, List.of(key(userId)), Long.toString(ttl.toMillis()));
    }

    /** How long a session number is kept after its last change (the refresh lifetime plus a day). */
    public Duration ttl() {
        return ttl;
    }

    /**
     * Checked on every signed-in request. If Redis doesn't answer (down, or slower than its short timeout), the
     * token counts as not revoked: everything that only needs the database keeps working, and the worst case is
     * that a token signed out during the outage works until it expires (at most 15 minutes).
     */
    public boolean isRevoked(UUID userId, long tokenGeneration) {
        try {
            return tokenGeneration < currentGeneration(userId);
        } catch (DataAccessException redisDown) {
            log.warn("Redis didn't answer the sign-out check ({}); letting the token through",
                    redisDown.getClass().getSimpleName());
            return false;
        }
    }

    private static String key(UUID userId) {
        return "session-gen:" + userId;
    }
}
