package in.apnidukan.shops.domain;

/** What the field team found wrong at a visit (ticked when sending a shop back or suspending it). */
public enum VisitIssue {
    NAME_BOARD_NOT_VISIBLE,
    OWNER_PHOTO_UNCLEAR,
    PIN_WRONG,
    ADDRESS_MISMATCH,
    GST_UDYAM_MISMATCH,
    SHOP_SHUT,
    OTHER
}
