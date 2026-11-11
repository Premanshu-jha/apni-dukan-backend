package in.apnidukan.salon.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request bodies for salon offers and the customer's offer messages. Unknown fields are refused (400). */
public final class SalonOfferRequests {

    private SalonOfferRequests() {
    }

    /**
     * An offer (to preview, or to publish).
     * @param percentOff 5 to 50
     * @param serviceIds the salon's services it covers; null or empty: all of them
     * @param startsOn   from this India date (today or later)
     * @param endsOn     until this date, included (at most 90 days after today)
     * @param from       optional "HH:mm" each day (with until); left out: all day. Both must lie inside the salon's
     *                   opening hours on every day it is open in those dates
     * @param until      optional "HH:mm" each day, not included
     */
    public record NewOffer(@NotNull @Min(5) @Max(50) Integer percentOff,
                           @Size(max = 100) List<@NotNull UUID> serviceIds,
                           @NotNull LocalDate startsOn,
                           @NotNull LocalDate endsOn,
                           String from,
                           String until) {

        public NewOffer {
            serviceIds = serviceIds == null ? List.of() : List.copyOf(serviceIds);
        }
    }

    /** A customer's offer messages from one salon: on or off. */
    public record OfferMessages(@NotNull Boolean on) {
    }
}
