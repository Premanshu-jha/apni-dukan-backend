package in.apnidukan.salon.service;

import in.apnidukan.salon.domain.Availability.BarberDay;
import in.apnidukan.salon.domain.BookedService;
import in.apnidukan.salon.domain.Interval;
import in.apnidukan.salon.repo.EarlierSlotAlertStore.Candidate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which start a freed time offers one booking (EarlierSlotAlerts.earliest), without a database. Tuesday 10 Nov 2026,
 * times in India; everyone works 10:00-21:00. Raju and Sunil do haircuts; only Raju does beards, only Asif facials.
 */
class EarlierSlotOfferTest {

    private static final UUID RAJU = new UUID(0, 1);
    private static final UUID SUNIL = new UUID(0, 2);
    private static final UUID ASIF = new UUID(0, 3);
    private static final UUID HAIRCUT = new UUID(1, 1);
    private static final UUID BEARD = new UUID(1, 2);
    private static final UUID FACIAL = new UUID(1, 3);
    private static final Instant ADDED = Instant.parse("2026-01-01T00:00:00Z");
    private static final BookedService HAIRCUT_30 = new BookedService(HAIRCUT, "Haircut", 30, 15000, false);
    private static final BookedService BEARD_15 = new BookedService(BEARD, "Beard trim", 15, 8000, false);
    private static final BookedService FACIAL_45 = new BookedService(FACIAL, "Facial", 45, 40000, false);
    private static final List<SalonSetups.Barber> BARBERS = List.of(barber(RAJU, HAIRCUT, BEARD),
            barber(SUNIL, HAIRCUT), barber(ASIF, FACIAL));

    @Test
    void theStartOfTheFreedTimeIsOfferedWhenNoOtherBarberHadIt() {
        Interval freed = between("12:00", "12:30");
        List<BarberDay> days = List.of(day(RAJU), day(SUNIL, between("12:00", "12:30")), day(ASIF));
        Candidate c = candidate(SUNIL, "15:00", "15:30");
        assertThat(earliest(c, List.of(HAIRCUT_30), days, freed, at("12:00"), List.of())).contains(at("12:00"));
    }

    @Test
    void aTimeAnotherBarberWhoDoesTheServicesHadFreeIsNoNewsButOneWhoDoesntDoThemDoesntCount() {
        Interval freed = between("12:00", "13:00");
        // Sunil is free 12:00-12:30 already: 12:00 could have been booked; from 12:15 he is busy
        List<BarberDay> days = List.of(day(RAJU), day(SUNIL, between("12:30", "13:30")), day(ASIF));
        Candidate c = candidate(RAJU, "15:00", "15:30");
        assertThat(earliest(c, List.of(HAIRCUT_30), days, freed, at("12:00"), List.of())).contains(at("12:15"));
        // Asif is free all day but does no haircuts: he doesn't make 12:00 old news
        List<BarberDay> onlyAsifFree = List.of(day(RAJU), day(SUNIL, between("10:00", "21:00")), day(ASIF));
        assertThat(earliest(c, List.of(HAIRCUT_30), onlyAsifFree, freed, at("12:00"), List.of()))
                .contains(at("12:00"));
    }

    @Test
    void nothingIsOfferedWhenTheFreedBarberDoesntDoAllTheServices() {
        Interval freed = between("12:00", "13:00");
        List<BarberDay> days = List.of(day(RAJU), day(SUNIL), day(ASIF, between("10:00", "21:00")));
        Candidate c = candidate(ASIF, "15:00", "15:45");
        assertThat(earliest(c, List.of(FACIAL_45), days, freed, at("12:00"), List.of())).isEmpty();
        assertThat(EarlierSlotAlerts.earliest(c, List.of(), BARBERS.get(0), BARBERS, days, freed, at("12:00"),
                List.of())).isEmpty();   // no services read: nothing to fit
    }

