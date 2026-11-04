package in.apnidukan.salon.domain;

/** Why a barber is unavailable. */
public enum BlockType {
    /** A short break in his day (tea, lunch). Owner, helper, or the barber himself. */
    BREAK,
    /** Time off. Owner only. */
    LEAVE,
    /** The whole salon is closed that day: one CLOSED block per barber. Owner only. */
    CLOSED
}
