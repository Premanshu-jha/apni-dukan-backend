package in.apnidukan.auth.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The refresh grace may not outlast the 60 s retry time (or be negative), and the admin email step lasts 1 to 24 hours:
 * otherwise the app refuses to start.
 */
class RefreshGraceSettingTest {

    private static AuthService withGrace(long seconds) {
        return with(seconds, 12);
    }

    private static AuthService with(long graceSeconds, long adminCodeHours) {
        return new AuthService(null, null, null, null, null, null,
                new in.apnidukan.common.security.JwtProperties("test-only-secret-0123456789-abcdefghijkl", 15, 30),
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, graceSeconds, adminCodeHours);
    }

    @Test
    void theAdminEmailStepLasts1To24Hours() {
        assertThatThrownBy(() -> with(30, 0)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("admin-code-hours");
        assertThatThrownBy(() -> with(30, 25)).isInstanceOf(IllegalStateException.class);
        for (long ok : new long[] {1, 12, 24}) {
            assertThatCode(() -> with(30, ok)).doesNotThrowAnyException();
        }
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
