package in.apnidukan.files.storage;

import in.apnidukan.files.config.R2Properties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.InputStream;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;

@Component
public class R2FileStorage implements FileStorage {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    public R2FileStorage(S3Client s3, S3Presigner presigner, R2Properties props) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = props.bucket();
    }

    @Override
    public void put(String key, InputStream content, long sizeBytes, String contentType, String sha256Hex) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .contentLength(sizeBytes)
                // End-to-end check: R2 recomputes the SHA-256 of what arrived and refuses the upload if it doesn't match.
                // The S3 header wants the raw 32-byte hash in Base64, so convert from our hex form.
                .checksumSHA256(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256Hex)))
                .build();
        s3.putObject(request, RequestBody.fromInputStream(content, sizeBytes));
    }

    @Override
    public String signedLink(String key, Duration validFor, String downloadName) {
        GetObjectRequest.Builder get = GetObjectRequest.builder().bucket(bucket).key(key);
        if (downloadName != null) {
            get.responseContentDisposition("attachment; filename=\"" + downloadName.replace("\"", "") + "\"");
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
