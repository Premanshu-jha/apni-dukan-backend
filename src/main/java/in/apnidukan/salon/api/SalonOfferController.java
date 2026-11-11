package in.apnidukan.salon.api;

import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.salon.api.SalonOfferRequests.NewOffer;
import in.apnidukan.salon.api.SalonOfferResponses.OfferPreview;
import in.apnidukan.salon.api.SalonOfferResponses.OwnerOffer;
import in.apnidukan.salon.service.SalonOfferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The salon's offers for the partner app: the list (owner and helpers), and the preview, publishing and ending
 * (owner only; 403 OWNER_ONLY otherwise). See SalonOfferService for every check.
 */
@RestController
public class SalonOfferController {

    private final SalonOfferService offers;
    private final CurrentUserProvider currentUser;

    public SalonOfferController(SalonOfferService offers, CurrentUserProvider currentUser) {
        this.offers = offers;
        this.currentUser = currentUser;
    }

    /** The salon's offers, newest first (up to 50), each RUNNING, UPCOMING or ENDED with its bookings. */
    @GetMapping("/api/v1/salon/offers")
    public List<OwnerOffer> list() {
        return offers.list(me());
    }

    /** What publishing would do: each service's price and offer price, and how many customers would be told. */
    @PostMapping("/api/v1/salon/offers/preview")
    public OfferPreview preview(@Valid @RequestBody NewOffer body) {
        return offers.preview(me(), body);
    }

    /** Publishes the offer: 201, and its message goes to the salon's past customers at once. */
    @PostMapping("/api/v1/salon/offers")
    @ResponseStatus(HttpStatus.CREATED)
    public OwnerOffer publish(@Valid @RequestBody NewOffer body) {
        return offers.publish(me(), body);
    }

    /** Ends the offer now; bookings already made keep their price. 409 OFFER_OVER if it already ended. */
    @PostMapping("/api/v1/salon/offers/{id}/end")
    public OwnerOffer end(@PathVariable UUID id) {
        return offers.end(me(), id);
    }

    private UUID me() {
        return currentUser.get().userId();
    }
}
