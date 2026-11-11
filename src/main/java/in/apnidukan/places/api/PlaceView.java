package in.apnidukan.places.api;

import in.apnidukan.places.domain.Place;
import in.apnidukan.places.domain.Place.Kind;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A saved place as the customer app gets it. name is null for Home and Work. */
public record PlaceView(UUID id, Kind kind, String name, String addressLine, String area, String city, String pincode,
                        BigDecimal latitude, BigDecimal longitude, Instant updatedAt) {

    public static PlaceView of(Place p) {
        return new PlaceView(p.id(), p.kind(), p.name(), p.addressLine(), p.area(), p.city(), p.pincode(),
                p.latitude(), p.longitude(), p.updatedAt());
    }
}
