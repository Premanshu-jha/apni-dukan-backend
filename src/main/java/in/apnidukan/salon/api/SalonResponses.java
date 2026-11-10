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

    /**
     * A service as the salon sets it up.
     * @param returnAfterDays days after a visit the customer is reminded to come back for it; 0 = no reminder
     */
    public record ServiceResponse(UUID id, String name, int durationMinutes, long pricePaise, boolean priceIsFrom,
                                  boolean active, int sortOrder, int returnAfterDays, long version, Instant createdAt,
                                  Instant updatedAt) {

        public static ServiceResponse from(SalonServiceItem item) {
            ServiceDetails d = item.details();
            return new ServiceResponse(item.getId(), d.name(), d.durationMin(), d.pricePaise(), d.priceIsFrom(),
                    d.active(), d.sortOrder(), d.returnAfterDays(), item.getVersion(), item.getCreatedAt(),
                    item.getUpdatedAt());
        }
    }

    /**
     * A barber with the services he does and his week.
     * @param hours sorted by day, then start time
     * @param loginPhone the phone of his own sign-in, or null if he has none
     * @param photoFileId his photo (open it with GET /files/{id}/link), or null
     * @param invitedPhone the phone invited to sign in as him, while that invitation waits (owner only), else null
     */
    public record StaffResponse(UUID id, String name, boolean active, List<UUID> serviceIds, List<ShiftOut> hours,
                                String loginPhone, UUID photoFileId, long version, Instant createdAt,
                                Instant updatedAt, String invitedPhone) {

        public static StaffResponse from(SalonStaff staff, List<UUID> serviceIds, List<Shift> shifts,
                                         String loginPhone, String invitedPhone) {
            List<ShiftOut> hours = shifts.stream()
                    .sorted(Comparator.comparingInt(Shift::weekday).thenComparingInt(Shift::startMin))
                    .map(ShiftOut::from)
                    .toList();
            return new StaffResponse(staff.getId(), staff.getName(), staff.isActive(), serviceIds, hours, loginPhone,
                    staff.getPhotoFileId(), staff.getVersion(), staff.getCreatedAt(), staff.getUpdatedAt(),
                    invitedPhone);
        }
    }

    /** A block of working hours, times as "HH:mm" in salon time. */
    public record ShiftOut(int weekday, String start, String end) {

        static ShiftOut from(Shift shift) {
            return new ShiftOut(shift.weekday(), SalonTime.format(shift.startMin()), SalonTime.format(shift.endMin()));
        }
    }

    /**
     * The salon's opening hours.
     * @param version send it back with PUT /salon/hours; null while none are set
     * @param days    the open days, Monday first; a weekday missing is closed. Empty while none are set
     */
    public record HoursResponse(String version, List<BookingResponses.DayHours> days) {

        public HoursResponse {
            days = List.copyOf(days);
        }
    }

    /**
     * A barber whose hours fall outside the salon's new hours (409 STAFF_HOURS_OUTSIDE lists them).
     */
    public record StaffOutside(UUID staffId, String name) {
    }

    /**
     * A common service the owner can tick at registration, with a suggested time and come-back interval; the owner
     * sets the price (and may change the rest).
     */
    public record CatalogService(String name, int durationMinutes, int returnAfterDays) {
    }

    /**
     * The salon's booking settings.
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
