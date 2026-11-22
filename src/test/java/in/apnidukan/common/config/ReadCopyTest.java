package in.apnidukan.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ReadCopyTest {

    private final JdbcTemplate main = mock(JdbcTemplate.class);

    @Test
    void withoutReadDbUrlTheListIsReadFromTheMainDatabase() {
        ReadCopy off = new ReadCopy(main, "", "u", "p", 10, "5s");
        assertThat(off.enabled()).isFalse();
        JdbcTemplate used = off.read(jdbc -> jdbc);
        assertThat(used).isSameAs(main);
        off.destroy();   // nothing to close
    }

    @Test
    void aCopyThatIsDownDoesntStopTheBackendAndTheMainDatabaseAnswersInstead() {
        // Nothing listens there: made anyway, and each read falls back to the main database
        ReadCopy on = new ReadCopy(main, "jdbc:postgresql://127.0.0.1:1/neondb", "u", "p", 4, "5s");
        assertThat(on.enabled()).isTrue();
        String answered = on.read(jdbc -> jdbc == main ? "main" : jdbc.queryForObject("SELECT 'copy'", String.class));
        assertThat(answered).isEqualTo("main");
        on.destroy();
    }

    @Test
    void aPoolSizeOutOfRangeIsRefused() {
        assertThatThrownBy(() -> new ReadCopy(main, "jdbc:postgresql://127.0.0.1:1/neondb", "u", "p", 0, "5s"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("READ_DB_POOL_SIZE");
    }

    @Test
    void aStatementThatRanOutOfTimeIsNotRunAgainOnTheMainDatabase() {
        java.sql.SQLException cancelled = new java.sql.SQLException("canceling statement due to statement timeout",
                "57014");
        assertThat(ReadCopy.ranOutOfTime(new org.springframework.dao.DataAccessResourceFailureException("x",
                cancelled))).isTrue();
        assertThat(ReadCopy.ranOutOfTime(new org.springframework.dao.DataAccessResourceFailureException("x",
                new java.sql.SQLException("connection refused", "08001")))).isFalse();
    }
}
