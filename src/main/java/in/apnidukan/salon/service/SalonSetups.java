package in.apnidukan.salon.service;

import in.apnidukan.salon.domain.BookedService;
import in.apnidukan.salon.domain.SalonStaff;
import in.apnidukan.salon.domain.Shift;
import in.apnidukan.salon.domain.StaffShift;
import in.apnidukan.salon.domain.StaffSkill;
import in.apnidukan.salon.repo.SalonServiceItemRepository;
import in.apnidukan.salon.repo.SalonStaffRepository;
import in.apnidukan.salon.repo.SalonVersionStore;
import in.apnidukan.salon.repo.StaffShiftRepository;
import in.apnidukan.salon.repo.StaffSkillRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A salon's setup as customers' pages use it (its active barbers with their services and weekly hours, and its
 * active services), kept in memory per server for the salons asked about most recently. Customers check free times
 * far more often than a salon changes its setup: each check then costs one primary-key read (salon_versions) instead
 * of four queries, and only the barbers' busy time of that day is read fresh.
 *
 * An entry is used only while the salon's setup_version is the one it was read under. That number moves (by a
 * database trigger) with every change to barbers, their hours or services, or the services, and is never given out
 * twice, so each server can keep its own copy safely: a change on any server is seen by all at their next read.
 * Bookings coming in don't move it (they move only salon_versions.version), so they don't throw the entry away.
 */
@Component
public class SalonSetups {

    /** An active barber: when he was added (ties in who is offered first), what he does and his week. */
    public record Barber(UUID id, Instant addedAt, Set<UUID> serviceIds, List<Shift> week) {

        public Barber {
            serviceIds = Set.copyOf(serviceIds);
            week = List.copyOf(week);
        }
    }

    /**
     * @param barbers  active barbers, oldest first
     * @param services active services in the salon's order (sort order, then name), as a booking copies them
     */
    public record Setup(long version, List<Barber> barbers, List<BookedService> services) {

        public Setup {
            barbers = List.copyOf(barbers);
            services = List.copyOf(services);
        }
    }

    private final SalonVersionStore versions;
    private final SalonStaffRepository staff;
    private final StaffSkillRepository skills;
    private final StaffShiftRepository shifts;
    private final SalonServiceItemRepository services;
    private final Map<UUID, Setup> cache;

    public SalonSetups(SalonVersionStore versions, SalonStaffRepository staff, StaffSkillRepository skills,
                       StaffShiftRepository shifts, SalonServiceItemRepository services,
                       @Value("${app.salon.setup-cache-size:2000}") int capacity) {
        this.versions = versions;
        this.staff = staff;
        this.skills = skills;
        this.shifts = shifts;
        this.services = services;
        this.cache = java.util.Collections.synchronizedMap(new Lru(Math.max(1, capacity)));
    }

    /** The least recently used salon goes when the map is full. */
    private static final class Lru extends LinkedHashMap<UUID, Setup> {
        private static final long serialVersionUID = 1L;
        private final int capacity;

        Lru(int capacity) {
            super(64, 0.75f, true);
            this.capacity = capacity;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Setup> eldest) {
            return size() > capacity;
        }
    }

    /** The salon's setup now: from memory while unchanged, else read again (four queries). */
    public Setup of(UUID shopId) {
        long version = versions.find(shopId).setupVersion();   // first, so a change after it is never kept under it
        Setup kept = cache.get(shopId);
        if (kept != null && kept.version() == version) {
            return kept;
        }
        Setup fresh = load(shopId, version);
        cache.put(shopId, fresh);
        return fresh;
    }

    private Setup load(UUID shopId, long version) {
        List<SalonStaff> active = staff.findByShopIdAndActiveTrueOrderByCreatedAtAscIdAsc(shopId);
        List<UUID> ids = active.stream().map(SalonStaff::getId).toList();
        Map<UUID, Set<UUID>> does = skills.findByStaffIdIn(ids).stream()
                .collect(Collectors.groupingBy(StaffSkill::getStaffId,
                        Collectors.mapping(StaffSkill::getServiceId, Collectors.toSet())));
        Map<UUID, List<Shift>> weeks = shifts.findByStaffIdIn(ids).stream()
                .collect(Collectors.groupingBy(StaffShift::getStaffId,
                        Collectors.mapping(StaffShift::shift, Collectors.toList())));
        List<Barber> barbers = active.stream().map(b -> new Barber(b.getId(), b.getCreatedAt(),
                does.getOrDefault(b.getId(), Set.of()), weeks.getOrDefault(b.getId(), List.of()))).toList();
        List<BookedService> offered = services.findByShopIdAndActiveTrueOrderBySortOrderAscNameAsc(shopId).stream()
                .map(s -> new BookedService(s.getId(), s.details().name(), s.details().durationMin(),
                        s.details().pricePaise(), s.details().priceIsFrom()))
                .toList();
        return new Setup(version, barbers, offered);
    }
}
