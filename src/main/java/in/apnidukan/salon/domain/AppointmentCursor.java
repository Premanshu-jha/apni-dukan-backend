package in.apnidukan.salon.domain;

import in.apnidukan.common.error.ApiException;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Where a page of appointments ended: the last one's start and id. Lists are ordered by both, so the next page is
 * everything after this pair (keyset paging): each page is one index range, however deep, and an appointment
 * starting at the same moment as the page's last one is never skipped or shown twice.
 * Sent back by the apps as `afterStart` and `afterId`, exactly as they came in the last item (or in `next`).
 */
public record AppointmentCursor(Instant afterStart, UUID afterId) {

    /**
     * The cursor from the two query values: both (the next page) or neither (the first page, null); one alone is 400
     * VALIDATION_FAILED. A value that isn't a moment or an id never gets here (400 BAD_REQUEST before).
     */
    public static AppointmentCursor of(Instant afterStart, UUID afterId) {
        if ((afterStart == null) != (afterId == null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid",
                    Map.of("afterId", "send afterStart and afterId together"));
        }
        return afterId == null ? null : new AppointmentCursor(afterStart, afterId);
    }

    /** The cursor after this appointment. */
    public static AppointmentCursor after(Appointment a) {
        return new AppointmentCursor(a.startsAt(), a.id());
    }
}
