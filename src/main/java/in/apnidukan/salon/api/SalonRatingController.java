package in.apnidukan.salon.api;

import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.salon.api.BookingResponses.RatingsPage;
import in.apnidukan.salon.service.SalonRatingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** The salon's ratings for the partner app (owner and helpers; 403 NOT_ALLOWED for a barber). */
@RestController
public class SalonRatingController {

    private final SalonRatingService ratings;
    private final CurrentUserProvider currentUser;

    public SalonRatingController(SalonRatingService ratings, CurrentUserProvider currentUser) {
        this.ratings = ratings;
        this.currentUser = currentUser;
    }

    /**
     * The salon's ratings, newest first, 30 a page, with the comments only the salon sees; the first page also has
     * the totals. The next page: afterAt and afterId from `next` (both or neither).
     */
    @GetMapping("/api/v1/salon/ratings")
    public RatingsPage ratings(@RequestParam(required = false) Instant afterAt,
                               @RequestParam(required = false) UUID afterId) {
        return ratings.page(currentUser.get().userId(), afterAt, afterId);
    }
}
