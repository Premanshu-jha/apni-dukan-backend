package in.apnidukan.salon.repo;

import in.apnidukan.salon.domain.StaffShift;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface StaffShiftRepository extends JpaRepository<StaffShift, UUID> {

    List<StaffShift> findByStaffId(UUID staffId);

    /** For the barbers list: everyone's hours in one query. */
    List<StaffShift> findByStaffIdIn(Collection<UUID> staffIds);
}
