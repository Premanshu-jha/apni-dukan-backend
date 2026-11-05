package in.apnidukan.shops.domain;

/**
 * Where a shop stands for the field team (the admin website's dashboard and lists).
 * FIRST_VISIT: waiting for its first visit. RECHECK: waiting for a visit after it was approved before (the owner
 * moved the pin or changed a photo, or it was reinstated). SENT_BACK: a draft the field team sent back with a
 * reason. LIVE: approved. SUSPENDED: off the app. A draft the owner hasn't submitted yet is in no group.
 */
public enum ReviewGroup {
    FIRST_VISIT,
    RECHECK,
    SENT_BACK,
    LIVE,
    SUSPENDED;

    /** Null for a draft that was never sent back (registration not finished). */
    public static ReviewGroup of(ShopStatus status, String statusReason, boolean everApproved) {
        return switch (status) {
            case PENDING_VISIT -> everApproved ? RECHECK : FIRST_VISIT;
            case DRAFT -> statusReason == null ? null : SENT_BACK;
            case APPROVED -> LIVE;
            case SUSPENDED -> SUSPENDED;
        };
    }
}
