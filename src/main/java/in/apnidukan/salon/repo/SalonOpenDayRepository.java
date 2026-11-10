package in.apnidukan.salon.repo;

import in.apnidukan.salon.domain.SalonOpenDay;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SalonOpenDayRepository extends JpaRepository<SalonOpenDay, UUID> {

    List<SalonOpenDay> findByShopIdOrderByWeekdayAsc(UUID shopId);

    boolean existsByShopId(UUID shopId);
}
