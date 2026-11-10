package in.apnidukan.salon.service;

import in.apnidukan.common.error.ApiException;
import in.apnidukan.salon.api.BookingResponses.CardFacts;
import in.apnidukan.salon.api.BookingResponses.RatingSummary;
import in.apnidukan.salon.api.SalonRequests.SalonDayIn;
import in.apnidukan.salon.domain.RatingTag;
import in.apnidukan.salon.domain.Shift;
import in.apnidukan.salon.repo.RatingStore;
import in.apnidukan.salon.repo.SalonCardStore;
import in.apnidukan.salon.repo.VisitFollowUpStore;
import in.apnidukan.salon.repo.VisitFollowUpStore.Reminder;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The rules behind salon hours, cards, ratings and the messages after a visit, without a database. */
class VisitRulesTest {

    private static final LocalDate TUESDAY = LocalDate.of(2026, 11, 10);

    // ---------- the salon's hours ----------

    @Test
    void aBarbersWeekIsCutToTheSalonsHours() {
        Map<Integer, Shift> salon = Map.of(1, new Shift(1, 600, 1200), 2, new Shift(2, 600, 1260));
        List<Shift> barber = List.of(new Shift(2, 540, 1320), new Shift(1, 540, 720), new Shift(1, 900, 1260),
                new Shift(7, 600, 1200), new Shift(1, 1200, 1260));
        assertThat(SalonSetupService.trimmed(barber, salon)).containsExactly(new Shift(1, 600, 720),
                new Shift(1, 900, 1200), new Shift(2, 600, 1260));
    }

