package in.apnidukan.shops.repo;

import in.apnidukan.shops.domain.ShopMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShopMemberRepository extends JpaRepository<ShopMember, UUID> {

    Optional<ShopMember> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);

    /** Everyone in the shop, by when they joined; the id breaks ties so the order never changes. */
    List<ShopMember> findByShopIdOrderByCreatedAtAscIdAsc(UUID shopId);

    Optional<ShopMember> findByShopIdAndUserId(UUID shopId, UUID userId);
}
