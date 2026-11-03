package in.apnidukan.salon.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Request bodies for salon setup. Unknown fields are refused (400 UNKNOWN_FIELD). Names are cleaned and their
 * length checked again after cleaning in SalonSetupService; times ("10:00") are checked there too.
 * In edits, leave a field out (or null) to keep it.
 */
public final class SalonRequests {

    private SalonRequests() {
    }

    /**
     * Add a service.
     * @param durationMinutes 5 to 480, in steps of 5; include any clean-up time
     * @param pricePaise      whole paise, ₹150 = 15000; up to ₹1,00,000
     * @param priceIsFrom     optional, false if left out; true shows "from ₹150"
     * @param sortOrder       optional, 0 if left out; lower comes first in the list
     */
    public record CreateService(
            @NotBlank @Size(min = 2, max = 60) String name,
            @NotNull @Min(5) @Max(480) Integer durationMinutes,
            @NotNull @Min(0) @Max(10_000_000) Long pricePaise,
            Boolean priceIsFrom,
            @Min(0) @Max(999) Integer sortOrder) {
    }

    /**
     * Edit a service, or switch it off and on with active.
     * @param version the version you last read; if someone saved in between you get 409 SERVICE_CHANGED
     */
    public record UpdateService(
            @NotNull Long version,
            @Size(min = 2, max = 60) String name,
            @Min(5) @Max(480) Integer durationMinutes,
            @Min(0) @Max(10_000_000) Long pricePaise,
            Boolean priceIsFrom,
            Boolean active,
            @Min(0) @Max(999) Integer sortOrder) {
    }

    /**
     * Add a barber.
     * @param phone optional: his 10-digit mobile number, to give him his own sign-in (he sees his own day). Can be
     *              linked later with PUT /salon/staff/{id}/login.
     */
    public record CreateStaff(@NotBlank @Size(min = 2, max = 60) String name, @Size(max = 20) String phone) {
    }

    /**
     * Rename a barber, or switch him off (left) and on.
     * @param version the version you last read; if someone saved in between you get 409 STAFF_CHANGED
     */
    public record UpdateStaff(
            @NotNull Long version,
            @Size(min = 2, max = 60) String name,
            Boolean active) {
    }

    /**
     * Every service this barber does; replaces the list. Empty = none. Up to 200 ids: a barber can keep services
     * that were switched off after he got them, on top of up to 100 active ones.
     * @param version the barber's version you last read; if someone saved in between you get 409 STAFF_CHANGED
     */
    public record SetServices(
            @NotNull Long version,
            @NotNull @Size(max = 200) List<@NotNull UUID> serviceIds) {
    }

    /**
     * The barber's whole week; replaces it. Empty = not working any day. Named hours, like the list in the
     * response, so the app can send back what it shows.
     * @param version the barber's version you last read; if someone saved in between you get 409 STAFF_CHANGED
     */
    public record SetHours(
            @NotNull Long version,
            @NotNull @Size(max = 50) List<@NotNull @Valid ShiftIn> hours) {
    }

    /**
     * One block of working hours.
     * @param weekday 1 = Monday ... 7 = Sunday
     * @param start   "HH:mm" on a 15-minute step, e.g. "10:00"
     * @param end     "HH:mm" after start, up to "24:00"
     */
    public record ShiftIn(
            @NotNull @Min(1) @Max(7) Integer weekday,
            @NotBlank String start,
            @NotBlank String end) {
    }

    /**
     * Online booking settings.
     * @param version        the version from GET /salon/settings; null while the defaults are still in use
     * @param minLeadMinutes earliest booking, minutes from now (0 to 1440)
     * @param maxDaysAhead   latest booking, days from today (1 to 30)
     */
    public record UpdateSettings(
            Long version,
            @Min(0) @Max(1440) Integer minLeadMinutes,
            @Min(1) @Max(30) Integer maxDaysAhead) {
    }

    /** A barber's photo: the id of a STAFF_PHOTO upload, or null to remove it. */
    public record StaffPhoto(UUID fileId) {
    }
}
