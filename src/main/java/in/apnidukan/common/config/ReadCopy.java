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
 * If the copy can't answer (down, no connection within 2 s, or a query cancelled because the copy was replaying a
 * change it conflicted with), the read is done again on the main database: the list keeps working, a WARN at most
 * once a minute says so.
 *
 * Not a DataSource bean on purpose: one would make Spring Boot stop making the main one.
 */
@Component
public class ReadCopy implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ReadCopy.class);

    private final HikariDataSource copy;
    private final JdbcTemplate jdbc;
    private final JdbcTemplate main;
    private final java.util.concurrent.atomic.AtomicLong lastWarned = new java.util.concurrent.atomic.AtomicLong();

    public ReadCopy(JdbcTemplate main, @Value("${app.db.read-url:}") String url,
                    @Value("${spring.datasource.username:}") String user,
                    @Value("${spring.datasource.password:}") String password,
                    @Value("${app.db.read-pool-size:10}") int poolSize,
                    @Value("${DB_STATEMENT_TIMEOUT:5s}") String statementTimeout) {
        this.main = main;
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
        config.setConnectionTimeout(2_000);   // then the main database instead (read)
        config.setConnectionInitSql("SET statement_timeout = '" + statementTimeout.replace("'", "") + "'");
        // Started lazily: a copy that is down at start doesn't stop the backend (the main database answers meanwhile)
        config.setInitializationFailTimeout(-1);
        this.copy = new HikariDataSource(config);
        this.jdbc = new JdbcTemplate(copy);
        log.info("The salon list is read from the read copy (READ_DB_URL)");
    }

    /**
     * Runs the read on the copy when READ_DB_URL is set, else on the main database; on the main database too when
     * the copy can't answer (see the class comment). The read must only read.
     */
    public <T> T read(java.util.function.Function<JdbcTemplate, T> read) {
        if (copy == null) {
            return read.apply(main);
        }
        try {
            return read.apply(jdbc);
        } catch (org.springframework.dao.DataAccessResourceFailureException
                 | org.springframework.dao.ConcurrencyFailureException e) {
            // Not a statement that ran out of its time (57014, which Spring files as a resource failure too):
            // running it again on the main database would only double the load when things are slow
            if (ranOutOfTime(e)) {
                throw e;
            }
            long now = System.currentTimeMillis();
            long last = lastWarned.get();
            if (now - last > 60_000 && lastWarned.compareAndSet(last, now)) {
                log.warn("The read copy didn't answer ({}): reading the salon list from the main database",
                        e.getClass().getSimpleName());
            }
            return read.apply(main);
        }
    }

    /** Whether Postgres stopped the statement at its time limit (SQLSTATE 57014) somewhere in the causes. */
    static boolean ranOutOfTime(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException sql && "57014".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
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
