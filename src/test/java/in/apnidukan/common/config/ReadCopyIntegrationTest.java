package in.apnidukan.common.config;

import in.apnidukan.support.TestContainers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** The read copy's pool against a real Postgres (the tests' own, standing in for a Neon read replica). */
@EnabledIf("in.apnidukan.support.TestContainers#dockerAvailable")
class ReadCopyIntegrationTest {

    @Test
    void itReadsWithTheRequestLimitAndNeverWrites() {
        TestContainers.postgres();
        ReadCopy copy = new ReadCopy(mock(JdbcTemplate.class), TestContainers.jdbcUrl(), TestContainers.DB_USER,
                TestContainers.DB_PASSWORD, 2, "5s");
        try {
            assertThat(copy.jdbc().queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
            assertThat(copy.jdbc().queryForObject("SHOW statement_timeout", String.class)).isEqualTo("5s");
            assertThatThrownBy(() -> copy.jdbc().execute("CREATE TABLE should_fail (id int)"))
                    .rootCause().hasMessageContaining("read-only");
        } finally {
            copy.destroy();
        }
    }
}
