package in.apnidukan.files.config;

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

/**
 * The AWS S3 library, pointed at Cloudflare R2 instead of Amazon.
 * The only R2-specific parts: endpointOverride (your R2 address) and region "auto".
 */
@Configuration
public class R2Config {

    @Bean
    public StaticCredentialsProvider r2Credentials(R2Properties props) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey(), props.secretKey()));
    }

    @Bean
    public S3Client s3Client(R2Properties props, StaticCredentialsProvider r2Credentials) {
        return S3Client.builder()
                .endpointOverride(URI.create(props.endpoint()))
                .region(Region.of("auto"))
                .credentialsProvider(r2Credentials)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                // Newer AWS SDKs add a CRC32 "trailer" checksum to every upload by default; R2's docs don't list that
                // format, and it used to make every upload fail. We send our own SHA-256 instead (see R2FileStorage.put).
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
    }

    @Bean
    public S3Presigner s3Presigner(R2Properties props, StaticCredentialsProvider r2Credentials) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(props.endpoint()))
                .region(Region.of("auto"))
                .credentialsProvider(r2Credentials)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }
}
