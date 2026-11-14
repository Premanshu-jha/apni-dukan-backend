package in.apnidukan.shops.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LicenceAiWatchTest {

    private static LicenceAiWatch watch(int day) {
        return new LicenceAiWatch(null, null, null, null, Clock.systemUTC(), new BigDecimal("100"), day, new BigDecimal("2"),
                new BigDecimal("10"));
    }

    @Test
    void thePeriodStartsOnTheDayTheCreditsArrive() {
        LicenceAiWatch fourth = watch(4);
        assertThat(fourth.periodStart(LocalDate.of(2026, 11, 14))).isEqualTo(LocalDate.of(2026, 11, 4));
        assertThat(fourth.periodStart(LocalDate.of(2026, 11, 4))).isEqualTo(LocalDate.of(2026, 11, 4));
        assertThat(fourth.periodStart(LocalDate.of(2026, 11, 3))).isEqualTo(LocalDate.of(2026, 10, 4));
        assertThat(fourth.periodStart(LocalDate.of(2027, 1, 2))).isEqualTo(LocalDate.of(2026, 12, 4));
        assertThat(watch(1).periodStart(LocalDate.of(2026, 11, 30))).isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    void aPricePerMillionTokensIsMicroDollarsPerToken() {
        // A typical licence: 1,800 tokens in at $2 and 240 out at $10 per million = $0.0036 + $0.0024
        assertThat(watch(1).costMicros(1_800, 240)).isEqualTo(6_000);
        assertThat(watch(1).costMicros(0, 0)).isZero();
    }

    @Test
    void aDayPastTheShortestMonthOrNoBudgetIsRefusedAtStart() {
        assertThatThrownBy(() -> watch(29)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> watch(0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new LicenceAiWatch(null, null, null, null, Clock.systemUTC(), BigDecimal.ZERO, 1, BigDecimal.ONE,
                BigDecimal.ONE)).isInstanceOf(IllegalStateException.class);
    }
}
