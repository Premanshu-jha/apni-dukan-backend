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
        assertThat(off.jdbc()).isSameAs(main);
        off.destroy();   // nothing to close
    }

    @Test
    void withItTheListHasAPoolOfItsOwnThatDoesntConnectUntilUsed() {
        // Nothing listens there: made anyway (a copy that is down never stops the backend starting)
        ReadCopy on = new ReadCopy(main, "jdbc:postgresql://127.0.0.1:1/neondb", "u", "p", 4, "5s");
        assertThat(on.enabled()).isTrue();
        assertThat(on.jdbc()).isNotSameAs(main);
        on.destroy();
    }

    @Test
    void aPoolSizeOutOfRangeIsRefused() {
        assertThatThrownBy(() -> new ReadCopy(main, "jdbc:postgresql://127.0.0.1:1/neondb", "u", "p", 0, "5s"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("READ_DB_POOL_SIZE");
    }
}
