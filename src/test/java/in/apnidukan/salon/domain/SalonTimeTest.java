package in.apnidukan.salon.domain;

import in.apnidukan.common.error.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SalonTimeTest {

    @Test
    void timesBecomeMinutesAfterMidnightAndBack() {
        assertThat(SalonTime.minutes("start", "00:00")).isZero();
        assertThat(SalonTime.minutes("start", "10:15")).isEqualTo(615);
        assertThat(SalonTime.minutes("end", "24:00")).isEqualTo(1440);
        assertThat(SalonTime.format(615)).isEqualTo("10:15");
        assertThat(SalonTime.format(0)).isEqualTo("00:00");
        assertThat(SalonTime.format(1440)).isEqualTo("24:00");
    }

    @ParameterizedTest
    @ValueSource(strings = {"9:00", "09:00:00", "10:10", "24:15", "25:00", "10:60", "ab:cd", "", " 10:00"})
    void anythingElseIsRefusedWithTheFieldNamed(String time) {
        assertThatThrownBy(() -> SalonTime.minutes("hours[0].start", time))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getDetails())
                        .containsKey("hours[0].start"));
    }

    @Test
    void nullIsRefusedToo() {
        assertThatThrownBy(() -> SalonTime.minutes("start", null)).isInstanceOf(ApiException.class);
    }

    @Test
    void salonTimeIsIndiaWithNoDaylightSaving() {
        // Same offset in January and July: a weekly "10:00" is always 04:30 UTC
        assertThat(SalonTime.ZONE.getRules().getOffset(LocalDateTime.of(2026, 1, 15, 10, 0)))
                .isEqualTo(ZoneOffset.ofHoursMinutes(5, 30));
        assertThat(SalonTime.ZONE.getRules().getOffset(LocalDateTime.of(2026, 7, 15, 10, 0)))
                .isEqualTo(ZoneOffset.ofHoursMinutes(5, 30));
    }
}
