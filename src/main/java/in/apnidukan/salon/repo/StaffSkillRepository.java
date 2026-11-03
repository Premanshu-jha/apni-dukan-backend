package in.apnidukan.salon.repo;

import in.apnidukan.salon.domain.StaffSkill;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface StaffSkillRepository extends JpaRepository<StaffSkill, UUID> {

    List<StaffSkill> findByStaffId(UUID staffId);

    /** For the barbers list: everyone's services in one query. */
    List<StaffSkill> findByStaffIdIn(Collection<UUID> staffIds);
}
