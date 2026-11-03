package in.apnidukan.shops.repo;

import in.apnidukan.shops.domain.Shop;
import in.apnidukan.shops.domain.ShopStatus;
import in.apnidukan.shops.domain.ShopType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShopRepository extends JpaRepository<Shop, UUID> {

    /** The next shop code (1001, 1002, ...). The database hands each one out once, even under parallel sign-ups. */
    @Query(value = "SELECT nextval('shop_code_seq')", nativeQuery = true)
    long nextShopCode();

    /**
     * Shops of this type and status whose pin lies inside a box around the customer. The box is a cheap first cut;
     * the exact distance is worked out afterwards (ShopDirectoryService), which drops the box's corners.
     */
    @Query("SELECT s FROM Shop s WHERE s.type = :type AND s.status = :status"
            + " AND s.latitude BETWEEN :minLat AND :maxLat AND s.longitude BETWEEN :minLng AND :maxLng")
    List<Shop> findInBox(ShopType type, ShopStatus status, BigDecimal minLat, BigDecimal maxLat,
                         BigDecimal minLng, BigDecimal maxLng);

    /** The shop, its row held in shared mode (FOR SHARE) until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT s FROM Shop s WHERE s.id = :id")
    Optional<Shop> findForShare(UUID id);

    /** Whether a file is some shop's owner or shop photo. */
    boolean existsByOwnerPhotoFileIdOrShopPhotoFileId(UUID ownerPhotoFileId, UUID shopPhotoFileId);

    /** For the field team: shops waiting for approval (or in any other status), oldest first. */
    List<Shop> findByStatusOrderByCreatedAtAsc(ShopStatus status);
}
