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

    /** find() for many shops in one query, by id (unknown ids are left out). */
    java.util.Map<UUID, ShopCard> findAll(java.util.Collection<UUID> shopIds);

    /**
     * Like find, and holds the shop's row in shared mode until the transaction ends (SELECT ... FOR SHARE). A
     * booking takes this: a change to the shop (a new address sending it back for a check) then waits for bookings
     * in progress to finish, and bookings after it see the new status and address.
     */
    Optional<ShopCard> findForBooking(UUID shopId);

    /**
     * Locks the shop's row against bookings until the transaction ends (SELECT ... FOR NO KEY UPDATE: it waits for
     * bookings in progress, which hold it FOR SHARE, and bookings after it wait for this one, but foreign-key checks
     * are not held up). A salon pausing online bookings takes it, so a booking either finished before the pause or
     * sees it.
     */
    void holdAgainstBookings(UUID shopId);

    /**
     * APPROVED shops of this type within radiusMetres of the point, nearest first, at most limit, after `after`
     * (the last card of the previous page; null for the first page). Found by the database's map index (PostGIS),
     * so it reads only shops near the point, however many there are.
     * @param type                KIRANA or SALON
     * @param skipPausedSalons    leave out salons that paused online bookings (salon_pauses) right now
     */
    List<ShopCard> nearby(String type, BigDecimal latitude, BigDecimal longitude, int radiusMetres, int limit,
                          Cursor after, boolean skipPausedSalons);

    /** Where a page of nearby shops ended: the last card's exact distance and id. */
    record Cursor(double distanceMetres, UUID id) {
    }

    /**
     * @param status     DRAFT, PENDING_VISIT, APPROVED or SUSPENDED
     * @param type       KIRANA or SALON
     * @param shopPhotoFileId the shop's photo, or null
     * @param distanceKm     from the point asked about in nearby(), rounded to 0.1 km; null from find()
     * @param distanceMetres the exact distance, for the next page's Cursor; null from find()
     */
    record ShopCard(UUID id, String name, String addressLine, String area, String city, String pincode,
                    BigDecimal latitude, BigDecimal longitude, String status, String type, UUID shopPhotoFileId,
                    Double distanceKm, Double distanceMetres) {
    }
}
