package in.apnidukan.files.storage;

import in.apnidukan.files.config.R2Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import org.springframework.http.ContentDisposition;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class R2FileStorage implements FileStorage {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;
    private final Duration uploadTimeout;

    /**
     * @param uploadTimeout one try of an upload (app.r2.upload-timeout, 60 s): a 10 MB file needs far longer than the
     *                      short limit every other call has (R2Config), so a put gets its own; the whole put with its
     *                      retries may take twice that
     */
    public R2FileStorage(S3Client s3, S3Presigner presigner, R2Properties props,
                         @Value("${app.r2.upload-timeout:60s}") Duration uploadTimeout) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = props.bucket();
        this.uploadTimeout = uploadTimeout;
    }

    @Override
    public void put(String key, InputStream content, long sizeBytes, String contentType) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                // A file never changes under its key (a new photo gets a new key), so a phone may keep it for as long
                // as a link lasts; "private": only that phone's browser, never a shared cache
                .cacheControl("private, max-age=3600")
                .contentLength(sizeBytes)
                .overrideConfiguration(o -> o.apiCallAttemptTimeout(uploadTimeout)
                        .apiCallTimeout(uploadTimeout.multipliedBy(2)))
                .build();
        s3.putObject(request, RequestBody.fromInputStream(content, sizeBytes));
    }

    @Override
    public String signedLink(String key, Duration validFor, String downloadName) {
        GetObjectRequest.Builder get = GetObjectRequest.builder().bucket(bucket).key(key);
        if (downloadName != null) {
            // Spring builds the header safely: quotes are escaped and non-English names (e.g. Hindi) are
            // encoded as filename*=UTF-8''..., which browsers understand.
            get.responseContentDisposition(ContentDisposition.attachment()
                    .filename(downloadName, StandardCharsets.UTF_8)
                    .build()
                    .toString());
        }
        GetObjectPresignRequest presign = GetObjectPresignRequest.builder()
                .signatureDuration(validFor)
                .getObjectRequest(get.build())
                .build();
        return presigner.presignGetObject(presign).url().toString();
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }
}
