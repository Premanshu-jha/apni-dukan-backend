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
    private static final JwtProperties JWT =
            new JwtProperties("test-only-secret-0123456789-abcdefghijklmnopqrstuvwxyz", 15, 30);
    /** Without the memory (0 seconds): every check reads Redis, as these tests expect */
    private final SessionRevocations revocations = new SessionRevocations(redis, JWT, 0, 1);

    @Test
    void theSessionNumberOutlivesEveryRefreshToken() {
        assertThat(revocations.ttl()).isEqualTo(java.time.Duration.ofDays(31));
    }

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

    @Test
    void theCheckIsRememberedForAFewSecondsAndOnlyEverRaised() {
        SessionRevocations remembering = new SessionRevocations(redis, JWT, 5, 1000);
        UUID who = UUID.randomUUID();
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn("2");
        assertThat(remembering.isRevoked(who, 1)).isTrue();
        assertThat(remembering.isRevoked(who, 2)).isFalse();
        // One Redis read for both checks: the second came from memory
        org.mockito.Mockito.verify(values, org.mockito.Mockito.times(1)).get(anyString());

        // A slower read answering an older number never lowers what this copy knows
        when(values.get(anyString())).thenReturn("1");
        assertThat(remembering.currentGeneration(who)).isEqualTo(2);
        assertThat(remembering.isRevoked(who, 1)).isTrue();
    }

    @Test
    void endingAllSessionsHereIsKnownHereAtOnce() {
        SessionRevocations remembering = new SessionRevocations(redis, JWT, 5, 1000);
        UUID who = UUID.randomUUID();
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn("2");
        assertThat(remembering.isRevoked(who, 2)).isFalse();   // remembered: 2
        when(redis.execute(org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<Long>>any(),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.<Object>any())).thenReturn(99L);
        remembering.revokeAll(who);
        assertThat(remembering.isRevoked(who, 2)).isTrue();    // without waiting for the memory to expire
    }

    @Test
    void theMemoryMayLastAtMostAMinute() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new SessionRevocations(redis, JWT, 61, 1000))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new SessionRevocations(redis, JWT, -1, 1000))
                .isInstanceOf(IllegalStateException.class);
    }
}
