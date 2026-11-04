package in.apnidukan.common.context;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Public facts about shops, for modules that show shops to customers (the salon booking pages). Implemented by the
 * shops module. Never includes private details such as GSTIN or the owner's phone.
 */
public interface ShopDirectory {

    Optional<ShopCard> find(UUID shopId);

    /**
     * Like find, and holds the shop's row in shared mode until the transaction ends (SELECT ... FOR SHARE). A
     * booking takes this: a change to the shop (a new address sending it back for a check) then waits for bookings
     * in progress to finish, and bookings after it see the new status and address.
     */
    Optional<ShopCard> findForBooking(UUID shopId);

    /**
     * APPROVED shops of this type within radiusKm of the point, nearest first, at most limit.
     * @param type KIRANA or SALON
     */
    List<ShopCard> nearby(String type, BigDecimal latitude, BigDecimal longitude, double radiusKm, int limit);

    /**
     * @param status     DRAFT, PENDING_VISIT, APPROVED or SUSPENDED
     * @param type       KIRANA or SALON
     * @param shopPhotoFileId the shop's photo, or null
     * @param distanceKm from the point asked about in nearby(); null from find()
     */
    record ShopCard(UUID id, String name, String addressLine, String area, String city, String pincode,
                    BigDecimal latitude, BigDecimal longitude, String status, String type, UUID shopPhotoFileId,
                    Double distanceKm) {
    }
}
