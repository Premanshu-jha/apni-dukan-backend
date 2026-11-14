package in.apnidukan.shops.domain;

/** Why a salon's trade licence didn't pass the automatic check. The owner's app turns each into a short fix. */
public enum LicenceCheckReason {
    /** Too blurred, cut off or dark to read. */
    UNREADABLE,
    /** Not a municipal trade licence (another certificate, a receipt, a random photo). */
    NOT_TRADE_LICENCE,
    /** Looks changed (text pasted over, numbers edited). */
    LOOKS_EDITED,
    /** No licence number could be read. */
    NO_NUMBER,
    /** A licence for another kind of trade, not a salon or barber shop. */
    NOT_A_SALON_TRADE,
    /** No "valid up to" date could be read. */
    NO_VALIDITY,
    /** Its last valid day has passed. */
    EXPIRED,
    /** Neither the business name nor the owner's name matches this salon. */
    NAME_MISMATCH,
    /** The address on it is not this salon's (another pincode or area). */
    ADDRESS_MISMATCH,
    /** Another salon on the app already has this licence. */
    DUPLICATE,
    /** The AI answered something we couldn't use, several times in a row. */
    NOT_READ
}
