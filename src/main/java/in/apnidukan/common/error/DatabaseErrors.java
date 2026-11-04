package in.apnidukan.common.error;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

/** Helpers for telling apart the database's refusals, e.g. which unique key was hit. */
public final class DatabaseErrors {

    private DatabaseErrors() {
    }

    /**
     * Whether the database refused the write because of this named constraint (a unique key, an exclusion rule,
     * a check). The constraint's name is set in the Flyway migration. Walks the causes, because Spring wraps
     * Hibernate's exception, which wraps the driver's.
     */
    public static boolean violated(DataIntegrityViolationException e, String constraint) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException c
                    && constraint.equalsIgnoreCase(c.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the database refused the write with this SQL state, e.g. 23P01 (an exclusion rule such as "no two
     * appointments of one barber overlap"). For writes made with plain SQL (JdbcTemplate), where no constraint
     * name is passed up.
     */
    public static boolean hasSqlState(DataAccessException e, String sqlState) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException sql && sqlState.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
