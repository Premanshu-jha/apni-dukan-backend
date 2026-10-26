package in.apnidukan.auth.domain;

/** Why a refresh token stopped working. */
public enum RevokeReason {
    /** Swapped for a new one by /auth/refresh. If it ever comes back, it was copied. */
    ROTATED,
    /** This device signed out. */
    SIGNED_OUT,
    /** Every session ended (account recovery, or a copied token was detected). */
    SIGNED_OUT_EVERYWHERE,
    /** This token came back after being rotated; it already triggered "signed out everywhere" once. */
    REUSE_DETECTED
}
