package in.apnidukan.common.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * An optional read-only copy of the database (README: "Read copy"), for the busiest read that may be a moment
 * behind: the customer's salon list and its search (ShopDirectory.nearby). Off unless READ_DB_URL is set (a Neon
 * read replica's pooled address); then that list is read there, so a flood of people browsing doesn't slow
 * bookings on the main database. Without it, {@link #jdbc()} is the main database's own JdbcTemplate.
 *
 * Nothing that books, changes or must see its own change reads here: a copy is usually under a second behind, so
 * a salon that just paused or changed its name may show the old way for that long in the list, never in a booking.
 *
 * Not a DataSource bean on purpose: one would make Spring Boot stop making the main one.
 */
@Component
public class ReadCopy implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ReadCopy.class);

    private final HikariDataSource copy;
    private final JdbcTemplate jdbc;

    public ReadCopy(JdbcTemplate main, @Value("${app.db.read-url:}") String url,
                    @Value("${spring.datasource.username:}") String user,
                    @Value("${spring.datasource.password:}") String password,
                    @Value("${app.db.read-pool-size:10}") int poolSize,
                    @Value("${DB_STATEMENT_TIMEOUT:5s}") String statementTimeout) {
        if (url.isBlank()) {
            this.copy = null;
            this.jdbc = main;
            return;
        }
        if (poolSize < 1 || poolSize > 100) {
            throw new IllegalStateException("app.db.read-pool-size (READ_DB_POOL_SIZE) must be 1 to 100");
        }
        HikariConfig config = new HikariConfig();
        config.setPoolName("read-copy");
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(Math.min(2, poolSize));
        // A write by mistake fails here instead of going anywhere: read-only, also outside a transaction (the
        // driver's default only marks transactions, and JdbcTemplate runs each statement on its own)
        config.setReadOnly(true);
        config.addDataSourceProperty("readOnlyMode", "always");
        config.setConnectionTimeout(10_000);
        config.setConnectionInitSql("SET statement_timeout = '" + statementTimeout.replace("'", "") + "'");
        // Started lazily: a copy that is down at start doesn't stop the backend; the list then fails until it's up
        config.setInitializationFailTimeout(-1);
        this.copy = new HikariDataSource(config);
        this.jdbc = new JdbcTemplate(copy);
        log.info("The salon list is read from the read copy (READ_DB_URL)");
    }

    /** Where the salon list is read: the copy when READ_DB_URL is set, else the main database. */
    public JdbcTemplate jdbc() {
        return jdbc;
    }

    /** Whether a separate copy is in use. */
    public boolean enabled() {
        return copy != null;
    }

    @Override
    public void destroy() {
        if (copy != null) {
            copy.close();
        }
    }
}
