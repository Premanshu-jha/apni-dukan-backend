package in.apnidukan.salon.domain;

/** What an appointment row is. All three hold a barber's time the same way, so one database rule covers them. */
public enum AppointmentKind {
    /** Booked online by a customer. */
    BOOKING,
    /** Added by the salon for someone who walked in. */
    WALK_IN,
    /** A break, leave or closed day: the barber isn't available. */
    BLOCK
}
