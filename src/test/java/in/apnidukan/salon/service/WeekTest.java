package in.apnidukan.salon.service;

import in.apnidukan.common.error.ApiException;
import in.apnidukan.salon.api.SalonRequests.ShiftIn;
import in.apnidukan.salon.domain.Shift;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The checks on a barber's week (SalonSetupService.parseWeek), without a database. */
class WeekTest {

    @Test
    void aWeekIsSortedByDayThenStart() {
        List<Shift> week = SalonSetupService.parseWeek(List.of(
                new ShiftIn(2, "16:00", "21:00"), new ShiftIn(1, "10:00", "14:00"), new ShiftIn(2, "10:00", "14:00")));
        assertThat(week).containsExactly(new Shift(1, 600, 840), new Shift(2, 600, 840), new Shift(2, 960, 1260));
    }

    @Test
    void blocksThatTouchAreFineButOverlapsAreNamed() {
        assertThat(SalonSetupService.parseWeek(List.of(new ShiftIn(1, "10:00", "14:00"),
                new ShiftIn(1, "14:00", "18:00")))).hasSize(2);
        assertThatThrownBy(() -> SalonSetupService.parseWeek(List.of(new ShiftIn(1, "10:00", "14:00"),
                new ShiftIn(1, "13:45", "18:00"))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getDetails())
                        .containsEntry("hours", "Monday 10:00-14:00 and 13:45-18:00 overlap"));
    }

    @Test
    void theSameTimesOnDifferentDaysDontOverlap() {
        assertThat(SalonSetupService.parseWeek(List.of(new ShiftIn(1, "10:00", "14:00"),
                new ShiftIn(7, "10:00", "14:00")))).hasSize(2);
    }

    @Test
    void aBlockThatEndsBeforeItStartsIsRefused() {
        assertThatThrownBy(() -> SalonSetupService.parseWeek(List.of(new ShiftIn(3, "18:00", "18:00"))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getDetails())
                        .containsKey("hours[0].end"));
    }

    @Test
    void aBlockCantStartAtMidnightAtTheEndOfTheDay() {
        assertThatThrownBy(() -> SalonSetupService.parseWeek(List.of(new ShiftIn(5, "24:00", "24:00"))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getDetails())
                        .containsEntry("hours[0].start", "must be before 24:00"));
    }

    @Test
    void shiftOverlapIsSymmetric() {
        Shift a = new Shift(1, 600, 840);
        Shift b = new Shift(1, 780, 1080);
        assertThat(a.overlaps(b)).isTrue();
        assertThat(b.overlaps(a)).isTrue();
        assertThat(a.overlaps(new Shift(1, 840, 900))).isFalse();
        assertThat(a.overlaps(new Shift(2, 600, 840))).isFalse();
    }
}
