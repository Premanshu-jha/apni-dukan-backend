package in.apnidukan.places.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A customer's saved place (V40): Home, Work, or another with a name of its own, its address as typed (or filled
 * from GPS), and the pin the customer set on the map. Salons find nothing of it; it is only where to look for salons.
 *
 * @param name only for OTHER ("Mom's place"); null for Home and Work
 */
public record Place(UUID id, UUID userId, Kind kind, String name, String addressLine, String area, String city,
                   String pincode, BigDecimal latitude, BigDecimal longitude, Instant createdAt, Instant updatedAt) {

    /** Home and Work: one each. Other: as many as fit, each with a name. */
    public enum Kind {
        HOME, WORK, OTHER
    }
}
