package in.apnidukan.salon.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SalonStaffTest {

    @Test
    void touchAlwaysChangesTheRowEvenIfTheClockHasntMoved() {
        Instant now = Instant.parse("2026-11-03T05:30:00Z");
        SalonStaff raju = new SalonStaff(UUID.randomUUID(), "Raju", now);

        raju.touch(now);   // same instant: a fixed clock, or two saves in the same microsecond
        assertThat(raju.getUpdatedAt()).isEqualTo(now.plusNanos(1_000));

        Instant later = now.plusSeconds(60);
        raju.touch(later);
        assertThat(raju.getUpdatedAt()).isEqualTo(later);
    }
}
