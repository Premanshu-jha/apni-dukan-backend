package in.apnidukan.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The sign-out check must never take the whole API down with Redis. */
class SessionRevocationsTest {

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final SessionRevocations revocations = new SessionRevocations(redis);

    @Test
    void anOlderGenerationIsRevoked() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn("2");
        assertThat(revocations.isRevoked(UUID.randomUUID(), 1)).isTrue();
        assertThat(revocations.isRevoked(UUID.randomUUID(), 2)).isFalse();
    }

    @Test
    void withRedisDownNewTokensAreStillIssued() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        assertThat(revocations.generationForNewToken(UUID.randomUUID())).isZero();
    }

    @Test
    void withRedisDownTheTokenIsLetThrough() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        assertThat(revocations.isRevoked(UUID.randomUUID(), 0)).isFalse();
    }
}
