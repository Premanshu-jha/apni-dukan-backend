package in.apnidukan.shops.domain;

/**
 * Where a shop stands for the field team (the admin website's dashboard and lists).
 * FIRST_VISIT: waiting for its first visit. RECHECK: waiting for a visit after it was approved before (a live shop
 * whose owner changed what was checked, which stays live meanwhile; or a reinstated one). SENT_BACK: a draft the field team sent back with a
 * reason. LIVE: approved. SUSPENDED: off the app. CLOSED: closed by its owner for good. A draft the owner hasn't
 * submitted yet is in no group.
 */
public enum ReviewGroup {
    FIRST_VISIT,
    RECHECK,
    SENT_BACK,
    LIVE,
    SUSPENDED,
    CLOSED;

    /** Null for a draft that was never sent back (registration not finished). */
    public static ReviewGroup of(ShopStatus status, String statusReason, boolean everApproved, boolean rechecking) {
        return switch (status) {
            case PENDING_VISIT -> everApproved ? RECHECK : FIRST_VISIT;
            case DRAFT -> statusReason == null ? null : SENT_BACK;
            case APPROVED -> rechecking ? RECHECK : LIVE;
            case SUSPENDED -> SUSPENDED;
            case CLOSED -> CLOSED;
        };
    }
}
