package in.apnidukan.salon.service;

import in.apnidukan.common.error.ApiException;
import in.apnidukan.salon.domain.Availability;
import in.apnidukan.salon.domain.Availability.BarberDay;
import in.apnidukan.salon.domain.BookedService;
import in.apnidukan.salon.domain.Interval;
import in.apnidukan.salon.domain.SalonServiceItem;
import in.apnidukan.salon.domain.SalonSettings;
import in.apnidukan.salon.domain.SalonStaff;
import in.apnidukan.salon.domain.SalonTime;
import in.apnidukan.salon.domain.Shift;
import in.apnidukan.salon.domain.StaffShift;
import in.apnidukan.salon.domain.StaffSkill;
import in.apnidukan.salon.repo.AppointmentStore;
import in.apnidukan.salon.repo.AppointmentStore.Busy;
import in.apnidukan.salon.repo.SalonVersionStore;
import in.apnidukan.salon.repo.SalonServiceItemRepository;
import in.apnidukan.salon.repo.SalonSettingsRepository;
import in.apnidukan.salon.repo.SalonStaffRepository;
import in.apnidukan.salon.repo.StaffShiftRepository;
import in.apnidukan.salon.repo.StaffSkillRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Loads what the time arithmetic ({@link Availability}) needs from the database: the chosen services, the barbers
 * who do all of them, and each barber's day (working hours, busy time, booked minutes). Shared by online booking
 * and the salon's own screens, so both always see the same picture.
 */
@Component
public class SalonCalendar {

    /** Services in one booking or walk-in. */
    static final int MAX_SERVICES_PER_VISIT = 10;

    private final SalonServiceItemRepository services;
    private final SalonStaffRepository staff;
    private final StaffSkillRepository skills;
    private final StaffShiftRepository shifts;
    private final SalonSettingsRepository settings;
    private final AppointmentStore appointments;
    private final SalonVersionStore versions;
    private final SalonSetups setups;
    /** Each salon's busy time per day, under the change counter it was read with (see setupDays(Current, ...)). */
    private final Cache<String, DayBusy> busyByDay;

    /** One salon's busy time on one day, by barber. */
    private record DayBusy(long version, Map<UUID, List<Busy>> byBarber, int rows) {
    }

    public SalonCalendar(SalonServiceItemRepository services, SalonStaffRepository staff,
                         StaffSkillRepository skills, StaffShiftRepository shifts, SalonSettingsRepository settings,
                         AppointmentStore appointments, SalonVersionStore versions, SalonSetups setups,
                         @Value("${app.salon.busy-cache-rows:500000}") long busyCacheRows) {
        if (busyCacheRows < 1) {
            throw new IllegalStateException("app.salon.busy-cache-rows must be at least 1");
        }
        this.versions = versions;
        this.setups = setups;
        // Bounded by the rows kept (a 50-barber salon's day weighs its 2,000 rows), and days not asked about for an
        // hour go (yesterday's)
        this.busyByDay = Caffeine.newBuilder().maximumWeight(busyCacheRows)
                .weigher((String key, DayBusy day) -> 1 + day.rows())
                .expireAfterAccess(Duration.ofHours(1)).build();
        this.services = services;
        this.staff = staff;
        this.skills = skills;
        this.shifts = shifts;
        this.settings = settings;
        this.appointments = appointments;
    }

    /**
     * The chosen services, in the order chosen, copied for the appointment. Each must be an active service of this
     * salon; the same one twice counts once. 400 naming the field otherwise.
     */
    public List<BookedService> chosenServices(UUID shopId, List<UUID> serviceIds) {
        Set<UUID> ids = new LinkedHashSet<>(serviceIds);
        if (ids.isEmpty() || ids.size() > MAX_SERVICES_PER_VISIT) {
            throw invalid("serviceIds", "choose 1 to " + MAX_SERVICES_PER_VISIT + " services");
        }
        Map<UUID, SalonServiceItem> found = services.findAllById(ids).stream()
                .filter(s -> s.getShopId().equals(shopId) && s.isActive())
                .collect(Collectors.toMap(SalonServiceItem::getId, Function.identity()));
        List<BookedService> out = new ArrayList<>();
        for (UUID id : ids) {
            SalonServiceItem s = found.get(id);
            if (s == null) {
                throw invalid("serviceIds", "has a service this salon doesn't offer (or switched off): " + id);
            }
            out.add(new BookedService(s.getId(), s.details().name(), s.details().durationMin(),
                    s.details().pricePaise(), s.details().priceIsFrom()));
        }
        return out;
    }

