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
}
