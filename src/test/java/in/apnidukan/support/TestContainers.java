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
 * Throwaway Postgres and MinIO (an S3-compatible store standing in for Cloudflare R2), started once
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

    private static GenericContainer<?> postgres;
    private static GenericContainer<?> minio;

    private TestContainers() {
    }

    public static boolean dockerAvailable() {
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
            minio = new GenericContainer<>("minio/minio:latest")
                    .withEnv("MINIO_ROOT_USER", S3_ACCESS_KEY)
                    .withEnv("MINIO_ROOT_PASSWORD", S3_SECRET_KEY)
                    .withCommand("server", "/data")
                    .withExposedPorts(9000)
                    .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000)
                            .withStartupTimeout(Duration.ofMinutes(2)));
            minio.start();
            try (S3Client s3 = s3Client()) {
                s3.createBucket(b -> b.bucket(BUCKET));
            }
        }
        return minio;
    }

    public static String s3Endpoint() {
        GenericContainer<?> m = minio();
        return "http://" + m.getHost() + ":" + m.getMappedPort(9000);
    }

    /** A plain S3 client for the test itself, to look inside the bucket. */
    public static S3Client s3Client() {
        GenericContainer<?> m = minio;
        return S3Client.builder()
                .endpointOverride(URI.create("http://" + m.getHost() + ":" + m.getMappedPort(9000)))
                .region(Region.of(S3_REGION))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(S3_ACCESS_KEY, S3_SECRET_KEY)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }
}
