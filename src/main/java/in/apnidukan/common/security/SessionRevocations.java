package in.apnidukan.common.security;

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

    private final StringRedisTemplate redis;

    public SessionRevocations(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public long currentGeneration(UUID userId) {
        String value = redis.opsForValue().get(key(userId));
        return value == null ? 0 : Long.parseLong(value);
    }

    public void revokeAll(UUID userId) {
        redis.opsForValue().increment(key(userId));
    }

    public boolean isRevoked(UUID userId, long tokenGeneration) {
        return tokenGeneration < currentGeneration(userId);
    }

    private static String key(UUID userId) {
        return "session-gen:" + userId;
    }
}
