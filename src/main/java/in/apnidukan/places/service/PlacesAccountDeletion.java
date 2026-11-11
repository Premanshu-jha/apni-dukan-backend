package in.apnidukan.places.service;

import in.apnidukan.common.context.AccountDeletionListener;
import in.apnidukan.places.repo.PlaceStore;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Someone deleted their account: their saved places (home and work addresses) go with it. The person's lock first, as
 * an add takes it: a place being saved at this moment commits first and is deleted here, and one after this finds
 * the account gone (PlaceService checks it under the same lock).
 */
@Component
@Order(20)
class PlacesAccountDeletion implements AccountDeletionListener {

    private final PlaceStore places;

    PlacesAccountDeletion(PlaceStore places) {
        this.places = places;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void accountDeleted(UUID userId, Instant now) {
        places.lockPerson(userId);
        places.deleteAll(userId);
    }
}
