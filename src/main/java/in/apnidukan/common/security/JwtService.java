package in.apnidukan.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Creates and checks the short-lived access token (JWT, 15 minutes) the app sends as "Authorization: Bearer ...".
 * Signed with HMAC-SHA256 using JWT_SECRET; anyone changing a single character breaks the signature.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final Duration accessValidity;
    private final Clock clock;

    public JwtService(JwtProperties props, Clock clock) {
        this.key = Keys.hmacShaKeyFor(props.secret().getBytes(StandardCharsets.UTF_8));
        this.accessValidity = Duration.ofMinutes(props.accessMinutes());
        this.clock = clock;
    }

    public IssuedToken issue(AuthPrincipal who) {
        Instant now = Instant.now(clock);
        Instant expires = now.plus(accessValidity);
        var builder = Jwts.builder()
                .subject(who.userId().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expires))
                .claim("adm", who.admin());
        if (who.shopId() != null) {
            builder.claim("shop", who.shopId().toString()).claim("role", who.shopRole());
        }
        return new IssuedToken(builder.signWith(key).compact(), expires);
    }

    /** @throws TokenExpiredException if it was valid but is too old; @throws InvalidTokenException otherwise */
    public AuthPrincipal parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(Instant.now(clock)))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String shop = claims.get("shop", String.class);
            Boolean admin = claims.get("adm", Boolean.class);
            return new AuthPrincipal(
                    UUID.fromString(claims.getSubject()),
                    shop == null ? null : UUID.fromString(shop),
                    claims.get("role", String.class),
                    Boolean.TRUE.equals(admin));
        } catch (ExpiredJwtException e) {
            throw new TokenExpiredException();
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException();
        }
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }

    public static class TokenExpiredException extends RuntimeException {
    }

    public static class InvalidTokenException extends RuntimeException {
    }
}
