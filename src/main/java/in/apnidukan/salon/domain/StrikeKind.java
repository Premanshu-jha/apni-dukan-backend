package in.apnidukan.salon.domain;

/** Why a customer got a strike. Three in 60 days stop online booking for 30 days. */
public enum StrikeKind {
    /** Didn't come and the salon marked No-show (15 minutes after the start at the earliest). */
    NO_SHOW,
    /** Cancelled less than 1 hour before the start. */
    LATE_CANCEL
}
