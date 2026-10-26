package in.apnidukan.auth.api;

import in.apnidukan.auth.domain.User;
import in.apnidukan.auth.service.AuthTokens;

import java.time.Instant;
import java.util.UUID;

public final class AuthResponses {

    private AuthResponses() {
    }

    public record UserResponse(UUID id, String phone, String name, String email, boolean emailVerified,
                               boolean admin) {
        static UserResponse from(User u) {
            return new UserResponse(u.getId(), u.getPhone(), u.getName(), u.getEmail(), u.isEmailVerified(),
                    u.isAdmin());
        }
    }

    /**
     * @param newUser true the first time this phone signs in: the app then asks for email and shop registration
     */
    public record TokenResponse(String accessToken, Instant accessExpiresAt,
                                String refreshToken, Instant refreshExpiresAt,
                                UserResponse user, boolean newUser) {
        static TokenResponse from(AuthTokens t) {
            return new TokenResponse(t.accessToken(), t.accessExpiresAt(), t.refreshToken(), t.refreshExpiresAt(),
                    UserResponse.from(t.user()), t.newUser());
        }
    }
}
