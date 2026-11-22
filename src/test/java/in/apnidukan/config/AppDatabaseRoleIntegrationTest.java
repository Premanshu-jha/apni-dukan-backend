package in.apnidukan.config;

import in.apnidukan.support.CapturingSenders;
import in.apnidukan.support.TestContainers;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static in.apnidukan.support.CapturingSenders.LAST_SMS;
import static in.apnidukan.support.CapturingSenders.codeSentTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The production set-up of database users (README: "Database users"), on a database of its own: GitHub's Migrate job
 * (MigrateOnly) runs every migration as the owner, then the whole app starts as clozest_app with MIGRATE_ON_START=false
 * and works (Hibernate's check of the tables, sign-in, the account), while clozest_app itself can't create, change or
 * drop a table, nor change Flyway's history, and gets the same rights on a table a later migration makes.
 */
@EnabledIf("in.apnidukan.support.TestContainers#dockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CapturingSenders.class)
class AppDatabaseRoleIntegrationTest {

    private static final String APP_PASSWORD = "app-test-password";
    private static String url;
    private static MigrateResult migrated;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestContainers.register(registry);
        prepare();
        registry.add("DB_URL", () -> url);
        registry.add("DB_USER", () -> "clozest_app");
        registry.add("DB_PASSWORD", () -> APP_PASSWORD);
        registry.add("MIGRATE_ON_START", () -> "false");
    }

    /** A new database, migrated as GitHub's job does it (as the owner), and the app's user given a password. */
    private static synchronized void prepare() {
        if (url != null) {
            return;
        }
        String name = "roles_" + UUID.randomUUID().toString().replace("-", "");
        owner(TestContainers.jdbcUrl()).execute("CREATE DATABASE " + name);
        url = TestContainers.jdbcUrl().replaceAll("/[^/]+$", "/" + name);
        migrated = MigrateOnly.migrate(url, TestContainers.DB_USER, TestContainers.DB_PASSWORD);
        // The owner's one step on Neon (V87's comment): the role exists already; it gets a password
        owner(url).execute("ALTER ROLE clozest_app WITH LOGIN PASSWORD '" + APP_PASSWORD + "'");
    }

    private static JdbcTemplate owner(String jdbcUrl) {
        return new JdbcTemplate(new DriverManagerDataSource(jdbcUrl, TestContainers.DB_USER, TestContainers.DB_PASSWORD));
    }

    private static JdbcTemplate app() {
        return new JdbcTemplate(new DriverManagerDataSource(url, "clozest_app", APP_PASSWORD));
    }

    @Autowired Environment environment;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;

    @Test
    void migrateOnlyRanEveryMigrationAndRefusesNeonsPooledAddress() {
        assertThat(migrated.success).isTrue();
        assertThat(migrated.migrationsExecuted).isGreaterThan(80);
        assertThat(migrated.targetSchemaVersion).isNotNull();
        assertThatThrownBy(() -> MigrateOnly.migrate("jdbc:postgresql://ep-x-pooler.aws.neon.tech/db", "u", "p"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("pooled");
    }

    @Test
    void theAppStartsAsClozestAppAndSignInWorks() {
        RestClient http = RestClient.builder()
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + environment.getProperty("local.server.port") + "/api/v1").build();
        String phone = "9848011111";
        LAST_SMS.clear();
        // Redis is shared with the other test classes: start this person's and this network's counts afresh
        redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        http.post().uri("/auth/otp/send").contentType(MediaType.APPLICATION_JSON).body(Map.of("phone", phone))
                .retrieve().toBodilessEntity();
        Map<?, ?> tokens = http.post().uri("/auth/otp/verify").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("phone", phone, "code", codeSentTo(LAST_SMS, phone))).retrieve().body(Map.class);
        Map<?, ?> me = http.get().uri("/auth/me").headers(h -> h.setBearerAuth((String) tokens.get("accessToken")))
                .retrieve().body(Map.class);
        assertThat(me.get("phone")).isEqualTo(phone);
        // ...and its connections really are clozest_app's, with the 5 s statement limit as the user's default
        assertThat(app().queryForObject("SELECT current_user", String.class)).isEqualTo("clozest_app");
        assertThat(app().queryForObject("SHOW statement_timeout", String.class)).isEqualTo("5s");
    }

    @Test
    void clozestAppChangesRowsButNeverTables() {
        JdbcTemplate app = app();
        // Rights on every table and sequence there is (listed as the owner, who sees them all; checked one by one)
        JdbcTemplate owner = owner(url);
        List<String> tables = owner.queryForList("SELECT tablename FROM pg_tables WHERE schemaname = 'public' "
                + "AND tablename <> 'flyway_schema_history' AND tablename <> 'spatial_ref_sys'", String.class);
        assertThat(tables).hasSizeGreaterThan(50);
        for (String table : tables) {
            for (String right : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
                assertThat(owner.queryForObject("SELECT has_table_privilege('clozest_app', ?, ?)", Boolean.class,
                        "public.\"" + table + "\"", right)).as(table + " " + right).isTrue();
            }
        }
        List<String> sequences = owner.queryForList("SELECT sequencename FROM pg_sequences WHERE schemaname = 'public'",
                String.class);
        for (String sequence : sequences) {
            assertThat(owner.queryForObject("SELECT has_sequence_privilege('clozest_app', ?, 'USAGE')", Boolean.class,
                    "public.\"" + sequence + "\"")).as(sequence).isTrue();
        }

        // No table changes of any kind
        for (String ddl : List.of("CREATE TABLE sneaky (id int)", "DROP TABLE users", "ALTER TABLE users ADD COLUMN x int",
                "TRUNCATE users", "CREATE INDEX ix_sneaky ON users (phone)", "ALTER TABLE users DISABLE TRIGGER ALL")) {
            // Spring wraps Postgres' answer; its own words are the root cause's
            assertThatThrownBy(() -> app.execute(ddl)).as(ddl).rootCause()
                    .satisfiesAnyOf(e -> assertThat(e).hasMessageContaining("permission denied"),
                            e -> assertThat(e).hasMessageContaining("must be owner"));
        }
        // Flyway's history: read, never change
        assertThat(app.queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class)).isPositive();
        assertThatThrownBy(() -> app.update("DELETE FROM flyway_schema_history")).rootCause()
                .hasMessageContaining("permission denied");

        // A table a later migration makes (as the owner) gets the same rights by itself
        owner(url).execute("CREATE TABLE later_table (id int PRIMARY KEY)");
        app.update("INSERT INTO later_table VALUES (1)");
        assertThat(app.queryForObject("SELECT count(*) FROM later_table", Integer.class)).isEqualTo(1);
        owner(url).execute("DROP TABLE later_table");
    }
}
