package in.apnidukan.common.security;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Sign-in token settings from app.jwt.* (the secret comes from the JWT_SECRET environment variable). */
@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        @NotBlank @Size(min = 32, message = "must be at least 32 characters") String secret,
        @Min(1) int accessMinutes,
        @Min(1) int refreshDays) {
}
