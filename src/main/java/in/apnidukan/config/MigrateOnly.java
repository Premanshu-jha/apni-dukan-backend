package in.apnidukan.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Runs the database migrations and nothing else: no web server, no Redis, no R2. GitHub's "Migrate" job runs it on
 * every push to main (.github/workflows/migrate.yml), with the database OWNER's login from GitHub's secrets, before
 * Railway deploys the new version ("Wait for CI"). The server itself then runs with MIGRATE_ON_START=false as the
 * backend's own user (clozest_app, V87), which can change rows but not tables, so the owner's password is never in
 * Railway's variables (see "Database users" in the README).
 *
 * Flyway is set up exactly as the app sets it up when it migrates itself (application.properties: the spring.flyway
 * lines): mixed files, no transactional lock, no statement limit on its own connection, waiting up to 15 minutes for
 * another copy's migration lock.
 *
 * Reads DB_MIGRATION_URL (Neon's direct connection string, jdbc:postgresql://..., never the -pooler one),
 * DB_MIGRATION_USER and DB_MIGRATION_PASSWORD.
 */
public final class MigrateOnly {

    private static final Logger log = LoggerFactory.getLogger(MigrateOnly.class);

    private MigrateOnly() {
    }

    public static void main(String[] args) {
        MigrateResult result = migrate(required("DB_MIGRATION_URL"), required("DB_MIGRATION_USER"),
                required("DB_MIGRATION_PASSWORD"));
        // For the job's log: which version the database is at now, and how many migrations ran
        log.info("Database at version {} (was {}); {} migration(s) ran.", result.targetSchemaVersion,
                result.initialSchemaVersion, result.migrationsExecuted);
    }

    /** The migrations, as the app runs them. Refuses Neon's pooled address (see ProductionSafetyCheck). */
    public static MigrateResult migrate(String url, String user, String password) {
        if (url.contains("-pooler")) {
            throw new IllegalStateException("DB_MIGRATION_URL is Neon's pooled address (-pooler in the host): "
                    + "migrations must use the direct connection string.");
        }
        return Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .mixed(true)
                .initSql("SET statement_timeout = 0")
                .lockRetryCount(900)
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                .load()
                .migrate();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set");
        }
        return value;
    }
}
