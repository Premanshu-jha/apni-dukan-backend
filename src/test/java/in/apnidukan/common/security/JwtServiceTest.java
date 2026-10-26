package in.apnidukan.common.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "test-only-secret-0123456789-abcdefghijklmnopqrstuvwxyz";
    private static final Instant NOW = Instant.parse("2026-10-26T09:00:00Z");

    private final JwtService jwt = service(SECRET, NOW);

    @Test
    void tokenCarriesUserShopRoleAndAdminFlag() {
        AuthPrincipal who = new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), "OWNER", true);
        JwtService.IssuedToken issued = jwt.issue(who);

        assertThat(jwt.parse(issued.token())).isEqualTo(who);
        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    }

    @Test
    void tokenWithoutAShopHasNullShop() {
        AuthPrincipal who = new AuthPrincipal(UUID.randomUUID(), null, null, false);
        assertThat(jwt.parse(jwt.issue(who).token())).isEqualTo(who);
    }

    @Test
    void expiredTokenIsRecognisedAsExpired() {
        String token = jwt.issue(new AuthPrincipal(UUID.randomUUID(), null, null, false)).token();
        JwtService later = service(SECRET, NOW.plus(Duration.ofMinutes(16)));
        assertThatThrownBy(() -> later.parse(token)).isInstanceOf(JwtService.TokenExpiredException.class);
    }

    @Test
    void changedTokenIsInvalid() {
        String token = jwt.issue(new AuthPrincipal(UUID.randomUUID(), null, null, false)).token();
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "BB" : "AA");
        assertThatThrownBy(() -> jwt.parse(tampered)).isInstanceOf(JwtService.InvalidTokenException.class);
    }

    @Test
    void tokenSignedWithAnotherSecretIsInvalid() {
        String token = service("another-secret-that-is-also-long-enough-123456", NOW)
                .issue(new AuthPrincipal(UUID.randomUUID(), null, null, false)).token();
        assertThatThrownBy(() -> jwt.parse(token)).isInstanceOf(JwtService.InvalidTokenException.class);
    }

    @Test
    void garbageIsInvalid() {
        assertThatThrownBy(() -> jwt.parse("hello")).isInstanceOf(JwtService.InvalidTokenException.class);
    }

    private static JwtService service(String secret, Instant now) {
        return new JwtService(new JwtProperties(secret, 15, 30), Clock.fixed(now, ZoneOffset.UTC));
    }
}
