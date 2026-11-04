package in.apnidukan.salon.domain;

import java.time.Duration;
import java.time.Instant;

/** A stretch of time [start, end): it includes its start but not its end, so 10:00-10:30 and 10:30-11:00 touch. */
public record Interval(Instant start, Instant end) {

    public Interval {
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("An interval must end after it starts: " + start + " - " + end);
        }
    }

    public static Interval of(Instant start, Duration length) {
        return new Interval(start, start.plus(length));
    }

    public boolean overlaps(Interval other) {
        return start.isBefore(other.end) && other.start.isBefore(end);
    }

    public boolean contains(Interval other) {
        return !other.start.isBefore(start) && !other.end.isAfter(end);
    }

    public Duration length() {
        return Duration.between(start, end);
    }
}