    @Test
    void theBookingsOwnTimeWithTheFreedBarberCountsAsFree() {
        // A 15-minute beard was cancelled 12:00-12:15; the haircut right after it, 12:15-12:45 with Raju, can start at
        // 12:00 once it leaves its own time
        Interval freed = between("12:00", "12:15");
        List<BarberDay> days = List.of(day(RAJU, between("12:15", "12:45")), day(SUNIL, between("10:00", "21:00")),
                day(ASIF));
        Candidate c = candidate(RAJU, "12:15", "12:45");
        assertThat(earliest(c, List.of(HAIRCUT_30), days, freed, at("12:00"), List.of())).contains(at("12:00"));
    }

    @Test
    void aTimeClashingWithTheCustomersOtherBookingIsSkipped() {
        Interval freed = between("12:00", "13:00");
        List<BarberDay> days = List.of(day(RAJU), day(SUNIL, between("10:00", "21:00")), day(ASIF));
        Candidate c = candidate(RAJU, "15:00", "15:30");
        List<Interval> theirOther = List.of(between("11:45", "12:20"));
        assertThat(earliest(c, List.of(HAIRCUT_30), days, freed, at("12:00"), theirOther)).contains(at("12:30"));
    }

    @Test
    void onlyStartsInsideTheFreedTimeFromTheSalonsNoticeAndBeforeTheBookingAreOffered() {
        Interval freed = between("12:00", "13:00");
        List<BarberDay> days = List.of(day(RAJU), day(SUNIL, between("10:00", "21:00")), day(ASIF));
        // The notice: from 12:30 at the earliest
        assertThat(earliest(candidate(RAJU, "15:00", "15:30"), List.of(HAIRCUT_30), days, freed, at("12:30"),
                List.of())).contains(at("12:30"));
        // Booked 12:30 and the notice runs to 12:20: no quarter hour is left before it
        assertThat(earliest(candidate(RAJU, "12:30", "13:00"), List.of(HAIRCUT_30), days, freed,
                at("12:20"), List.of())).isEmpty();
        // A start after the freed time ends uses none of it: Raju free at 13:00 is no offer
        List<BarberDay> busyInside = List.of(day(RAJU, between("12:00", "13:00")), day(SUNIL, between("10:00", "21:00")),
                day(ASIF));
        assertThat(earliest(candidate(SUNIL, "15:00", "15:30"), List.of(HAIRCUT_30), busyInside, freed,
                at("12:00"), List.of())).isEmpty();
    }

    @Test
    void theWholeLengthMustFitWithTheFreedBarber() {
        // 30 minutes freed, a haircut + beard (45) doesn't fit before Raju's 12:30 booking
        Interval freed = between("12:00", "12:30");
        List<BarberDay> days = List.of(day(RAJU, between("12:30", "13:00")), day(SUNIL, between("10:00", "21:00")),
                day(ASIF));
        assertThat(earliest(candidate(SUNIL, "15:00", "15:45"), List.of(HAIRCUT_30, BEARD_15), days, freed,
                at("12:00"), List.of())).isEmpty();
    }

    private static Optional<Instant> earliest(Candidate c, List<BookedService> services, List<BarberDay> days,
                                              Interval freed, Instant from, List<Interval> others) {
        return EarlierSlotAlerts.earliest(c, services, BARBERS.get(0), BARBERS, days, freed, from, others);
    }

    private static Candidate candidate(UUID staffId, String from, String to) {
        return new Candidate(UUID.randomUUID(), UUID.randomUUID(), staffId, at(from), at(to), null);
    }

    private static SalonSetups.Barber barber(UUID id, UUID... services) {
        return new SalonSetups.Barber(id, id.equals(RAJU) ? "Raju" : "Other", ADDED, Set.of(services), List.of(),
                null);
    }

    private static BarberDay day(UUID staffId, Interval... busy) {
        return new BarberDay(staffId, List.of(between("10:00", "21:00")), List.of(busy), 0, ADDED);
    }

    private static Interval between(String from, String to) {
        return new Interval(at(from), at(to));
    }

    private static Instant at(String hhmm) {
        return LocalDate.of(2026, 11, 10).atTime(LocalTime.parse(hhmm)).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
    }
}
