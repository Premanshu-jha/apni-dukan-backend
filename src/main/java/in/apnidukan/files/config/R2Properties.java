package in.apnidukan.files.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Cloudflare R2 settings, read from app.r2.* (values come from .env). */
@Validated
@ConfigurationProperties(prefix = "app.r2")
public record R2Properties(
        @NotBlank String endpoint,
        @NotBlank String bucket,
        @NotBlank String accessKey,
        @NotBlank String secretKey) {
}
