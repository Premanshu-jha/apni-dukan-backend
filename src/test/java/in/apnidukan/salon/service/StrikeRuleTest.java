package in.apnidukan.salon.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 3 strikes within 60 days block online booking until 30 days after the latest of them. */
class StrikeRuleTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    @Test
    void twoStrikesNeverBlock() {
        assertThat(BookingService.blockedUntil(List.of(ago(1), ago(2)), NOW)).isEmpty();
    }

    @Test
    void threeWithinSixtyDaysBlockUntilThirtyDaysAfterTheLatest() {
        assertThat(BookingService.blockedUntil(List.of(ago(5), ago(20), ago(64)), NOW))
                .contains(ago(5).plus(Duration.ofDays(30)));
    }

    @Test
    void threeSpreadOverMoreThanSixtyDaysDontBlock() {
        assertThat(BookingService.blockedUntil(List.of(ago(5), ago(20), ago(66)), NOW)).isEmpty();
    }

    @Test
    void theBlockEndsThirtyDaysAfterTheLatestStrike() {
        assertThat(BookingService.blockedUntil(List.of(ago(31), ago(40), ago(50)), NOW)).isEmpty();
        assertThat(BookingService.blockedUntil(List.of(ago(29), ago(40), ago(50)), NOW)).isPresent();
    }

    @Test
    void anOlderTripleCountsWhenTheNewestThreeAreSpreadOut() {
        // The newest three span 61 days, but the 2nd to 4th are within 55 days, and the 2nd was 25 days ago
        assertThat(BookingService.blockedUntil(List.of(ago(1), ago(25), ago(62), ago(80)), NOW))
                .contains(ago(25).plus(Duration.ofDays(30)));
    }

    private static Instant ago(int days) {
        return NOW.minus(Duration.ofDays(days));
    }
}
