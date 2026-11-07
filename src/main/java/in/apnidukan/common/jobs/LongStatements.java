package in.apnidukan.common.jobs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * For the background jobs: runs one slice of their work in its own transaction that allows longer statements
 * (app.db.job-statement-timeout, 60 s) than a request does (DB_STATEMENT_TIMEOUT, 5 s, set on every connection).
 * A cleanup slice of 10,000 rows on a busy night may need more than 5 s and must not be cut; a request never should.
 *
 * SET LOCAL (set_config(..., true)) ends with the transaction, so the connection goes back to the pool with the
 * request limit again; and it works through Neon's pooler, where a session setting wouldn't stay.
 */
@Component
public class LongStatements {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final String timeout;

    public LongStatements(JdbcTemplate jdbc, PlatformTransactionManager transactions,
                          @Value("${app.db.job-statement-timeout:60s}") String timeout) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactions);
        this.timeout = timeout;
    }

    /**
     * Runs the work in a transaction (its own: the jobs call this outside any) whose statements may each take up to
     * the job limit. The work returns a value, never null (a count, an Optional).
     */
    public <T> T run(Supplier<T> work) {
        T result = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('statement_timeout', ?, true)", String.class, timeout);
            return work.get();
        });
        return Objects.requireNonNull(result, "a job slice returned nothing");
    }
}
