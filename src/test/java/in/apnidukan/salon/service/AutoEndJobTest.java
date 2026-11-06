package in.apnidukan.salon.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutoEndJobTest {

    @Test
    void aBatchSizeBelowOneIsRefusedAtStartup() {
        // 0 would make every batch look full and loop forever
        for (int size : new int[] {0, -1}) {
            assertThatThrownBy(() -> new AutoEndJob(null, null, null, Clock.systemUTC(), true, size))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("auto-end-batch");
        }
    }
}
