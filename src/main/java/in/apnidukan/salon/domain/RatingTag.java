package in.apnidukan.salon.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * What went well, as a customer can tick it when rating a visit. Stored as one bit each in salon_ratings.tags
 * (V34), counted per salon in salon_rating_totals.
 */
public enum RatingTag {
    ON_TIME(1, "On time"),
    CLEAN(2, "Clean"),
    FRIENDLY(4, "Friendly"),
    SKILLED(8, "Skilled"),
    GOOD_VALUE(16, "Good value");

    private final int bit;
    private final String label;

    RatingTag(int bit, String label) {
        this.bit = bit;
        this.label = label;
    }

    public int bit() {
        return bit;
    }

    public String label() {
        return label;
    }

    /** The stored number for these tags (none: 0). */
    public static int mask(Collection<RatingTag> tags) {
        int mask = 0;
        for (RatingTag t : tags) {
            mask |= t.bit;
        }
        return mask;
    }

    /** The tags in a stored number, in this enum's order. */
    public static List<RatingTag> of(int mask) {
        List<RatingTag> out = new ArrayList<>();
        for (RatingTag t : values()) {
            if ((mask & t.bit) != 0) {
                out.add(t);
            }
        }
        return out;
    }

    /** 1 if the stored number has this tag, else 0 (for the totals' counters). */
    public int in(int mask) {
        return (mask & bit) != 0 ? 1 : 0;
    }
}
