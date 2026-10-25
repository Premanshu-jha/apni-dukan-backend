package in.apnidukan.files.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Cloudflare R2 settings, bound from the app.r2.* lines in application.properties.
 * Those lines get their values from environment variables (IntelliJ run configuration, Railway settings, or a .env file).
 */
@Validated
@ConfigurationProperties(prefix = "app.r2")
public record R2Properties(
        @NotBlank String endpoint,
        @NotBlank String bucket,
        @NotBlank String accessKey,
        @NotBlank String secretKey,
        @NotBlank String region) {
}
