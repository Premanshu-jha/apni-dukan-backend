package in.apnidukan.salon.api;

import in.apnidukan.salon.domain.BlockType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Request bodies for bookings, walk-ins and the salon's daily work. Unknown fields are refused. Times are exact
 * moments in ISO-8601, e.g. "2026-11-10T13:00:00Z" (6:30 PM in India); the app shows them in India time.
 */
public final class BookingRequests {

    private BookingRequests() {
    }

    /**
     * Book online. Send an Idempotency-Key header (a new UUID per booking attempt; the same one when retrying).
     * @param startAt    one of the starts from the availability call
     * @param bookingFor optional: the name of the person it's for, if not you
     */
    public record Book(@NotNull UUID salonId,
                       @NotEmpty @Size(max = 10) List<@NotNull UUID> serviceIds,
                       @NotNull Instant startAt,
                       @Size(max = 60) String bookingFor) {
    }

    /** Move my booking to another start (from the availability call). */
    public record Reschedule(@NotNull Instant startAt) {
    }

    /**
     * Add a walk-in: it starts now with a barber who is free now. Send an Idempotency-Key header.
     * @param name    optional, the customer's name
     */
    public record WalkIn(@NotEmpty @Size(max = 10) List<@NotNull UUID> serviceIds,
                         @NotNull UUID staffId,
                         @Size(max = 60) String name) {
    }

    /**
     * Start an appointment. An online booking needs the customer's 4-digit code, or withoutCode = true (owner or
     * helper only, e.g. the customer's phone is dead). Walk-ins need neither.
     */
    public record Start(@Pattern(regexp = "[0-9]{4}") String code, Boolean withoutCode) {
    }

    /** Give the appointment to another barber, same time. */
    public record Reassign(@NotNull UUID staffId) {
    }

    /** The salon cancels a booking or a walk-in; the reason is sent to the customer. */
    public record SalonCancel(@NotBlank @Size(min = 2, max = 200) String reason) {
    }

    /** A break (owner, helper, or the barber himself) or leave (owner only). */
    public record AddBlock(@NotNull BlockType type, @NotNull Instant startAt, @NotNull Instant endAt) {
    }

    /** Close the whole salon for a day (India date). */
    public record AddClosure(@NotNull LocalDate date) {
    }

    /** Pause online bookings; until left out = the end of today. */
    public record Pause(Instant until) {
    }

    /** Give or take back walk-in access. */
    public record WalkInAccess(@NotNull Boolean allowed) {
    }

    /** Link a barber's sign-in: his 10-digit mobile number. He then signs in with it as usual. */
    public record LinkLogin(@NotBlank @Size(max = 20) String phone) {
    }
}
