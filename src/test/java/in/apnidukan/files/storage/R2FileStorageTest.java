package in.apnidukan.files.storage;

import in.apnidukan.files.config.R2Properties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayInputStream;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** An upload gets its own, longer time limit; every other call keeps the client's short one (R2Config). */
class R2FileStorageTest {

    private final S3Client s3 = mock(S3Client.class);
    private final R2FileStorage storage = new R2FileStorage(s3, null,
            new R2Properties("http://r2.example", "bucket", "key", "secret", "auto"), Duration.ofSeconds(60));

    @Test
    void anUploadMayTake60SecondsATryAndTwiceThatInAll() {
        storage.put("shops/s/bills/f.jpg", new ByteArrayInputStream(new byte[] {1, 2, 3}), 3, "image/jpeg");
        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(put.capture(), any(RequestBody.class));
        var override = put.getValue().overrideConfiguration().orElseThrow();
        assertThat(override.apiCallAttemptTimeout()).contains(Duration.ofSeconds(60));
        assertThat(override.apiCallTimeout()).contains(Duration.ofSeconds(120));
    }

    @Test
    void aDeleteKeepsTheClientsShortLimit() {
        storage.delete("shops/s/bills/f.jpg");
        ArgumentCaptor<DeleteObjectRequest> delete = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(delete.capture());
        assertThat(delete.getValue().overrideConfiguration()).isEmpty();
    }
    @Test
    void aPhotoLinkSignedForAMomentIsExactlyTheLinkTheSdksPresignerMakesThen() {
        R2Properties props = new R2Properties("https://acc.r2.cloudflarestorage.com", "bucket", "key", "secret", "auto");
        try (software.amazon.awssdk.services.s3.presigner.S3Presigner presigner =
                     software.amazon.awssdk.services.s3.presigner.S3Presigner.builder()
                             .endpointOverride(java.net.URI.create(props.endpoint()))
                             .region(software.amazon.awssdk.regions.Region.of(props.region()))
                             .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
                                     .create(software.amazon.awssdk.auth.credentials.AwsBasicCredentials
                                             .create("key", "secret")))
                             .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder()
                                     .pathStyleAccessEnabled(true).build())
                             .build()) {
            R2FileStorage storage = new R2FileStorage(s3, presigner, props, Duration.ofSeconds(60));
            String sdk = storage.signedLink("shops/s/photos/f.jpg", Duration.ofMinutes(120), null);
            String date = java.net.URI.create(sdk).getQuery().replaceAll(".*X-Amz-Date=([0-9TZ]+).*", "$1");
            java.time.Instant at = java.time.LocalDateTime.parse(date,
                    java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
                    .toInstant(java.time.ZoneOffset.UTC);
            String ours = storage.signedLinkAt("shops/s/photos/f.jpg", at, Duration.ofMinutes(120));
            // The same request, signed the same way: R2 accepts ours exactly as it accepts the SDK's
            assertThat(query(ours)).isEqualTo(query(sdk));
            assertThat(java.net.URI.create(ours).getRawPath()).isEqualTo(java.net.URI.create(sdk).getRawPath());

            // A download link (saved under its name, a non-English one too): the same as the SDK's as well
            String sdkDownload = storage.signedLink("tickets/u/f.bin", Duration.ofMinutes(120), "बिल quote 1.docx");
            String dateD = java.net.URI.create(sdkDownload).getQuery().replaceAll(".*X-Amz-Date=([0-9TZ]+).*", "$1");
            java.time.Instant atD = java.time.LocalDateTime.parse(dateD,
                    java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
                    .toInstant(java.time.ZoneOffset.UTC);
            String oursDownload = storage.signedLinkAt("tickets/u/f.bin", atD, Duration.ofMinutes(120),
                    "बिल quote 1.docx");
            assertThat(query(oursDownload)).isEqualTo(query(sdkDownload)).containsKey("response-content-disposition");
        }
    }

    @Test
    void twoServersSignTheSameLinkForTheSameHour() {
        R2Properties props = new R2Properties("https://acc.r2.cloudflarestorage.com", "bucket", "key", "secret", "auto");
        R2FileStorage one = new R2FileStorage(mock(S3Client.class), null, props, Duration.ofSeconds(60));
        R2FileStorage two = new R2FileStorage(mock(S3Client.class), null, props, Duration.ofSeconds(60));
        java.time.Instant hour = java.time.Instant.parse("2026-11-10T04:00:00Z");
        assertThat(one.signedLinkAt("k.jpg", hour, Duration.ofMinutes(120)))
                .isEqualTo(two.signedLinkAt("k.jpg", hour, Duration.ofMinutes(120)))
                .contains("X-Amz-Date=20261110T040000Z").contains("X-Amz-Expires=7200");
        assertThat(one.signedLinkAt("k.jpg", hour.plusSeconds(3600), Duration.ofMinutes(120)))
                .isNotEqualTo(one.signedLinkAt("k.jpg", hour, Duration.ofMinutes(120)));
    }

    private static java.util.Map<String, String> query(String url) {
        java.util.Map<String, String> out = new java.util.TreeMap<>();
        for (String pair : java.net.URI.create(url).getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            out.put(eq < 0 ? pair : pair.substring(0, eq), eq < 0 ? "" : pair.substring(eq + 1));
        }
        return out;
    }
}
