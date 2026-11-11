package in.apnidukan.salon.api;

import in.apnidukan.salon.domain.SalonOffer;
import in.apnidukan.salon.domain.SalonTime;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** What the salon-offer endpoints return. Dates are India dates; times "HH:mm" in India time. */
public final class SalonOfferResponses {

    private SalonOfferResponses() {
    }

    /**
     * An offer as customers see it.
     * @param serviceIds the services it covers; empty when allServices
     * @param from       each day from this time; null: all day (then until is null too)
     */
    public record OfferView(UUID id, int percentOff, boolean allServices, List<UUID> serviceIds, LocalDate startsOn,
                            LocalDate endsOn, String from, String until) {

        public OfferView {
            serviceIds = List.copyOf(serviceIds);
        }

        public static OfferView of(SalonOffer o) {
            return new OfferView(o.id(), o.percentOff(), o.allServices(), o.serviceIds().stream().sorted().toList(),
                    o.startsOn(), o.endsOn(), o.fromMin() == null ? null : SalonTime.format(o.fromMin()),
                    o.toMin() == null ? null : SalonTime.format(o.toMin()));
        }
    }

    /** RUNNING: inside its dates now; UPCOMING: starts later; ENDED: past its last day, or ended early. */
    public enum OfferState { RUNNING, UPCOMING, ENDED }

    /**
     * An offer in the owner's list.
     * @param endedAt   set when the owner ended it early
     * @param notified  how many customers the message went to when it was published
     * @param bookings  bookings made with it (not cancelled)
     */
    public record OwnerOffer(OfferView offer, OfferState state, Instant createdAt, Instant endedAt, int notified,
                             int bookings) {
    }

    /** A service with its price and its offer price (the owner's preview). */
    public record PricedService(UUID serviceId, String name, long pricePaise, long offerPricePaise,
                                boolean priceIsFrom) {
    }

    /**
     * What publishing this offer would do.
     * @param services  the services it covers, with both prices
     * @param customers how many past customers the message would go to now
     */
    public record OfferPreview(List<PricedService> services, int customers) {

        public OfferPreview {
            services = List.copyOf(services);
        }
    }
}
