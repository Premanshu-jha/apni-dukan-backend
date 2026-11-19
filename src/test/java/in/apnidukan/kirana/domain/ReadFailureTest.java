package in.apnidukan.kirana.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Every reason a read fails has a code the app words in the shop's language. */
class ReadFailureTest {

    @Test
    void everyReasonAndItsListWordingGiveTheirCode() {
        for (ReadFailure f : ReadFailure.values()) {
            if (f == ReadFailure.OTHER) {
                continue;
            }
            assertThat(ReadFailure.code(f.text())).isEqualTo(f.name());
            assertThat(ReadFailure.code(ReadFailure.forList(f.text()))).isEqualTo(f.name());
        }
        assertThat(ReadFailure.code("This list is too long to read at once. Send it in parts.")).isEqualTo("TOO_LONG");
        assertThat(ReadFailure.code("This PDF has 30 pages: send at most 10 at a time.")).isEqualTo("TOO_LONG");
        assertThat(ReadFailure.code("Something from an older version")).isEqualTo("OTHER");
        assertThat(ReadFailure.code(null)).isNull();
    }
}
