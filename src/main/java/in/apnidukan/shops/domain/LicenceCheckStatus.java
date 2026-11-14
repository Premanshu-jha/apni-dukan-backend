package in.apnidukan.shops.domain;

/** Where the automatic check of a salon's trade licence stands (null on the shop: no document). */
public enum LicenceCheckStatus {
    /** Waiting for LicenceCheckJob (also while no AI key is set yet, or the AI service is down). */
    PENDING,
    /** Read and matched: customers see "Licensed" until its last valid day. */
    PASSED,
    /** Didn't pass: the owner sees why (LicenceCheckReason) and can upload another. */
    FAILED
}
