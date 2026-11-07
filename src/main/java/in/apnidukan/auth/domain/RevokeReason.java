package in.apnidukan.auth.domain;

/** Why a refresh token stopped working. */
public enum RevokeReason {
    /** Swapped for a new one by /auth/refresh. If it ever comes back, it was copied. */
    ROTATED,
    /** Used after its 30 days: just "sign in again", never a sign of copying. */
    EXPIRED,
    /** This device signed out. */
    SIGNED_OUT,
    /** Every session ended (account recovery, or a copied token was detected). */
    SIGNED_OUT_EVERYWHERE,
    /** This token came back after being rotated; it already triggered "signed out everywhere" once. */
    REUSE_DETECTED,
    /**
     * Swapped under the refresh grace: an old token came back within 30 s while this one was unused, and got a new
     * pair in its place. A phone that really reloaded never saved this one, so it never sends it; if it comes back
     * after the grace, someone else used the old token, and it counts as a copy (like ROTATED).
     */
    GRACE_REPLACED
}
