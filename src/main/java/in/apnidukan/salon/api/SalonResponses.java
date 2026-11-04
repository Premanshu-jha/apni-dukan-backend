package in.apnidukan.salon.api;

import in.apnidukan.salon.domain.SalonServiceItem;
import in.apnidukan.salon.domain.SalonSettings;
import in.apnidukan.salon.domain.SalonStaff;
import in.apnidukan.salon.domain.SalonTime;
import in.apnidukan.salon.domain.ServiceDetails;
import in.apnidukan.salon.domain.Shift;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** What the salon setup endpoints return. Each carries a version to send back with the next edit. */
public final class SalonResponses {

    private SalonResponses() {
    }

    public record ServiceResponse(UUID id, String name, int durationMinutes, long pricePaise, boolean priceIsFrom,
                                  boolean active, int sortOrder, long version, Instant createdAt, Instant updatedAt) {

        public static ServiceResponse from(SalonServiceItem item) {
            ServiceDetails d = item.details();
            return new ServiceResponse(item.getId(), d.name(), d.durationMin(), d.pricePaise(), d.priceIsFrom(),
                    d.active(), d.sortOrder(), item.getVersion(), item.getCreatedAt(), item.getUpdatedAt());
        }
    }

    /**
     * A barber with the services he does and his week.
     * @param hours sorted by day, then start time
     * @param loginPhone the phone of his own sign-in, or null if he has none
     * @param photoFileId his photo (open it with GET /files/{id}/link), or null
     */
    public record StaffResponse(UUID id, String name, boolean active, List<UUID> serviceIds, List<ShiftOut> hours,
                                String loginPhone, UUID photoFileId, long version, Instant createdAt,
                                Instant updatedAt) {

        public static StaffResponse from(SalonStaff staff, List<UUID> serviceIds, List<Shift> shifts,
                                         String loginPhone) {
            List<ShiftOut> hours = shifts.stream()
                    .sorted(Comparator.comparingInt(Shift::weekday).thenComparingInt(Shift::startMin))
                    .map(ShiftOut::from)
                    .toList();
            return new StaffResponse(staff.getId(), staff.getName(), staff.isActive(), serviceIds, hours, loginPhone,
                    staff.getPhotoFileId(), staff.getVersion(), staff.getCreatedAt(), staff.getUpdatedAt());
        }
    }

    /** A block of working hours, times as "HH:mm" in salon time. */
    public record ShiftOut(int weekday, String start, String end) {

        static ShiftOut from(Shift shift) {
            return new ShiftOut(shift.weekday(), SalonTime.format(shift.startMin()), SalonTime.format(shift.endMin()));
        }
    }

    /**
     * @param version   null while the defaults are in use (nothing saved yet)
     * @param updatedAt null while the defaults are in use
     */
    public record SettingsResponse(int minLeadMinutes, int maxDaysAhead, Long version, Instant updatedAt) {

        public static SettingsResponse from(SalonSettings settings) {
            return new SettingsResponse(settings.getMinLeadMin(), settings.getMaxDaysAhead(), settings.getVersion(),
                    settings.getVersion() == null ? null : settings.getUpdatedAt());
        }
    }
}
