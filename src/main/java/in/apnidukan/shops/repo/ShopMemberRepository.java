package in.apnidukan.shops.repo;

import in.apnidukan.shops.domain.ShopMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShopMemberRepository extends JpaRepository<ShopMember, UUID> {

    Optional<ShopMember> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);

    /** Everyone in the shop, by when they joined; the id breaks ties so the order never changes. */
    List<ShopMember> findByShopIdOrderByCreatedAtAscIdAsc(UUID shopId);

    Optional<ShopMember> findByShopIdAndUserId(UUID shopId, UUID userId);

    /**
     * Takes this shop's member lock until the transaction ends (a transaction-level advisory lock), so two helper adds
     * at the same moment are counted one after the other. Returns 1.
     */
    @Query(value = "SELECT count(*) FROM (SELECT pg_advisory_xact_lock(7103, hashtext(CAST(:shopId AS text)))) l",
            nativeQuery = true)
    long lockMembers(@Param("shopId") UUID shopId);
}
