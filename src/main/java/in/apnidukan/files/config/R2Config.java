package in.apnidukan.files.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Duration;

/**
 * The AWS S3 library, pointed at Cloudflare R2 instead of Amazon.
 * The only R2-specific parts: endpointOverride (your R2 address) and region "auto" (app.r2.region).
 * Every call is bounded: each try at most app.r2.attempt-timeout (5 s), the whole call with its retries at most
 * app.r2.call-timeout (15 s), so a hung R2 can't hold a request's thread for minutes (the library has no limit of its
 * own).
 */
@Configuration
public class R2Config {

    @Bean
    public StaticCredentialsProvider r2Credentials(R2Properties props) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey(), props.secretKey()));
    }

    @Bean
    public S3Client s3Client(R2Properties props, StaticCredentialsProvider r2Credentials,
                             @Value("${app.r2.attempt-timeout:5s}") Duration attemptTimeout,
                             @Value("${app.r2.call-timeout:15s}") Duration callTimeout) {
        return S3Client.builder()
                .endpointOverride(URI.create(props.endpoint()))
                .region(Region.of(props.region()))
                .credentialsProvider(r2Credentials)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                // Newer AWS SDKs add extra checksums by default; R2 only needs them when required.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .overrideConfiguration(c -> c.apiCallAttemptTimeout(attemptTimeout).apiCallTimeout(callTimeout))
                .build();
    }

    @Bean
    public S3Presigner s3Presigner(R2Properties props, StaticCredentialsProvider r2Credentials) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(props.endpoint()))
                .region(Region.of(props.region()))
                .credentialsProvider(r2Credentials)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }
}
