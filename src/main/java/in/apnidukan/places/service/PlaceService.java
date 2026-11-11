package in.apnidukan.places.service;

import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.text.TextCleaner.TextField;
import in.apnidukan.places.api.PlaceRequests.SavePlace;
import in.apnidukan.places.api.PlaceView;
import in.apnidukan.places.domain.Place;
import in.apnidukan.places.domain.Place.Kind;
import in.apnidukan.places.repo.PlaceStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static in.apnidukan.common.text.TextCleaner.clean;

/**
 * A customer's saved places: where the customer app looks for salons besides the phone's GPS point. Each is set like
 * a salon's address at registration (the address fields, then the pin the customer drags on the map). At most
 * {@value #MAX_PLACES}, one Home and one Work; only their owner reads or changes them.
 */
@Service
public class PlaceService {

    /** How many places one person can save. */
    public static final int MAX_PLACES = 10;

    static final TextField NAME = new TextField("name", 2, 30);
    static final TextField ADDRESS_LINE = new TextField("addressLine", 2, 200);
    static final TextField AREA = new TextField("area", 2, 100);
    static final TextField CITY = new TextField("city", 2, 60);

    private final PlaceStore places;
    private final UserDirectory users;
    private final Clock clock;

    /**
     * @param users whether the account is still there, checked under the person's lock (a token can outlive a
     *              deleted account by a few minutes)
     */
    public PlaceService(PlaceStore places, UserDirectory users, Clock clock) {
        this.places = places;
        this.users = users;
        this.clock = clock;
    }

    /** Home, Work, then the others as saved. */
    @Transactional(readOnly = true)
    public List<PlaceView> list(UUID userId) {
        return places.list(userId).stream().map(PlaceView::of).toList();
    }

    /**
     * Saves a new place. 409 TOO_MANY_PLACES past {@value #MAX_PLACES}; 409 PLACE_KIND_TAKEN when there's a Home (or
     * Work) already (details.placeId: edit that one instead); 400 for a field (an Other without a name).
     */
    @Transactional
    public PlaceView add(UUID userId, SavePlace in) {
        Fields f = fields(in);
        lockLivePerson(userId);   // two phones adding at once: one after the other
        if (places.count(userId) >= MAX_PLACES) {
            throw new ApiException(HttpStatus.CONFLICT, "TOO_MANY_PLACES", "You can save up to " + MAX_PLACES
                    + " places. Delete one you don't use, then add this one.", Map.of("max", MAX_PLACES));
        }
        UUID id = UUID.randomUUID();
        requireKindFree(userId, in.kind(), id);
        Instant now = clock.instant();
        Place p = new Place(id, userId, in.kind(), f.name(), f.addressLine(), f.area(), f.city(), f.pincode(),
                pin(in.latitude()), pin(in.longitude()), now, now);
        places.insert(p);
        return PlaceView.of(p);
    }

    /** Changes a saved place (the whole place). 404 PLACE_NOT_FOUND if not this person's; 409 PLACE_KIND_TAKEN. */
    @Transactional
    public PlaceView update(UUID userId, UUID id, SavePlace in) {
        Fields f = fields(in);
        lockLivePerson(userId);
        Place old = places.find(userId, id).orElseThrow(PlaceService::notFound);
        requireKindFree(userId, in.kind(), id);
        Place p = new Place(id, userId, in.kind(), f.name(), f.addressLine(), f.area(), f.city(), f.pincode(),
                pin(in.latitude()), pin(in.longitude()), old.createdAt(), clock.instant());
        if (!places.update(p)) {
            throw notFound();
        }
        return PlaceView.of(p);
    }

    /** Deletes it. Deleting one already gone (a second tap, another phone) is fine: 204 either way. */
    @Transactional
    public void delete(UUID userId, UUID id) {
        places.delete(userId, id);
    }

    /**
     * The person's lock, then their row, the order deleting the account takes them in (PlacesAccountDeletion first,
     * then the users row): a deleted account gets 401 NOT_SIGNED_IN and saves nothing.
     */
    private void lockLivePerson(UUID userId) {
        places.lockPerson(userId);
        if (!users.lockIfActive(userId)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "NOT_SIGNED_IN", "Sign in first.");
        }
    }

    private record Fields(String name, String addressLine, String area, String city, String pincode) {
    }

    /** The fields cleaned like a shop's address; a name only for Other, where it is needed. */
    private static Fields fields(SavePlace in) {
        String name = null;
        if (in.kind() == Kind.OTHER) {
            if (in.name() == null || in.name().isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid",
                        Map.of("name", "give this place a name"));
            }
            name = clean(NAME, in.name());
        }
        return new Fields(name, clean(ADDRESS_LINE, in.addressLine()), clean(AREA, in.area()), clean(CITY, in.city()),
                in.pincode().strip());
    }

    private void requireKindFree(UUID userId, Kind kind, UUID self) {
        if (kind == Kind.OTHER) {
            return;
        }
        places.holderOf(userId, kind, self).ifPresent(other -> {
            String what = kind == Kind.HOME ? "Home" : "Work";
            throw new ApiException(HttpStatus.CONFLICT, "PLACE_KIND_TAKEN", "You already have a " + what
                    + " address. Change that one, or save this as another place.", Map.of("placeId", other));
        });
    }

    /** As stored: 6 decimals (about 10 cm). */
    private static BigDecimal pin(BigDecimal degrees) {
        return degrees.setScale(6, RoundingMode.HALF_UP);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND", "That place isn't saved any more.");
    }
}
