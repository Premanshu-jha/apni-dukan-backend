package in.apnidukan.shops.domain;

/**
 * Where a shop is in registration. DRAFT → PENDING_VISIT (submitted) → APPROVED (after the field visit), or SUSPENDED.
 * CLOSED: its owner closed it for good (from any other status); never opened again, the owner registers a new shop.
 */
public enum ShopStatus {
    DRAFT,
    PENDING_VISIT,
    APPROVED,
    SUSPENDED,
    CLOSED
}
