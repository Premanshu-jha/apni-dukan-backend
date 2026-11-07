package in.apnidukan.salon.repo;

import in.apnidukan.salon.domain.SalonServiceItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SalonServiceItemRepository extends JpaRepository<SalonServiceItem, UUID> {

    /** The salon's list as the app shows it: by the owner's order, then by name. */
    List<SalonServiceItem> findByShopIdOrderBySortOrderAscNameAsc(UUID shopId);

    /** The salon's active services only, in the app's order. */
    List<SalonServiceItem> findByShopIdAndActiveTrueOrderBySortOrderAscNameAsc(UUID shopId);

    /** Only finds it if it belongs to this salon, so one salon can never read or edit another's. */
    Optional<SalonServiceItem> findByIdAndShopId(UUID id, UUID shopId);

    /** Switched-off services don't count towards the salon's limit. */
    long countByShopIdAndActiveTrue(UUID shopId);
}
