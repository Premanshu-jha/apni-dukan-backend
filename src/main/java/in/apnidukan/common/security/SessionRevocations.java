package in.apnidukan.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Makes "signed out everywhere" instant. Access tokens can't be cancelled one by one, so each person has a
 * session generation number in Redis, and every access token carries the generation it was issued in.
 * Ending all sessions (account recovery, a copied refresh token) adds 1: older tokens are refused on their next
 * call, while tokens issued afterwards carry the new number and keep working.
 */
@Component
public class SessionRevocations {

    private static final Logger log = LoggerFactory.getLogger(SessionRevocations.class);

    private final StringRedisTemplate redis;

    public SessionRevocations(StringRedisTemplate redis) {
        this.redis = redis;
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
        redis.opsForValue().increment(key(userId));
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
