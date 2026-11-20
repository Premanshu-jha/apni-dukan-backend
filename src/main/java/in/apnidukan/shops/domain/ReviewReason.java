package in.apnidukan.shops.domain;

/**
 * What the field team asks the owner to fix, picked from a fixed list when they send a shop back, ask for a fix on a
 * live shop's re-check, or decline an address change: the apps show each in the owner's language (partner app keys
 * review.reason&lt;Code&gt;, messages notices/*.properties reason.&lt;CODE&gt;), so an owner who can't read the team's
 * English still knows what to do. Free words for anything else go in the note (required with OTHER).
 * Stored by name (shops.status_reason_codes, address_change_requests.reason_codes, shop_events.reason_codes):
 * never rename one, only add.
 */
public enum ReviewReason {
    /** The photo of the shop front is missing, dark or not of this shop. */
    SHOP_FRONT_PHOTO,
    /** The name board can't be seen in the shop photo. */
    BOARD_NOT_VISIBLE,
    /** Photos of the inside are missing or unclear. */
    INSIDE_PHOTOS,
    /** The trade licence can't be read. */
    LICENCE_UNREADABLE,
    /** The name on the trade licence doesn't match the salon or the owner. */
    LICENCE_NAME_MISMATCH,
    /** The trade licence has expired. */
    LICENCE_EXPIRED,
    /** The document isn't a trade licence (or not one for a salon). */
    LICENCE_WRONG_TYPE,
    /** The address (or its PIN code) is wrong or incomplete. */
    ADDRESS_WRONG,
    /** The pin on the map is in the wrong place. */
    PIN_WRONG_PLACE,
    /** Services or their prices are missing. */
    SERVICES_MISSING,
    /** Opening hours are missing. */
    HOURS_MISSING,
    /** The salon video is unclear. */
    VIDEO_UNCLEAR,
    /** The owner's name doesn't match (the licence, the person met at the visit). */
    OWNER_NAME,
    /** Something else: the note says what. */
    OTHER
}
