package in.apnidukan.notifications.domain;

/** What happened to a queued message. */
public enum JobStatus {
    /** Waiting to be sent (or to be retried). */
    PENDING,
    /** Delivered to at least one of the person's devices. */
    SENT,
    /**
     * Not delivered: every try failed (4 tries over about 21 minutes), it was claimed 8 times without any result,
     * or (SMS) the daily booking-SMS limit for the person or for everyone was reached.
     */
    FAILED,
    /** The subject changed (booking cancelled or moved) before it went out. */
    CANCELLED,
    /**
     * Nowhere to send it: Web Push, the person has no device that accepts notifications; SMS, the type's template
     * was removed from the settings meanwhile, or the person no longer exists.
     */
    NO_DEVICE
}
