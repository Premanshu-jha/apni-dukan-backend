package in.apnidukan.shops.repo;

import in.apnidukan.shops.domain.Shop;
import in.apnidukan.shops.domain.ShopStatus;
import in.apnidukan.shops.domain.ShopType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShopRepository extends JpaRepository<Shop, UUID> {

    /** The next shop code (1001, 1002, ...). The database hands each one out once, even under parallel sign-ups. */
    @Query(value = "SELECT nextval('shop_code_seq')", nativeQuery = true)
    long nextShopCode();

    /** The shop, its row held in shared mode (FOR SHARE) until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT s FROM Shop s WHERE s.id = :id")
    Optional<Shop> findForShare(UUID id);

    /** The shop, its row locked (FOR UPDATE) until the transaction ends: closing it, or handing it over. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Shop s WHERE s.id = :id")
    Optional<Shop> lockById(UUID id);

    /**
     * Whether changing the shop needs the owner's edit code (ShopService.requireUnlocked): verified and not a draft.
     * No entity is loaded, so the row locked next with lockById is read fresh.
     */
    @Query(value = "SELECT (ever_approved OR status = 'APPROVED') AND status <> 'DRAFT' FROM shops WHERE id = :id",
            nativeQuery = true)
    boolean needsEditCode(UUID id);

    /** Locks the shop's row against bookings (FOR NO KEY UPDATE, see ShopDirectory.holdAgainstBookings). */
    @Query(value = "SELECT CAST(id AS text) FROM shops WHERE id = :id FOR NO KEY UPDATE", nativeQuery = true)
    Optional<String> holdAgainstBookings(UUID id);

    /** Whether a file is some shop's owner or shop photo, or its salon video. */
    boolean existsByOwnerPhotoFileIdOrShopPhotoFileIdOrShopVideoFileId(UUID ownerPhotoFileId, UUID shopPhotoFileId,
                                                                       UUID shopVideoFileId);

    /** Whether a file is some shop's registration document. */
    boolean existsByRegistrationDocFileId(UUID registrationDocFileId);
}
