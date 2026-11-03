package in.apnidukan.salon.repo;

import in.apnidukan.salon.domain.SalonSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Keyed by the salon's shop id. */
public interface SalonSettingsRepository extends JpaRepository<SalonSettings, UUID> {
}
