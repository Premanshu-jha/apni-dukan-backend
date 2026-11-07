package in.apnidukan.auth.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Past the day's numbers over all networks, unknown networks wait 1, 2, 4 … minutes, never more than an hour. */
class OtpWaitTest {

    @Test
    void theWaitDoublesUpToAnHour() {
        assertThat(OtpService.waitFor(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(OtpService.waitFor(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(OtpService.waitFor(3)).isEqualTo(Duration.ofMinutes(4));
        assertThat(OtpService.waitFor(6)).isEqualTo(Duration.ofMinutes(32));
        assertThat(OtpService.waitFor(7)).isEqualTo(Duration.ofMinutes(60));
        assertThat(OtpService.waitFor(1_000)).isEqualTo(Duration.ofMinutes(60));
    }
}
