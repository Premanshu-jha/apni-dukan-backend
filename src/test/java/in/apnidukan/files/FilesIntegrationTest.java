package in.apnidukan.files;

import in.apnidukan.common.security.AuthPrincipal;
import in.apnidukan.common.security.JwtService;
import in.apnidukan.support.TestContainers;
import in.apnidukan.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Starts the whole app against a real Postgres and a real S3-compatible store (S3Mock) in Docker:
 * Flyway runs V1, Hibernate validates the entity against the table, and the files API is called over HTTP.
 * Skipped automatically on a machine without Docker; always runs on GitHub Actions.
 */
@EnabledIf("in.apnidukan.support.TestContainers#dockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(FilesIntegrationTest.FlakyR2.class)
class FilesIntegrationTest {

    /** While true, every delete in R2 fails, as when R2 is down or doesn't answer. */
    static volatile boolean r2DeletesFail;

    /** The real storage (S3Mock), with deletes that can be made to fail. */
    @org.springframework.boot.test.context.TestConfiguration
    static class FlakyR2 {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        in.apnidukan.files.storage.FileStorage flakyStorage(in.apnidukan.files.storage.R2FileStorage real) {
            return new in.apnidukan.files.storage.FileStorage() {
                @Override
                public void put(String key, java.io.InputStream content, long sizeBytes, String contentType) {
                    real.put(key, content, sizeBytes, contentType);
                }

                @Override
                public String signedLink(String key, java.time.Duration validFor, String downloadName) {
                    return real.signedLink(key, validFor, downloadName);
                }

                @Override
                public String signedLinkAt(String key, java.time.Instant signedAt, java.time.Duration validFor) {
                    return real.signedLinkAt(key, signedAt, validFor);
                }

                @Override
                public void delete(String key) {
                    if (r2DeletesFail) {
                        throw new IllegalStateException("R2 is down");
                    }
                    real.delete(key);
                }

                @Override
                public String uploadLink(String key, String contentType, long sizeBytes, java.time.Duration validFor) {
                    return real.uploadLink(key, contentType, sizeBytes, validFor);
                }

                @Override
                public void copy(String fromKey, String toKey) {
                    real.copy(fromKey, toKey);
                }

                @Override
                public java.util.OptionalLong size(String key) {
                    return real.size(key);
                }

                @Override
                public byte[] firstBytes(String key, int count) {
                    return real.firstBytes(key, count);
                }
            };
        }
    }

    private static final String SHOP_A = "11111111-1111-1111-1111-111111111111";
    private static final String SHOP_B = "33333333-3333-3333-3333-333333333333";
    private static final String USER = "22222222-2222-2222-2222-222222222222";
    private static final String OTHER_OWNER = "44444444-4444-4444-4444-444444444444";

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        TestContainers.register(registry);
    }

    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired in.apnidukan.files.service.FileDeletionJob deletionJob;

    RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.builder()
                // JDK client: the Apache client pulled in by Testcontainers silently retries 429 replies
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + environment.getProperty("local.server.port") + "/api/v1")
                .build();
        // Files must belong to real shops and real people (foreign keys since V3)
        TestData.clean(jdbc);
        TestData.user(jdbc, UUID.fromString(USER), "9000000101");
        TestData.user(jdbc, UUID.fromString(OTHER_OWNER), "9000000102");
        TestData.shop(jdbc, UUID.fromString(SHOP_A), UUID.fromString(USER));
        TestData.shop(jdbc, UUID.fromString(SHOP_B), UUID.fromString(OTHER_OWNER));
        jdbc.update("DELETE FROM file_deletions");
        r2DeletesFail = false;
    }

    @Test
    void aDeleteR2RefusesIsQueuedAndTheJobDeletesTheObjectLater() {
        Reply up = upload(SHOP_A, fakeJpeg(), "late.jpg", "BILL");
        String id = (String) up.body().get("id");
        String key = jdbc.queryForObject("SELECT storage_key FROM files WHERE id = ?::uuid", String.class, id);

        r2DeletesFail = true;
        assertThat(delete(SHOP_A, "/files/" + id).status()).isEqualTo(204);   // gone for the shop
        assertThat(jdbc.queryForObject("SELECT count(*) FROM files", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_deletions WHERE storage_key = ?", Integer.class,
                key)).isEqualTo(1);
        try (S3Client s3 = TestContainers.s3Client()) {
            s3.headObject(b -> b.bucket(TestContainers.BUCKET).key(key));   // still there
        }
        // Still down: tried, counted, and put off (1 minute after the first failure)
        assertThat(deletionJob.runOnce()).isZero();
        assertThat(jdbc.queryForObject("SELECT attempts FROM file_deletions WHERE storage_key = ?", Integer.class,
                key)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT next_try_at > now() FROM file_deletions WHERE storage_key = ?",
                Boolean.class, key)).isTrue();

        // R2 is back: not before its time, then deleted and off the queue
        r2DeletesFail = false;
        assertThat(deletionJob.runOnce()).isZero();
        jdbc.update("UPDATE file_deletions SET next_try_at = now() - interval '1 second'");
        assertThat(deletionJob.runOnce()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_deletions", Integer.class)).isZero();
        try (S3Client s3 = TestContainers.s3Client()) {
            assertThatThrownBy(() -> s3.headObject(b -> b.bucket(TestContainers.BUCKET).key(key)))
                    .isInstanceOf(NoSuchKeyException.class);
        }
    }

    @Test
    void aShopCanUploadAtMost100FilesADayAndPhotosMustBeRealImages() {
        for (int i = 0; i < 100; i++) {
            jdbc.update("INSERT INTO files (id, shop_id, purpose, storage_key, content_type, size_bytes, sha256, "
                    + "uploaded_by, created_at) VALUES (?, ?, 'BILL', ?, 'image/jpeg', 1, ?, ?, now())",
                    UUID.randomUUID(), UUID.fromString(SHOP_A), "test/" + i, String.format("%064d", i),
                    UUID.fromString(USER));
        }
        Reply over = upload(SHOP_A, fakeJpeg(), "bill.jpg", "BILL");
        assertThat(over.status()).isEqualTo(429);
        assertThat(over.body().get("code")).isEqualTo("TOO_MANY_UPLOADS");
        // Another shop isn't affected; a "photo" that is only a JPEG header is refused
        Reply fakePhoto = upload(SHOP_B, fakeJpeg(), "me.jpg", "OWNER_PHOTO");
        assertThat(fakePhoto.body().get("code")).isEqualTo("UNREADABLE_PHOTO");
    }

    @Test
    void flywayCreatedTheFilesTableAndHibernateAcceptedIt() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success", Integer.class);
        assertThat(applied).isEqualTo(1);
        // If the StoredFile entity didn't match the table, ddl-auto=validate would have stopped the app
        // before this test could run.
    }

    @Test
    void uploadViewDuplicateAndDeleteAgainstRealStorage() throws Exception {
        byte[] photo = fakeJpeg();

        // Upload: 201, row in Postgres, bytes in the bucket
        Reply first = upload(SHOP_A, photo, "KD-4471.jpg", "BILL");
        assertThat(first.status()).isEqualTo(201);
        String id = (String) first.body().get("id");
        assertThat(first.body().get("duplicate")).isEqualTo(false);
        String key = jdbc.queryForObject("SELECT storage_key FROM files WHERE id = ?::uuid", String.class, id);
        assertThat(key).startsWith("shops/" + SHOP_A + "/bills/").endsWith(".jpg");
        try (S3Client s3 = TestContainers.s3Client()) {
            assertThat(s3.headObject(b -> b.bucket(TestContainers.BUCKET).key(key)).contentLength())
                    .isEqualTo(photo.length);
        }

        // Same file again: 200, same id, nothing new stored
        Reply again = upload(SHOP_A, photo, "KD-4471-copy.jpg", "BILL");
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().get("id")).isEqualTo(id);
        assertThat(again.body().get("duplicate")).isEqualTo(true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM files", Integer.class)).isEqualTo(1);

        // Signed link: opening it returns exactly the uploaded bytes
        Reply link = get(SHOP_A, "/files/" + id + "/link");
        assertThat(link.status()).isEqualTo(200);
        HttpResponse<byte[]> download = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create((String) link.body().get("url"))).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(photo);

        // Another shop can't see or delete it
        assertThat(get(SHOP_B, "/files/" + id + "/link").status()).isEqualTo(404);
        assertThat(delete(SHOP_B, "/files/" + id).status()).isEqualTo(404);

        // Delete: 204, row gone, bytes gone
        assertThat(delete(SHOP_A, "/files/" + id).status()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM files", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_deletions", Integer.class)).isZero();
        try (S3Client s3 = TestContainers.s3Client()) {
            assertThatThrownBy(() -> s3.headObject(b -> b.bucket(TestContainers.BUCKET).key(key)))
                    .isInstanceOf(NoSuchKeyException.class);
        }
    }

    @Test
    void textFileNamedJpgIsRefused() {
        Reply reply = upload(SHOP_A, "this is not a photo".getBytes(), "bill.jpg", "BILL");
        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.body().get("code")).isEqualTo("UNSUPPORTED_FILE_TYPE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM files", Integer.class)).isZero();
    }

    @Test
    void callsWithoutASignInTokenAreRefused() {
        Reply reply = http.get().uri("/files/" + UUID.randomUUID() + "/link")
                .exchange((req, res) -> new Reply(res.getStatusCode(), res.bodyTo(Map.class)));
        assertThat(reply.status()).isEqualTo(401);
        assertThat(reply.body().get("code")).isEqualTo("NOT_SIGNED_IN");
    }

    @Test
    void signedInWithoutAShopCannotUploadYet() {
        String noShop = jwt.issue(new AuthPrincipal(UUID.fromString(USER), null, null, false), 0).token();
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("purpose", "BILL");
        form.add("file", new ByteArrayResource(fakeJpeg()) {
            @Override
            public String getFilename() {
                return "bill.jpg";
            }
        });
        Reply reply = http.post().uri("/files")
                .header("Authorization", "Bearer " + noShop)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .exchange((req, res) -> new Reply(res.getStatusCode(), res.bodyTo(Map.class)));
        assertThat(reply.status()).isEqualTo(403);
        assertThat(reply.body().get("code")).isEqualTo("SHOP_REQUIRED");
    }

    @Test
    void deletingTheSameFileTwiceGivesNotFoundTheSecondTime() {
        String id = (String) upload(SHOP_A, fakeJpeg(), "bill.jpg", "BILL").body().get("id");
        assertThat(delete(SHOP_A, "/files/" + id).status()).isEqualTo(204);
        assertThat(delete(SHOP_A, "/files/" + id).status()).isEqualTo(404);
    }

    @Test
    void downloadLinkKeepsAHindiFileName() throws Exception {
        String name = "\u092C\u093F\u0932 \"KD\".jpg";   // "बिल "KD".jpg": Hindi letters and quotes
        String id = (String) upload(SHOP_A, fakeJpeg(), name, "BILL").body().get("id");
        Reply link = get(SHOP_A, "/files/" + id + "/link?download=true");
        HttpResponse<byte[]> download = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create((String) link.body().get("url"))).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.headers().firstValue("Content-Disposition").orElse(""))
                .startsWith("attachment")
                .contains("filename*=UTF-8''");
    }

    @Test
    void unknownAddressAndWrongMethodKeepTheirProperStatus() {
        Reply unknown = get(SHOP_A, "/does-not-exist");
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.body().get("code")).isEqualTo("NOT_FOUND");

        Reply wrongMethod = http.put().uri("/files/" + UUID.randomUUID() + "/link")
                .header("Authorization", "Bearer " + tokenFor(SHOP_A))
                .exchange((req, res) -> new Reply(res.getStatusCode(), res.bodyTo(Map.class)));
        assertThat(wrongMethod.status()).isEqualTo(405);
        assertThat(wrongMethod.body().get("code")).isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    void tooLargeFileGetsAClear413() {
        Reply reply = upload(SHOP_A, new byte[11 * 1024 * 1024], "huge.jpg", "BILL");
        assertThat(reply.status()).isEqualTo(413);
        assertThat(reply.body().get("code")).isEqualTo("FILE_TOO_LARGE");
    }

    @Test
    void unknownPurposeSaysWhichValuesAreAllowed() {
        Reply reply = upload(SHOP_A, fakeJpeg(), "bill.jpg", "INVOICE");
        assertThat(reply.status()).isEqualTo(400);
        assertThat((String) reply.body().get("message")).contains("INVOICE").contains("purpose");
        assertThat(reply.body().get("details").toString()).contains("SHOP_PHOTO", "BILL", "RECEIPT");
    }

    // ---------- helpers ----------

    private Reply upload(String shop, byte[] bytes, String filename, String purpose) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("purpose", purpose);
        form.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        return http.post().uri("/files")
                .header("Authorization", "Bearer " + tokenFor(shop))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .exchange((req, res) -> new Reply(res.getStatusCode(), res.bodyTo(Map.class)));
    }

    private Reply get(String shop, String path) {
        return http.get().uri(path)
                .header("Authorization", "Bearer " + tokenFor(shop))
                .exchange((req, res) -> new Reply(res.getStatusCode(), res.bodyTo(Map.class)));
    }

    private Reply delete(String shop, String path) {
        return http.delete().uri(path)
                .header("Authorization", "Bearer " + tokenFor(shop))
                .exchange((req, res) -> new Reply(res.getStatusCode(),
                        res.getStatusCode().value() == 204 ? Map.of() : res.bodyTo(Map.class)));
    }

    /** A real access token for a person acting for this shop (the shop's id is made up until shops exist). */
    /** The token of that shop's owner (files act for the shop in the database, so it must be theirs). */
    private String tokenFor(String shop) {
        String owner = SHOP_B.equals(shop) ? OTHER_OWNER : USER;
        return jwt.issue(new AuthPrincipal(UUID.fromString(owner), UUID.fromString(shop), "OWNER", false), 0).token();
    }

    private static byte[] fakeJpeg() {
        byte[] bytes = new byte[2048];
        new Random(42).nextBytes(bytes);
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        return bytes;
    }

    private record Reply(int status, Map<?, ?> body) {
        Reply(HttpStatusCode code, Map<?, ?> body) {
            this(code.value(), body == null ? Map.of() : body);
        }
    }
}
