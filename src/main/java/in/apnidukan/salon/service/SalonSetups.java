package in.apnidukan.salon.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import in.apnidukan.salon.domain.BookedService;
import in.apnidukan.salon.domain.SalonOffer;
import in.apnidukan.salon.domain.SalonSettings;
import in.apnidukan.salon.domain.SalonStaff;
import in.apnidukan.salon.domain.SalonTime;
import in.apnidukan.salon.domain.Shift;
import in.apnidukan.salon.domain.StaffShift;
import in.apnidukan.salon.domain.StaffSkill;
import in.apnidukan.salon.repo.SalonOfferStore;
import in.apnidukan.salon.domain.SalonServiceItem;
import in.apnidukan.salon.repo.SalonServiceItemRepository;
import in.apnidukan.salon.repo.SalonSettingsRepository;
import in.apnidukan.salon.repo.SalonStaffRepository;
import in.apnidukan.salon.repo.SalonVersionStore;
import in.apnidukan.salon.repo.StaffShiftRepository;
import in.apnidukan.salon.repo.StaffSkillRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A salon's setup as booking uses it (its active barbers with their names, services and weekly hours, its active
 * services and its booking settings), kept in memory per server for the salons asked about most recently. Customers
 * check free times far more often than a salon changes its setup: each check, booking, move or walk-in then costs one
 * primary-key read (salon_versions, with the pause read in the same query) instead of five queries, and only the
 * barbers' busy time of that day is read fresh.
 *
 * An entry is used only while the salon's setup_version is the one it was read under. That number moves (by a
 * database trigger) with every change to barbers, their hours or services, the services or the settings, and is never
 * given out twice, so each server can keep its own copy safely: a change on any server is seen by all at their next
 * read. Bookings coming in don't move it (they move only salon_versions.version), so they don't throw the entry away.
 *
 * The entries live in a Caffeine cache: bounded by their size (app.salon.setup-cache-weight; the least used salons go
 * first), and read and written without one lock for the whole map, so free-time checks of different salons never
 * queue behind each other.
 */
@Component
public class SalonSetups {

    /**
     * An active barber: when he was added (ties in who is offered first), his name, what he does, his week and his own
     * sign-in (null if he has none: the salon's messages about his bookings go to it).
     */
    public record Barber(UUID id, String name, Instant addedAt, Set<UUID> serviceIds, List<Shift> week, UUID login) {

        public Barber {
            serviceIds = Set.copyOf(serviceIds);
            week = List.copyOf(week);
        }
    }

    /**
     * @param barbers      active barbers, oldest first
     * @param services     active services in the salon's order (sort order, then name), as a booking copies them
     * @param minLeadMin   earliest booking, minutes from now (the salon's settings, or the default)
     * @param maxDaysAhead latest booking, days from today
     * @param offers       its offers not ended early that lasted until the day it was read or later (each says itself
     *                     when it applies: SalonOffer.appliesAt), soonest first
     * @param icons        the active services' icons (ServiceIcon names; a service without one is left out)
     */
    public record Setup(long version, List<Barber> barbers, List<BookedService> services, int minLeadMin,
                        int maxDaysAhead, List<SalonOffer> offers, Map<UUID, String> icons) {

        public Setup {
            barbers = List.copyOf(barbers);
            services = List.copyOf(services);
            offers = List.copyOf(offers);
            icons = Map.copyOf(icons);
        }

        /** The icon of this service, or null. */
        public String icon(UUID serviceId) {
            return icons.get(serviceId);
        }

        /** Its offers that still last until this date or later. */
        public List<SalonOffer> offersFrom(LocalDate date) {
            return offers.stream().filter(o -> !o.endsOn().isBefore(date)).toList();
        }

        /** An active barber of this salon, if he is one. */
        public Optional<Barber> barber(UUID staffId) {
            return barbers.stream().filter(b -> b.id().equals(staffId)).findFirst();
        }
    }

    /**
     * The setup and the pause, read together.
     * @param pausedUntil set while online bookings are (or were) paused; it may have run out already
     * @param shopId      the salon
     * @param version     the salon's change counter when this was read (salon_versions.version): the barbers' busy
     *                    time kept in memory (SalonCalendar) is used only while it is the same
     */
    public record Current(Setup setup, Instant pausedUntil, UUID shopId, long version) {

        public boolean pausedAt(Instant now) {
            return pausedUntil != null && pausedUntil.isAfter(now);
        }
    }

