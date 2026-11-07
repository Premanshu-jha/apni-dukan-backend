package in.apnidukan.config;

import in.apnidukan.auth.service.SignInLimits;
import in.apnidukan.common.security.ClientIp;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The defaults the server runs with when no environment variable overrides them, read from application.properties
 * alone (no environment), so a changed default is a deliberate change.
 */
class ConfigurationDefaultsTest {

    private static PropertySourcesPropertyResolver defaults() throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new PropertiesPropertySource("application",
                PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"))));
        PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(sources);
        resolver.setIgnoreUnresolvableNestedPlaceholders(true);
        return resolver;
    }

    private static int number(PropertySourcesPropertyResolver r, String key) {
        return Integer.parseInt(r.getProperty(key));
    }

    @Test
    void signInAndSmsLimitsAreSizedForRealTraffic() throws IOException {
        PropertySourcesPropertyResolver r = defaults();
        SignInLimits limits = new SignInLimits(number(r, "app.auth.ip-sends-per-hour"),
                number(r, "app.auth.ipv6-sends-per-hour"), number(r, "app.auth.ip-verifies-per-hour"),
                number(r, "app.auth.ipv6-verifies-per-hour"), number(r, "app.sms.daily-limit"));
        assertThat(limits.sendsPerHour(ClientIp.limitKey("203.0.113.9"))).isEqualTo(200);
        assertThat(limits.sendsPerHour(ClientIp.limitKey("2001:db8::1"))).isEqualTo(20);
        assertThat(limits.verifiesPerHour(ClientIp.limitKey("203.0.113.9"))).isEqualTo(600);
        assertThat(limits.smsDailyLimit()).isEqualTo(50_000);
        assertThat(number(r, "app.sms.booking-daily-limit")).isEqualTo(100_000);
        assertThat(number(r, "app.sms.staff-per-person-daily-limit")).isEqualTo(1000);
    }

    @Test
    void theSenderRunsManySendsAtOnceWithoutTyingThemToTheDatabasePool() throws IOException {
        PropertySourcesPropertyResolver r = defaults();
        assertThat(number(r, "spring.datasource.hikari.maximum-pool-size")).isEqualTo(20);
        // Sends hold no connection (leases a group at a time, outcomes in batches): far above the pool, on purpose
        assertThat(number(r, "app.notifications.send-concurrency")).isEqualTo(64);
        assertThat(number(r, "app.notifications.per-host-concurrency")).isEqualTo(32);
        assertThat(number(r, "app.notifications.record-every")).isEqualTo(50);
        assertThat(number(r, "app.sms.hourly-limit")).isEqualTo(10_000);
    }

    @Test
    void storageAndDatabaseCallsAreBoundedAndMigrationsAreNot() throws IOException {
        PropertySourcesPropertyResolver r = defaults();
        assertThat(r.getProperty("app.r2.attempt-timeout")).isEqualTo("5s");
        assertThat(r.getProperty("app.r2.call-timeout")).isEqualTo("15s");
        assertThat(r.getProperty("app.r2.upload-timeout")).isEqualTo("60s");
        assertThat(r.getProperty("spring.datasource.hikari.connection-init-sql"))
                .isEqualTo("SET statement_timeout = '5s'");
        assertThat(r.getProperty("app.db.job-statement-timeout")).isEqualTo("60s");
        // Flyway's own connection has no limit, whatever the database role's default is
        assertThat(r.getProperty("spring.flyway.init-sqls")).isEqualTo("SET statement_timeout = 0");
        // A second copy waits for the first one's migrations (15 minutes) instead of giving up and crash-looping
        assertThat(number(r, "spring.flyway.lock-retry-count")).isEqualTo(900);
    }

    @Test
    void cachesAreBoundedBySizeAndOwnersGetBookingsByPushOnly() throws IOException {
        PropertySourcesPropertyResolver r = defaults();
        assertThat(r.getProperty("app.salon.setup-cache-weight")).isEqualTo("2000000");
        assertThat(r.getProperty("app.salon.busy-cache-rows")).isEqualTo("500000");
        assertThat(r.getProperty("app.notifications.owner-booking-sms")).isEqualTo("false");
        assertThat(r.getProperty("app.jobs.salon-closing-batch")).isEqualTo("500");
    }
}
