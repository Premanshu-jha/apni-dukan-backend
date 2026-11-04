package in.apnidukan.salon.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of appointments: a booking, a walk-in or a block. Read and written with plain SQL (AppointmentStore),
 * because the database decides the races here (the no-overlap rule, ON CONFLICT, conditional updates), and every
 * change is logged in appointment_events.
 *
 * @param customerId    bookings only: who booked
 * @param bookingNo     bookings only, e.g. SB7K3Q9X, shown to the customer and the salon
 * @param guestName     walk-ins only, optional
 * @param startCode     bookings only: the 4 digits the customer shows at the salon; never sent to salon screens
 * @param startedWithCode null until started; false when staff used "Start without code"
 * @param closureId     CLOSED blocks only: groups one closed day's rows
 * @param createdBy     who made it (the customer, or the salon person who added the walk-in or block)
 */
public record Appointment(UUID id, UUID shopId, UUID staffId, AppointmentKind kind, BlockType blockType,
                          UUID closureId, UUID customerId, String bookingNo, String guestName,
                          String startCode, Instant startsAt, Instant endsAt, AppointmentStatus status,
                          Instant startedAt, Boolean startedWithCode, String cancelReason, UUID createdBy,
                          UUID clientRequestId, Instant createdAt, Instant updatedAt) {

    public Interval time() {
        return new Interval(startsAt, endsAt);
    }

    public boolean isBooking() {
        return kind == AppointmentKind.BOOKING;
    }
}
