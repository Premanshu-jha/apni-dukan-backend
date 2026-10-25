package in.apnidukan.files;

import in.apnidukan.support.TestContainers;
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
class FilesIntegrationTest {

    private static final String SHOP_A = "11111111-1111-1111-1111-111111111111";
    private static final String SHOP_B = "33333333-3333-3333-3333-333333333333";
    private static final String USER = "22222222-2222-2222-2222-222222222222";

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        // The same names application.properties expects as environment variables.
        registry.add("DB_URL", TestContainers::jdbcUrl);
        registry.add("DB_USER", () -> TestContainers.DB_USER);
        registry.add("DB_PASSWORD", () -> TestContainers.DB_PASSWORD);
        registry.add("R2_ENDPOINT", TestContainers::s3Endpoint);
        registry.add("R2_BUCKET", () -> TestContainers.BUCKET);
        registry.add("R2_ACCESS_KEY", () -> TestContainers.S3_ACCESS_KEY);
        registry.add("R2_SECRET_KEY", () -> TestContainers.S3_SECRET_KEY);
        registry.add("R2_REGION", () -> TestContainers.S3_REGION);
        registry.add("DEV_AUTH_ENABLED", () -> "true");
    }

    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;

    RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.builder()
                .baseUrl("http://localhost:" + environment.getProperty("local.server.port") + "/api/v1")
                .build();
        jdbc.update("DELETE FROM files");
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
    void callsWithoutTheShopHeaderAreRefused() {
        Reply reply = http.get().uri("/files/" + UUID.randomUUID() + "/link")
                .exchange((req, res) -> new Reply(res.getStatusCode(), res.bodyTo(Map.class)));
        assertThat(reply.status()).isEqualTo(401);
        assertThat(reply.body().get("code")).isEqualTo("NOT_SIGNED_IN");
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
                .header("X-Dev-Shop-Id", SHOP_A).header("X-Dev-User-Id", USER)
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
                .header("X-Dev-Shop-Id", shop).header("X-Dev-User-Id", USER)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .exchange((req, res) -> new Reply(res.getStatusCode(), res.bodyTo(Map.class)));
    }

    private Reply get(String shop, String path) {
        return http.get().uri(path)
                .header("X-Dev-Shop-Id", shop).header("X-Dev-User-Id", USER)
                .exchange((req, res) -> new Reply(res.getStatusCode(), res.bodyTo(Map.class)));
    }

    private Reply delete(String shop, String path) {
        return http.delete().uri(path)
                .header("X-Dev-Shop-Id", shop).header("X-Dev-User-Id", USER)
                .exchange((req, res) -> new Reply(res.getStatusCode(),
                        res.getStatusCode().value() == 204 ? Map.of() : res.bodyTo(Map.class)));
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
