package in.apnidukan.salon.service;

import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.salon.api.BookingResponses.RatingSummary;
import in.apnidukan.salon.api.BookingResponses.RatingsNext;
import in.apnidukan.salon.api.BookingResponses.RatingsPage;
import in.apnidukan.salon.api.BookingResponses.SalonRating;
import in.apnidukan.salon.domain.BookedService;
import in.apnidukan.salon.domain.RatingTag;
import in.apnidukan.salon.repo.AppointmentStore;
import in.apnidukan.salon.repo.RatingStore;
import in.apnidukan.salon.service.SalonAccess.Caller;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The salon's ratings (the owner and helpers): every rating its customers gave, newest first, with the comments only
 * the salon sees, and the totals. Customers rate through BookingService.rate.
 */
@Service
public class SalonRatingService {

    /** Ratings per page. */
    static final int PAGE = 30;

    private final SalonAccess access;
    private final RatingStore ratings;
    private final AppointmentStore appointments;
    private final UserDirectory users;

    public SalonRatingService(SalonAccess access, RatingStore ratings, AppointmentStore appointments,
                              UserDirectory users) {
        this.access = access;
        this.ratings = ratings;
        this.appointments = appointments;
        this.users = users;
    }

    /**
     * One page of the salon's ratings, newest first, after the cursor (both values or neither: 400 otherwise). The
     * first page carries the totals with the average from the first rating on (customers see it from 5).
     */
    @Transactional(readOnly = true)
    public RatingsPage page(UUID userId, Instant afterAt, UUID afterId) {
        if ((afterAt == null) != (afterId == null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid",
                    Map.of("afterId", "send afterAt and afterId together"));
        }
        Caller c = access.ownerOrHelper(userId);
        List<RatingStore.Listed> rows = ratings.page(c.shopId(), afterAt, afterId, PAGE);
        List<UUID> ids = rows.stream().map(r -> r.rating().appointmentId()).toList();
        Map<UUID, List<BookedService>> services = appointments.services(ids);
        Map<UUID, UserDirectory.Person> people = users.find(rows.stream().map(r -> r.rating().customerId())
                .collect(Collectors.toSet()));
        List<SalonRating> items = rows.stream().map(l -> {
            RatingStore.Rating r = l.rating();
            UserDirectory.Person who = people.get(r.customerId());
            String name = who == null || who.name() == null ? "Customer" : who.name();
            return new SalonRating(r.appointmentId(), l.bookingNo(), r.stars(), RatingTag.of(r.tags()), r.comment(),
                    services.getOrDefault(r.appointmentId(), List.of()).stream().map(BookedService::name).toList(),
                    name, l.visitAt(), r.createdAt());
        }).toList();
        RatingSummary summary = null;
        if (afterAt == null) {
            RatingStore.Totals t = ratings.totals(c.shopId());
            RatingSummary forCustomers = BookingService.ratingSummary(t, true);
            summary = new RatingSummary(t.ratings(), t.average(), forCustomers.tags());
        }
        RatingStore.Listed last = rows.isEmpty() ? null : rows.get(rows.size() - 1);
        RatingsNext next = rows.size() < PAGE || last == null ? null
                : new RatingsNext(last.rating().createdAt(), last.rating().appointmentId());
        return new RatingsPage(summary, items, next);
    }
}
