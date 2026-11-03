package in.apnidukan.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request bodies for the sign-in endpoints. Phone and email formats are checked in the service. */
public final class AuthRequests {

    private AuthRequests() {
    }

    /** @param captchaToken the Cloudflare Turnstile widget's token; required once TURNSTILE_SECRET_KEY is set */
    public record SendOtp(@NotBlank @Size(max = 20) String phone, @Size(max = 2048) String captchaToken) {
    }

    /** @param device optional label shown later in "signed-in devices", e.g. "Redmi Note 12" */
    public record VerifyOtp(@NotBlank @Size(max = 20) String phone, @NotBlank @Size(max = 10) String code,
                            @Size(max = 200) String device) {
    }

    public record Refresh(@NotBlank @Size(max = 100) String refreshToken, @Size(max = 200) String device) {
    }

    public record Logout(@NotBlank @Size(max = 100) String refreshToken) {
    }

    /** @param phoneCode only when replacing an already verified email: the code from /email/change-phone-code */
    public record SendEmailCode(@NotBlank @Size(max = 254) String email, @Size(max = 10) String phoneCode) {
    }

    public record VerifyEmail(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 10) String code) {
    }

    /** @param captchaToken as in SendOtp */
    public record StartRecovery(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 20) String newPhone,
                                @Size(max = 2048) String captchaToken) {
    }

    public record CompleteRecovery(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 10) String emailCode,
                                   @NotBlank @Size(max = 20) String newPhone,
                                   @NotBlank @Size(max = 10) String phoneCode, @Size(max = 200) String device) {
    }

    /** Your name as salons and shops see it, e.g. "Ravi". 2 to 60 characters after cleaning. */
    public record UpdateMe(@NotBlank @Size(min = 2, max = 60) String name) {
    }
}
