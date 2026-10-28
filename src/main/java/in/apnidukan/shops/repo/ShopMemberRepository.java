package in.apnidukan.shops.repo;

import in.apnidukan.shops.domain.ShopMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ShopMemberRepository extends JpaRepository<ShopMember, UUID> {

    Optional<ShopMember> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);
}
