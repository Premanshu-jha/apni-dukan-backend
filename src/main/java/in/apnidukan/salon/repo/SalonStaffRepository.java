package in.apnidukan.salon.repo;

import in.apnidukan.salon.domain.SalonStaff;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SalonStaffRepository extends JpaRepository<SalonStaff, UUID> {

    /** Oldest first; the id breaks ties so the order never changes between calls. */
    List<SalonStaff> findByShopIdOrderByCreatedAtAscIdAsc(UUID shopId);

    /** Only finds the barber if he belongs to this salon. */
    Optional<SalonStaff> findByIdAndShopId(UUID id, UUID shopId);

    /**
     * Like findByIdAndShopId, and locks the barber's row (SELECT ... FOR UPDATE) until the transaction ends.
     * Two edits of the same barber's services or hours then run one after the other instead of both comparing
     * against the old list.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SalonStaff s WHERE s.id = :id AND s.shopId = :shopId")
    Optional<SalonStaff> lockByIdAndShopId(UUID id, UUID shopId);

    /** Whether a file is some barber's photo. */
    boolean existsByPhotoFileId(UUID photoFileId);

    /** The barber this sign-in belongs to (a BARBER member), if any. */
    Optional<SalonStaff> findByUserId(UUID userId);

    /** Switched-off barbers (who left) don't count towards the salon's limit. */
    long countByShopIdAndActiveTrue(UUID shopId);
}
