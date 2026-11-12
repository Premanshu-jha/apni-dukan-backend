package in.apnidukan.shops.domain;

/** An entry in a shop's timeline (shop_events). */
public enum ShopEventType {
    /** The owner sent it for a visit. */
    SUBMITTED,
    /** The owner changed what was checked on an approved shop (address, pin, photos, video, GSTIN, Udyam). */
    EDITED,
    APPROVED,
    SENT_BACK,
    SUSPENDED,
    REINSTATED,
    /** A note from an admin, with no decision. */
    NOTE,
    /** The owner closed the shop for good (reason: theirs, if they gave one). */
    CLOSED,
    /** Another person took the shop over (the owner asked, they accepted). Not a change of status. */
    HANDED_OVER,
    /**
     * The field team asked the owner to fix something on a live shop's re-check (reason: what the owner is told). The
     * shop stays as it is and keeps its place in the queue (like a note, it doesn't move status_since).
     */
    FIX_ASKED
}
