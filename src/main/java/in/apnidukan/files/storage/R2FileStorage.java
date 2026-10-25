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
    public void put(String key, InputStream content, long sizeBytes, String contentType) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .contentLength(sizeBytes)
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