    @Test
    void barberHoursOutsideTheSalonsAreRefusedNamingTheDay() {
        Map<Integer, Shift> salon = Map.of(1, new Shift(1, 600, 1200));
        SalonSetupService.requireInsideSalonHours(salon, List.of(new Shift(1, 600, 1200)));
        SalonSetupService.requireInsideSalonHours(Map.of(), List.of(new Shift(3, 0, 1440)));   // none set: any
        assertThatThrownBy(() -> SalonSetupService.requireInsideSalonHours(salon, List.of(new Shift(2, 600, 700))))
                .isInstanceOf(ApiException.class).hasMessageContaining("not valid");
        assertThatThrownBy(() -> SalonSetupService.requireInsideSalonHours(salon, List.of(new Shift(1, 585, 700))))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void theSalonsWeekHasEachDayOnceWithCloseAfterOpen() {
        assertThat(SalonSetupService.parseSalonWeek(List.of(new SalonDayIn(3, "10:00", "21:00"),
                new SalonDayIn(1, "09:00", "24:00")))).containsExactly(new Shift(1, 540, 1440), new Shift(3, 600, 1260));
        assertThatThrownBy(() -> SalonSetupService.parseSalonWeek(List.of(new SalonDayIn(1, "10:00", "12:00"),
                new SalonDayIn(1, "13:00", "18:00")))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> SalonSetupService.parseSalonWeek(List.of(new SalonDayIn(1, "12:00", "12:00"))))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> SalonSetupService.parseSalonWeek(List.of(new SalonDayIn(1, "10:10", "12:00"))))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void theHoursVersionIsTheWeekItselfInOneOrder() {
        assertThat(SalonSetupService.hoursVersion(List.of())).isNull();
        assertThat(SalonSetupService.hoursVersion(List.of(new Shift(2, 600, 1260), new Shift(1, 540, 1200))))
                .isEqualTo("1:540-1200,2:600-1260");
    }

    // ---------- cards and ratings ----------

    @Test
    void aCardIsOpenOnlyInsideTodaysHoursAndNotOnAClosedDay() {
        SalonCardStore.Facts open = new SalonCardStore.Facts(3, 13, true, 600, 1260, false, 4,
                List.of(new SalonCardStore.Top("Haircut", 15000, false)), true, true);
        CardFacts at11 = BookingService.cardFacts(open, TUESDAY, 660);
        assertThat(at11.openNow()).isTrue();
        assertThat(at11.today().open()).isEqualTo("10:00");
        assertThat(at11.rating().average()).isNull();   // 3 ratings: "New"
        assertThat(at11.serviceCount()).isEqualTo(4);
        assertThat(at11.registered()).isTrue();
        assertThat(BookingService.cardFacts(open, TUESDAY, 1260).openNow()).isFalse();   // 21:00: closed
        assertThat(BookingService.cardFacts(open, TUESDAY, 599).openNow()).isFalse();

        SalonCardStore.Facts holiday = new SalonCardStore.Facts(0, 0, true, 600, 1260, true, 1, List.of(), false, true);
        CardFacts h = BookingService.cardFacts(holiday, TUESDAY, 660);
        assertThat(h.closedToday()).isTrue();
        assertThat(h.openNow()).isFalse();
        assertThat(h.today()).isNull();

        SalonCardStore.Facts weeklyOff = new SalonCardStore.Facts(0, 0, true, null, null, false, 1, List.of(), false, true);
        assertThat(BookingService.cardFacts(weeklyOff, TUESDAY, 660).closedToday()).isTrue();

        SalonCardStore.Facts noHours = new SalonCardStore.Facts(0, 0, false, null, null, false, 1, List.of(), false, false);
        assertThat(BookingService.cardFacts(noHours, TUESDAY, 660).openNow()).isNull();
        assertThat(BookingService.cardFacts(noHours, TUESDAY, 660).closedToday()).isFalse();

        // Open hours today but no working barber (none yet, all switched off, or none works Tuesdays): closed
        SalonCardStore.Facts nobody = new SalonCardStore.Facts(0, 0, true, 600, 1260, false, 0, List.of(), false,
                false);
        assertThat(BookingService.cardFacts(nobody, TUESDAY, 660).closedToday()).isTrue();
        assertThat(BookingService.cardFacts(nobody, TUESDAY, 660).openNow()).isFalse();
        assertThat(BookingService.cardFacts(nobody, TUESDAY, 660).today()).isNull();
    }

    @Test
    void customersSeeTheAverageFromFiveRatingsAndTheTagsMostMentionedFirst() {
        Map<RatingTag, Integer> tags = new EnumMap<>(RatingTag.class);
        tags.put(RatingTag.ON_TIME, 2);
        tags.put(RatingTag.CLEAN, 4);
        tags.put(RatingTag.FRIENDLY, 0);
        tags.put(RatingTag.SKILLED, 2);
        RatingSummary five = BookingService.ratingSummary(new RatingStore.Totals(5, 23, tags), true);
        assertThat(five.average()).isEqualTo(4.6);
        assertThat(five.tags()).extracting(t -> t.tag())
                .containsExactly(RatingTag.CLEAN, RatingTag.ON_TIME, RatingTag.SKILLED);
        assertThat(BookingService.ratingSummary(new RatingStore.Totals(4, 20, tags), false).average()).isNull();
        assertThat(BookingService.ratingSummary(new RatingStore.Totals(4, 20, tags), false).tags()).isEmpty();
        assertThat(new RatingStore.Totals(3, 13, Map.of()).average()).isEqualTo(4.3);
        assertThat(RatingStore.Totals.NONE.average()).isNull();
    }

    @Test
    void ratingTagsAreStoredAsBits() {
        int mask = RatingTag.mask(List.of(RatingTag.SKILLED, RatingTag.ON_TIME));
        assertThat(mask).isEqualTo(9);
        assertThat(RatingTag.of(mask)).containsExactly(RatingTag.ON_TIME, RatingTag.SKILLED);
        assertThat(RatingTag.of(31)).hasSize(5);
        assertThat(RatingTag.CLEAN.in(mask)).isZero();
    }

    // ---------- after the visit ----------

    @Test
    void aReminderGoesOnlyWhenNothingSpeaksAgainstIt() {
        UUID id = UUID.randomUUID();
        Instant t = Instant.parse("2026-11-10T06:00:00Z");
        List<UUID> services = List.of(UUID.randomUUID());
        assertThat(VisitFollowUpJob.send(new Reminder(id, id, id, t, "S", true, true, false, false, services,
                List.of("Haircut")))).isTrue();
        assertThat(VisitFollowUpJob.send(new Reminder(id, id, id, t, "S", false, true, false, false, services,
                List.of("Haircut")))).isFalse();   // salon not live
        assertThat(VisitFollowUpJob.send(new Reminder(id, id, id, t, "S", true, false, false, false, services,
                List.of("Haircut")))).isFalse();   // switched off
        assertThat(VisitFollowUpJob.send(new Reminder(id, id, id, t, "S", true, true, true, false, services,
                List.of("Haircut")))).isFalse();   // booked already
        assertThat(VisitFollowUpJob.send(new Reminder(id, id, id, t, "S", true, true, false, true, services,
                List.of("Haircut")))).isFalse();   // came back since
        assertThat(VisitFollowUpJob.send(new Reminder(id, id, id, t, "S", true, true, false, false, List.of(),
                List.of()))).isFalse();   // its services are gone
    }

    @Test
    void theReminderIsAboutTheServicesDueOnItsDayOrAllIfNoneIs() {
        // Haircut 21, beard trim 7, facial 30; scheduled for day 7: the beard trim
        assertThat(VisitFollowUpStore.dueAmong(List.of(21, 7, 30), 7)).containsExactly(1);
        assertThat(VisitFollowUpStore.dueAmong(List.of(21, 7, 30), 21)).containsExactly(0, 1);
        // The owner made the beard trim 10 days after the reminder was scheduled for day 7: still sent, about all
        assertThat(VisitFollowUpStore.dueAmong(List.of(21, 10), 7)).containsExactly(0, 1);
        assertThat(VisitFollowUpStore.dueAmong(List.of(), 7)).isEmpty();
    }

    @Test
    void remindersGoOutBetweenNineAndNineIndiaTime() {
        assertThat(VisitFollowUpJob.remindingHours(Instant.parse("2026-11-10T03:29:00Z"))).isFalse();   // 08:59
        assertThat(VisitFollowUpJob.remindingHours(Instant.parse("2026-11-10T03:30:00Z"))).isTrue();    // 09:00
        assertThat(VisitFollowUpJob.remindingHours(Instant.parse("2026-11-10T15:29:00Z"))).isTrue();    // 20:59
        assertThat(VisitFollowUpJob.remindingHours(Instant.parse("2026-11-10T15:30:00Z"))).isFalse();   // 21:00
    }

    @Test
    void theMessagesNameTheServicesAndHowLongAgo() {
        assertThat(VisitFollowUpJob.services(List.of("Haircut"))).isEqualTo("Haircut");
        assertThat(VisitFollowUpJob.services(List.of("Haircut", "Beard trim"))).isEqualTo("Haircut and Beard trim");
        assertThat(VisitFollowUpJob.services(List.of("A", "B", "C"))).isEqualTo("A, B and C");
        assertThat(VisitFollowUpJob.services(List.of())).isEqualTo("visit");
        assertThat(VisitFollowUpJob.ago(1)).isEqualTo("1 day");
        assertThat(VisitFollowUpJob.ago(7)).isEqualTo("a week");
        assertThat(VisitFollowUpJob.ago(10)).isEqualTo("10 days");
        assertThat(VisitFollowUpJob.ago(14)).isEqualTo("2 weeks");
        assertThat(VisitFollowUpJob.ago(30)).isEqualTo("a month");
        assertThat(VisitFollowUpJob.ago(42)).isEqualTo("6 weeks");
        assertThat(VisitFollowUpJob.ago(90)).isEqualTo("3 months");
    }

    @Test
    void aBatchSizeBelowOneIsRefusedAtStartup() {
        for (int size : new int[] {0, -1}) {
            assertThatThrownBy(() -> new VisitFollowUpJob(null, null, null, null, Clock.systemUTC(), true, size))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("follow-up-batch");
        }
    }

    @Test
    void theCatalogSuggestsSensibleTimesAndIntervals() {
        assertThat(ServiceCatalog.SERVICES).isNotEmpty();
        assertThat(ServiceCatalog.SERVICES).allSatisfy(s -> {
            assertThat(s.durationMinutes() % 5).isZero();
            assertThat(s.durationMinutes()).isBetween(5, 480);
            assertThat(s.returnAfterDays()).isBetween(0, 180);
        });
        assertThat(ServiceCatalog.SERVICES).extracting(s -> s.name().toLowerCase(java.util.Locale.ROOT))
                .doesNotHaveDuplicates();
    }
}
