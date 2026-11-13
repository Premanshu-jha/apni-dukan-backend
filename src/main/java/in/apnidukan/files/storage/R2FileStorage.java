package in.apnidukan.files.storage;

import in.apnidukan.files.config.R2Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import org.springframework.http.ContentDisposition;
import software.amazon.awssdk.utils.http.SdkHttpUtils;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.OptionalLong;

@Component
public class R2FileStorage implements FileStorage {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;
    private final Duration uploadTimeout;
    private final String endpoint;
    private final String region;
    private final AwsCredentialsIdentity credentials;

    private static final AwsV4HttpSigner SIGNER = AwsV4HttpSigner.create();

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
        this.endpoint = props.endpoint().endsWith("/") ? props.endpoint().substring(0, props.endpoint().length() - 1)
                : props.endpoint();
        this.region = props.region();
        this.credentials = AwsCredentialsIdentity.create(props.accessKey(), props.secretKey());
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

    /**
     * Signed with the AWS SDK's own SigV4 signer (the one S3Presigner uses), on a clock fixed at {@code signedAt}: the
     * same query-string signature R2 checks for any presigned GET (path-style, unsigned payload, host the only signed
     * header), only its date is the one given. R2FileStorageTest checks it is exactly the link S3Presigner makes for
     * that moment.
     */
    @Override
    public String signedLinkAt(String key, Instant signedAt, Duration validFor) {
        return signedLinkAt(key, signedAt, validFor, null);
    }

    @Override
    public String signedLinkAt(String key, Instant signedAt, Duration validFor, String downloadName) {
        SdkHttpRequest.Builder builder = SdkHttpRequest.builder()
                .method(SdkHttpMethod.GET)
                .uri(URI.create(endpoint + "/" + bucket + "/" + SdkHttpUtils.urlEncodeIgnoreSlashes(key)));
        if (downloadName != null) {
            // R2 answers with this Content-Disposition (signed with the rest): saved under its name, never shown
            builder.appendRawQueryParameter("response-content-disposition", ContentDisposition.attachment()
                    .filename(downloadName, StandardCharsets.UTF_8).build().toString());
        }
        SdkHttpRequest get = builder.build();
        SignedRequest signed = SIGNER.sign(r -> r
                .identity(credentials)
                .request(get)
                .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, "s3")
                .putProperty(AwsV4HttpSigner.REGION_NAME, region)
                .putProperty(AwsV4HttpSigner.AUTH_LOCATION, AwsV4HttpSigner.AuthLocation.QUERY_STRING)
                .putProperty(AwsV4HttpSigner.EXPIRATION_DURATION, validFor)
                .putProperty(AwsV4HttpSigner.PAYLOAD_SIGNING_ENABLED, false)
                .putProperty(AwsV4HttpSigner.DOUBLE_URL_ENCODE, false)
                .putProperty(AwsV4HttpSigner.NORMALIZE_PATH, false)
                .putProperty(HttpSigner.SIGNING_CLOCK, Clock.fixed(signedAt, ZoneOffset.UTC)));
        return signed.request().getUri().toString();
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    /**
     * A presigned PUT: the content type and length are part of the signature, so R2 refuses a PUT of another type or
     * size, and the object may be written only until the link runs out. Cache-Control is not signed (the phone can't
     * add it), so stored videos keep R2's default; their view links are short-lived anyway.
     */
    @Override
    public String uploadLink(String key, String contentType, long sizeBytes, Duration validFor) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .contentLength(sizeBytes)
                .build();
        PutObjectPresignRequest presign = PutObjectPresignRequest.builder()
                .signatureDuration(validFor)
                .putObjectRequest(put)
                .build();
        return presigner.presignPutObject(presign).url().toString();
    }

    /** A server-side copy (up to 5 GB in one call); given the upload's time limit, as it may move 100 MB. */
    @Override
    public void copy(String fromKey, String toKey) {
        s3.copyObject(CopyObjectRequest.builder()
                .sourceBucket(bucket).sourceKey(fromKey)
                .destinationBucket(bucket).destinationKey(toKey)
                .overrideConfiguration(o -> o.apiCallAttemptTimeout(uploadTimeout)
                        .apiCallTimeout(uploadTimeout.multipliedBy(2)))
                .build());
    }

    @Override
    public OptionalLong size(String key) {
        try {
            Long length = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()).contentLength();
            return length == null ? OptionalLong.empty() : OptionalLong.of(length);
        } catch (NoSuchKeyException missing) {
            return OptionalLong.empty();
        } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
            if (e.statusCode() == 404) {
                return OptionalLong.empty();   // HEAD has no body, so R2 answers a bare 404 for a missing key
            }
            throw e;
        }
    }

    @Override
    public byte[] firstBytes(String key, int count) {
        GetObjectRequest get = GetObjectRequest.builder().bucket(bucket).key(key)
                .range("bytes=0-" + (count - 1)).build();
        byte[] bytes = s3.getObjectAsBytes(get).asByteArray();
        return bytes.length <= count ? bytes : java.util.Arrays.copyOf(bytes, count);
    }
}
