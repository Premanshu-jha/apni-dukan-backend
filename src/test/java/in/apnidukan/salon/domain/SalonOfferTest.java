package in.apnidukan.salon.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The offer's rules: when it applies, its prices, and when two overlap. */
class SalonOfferTest {

    private static final UUID HAIRCUT = UUID.randomUUID();
    private static final UUID BEARD = UUID.randomUUID();
    private static final LocalDate FRI = LocalDate.of(2026, 11, 13);
    private static final Instant NOW = Instant.parse("2026-11-11T05:00:00Z");

    private static SalonOffer offer(int percent, Set<UUID> services, LocalDate from, LocalDate to, Integer fromMin,
                                    Integer toMin) {
        return new SalonOffer(UUID.randomUUID(), UUID.randomUUID(), percent, services.isEmpty(), services, from, to,
                fromMin, toMin, null, 0, NOW);
    }

    private static Instant at(LocalDate d, int hour, int minute) {
        return LocalDateTime.of(d, java.time.LocalTime.of(hour, minute)).atZone(SalonTime.ZONE).toInstant();
    }

    @Test
    void appliesOnlyInsideItsDaysAndHoursUntilEnded() {
        SalonOffer o = offer(20, Set.of(HAIRCUT), FRI, FRI.plusDays(2), 12 * 60, 16 * 60);
        assertThat(o.appliesAt(at(FRI, 12, 0))).isTrue();
        assertThat(o.appliesAt(at(FRI, 15, 45))).isTrue();
        assertThat(o.appliesAt(at(FRI, 16, 0))).isFalse();   // until 16:00, not included
        assertThat(o.appliesAt(at(FRI, 11, 45))).isFalse();
        assertThat(o.appliesAt(at(FRI.plusDays(2), 13, 0))).isTrue();   // the last day is included
        assertThat(o.appliesAt(at(FRI.plusDays(3), 13, 0))).isFalse();
        assertThat(o.appliesAt(at(FRI.minusDays(1), 13, 0))).isFalse();
        SalonOffer allDay = offer(10, Set.of(), FRI, FRI, null, null);
        assertThat(allDay.appliesAt(at(FRI, 0, 0))).isTrue();
        assertThat(allDay.appliesAt(at(FRI, 23, 45))).isTrue();
        SalonOffer ended = new SalonOffer(o.id(), o.shopId(), 20, false, Set.of(HAIRCUT), FRI, FRI, null, null, NOW, 0,
                NOW);
        assertThat(ended.appliesAt(at(FRI, 13, 0))).isFalse();
    }

    @Test
    void pricesAreRoundedToTheRupee() {
        assertThat(SalonOffer.priceOf(15_000, 20)).isEqualTo(12_000);   // ₹150 → ₹120
        assertThat(SalonOffer.priceOf(14_900, 15)).isEqualTo(12_700);   // ₹126.65 → ₹127
        assertThat(SalonOffer.priceOf(9_900, 50)).isEqualTo(5_000);     // ₹49.50 → ₹50 (half up)
        assertThat(SalonOffer.priceOf(100, 50)).isEqualTo(100);         // never below ₹1
        assertThat(SalonOffer.priceOf(0, 50)).isZero();                 // free stays free
    }

    @Test
    void onlyTheCoveredServicesOfABookingInsideTheOfferGetIt() {
        BookedService haircut = new BookedService(HAIRCUT, "Haircut", 30, 15_000, false);
        BookedService beard = new BookedService(BEARD, "Beard trim", 15, 8_000, false);
        SalonOffer o = offer(20, Set.of(HAIRCUT), FRI, FRI, 12 * 60, 16 * 60);
        List<BookedService> inside = SalonOffer.price(List.of(haircut, beard), List.of(o), at(FRI, 13, 0));
        assertThat(inside.get(0).pricePaise()).isEqualTo(12_000);
        assertThat(inside.get(0).listPricePaise()).isEqualTo(15_000);
        assertThat(inside.get(0).offerId()).isEqualTo(o.id());
        assertThat(inside.get(1)).isEqualTo(beard);   // not covered: its own price, no offer
        assertThat(SalonOffer.price(List.of(haircut, beard), List.of(o), at(FRI, 17, 0)))
                .containsExactly(haircut, beard);
        // Two that apply (not allowed, but never both): the bigger discount
        SalonOffer bigger = offer(30, Set.of(), FRI, FRI, null, null);
        assertThat(SalonOffer.price(List.of(haircut), List.of(o, bigger), at(FRI, 13, 0)).get(0).pricePaise())
                .isEqualTo(10_500);
    }

    @Test
    void overlapsNeedTheSameDaysHoursAndServices() {
        SalonOffer o = offer(20, Set.of(HAIRCUT), FRI, FRI.plusDays(2), 12 * 60, 16 * 60);
        // Same service, days and hours meet
        assertThat(o.overlaps(FRI.plusDays(2), FRI.plusDays(5), 15 * 60, 18 * 60, false, Set.of(HAIRCUT))).isTrue();
        // Hours only touch (16:00 to 18:00 after 12:00 to 16:00): no
        assertThat(o.overlaps(FRI, FRI, 16 * 60, 18 * 60, false, Set.of(HAIRCUT))).isFalse();
        // Other days: no
        assertThat(o.overlaps(FRI.plusDays(3), FRI.plusDays(4), null, null, false, Set.of(HAIRCUT))).isFalse();
        // Another service: no; all services: yes
        assertThat(o.overlaps(FRI, FRI, null, null, false, Set.of(BEARD))).isFalse();
        assertThat(o.overlaps(FRI, FRI, null, null, true, Set.of())).isTrue();
        // An all-day new one meets its hours
        assertThat(o.overlaps(FRI, FRI, null, null, false, Set.of(HAIRCUT))).isTrue();
        // Ended early: no longer in the way
        SalonOffer ended = new SalonOffer(o.id(), o.shopId(), 20, false, Set.of(HAIRCUT), FRI, FRI.plusDays(2),
                12 * 60, 16 * 60, NOW, 0, NOW);
        assertThat(ended.overlaps(FRI, FRI, null, null, true, Set.of())).isFalse();
    }
}
