package in.apnidukan.shops.domain;

/** An entry in a shop's timeline (shop_events). */
public enum ShopEventType {
    /** The owner sent it for a visit. */
    SUBMITTED,
    /** The owner changed an approved shop (pin or photos): it is checked again. */
    EDITED,
    APPROVED,
    SENT_BACK,
    SUSPENDED,
    REINSTATED,
    /** A note from an admin, with no decision. */
    NOTE
}