    public static Duration length(List<BookedService> chosen) {
        return Duration.ofMinutes(chosen.stream().mapToInt(BookedService::durationMin).sum());
    }

    /**
     * Active barbers of the salon who do every one of these services, oldest first.
     * 409 NO_BARBER_FOR_SERVICES if none does them all (the app then suggests splitting the visit).
     */
    public List<SalonStaff> barbersFor(UUID shopId, Collection<UUID> serviceIds) {
        List<SalonStaff> active = staff.findByShopIdAndActiveTrueOrderByCreatedAtAscIdAsc(shopId);
        Map<UUID, Set<UUID>> doesServices = skills.findByStaffIdIn(active.stream().map(SalonStaff::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(StaffSkill::getStaffId,
                        Collectors.mapping(StaffSkill::getServiceId, Collectors.toSet())));
        List<SalonStaff> able = active.stream()
                .filter(b -> doesServices.getOrDefault(b.getId(), Set.of()).containsAll(serviceIds))
                .toList();
        if (able.isEmpty()) {
            throw noBarberForServices();
        }
        return able;
    }

    /** barbersFor() from the salon's setup kept in memory (SalonSetups): the same barbers, the same 409. */
    public List<SalonSetups.Barber> barbersFor(SalonSetups.Setup setup, Collection<UUID> serviceIds) {
        List<SalonSetups.Barber> able = setup.barbers().stream()
                .filter(b -> b.serviceIds().containsAll(serviceIds))
                .toList();
        if (able.isEmpty()) {
            throw noBarberForServices();
        }
        return able;
    }

    /** chosenServices() from the salon's setup kept in memory (SalonSetups): the same checks and 400s. */
    public List<BookedService> chosenServices(SalonSetups.Setup setup, List<UUID> serviceIds) {
        Set<UUID> ids = new LinkedHashSet<>(serviceIds);
        if (ids.isEmpty() || ids.size() > MAX_SERVICES_PER_VISIT) {
            throw invalid("serviceIds", "choose 1 to " + MAX_SERVICES_PER_VISIT + " services");
        }
        Map<UUID, BookedService> offered = setup.services().stream()
                .collect(Collectors.toMap(BookedService::serviceId, Function.identity()));
        List<BookedService> out = new ArrayList<>();
        for (UUID id : ids) {
            BookedService s = offered.get(id);
            if (s == null) {
                throw invalid("serviceIds", "has a service this salon doesn't offer (or switched off): " + id);
            }
            out.add(s);
        }
        return out;
    }

    private static ApiException noBarberForServices() {
        return new ApiException(HttpStatus.CONFLICT, "NO_BARBER_FOR_SERVICES",
                "No barber here does all of these services together. Try booking them separately.");
    }

    /** Each barber's day on this date (busy time leaves out {@code except}, the appointment being moved). */
    public List<BarberDay> days(List<SalonStaff> barbers, LocalDate date, UUID except) {
        List<UUID> ids = barbers.stream().map(SalonStaff::getId).toList();
        Map<UUID, List<Shift>> weeks = shifts.findByStaffIdIn(ids).stream()
                .collect(Collectors.groupingBy(StaffShift::getStaffId,
                        Collectors.mapping(StaffShift::shift, Collectors.toList())));
        return setupDays(barbers.stream().map(b -> new SalonSetups.Barber(b.getId(), b.getName(), b.getCreatedAt(),
                Set.of(), weeks.getOrDefault(b.getId(), List.of()), b.getUserId())).toList(), date, except);
    }

    /**
     * days() for barbers of the setup kept in memory: their weeks are already there, only the busy time of that day
     * is read.
     */
    public List<BarberDay> setupDays(List<SalonSetups.Barber> barbers, LocalDate date, UUID except) {
        Instant from = Availability.dayStart(date);
        Instant to = Availability.dayStart(date.plusDays(1));
        List<UUID> ids = barbers.stream().map(SalonSetups.Barber::id).toList();
        Map<UUID, List<Busy>> busy = appointments.busy(ids, from, to, except).stream()
                .collect(Collectors.groupingBy(Busy::staffId));
        return build(barbers, date, busy);
    }

    /**
     * setupDays() with nothing being moved, the salon's busy time that day from memory while its change counter is the
     * one {@code current} was read with (it moves at the commit of every appointment change, on any copy of the
     * backend, so an entry is never used after a change). Customers checking free times all day then cost no busy-time
     * read until the next booking, cancel or walk-in. Read after the counter, so a newer change is never kept under an
     * older counter.
     */
    public List<BarberDay> setupDays(SalonSetups.Current current, List<SalonSetups.Barber> barbers, LocalDate date) {
        String key = current.shopId() + "|" + date;
        DayBusy kept = busyByDay.getIfPresent(key);
        if (kept == null || kept.version() != current.version()) {
            List<Busy> rows = appointments.salonBusy(current.shopId(), Availability.dayStart(date),
                    Availability.dayStart(date.plusDays(1)));
            kept = new DayBusy(current.version(), rows.stream().collect(Collectors.groupingBy(Busy::staffId)),
                    rows.size());
            busyByDay.put(key, kept);
        }
        return build(barbers, date, kept.byBarber());
    }

    private static List<BarberDay> build(List<SalonSetups.Barber> barbers, LocalDate date,
                                         Map<UUID, List<Busy>> busy) {
        return barbers.stream().map(b -> {
            List<Busy> mine = busy.getOrDefault(b.id(), List.of());
            long booked = mine.stream()
                    .filter(x -> !x.block())
                    .mapToLong(x -> x.time().length().toMinutes())
                    .sum();
            return new BarberDay(b.id(), Availability.working(b.week(), date),
                    mine.stream().map(Busy::time).sorted(Comparator.comparing(Interval::start)).toList(), booked,
                    b.addedAt());
        }).toList();
    }

    /**
     * After the barber's row is locked (AppointmentStore.lockActiveBarber, which re-checks only that he is active):
     * the times were worked out from the setup kept in memory, read under {@code setupVersion}. New hours or services
     * for him are saved under his row's lock too (SalonSetupService), so now that we hold it, a change made meanwhile
     * has committed and moved setup_version. One primary-key read says whether it did; only then is he checked
     * against the setup as it is now: he must still be active, do these services and (with a slot) work at that time.
     * @param slot the time he would hold, or null to check the services only (a walk-in starting now)
     */
    public boolean stillFits(UUID shopId, long setupVersion, UUID barberId, Collection<UUID> serviceIds,
                             Interval slot) {
        if (versions.find(shopId).setupVersion() == setupVersion) {
            return true;
        }
        return setups.of(shopId).barber(barberId)
                .filter(b -> b.serviceIds().containsAll(serviceIds))
                .filter(b -> slot == null || Availability.working(b.week(),
                        LocalDate.ofInstant(slot.start(), SalonTime.ZONE)).stream().anyMatch(w -> w.contains(slot)))
                .isPresent();
    }

    /** One barber's day. */
    public BarberDay day(SalonStaff barber, LocalDate date, UUID except) {
        return days(List.of(barber), date, except).get(0);
    }

    /** The salon's booking settings, or the defaults if never saved. */
    public SalonSettings settings(UUID shopId, Instant now) {
        return settings.findById(shopId).orElseGet(() -> new SalonSettings(shopId, now));
    }

    /**
     * When online bookings may start: from now + the minimum lead time, up to the end of the last bookable day
     * (today + max days ahead, in India).
     */
    public Interval window(SalonSettings s, Instant now) {
        return window(s.getMinLeadMin(), s.getMaxDaysAhead(), now);
    }

    /** window() with the settings of the setup kept in memory (SalonSetups). */
    public Interval window(SalonSetups.Setup s, Instant now) {
        return window(s.minLeadMin(), s.maxDaysAhead(), now);
    }

    private static Interval window(int minLeadMin, int maxDaysAhead, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, SalonTime.ZONE);
        return new Interval(now.plus(Duration.ofMinutes(minLeadMin)),
                Availability.dayStart(today.plusDays(maxDaysAhead + 1L)));
    }

    static ApiException invalid(String field, String problem) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid",
                Map.of(field, problem));
    }
}
