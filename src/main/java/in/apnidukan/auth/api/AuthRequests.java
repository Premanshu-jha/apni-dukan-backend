package in.apnidukan.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request bodies for the sign-in endpoints. Phone and email formats are checked in the service. */
public final class AuthRequests {

    private AuthRequests() {
    }

    public record SendOtp(@NotBlank String phone) {
    }

    /** @param device optional label shown later in "signed-in devices", e.g. "Redmi Note 12" */
    public record VerifyOtp(@NotBlank String phone, @NotBlank @Size(max = 10) String code, String device) {
    }

    public record Refresh(@NotBlank String refreshToken, String device) {
    }

    public record Logout(@NotBlank String refreshToken) {
    }

    public record SendEmailCode(@NotBlank String email) {
    }

    public record VerifyEmail(@NotBlank String email, @NotBlank @Size(max = 10) String code) {
    }

    public record StartRecovery(@NotBlank String email, @NotBlank String newPhone) {
    }

    public record CompleteRecovery(@NotBlank String email, @NotBlank @Size(max = 10) String emailCode,
                                   @NotBlank String newPhone, @NotBlank @Size(max = 10) String phoneCode,
                                   String device) {
    }
}
