package in.apnidukan.shops.domain;

/** Where a shop is in registration. DRAFT → PENDING_VISIT (submitted) → APPROVED (after the field visit), or SUSPENDED. */
public enum ShopStatus {
    DRAFT,
    PENDING_VISIT,
    APPROVED,
    SUSPENDED
}
