package in.apnidukan.salon.domain;

import in.apnidukan.salon.domain.Availability.BarberDay;
import in.apnidukan.salon.domain.Availability.WalkInSlot;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The time arithmetic, without a database. Tuesday 10 Nov 2026, times in India. */
class AvailabilityTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);   // a Tuesday
    private static final UUID RAJU = new UUID(0, 1);
    private static final UUID SUNIL = new UUID(0, 2);
    private static final Instant ADDED = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void workingHoursComeFromTheWeekForThatWeekday() {
        List<Interval> hours = Availability.working(List.of(new Shift(2, 600, 840), new Shift(2, 960, 1260),
                new Shift(3, 600, 1260)), DAY);
        assertThat(hours).containsExactly(between("10:00", "14:00"), between("16:00", "21:00"));
    }

    @Test
    void offersQuarterHoursThatFitInsideAShiftAndAroundBusyTime() {
        BarberDay raju = day(RAJU, List.of(between("10:00", "12:00")), List.of(between("10:30", "11:00")), 30);
        List<Instant> starts = Availability.starts(List.of(raju), Duration.ofMinutes(30), at("10:00"), at("23:00"));
        assertThat(starts).containsExactly(at("10:00"), at("11:00"), at("11:15"), at("11:30"));
    }

    @Test
    void theNextSlotIsOfferedRightAtThePlannedEnd() {
        BarberDay raju = day(RAJU, List.of(between("10:00", "13:00")), List.of(between("10:00", "10:45")), 45);
        assertThat(Availability.starts(List.of(raju), Duration.ofMinutes(45), at("10:00"), at("11:00")))
                .containsExactly(at("10:45"));
    }

    @Test
    void aSplitShiftBreakIsNeverOfferedAndASlotCantStraddleIt() {
        BarberDay raju = day(RAJU, List.of(between("10:00", "14:00"), between("16:00", "21:00")), List.of(), 0);
        List<Instant> starts = Availability.starts(List.of(raju), Duration.ofMinutes(60), at("13:00"), at("16:30"));
        // 13:15 to 15:45 would cross the gap; 16:15 still starts before the window's end (16:30)
        assertThat(starts).containsExactly(at("13:00"), at("16:00"), at("16:15"));
    }

    @Test
    void theWindowStartIsRoundedUpToTheNextQuarterHour() {
        BarberDay raju = day(RAJU, List.of(between("10:00", "12:00")), List.of(), 0);
        List<Instant> starts = Availability.starts(List.of(raju), Duration.ofMinutes(30),
                at("10:07").plusSeconds(13), at("11:00"));
        assertThat(starts).first().isEqualTo(at("10:15"));
        assertThat(Availability.onGrid(at("10:15"))).isTrue();
        assertThat(Availability.onGrid(at("10:07"))).isFalse();
    }

    @Test
    void theLeastBookedBarberIsTriedFirstThenTheOldest() {
        BarberDay raju = day(RAJU, List.of(between("10:00", "20:00")), List.of(between("10:00", "11:00")), 60);
        BarberDay sunil = new BarberDay(SUNIL, List.of(between("10:00", "20:00")), List.of(), 0,
                ADDED.plusSeconds(60));
        assertThat(Availability.barbersFor(List.of(raju, sunil), between("12:00", "12:30")))
                .containsExactly(SUNIL, RAJU);
        BarberDay rajuFree = day(RAJU, List.of(between("10:00", "20:00")), List.of(), 0);
        assertThat(Availability.barbersFor(List.of(sunil, rajuFree), between("12:00", "12:30")))
                .containsExactly(RAJU, SUNIL);   // same load: the one added first
        assertThat(Availability.barbersFor(List.of(raju), between("10:30", "11:00"))).isEmpty();
    }

    @Test
    void walkInIsFreeNowWhenNothingOverlapsEvenAfterHours() {
        BarberDay raju = day(RAJU, List.of(between("10:00", "18:00")), List.of(), 0);
        Optional<WalkInSlot> slot = Availability.walkIn(raju, Duration.ofMinutes(30), at("19:10").plusSeconds(30),
                at("23:59"));
        assertThat(slot).contains(new WalkInSlot(RAJU, true, at("19:10")));
    }

    @Test
    void walkInGetsTheFirstGapThatFitsBeforeTheNextBookingInExactMinutes() {
        // Raju busy until 18:40 with a booking at 19:15: 30 minutes fit at 18:40, 45 minutes don't (until 19:30)
        BarberDay raju = day(RAJU, List.of(between("10:00", "21:00")),
                List.of(between("18:00", "18:40"), between("19:15", "19:45")), 70);
        assertThat(Availability.walkIn(raju, Duration.ofMinutes(30), at("18:20"), at("23:59")))
                .contains(new WalkInSlot(RAJU, false, at("18:40")));
        assertThat(Availability.walkIn(raju, Duration.ofMinutes(45), at("18:20"), at("23:59")))
                .contains(new WalkInSlot(RAJU, false, at("19:45")));
    }

    @Test
    void walkInLaterMustEndBeforeClosingOrThereIsNoSuggestion() {
        BarberDay raju = day(RAJU, List.of(between("10:00", "21:00")), List.of(between("20:00", "20:50")), 50);
        assertThat(Availability.walkIn(raju, Duration.ofMinutes(30), at("20:10"), at("23:59"))).isEmpty();
    }

    @Test
    void walkInMovesOnToTheNextShift() {
        BarberDay raju = day(RAJU, List.of(between("10:00", "14:00"), between("16:00", "21:00")),
                List.of(between("13:00", "14:00")), 60);
        assertThat(Availability.walkIn(raju, Duration.ofMinutes(30), at("13:10"), at("23:59")))
                .contains(new WalkInSlot(RAJU, false, at("16:00")));
    }

    @Test
    void nearestStartsAreTheClosestThreeInTimeOrder() {
        List<Instant> free = List.of(at("10:00"), at("11:00"), at("12:00"), at("13:30"), at("15:00"));
        assertThat(Availability.nearest(free, at("12:15"), 3)).containsExactly(at("11:00"), at("12:00"), at("13:30"));
    }

    private static BarberDay day(UUID id, List<Interval> working, List<Interval> busy, long booked) {
        return new BarberDay(id, working, busy, booked, ADDED);
    }

    private static Interval between(String from, String to) {
        return new Interval(at(from), at(to));
    }

    private static Instant at(String hhmm) {
        return DAY.atTime(LocalTime.parse(hhmm)).atZone(SalonTime.ZONE).toInstant();
    }
}
