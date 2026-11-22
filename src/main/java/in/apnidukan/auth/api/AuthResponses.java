package in.apnidukan.auth.api;

import in.apnidukan.auth.domain.AdminRole;
import in.apnidukan.auth.domain.User;
import in.apnidukan.auth.service.AuthService;
import in.apnidukan.auth.service.AuthTokens;

import java.time.Instant;
import java.util.UUID;

public final class AuthResponses {

    private AuthResponses() {
    }

    /**
     * @param admin           true for the admin website's users (ADMIN or SUPER_ADMIN)
     * @param adminRole       NONE, ADMIN or SUPER_ADMIN
     * @param language        the language the person chose ("hi", "te", ...), or null (none chosen: English)
     * @param adminCodeNeeded an admin whose sign-in on this device still has to pass the admin website's email step
     *                        (POST /auth/admin-code/send, then /verify); false for everyone else
     */
    public record UserResponse(UUID id, String phone, String name, String email, boolean emailVerified,
                               boolean admin, AdminRole adminRole, String language, boolean adminCodeNeeded) {
        static UserResponse from(User u, boolean adminCodeNeeded) {
            return new UserResponse(u.getId(), u.getPhone(), u.getName(), u.getEmail(), u.isEmailVerified(),
                    u.isAdmin(), u.getAdminRole(), u.getLanguage(), adminCodeNeeded);
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
                    UserResponse.from(t.user(), t.adminCodeNeeded()), t.newUser());
        }
    }

    /** After the admin email step: a new access token with admin access (the refresh token stays the same). */
    public record AdminAccessResponse(String accessToken, Instant accessExpiresAt, UserResponse user) {
        static AdminAccessResponse from(AuthService.AdminAccess a) {
            return new AdminAccessResponse(a.accessToken(), a.accessExpiresAt(), UserResponse.from(a.user(), false));
        }
    }
}
