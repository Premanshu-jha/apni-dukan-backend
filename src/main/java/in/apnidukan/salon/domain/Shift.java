package in.apnidukan.salon.domain;

/**
 * A checked block of weekly hours: weekday 1-7 (Monday first), start and end in minutes after midnight, on
 * 15-minute steps, end after start. Example: Monday 10:00-14:00 is Shift(1, 600, 840).
 */
public record Shift(int weekday, int startMin, int endMin) {

    /** Whether the two blocks share any minute. Touching (one ends at 14:00, the next starts at 14:00) is fine. */
    public boolean overlaps(Shift other) {
        return weekday == other.weekday && startMin < other.endMin && other.startMin < endMin;
    }
}
