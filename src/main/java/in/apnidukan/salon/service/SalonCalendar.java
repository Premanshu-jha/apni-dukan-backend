package in.apnidukan.salon.service;

import in.apnidukan.common.error.ApiException;
import in.apnidukan.salon.domain.Appointment;
import in.apnidukan.salon.domain.AppointmentKind;
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
import in.apnidukan.salon.repo.SalonServiceItemRepository;
import in.apnidukan.salon.repo.SalonSettingsRepository;
import in.apnidukan.salon.repo.SalonStaffRepository;
import in.apnidukan.salon.repo.StaffShiftRepository;
import in.apnidukan.salon.repo.StaffSkillRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
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

    public SalonCalendar(SalonServiceItemRepository services, SalonStaffRepository staff,
                         StaffSkillRepository skills, StaffShiftRepository shifts, SalonSettingsRepository settings,
                         AppointmentStore appointments) {
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
        List<SalonStaff> active = staff.findByShopIdOrderByCreatedAtAscIdAsc(shopId).stream()
                .filter(SalonStaff::isActive)
                .toList();
        Map<UUID, Set<UUID>> doesServices = skills.findByStaffIdIn(active.stream().map(SalonStaff::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(StaffSkill::getStaffId,
                        Collectors.mapping(StaffSkill::getServiceId, Collectors.toSet())));
        List<SalonStaff> able = active.stream()
                .filter(b -> doesServices.getOrDefault(b.getId(), Set.of()).containsAll(serviceIds))
                .toList();
        if (able.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_BARBER_FOR_SERVICES",
                    "No barber here does all of these services together. Try booking them separately.");
        }
        return able;
    }

    /** Each barber's day on this date (busy time leaves out {@code except}, the appointment being moved). */
    public List<BarberDay> days(List<SalonStaff> barbers, LocalDate date, UUID except) {
        Instant from = Availability.dayStart(date);
        Instant to = Availability.dayStart(date.plusDays(1));
        List<UUID> ids = barbers.stream().map(SalonStaff::getId).toList();
        Map<UUID, List<Shift>> weeks = shifts.findByStaffIdIn(ids).stream()
                .collect(Collectors.groupingBy(StaffShift::getStaffId,
                        Collectors.mapping(StaffShift::shift, Collectors.toList())));
        Map<UUID, List<Appointment>> busy = appointments.holding(ids, from, to, except).stream()
                .collect(Collectors.groupingBy(Appointment::staffId));
        return barbers.stream().map(b -> {
            List<Appointment> mine = busy.getOrDefault(b.getId(), List.of());
            long booked = mine.stream()
                    .filter(a -> a.kind() != AppointmentKind.BLOCK)
                    .mapToLong(a -> a.time().length().toMinutes())
                    .sum();
            return new BarberDay(b.getId(), Availability.working(weeks.getOrDefault(b.getId(), List.of()), date),
                    mine.stream().map(Appointment::time).toList(), booked, b.getCreatedAt());
        }).toList();
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
        LocalDate today = LocalDate.ofInstant(now, SalonTime.ZONE);
        return new Interval(now.plus(Duration.ofMinutes(s.getMinLeadMin())),
                Availability.dayStart(today.plusDays(s.getMaxDaysAhead() + 1L)));
    }

    static ApiException invalid(String field, String problem) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid",
                Map.of(field, problem));
    }
}
