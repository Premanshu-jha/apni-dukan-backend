package in.apnidukan.auth.service;

import in.apnidukan.auth.domain.User;

import java.time.Instant;

/** What a successful sign-in returns: a short access token, a long refresh token, and who signed in. */
public record AuthTokens(String accessToken, Instant accessExpiresAt,
                         String refreshToken, Instant refreshExpiresAt,
                         User user, boolean newUser) {
}
