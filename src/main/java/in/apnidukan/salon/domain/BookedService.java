package in.apnidukan.salon.domain;

import java.util.UUID;

/**
 * A service inside an appointment, copied at booking time: changing the service's price or length later doesn't
 * change bookings already made.
 */
public record BookedService(UUID serviceId, String name, int durationMin, long pricePaise, boolean priceIsFrom) {
}
