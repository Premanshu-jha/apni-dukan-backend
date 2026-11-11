package in.apnidukan.salon.domain;

import java.util.UUID;

/**
 * A service inside an appointment, copied at booking time: changing the service's price or length later doesn't
 * change bookings already made.
 * @param pricePaise     what the customer pays (with an offer: the offer price)
 * @param offerId        the salon offer it was booked under; null without one
 * @param listPricePaise the price before the offer; null without one
 */
public record BookedService(UUID serviceId, String name, int durationMin, long pricePaise, boolean priceIsFrom,
                            UUID offerId, Long listPricePaise) {

    /** A service at its own price (no offer). */
    public BookedService(UUID serviceId, String name, int durationMin, long pricePaise, boolean priceIsFrom) {
        this(serviceId, name, durationMin, pricePaise, priceIsFrom, null, null);
    }

    /** The same service priced under this offer (its list price kept). */
    public BookedService withOffer(SalonOffer offer) {
        return new BookedService(serviceId, name, durationMin, offer.priceOf(pricePaise), priceIsFrom, offer.id(),
                pricePaise);
    }
}
