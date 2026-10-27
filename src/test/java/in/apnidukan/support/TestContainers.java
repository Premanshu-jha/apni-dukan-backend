package in.apnidukan.support;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.time.Duration;

/**
 * Throwaway Postgres and S3Mock (an S3-compatible store standing in for Cloudflare R2), started once
 * per test run in Docker and removed afterwards. Credentials here are made up; nothing real is used.
 */
public final class TestContainers {

    public static final String DB_NAME = "apnidukan";
    public static final String DB_USER = "test";
    public static final String DB_PASSWORD = "test";
    public static final String S3_ACCESS_KEY = "testaccesskey";
    public static final String S3_SECRET_KEY = "testsecretkey123";
    public static final String S3_REGION = "us-east-1";
    public static final String BUCKET = "apnidukan-files";

    /** Made-up signing secret for tests only (at least 32 characters). */
    public static final String JWT_SECRET = "test-only-secret-0123456789-abcdefghijklmnopqrstuvwxyz";

    private static GenericContainer<?> postgres;
    private static GenericContainer<?> minio;
    private static GenericContainer<?> redis;

    private TestContainers() {
    }

    /**
     * Integration tests run only when Docker is available. On your laptop without Docker they are skipped.
     * On CI they always run: there, missing Docker must fail loudly, not quietly skip half the tests.
     */
    public static boolean dockerAvailable() {
        if (System.getenv("CI") != null) {
            return true;
        }
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable e) {
            return false;
        }
    }

    public static synchronized GenericContainer<?> postgres() {
        if (postgres == null) {
            postgres = new GenericContainer<>("postgres:16-alpine")
                    .withEnv("POSTGRES_DB", DB_NAME)
                    .withEnv("POSTGRES_USER", DB_USER)
                    .withEnv("POSTGRES_PASSWORD", DB_PASSWORD)
                    .withExposedPorts(5432)
                    .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2)
                            .withStartupTimeout(Duration.ofMinutes(2)));
            postgres.start();
        }
        return postgres;
    }

    public static String jdbcUrl() {
        GenericContainer<?> pg = postgres();
        return "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/" + DB_NAME;
    }

    public static synchronized GenericContainer<?> minio() {
        if (minio == null) {
            // Adobe S3Mock: open-source S3-compatible test server (MinIO no longer publishes a Docker image).
            minio = new GenericContainer<>("adobe/s3mock:latest")
                    .withExposedPorts(9090)
                    .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
            minio.start();
            try (S3Client s3 = s3Client()) {
                s3.createBucket(b -> b.bucket(BUCKET));
            }
        }
        return minio;
    }

    public static String s3Endpoint() {
        GenericContainer<?> m = minio();
        return "http://" + m.getHost() + ":" + m.getMappedPort(9090);
    }

    public static synchronized GenericContainer<?> redis() {
        if (redis == null) {
            redis = new GenericContainer<>("redis:7-alpine")
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\s", 1)
                            .withStartupTimeout(Duration.ofMinutes(2)));
            redis.start();
        }
        return redis;
    }

    public static String redisUrl() {
        GenericContainer<?> r = redis();
        return "redis://" + r.getHost() + ":" + r.getMappedPort(6379);
    }

    /** Points the app at the test containers, using the same names application.properties reads. */
    public static void register(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("app.sms.provider", () -> "log"); // tests capture SMS and email (CapturingSenders),
        registry.add("app.mail.enabled", () -> "false"); // nothing is really sent
        registry.add("DB_URL", TestContainers::jdbcUrl);
        registry.add("DB_USER", () -> DB_USER);
        registry.add("DB_PASSWORD", () -> DB_PASSWORD);
        registry.add("R2_ENDPOINT", TestContainers::s3Endpoint);
        registry.add("R2_BUCKET", () -> BUCKET);
        registry.add("R2_ACCESS_KEY", () -> S3_ACCESS_KEY);
        registry.add("R2_SECRET_KEY", () -> S3_SECRET_KEY);
        registry.add("R2_REGION", () -> S3_REGION);
        registry.add("REDIS_URL", TestContainers::redisUrl);
        registry.add("JWT_SECRET", () -> JWT_SECRET);
    }

    /** A plain S3 client for the test itself, to look inside the bucket. */
    public static S3Client s3Client() {
        GenericContainer<?> m = minio;
        return S3Client.builder()
                .endpointOverride(URI.create("http://" + m.getHost() + ":" + m.getMappedPort(9090)))
                .region(Region.of(S3_REGION))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(S3_ACCESS_KEY, S3_SECRET_KEY)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }
}
