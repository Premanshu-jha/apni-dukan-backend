package in.apnidukan.auth.service;

import in.apnidukan.auth.domain.User;

import java.time.Instant;

/**
 * What a successful sign-in returns: a short access token, a long refresh token, and who signed in.
 * @param adminCodeNeeded an admin whose device sign-in hasn't passed the admin website's email step (or passed it more
 *                        than app.auth.admin-code-hours ago): the access token has no admin access until it does
 */
public record AuthTokens(String accessToken, Instant accessExpiresAt,
                         String refreshToken, Instant refreshExpiresAt,
                         User user, boolean newUser, boolean adminCodeNeeded) {
}