    private final SalonVersionStore versions;
    private final SalonStaffRepository staff;
    private final StaffSkillRepository skills;
    private final StaffShiftRepository shifts;
    private final SalonServiceItemRepository services;
    private final SalonSettingsRepository settings;
    private final SalonOfferStore offers;
    private final Clock clock;
    private final Cache<UUID, Setup> cache;

    public SalonSetups(SalonVersionStore versions, SalonStaffRepository staff, StaffSkillRepository skills,
                       StaffShiftRepository shifts, SalonServiceItemRepository services,
                       SalonSettingsRepository settings, SalonOfferStore offers, Clock clock,
                       @Value("${app.salon.setup-cache-weight:2000000}") long maxWeight) {
        if (maxWeight < 1) {
            throw new IllegalStateException("app.salon.setup-cache-weight must be at least 1");
        }
        this.versions = versions;
        this.staff = staff;
        this.skills = skills;
        this.shifts = shifts;
        this.services = services;
        this.settings = settings;
        this.offers = offers;
        this.clock = clock;
        // Bounded by size, not by count: a 50-barber salon with 100 services weighs as much as dozens of small ones
        this.cache = Caffeine.newBuilder().maximumWeight(maxWeight).weigher(SalonSetups::weight).build();
    }

    /**
     * About how much memory an entry takes, in units of one barber, service or block of weekly hours (each well under
     * 100 bytes): 1 + barbers + their services + their hours + the salon's services. The default 2,000,000 holds about
     * 50,000 typical salons (some 40 units each) in about 100 MB per server.
     */
    static int weight(UUID shopId, Setup setup) {
        long units = 1L + setup.services().size() + setup.offers().size();
        for (Barber b : setup.barbers()) {
            units += 1L + b.serviceIds().size() + b.week().size();
        }
        return (int) Math.min(Integer.MAX_VALUE, units);
    }

    /** The salon's setup now: from memory while unchanged, else read again. */
    public Setup of(UUID shopId) {
        return current(shopId).setup();
    }

    /**
     * The setup and the pause: one query (the counters and the pause together), plus the setup's own reads only when
     * it changed since this server last read it.
     */
    public Current current(UUID shopId) {
        // First, so a change after it is never kept under it
        SalonVersionStore.Now now = versions.findWithPause(shopId);
        long version = now.versions().setupVersion();
        Setup kept = cache.getIfPresent(shopId);
        if (kept == null || kept.version() != version) {
            kept = load(shopId, version);
            cache.put(shopId, kept);
        }
        return new Current(kept, now.pausedUntil(), shopId, now.versions().version());
    }

    /** The offers that still cover some active service (one whose services were all switched off since offers none). */
    static List<SalonOffer> stillOn(List<SalonOffer> all, List<BookedService> active) {
        Set<UUID> on = active.stream().map(BookedService::serviceId).collect(Collectors.toSet());
        return all.stream().filter(o -> o.allServices() || o.serviceIds().stream().anyMatch(on::contains)).toList();
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
        List<Barber> barbers = active.stream().map(b -> new Barber(b.getId(), b.getName(), b.getCreatedAt(),
                does.getOrDefault(b.getId(), Set.of()), weeks.getOrDefault(b.getId(), List.of()), b.getUserId()))
                .toList();
        List<SalonServiceItem> activeServices = services.findByShopIdAndActiveTrueOrderBySortOrderAscNameAsc(shopId);
        List<BookedService> offered = activeServices.stream()
                .map(s -> new BookedService(s.getId(), s.details().name(), s.details().durationMin(),
                        s.details().pricePaise(), s.details().priceIsFrom()))
                .toList();
        Map<UUID, String> icons = new java.util.HashMap<>();
        activeServices.stream().filter(s -> s.details().icon() != null)
                .forEach(s -> icons.put(s.getId(), s.details().icon().name()));
        Optional<SalonSettings> saved = settings.findById(shopId);
        // From yesterday: a server's clock a moment behind the database's day still has today's
        LocalDate yesterday = LocalDate.ofInstant(clock.instant(), SalonTime.ZONE).minusDays(1);
        return new Setup(version, barbers, offered,
                saved.map(SalonSettings::getMinLeadMin).orElse(SalonSettings.DEFAULT_MIN_LEAD_MIN),
                saved.map(SalonSettings::getMaxDaysAhead).orElse(SalonSettings.DEFAULT_MAX_DAYS_AHEAD),
                stillOn(offers.current(shopId, yesterday), offered), icons);
    }
}
