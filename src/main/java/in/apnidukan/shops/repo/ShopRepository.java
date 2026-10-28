package in.apnidukan.shops.repo;

import in.apnidukan.shops.domain.Shop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface ShopRepository extends JpaRepository<Shop, UUID> {

    /** The next shop code (1001, 1002, ...). The database hands each one out once, even under parallel sign-ups. */
    @Query(value = "SELECT nextval('shop_code_seq')", nativeQuery = true)
    long nextShopCode();
}
