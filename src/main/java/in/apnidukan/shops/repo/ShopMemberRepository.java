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

    /**
     * The person's shop, role and the shop's type in one query (sign-in puts them in every token, and every salon
     * screen asks "who is calling"): one row [shopId, ShopRole, ShopType], or none.
     */
    @Query("SELECT m.shopId, m.role, s.type FROM ShopMember m, Shop s WHERE s.id = m.shopId AND m.userId = :userId")
    List<Object[]> findMembershipWithType(@Param("userId") UUID userId);

    /** Everyone in the shop, by when they joined; the id breaks ties so the order never changes. */
    List<ShopMember> findByShopIdOrderByCreatedAtAscIdAsc(UUID shopId);

    Optional<ShopMember> findByShopIdAndUserId(UUID shopId, UUID userId);

    /**
     * The member's role read fresh from the database, the row locked (FOR UPDATE) until the transaction ends: a
     * hand-over that just made them the owner is seen (an entity loaded earlier in the transaction would still say
     * HELPER).
     */
    @Query(value = "SELECT role FROM shop_members WHERE shop_id = :shopId AND user_id = :userId FOR UPDATE",
            nativeQuery = true)
    Optional<String> lockRole(@Param("shopId") UUID shopId, @Param("userId") UUID userId);

    /**
     * Locks this person's membership row, in whichever shop (FOR UPDATE), until the transaction ends; returns its
     * shop id, or empty if they have none. A hand-over takes it before the person's own row, the order their leaving
     * or account deletion takes them in (membership first), so the two can only wait for each other.
     */
    @Query(value = "SELECT shop_id FROM shop_members WHERE user_id = :userId FOR UPDATE", nativeQuery = true)
    Optional<UUID> lockByUserId(@Param("userId") UUID userId);

    /**
     * Takes this shop's member lock until the transaction ends (a transaction-level advisory lock), so two helper adds
     * at the same moment are counted one after the other. Returns 1.
     */
    @Query(value = "SELECT count(*) FROM (SELECT pg_advisory_xact_lock(7103, hashtext(CAST(:shopId AS text)))) l",
            nativeQuery = true)
    long lockMembers(@Param("shopId") UUID shopId);
}
