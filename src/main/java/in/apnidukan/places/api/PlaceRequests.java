package in.apnidukan.places.api;

import in.apnidukan.places.domain.Place.Kind;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** What the customer app sends for saved places and their address search. */
public final class PlaceRequests {

    private PlaceRequests() {
    }

    /**
     * A place to save, or a saved one's new details (the whole place each time).
     *
     * @param name      only for OTHER, 2 to 30 letters ("Mom's place", checked when cleaned); ignored for HOME and WORK
     * @param latitude  the pin, as the customer left it on the map (inside India)
     */
    public record SavePlace(
            @NotNull Kind kind,
            String name,
            @NotBlank @Size(min = 2, max = 200) String addressLine,
            @NotBlank @Size(min = 2, max = 100) String area,
            @NotBlank @Size(min = 2, max = 60) String city,
            @NotNull @Pattern(regexp = "[1-9][0-9]{5}", message = "must be a 6-digit pincode") String pincode,
            @NotNull @DecimalMin("6.0") @DecimalMax("37.6") BigDecimal latitude,
            @NotNull @DecimalMin("68.0") @DecimalMax("97.5") BigDecimal longitude) {
    }

    /** "Find on map": the typed address (the same fields and limits as saving it). */
    public record FindAddress(
            @NotBlank @Size(min = 2, max = 200) String addressLine,
            @NotBlank @Size(min = 2, max = 100) String area,
            @NotBlank @Size(min = 2, max = 60) String city,
            @NotNull @Pattern(regexp = "[1-9][0-9]{5}", message = "must be a 6-digit pincode") String pincode) {
    }

    /** "Use current location": the phone's GPS point, whose address fills the empty fields. */
    public record GpsPoint(
            @NotNull @DecimalMin("6.0") @DecimalMax("37.6") BigDecimal latitude,
            @NotNull @DecimalMin("68.0") @DecimalMax("97.5") BigDecimal longitude) {
    }
}
