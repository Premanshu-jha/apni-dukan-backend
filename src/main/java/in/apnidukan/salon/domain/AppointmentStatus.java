package in.apnidukan.salon.domain;

import java.util.List;

/**
 * Where an appointment is in its life. Only CONFIRMED, IN_SERVICE and BLOCKED hold the barber's time; every other
 * status frees it at once (that is how a cancel or a no-show reopens the slot).
 * <pre>
 * BOOKING / WALK_IN:  CONFIRMED → IN_SERVICE (Start) → COMPLETED (by itself at its end time)
 *                     CONFIRMED → NO_SHOW | CANCELLED_BY_CUSTOMER | CANCELLED_BY_SALON
 *                     WALK_IN "start now" begins at IN_SERVICE
 * BLOCK:              BLOCKED → REMOVED
 * </pre>
 */
public enum AppointmentStatus {
    CONFIRMED,
    IN_SERVICE,
    COMPLETED,
    CANCELLED_BY_CUSTOMER,
    CANCELLED_BY_SALON,
    NO_SHOW,
    BLOCKED,
    REMOVED;

    /** The statuses that hold the barber's time; the same list is in V4's no-overlap rule. */
    public static final List<String> HOLDING = List.of("CONFIRMED", "IN_SERVICE", "BLOCKED");

    public boolean holdsTime() {
        return HOLDING.contains(name());
    }
}
