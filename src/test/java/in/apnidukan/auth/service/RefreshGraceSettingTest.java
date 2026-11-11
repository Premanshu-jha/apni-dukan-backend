package in.apnidukan.auth.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The refresh grace may not outlast the 60 s retry time (or be negative): the app refuses to start. */
class RefreshGraceSettingTest {

    private static AuthService withGrace(long seconds) {
        return new AuthService(null, null, null, null, null, null,
                new in.apnidukan.common.security.JwtProperties("test-only-secret-0123456789-abcdefghijkl", 15, 30),
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, seconds);
    }

    @Test
    void aGraceAbove60SecondsOrBelow0IsRefused() {
        assertThatThrownBy(() -> withGrace(61)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0 to 60");
        assertThatThrownBy(() -> withGrace(-1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void zeroTo60Seconds() {
        for (long ok : new long[] {0, 30, 60}) {
            assertThatCode(() -> withGrace(ok)).doesNotThrowAnyException();
        }
    }
}
