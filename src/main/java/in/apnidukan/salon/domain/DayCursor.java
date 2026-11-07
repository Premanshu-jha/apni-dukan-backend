package in.apnidukan.salon.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Where an answer of the day board or the upcoming list stood, sent back by the app as {@code since} to get only what
 * changed after it (GET /salon/day?since=, GET /salon/upcoming?since=). Opaque to the app:
 * "d3.{date}.{oldest running xid}.{setup version}.{version}.{epoch seconds}.{valid until, epoch seconds}" for the day
 * board, the same with "u3" for the upcoming list, so one list's cursor is never taken for the other's (it is then no
 * cursor: a whole answer). A cursor of an older form ("d2") is no cursor either: one whole answer, then deltas again.
 *
 * @param upcoming         true for the upcoming list's cursor, false for the day board's
 * @param date             the day the answer was for (the day board's date; for the upcoming list, the India date it
 *                         was read on): a delta for another day would mix two boards, so the answer is whole then
 * @param oldestRunningXid the oldest transaction still running when the answer was read: every row written by it or
 *                         a later one may be missing from that answer (V21)
 * @param setupVersion     the salon's setup_version then: barbers, their hours and services, who works there and who
 *                         may add walk-ins. Another one means the columns themselves may differ: the whole answer again
 * @param version          the salon's change counter then: unchanged means no appointment of the salon changed since
 * @param at               when the answer was read (time alone turns waiting bookings "not started")
 * @param validUntil       the first moment after {@code at} that time alone changes the answer (a waiting booking's
 *                         start, or for the upcoming list also an end): before it, with the counter unchanged, nothing
 *                         at all can have changed, and the next delta is answered from the counters alone
 */
public record DayCursor(boolean upcoming, LocalDate date, long oldestRunningXid, long setupVersion, long version,
                        Instant at, Instant validUntil) {

    private static final String DAY = "d3";
    private static final String UPCOMING = "u3";

    public String text() {
        return (upcoming ? UPCOMING : DAY) + "." + date + "." + oldestRunningXid + "." + setupVersion + "." + version + "."
                + at.getEpochSecond() + "." + validUntil.getEpochSecond();
    }

    /**
     * The cursor in this text, or empty for anything that isn't one of this list's (the answer is then whole).
     * @param upcoming true when the upcoming list asks, false for the day board
     */
    public static Optional<DayCursor> parse(String text, boolean upcoming) {
        if (text == null || text.length() > 120) {
            return Optional.empty();
        }
        String[] parts = text.split("\\.", -1);
        if (parts.length != 7 || !(upcoming ? UPCOMING : DAY).equals(parts[0])) {
            return Optional.empty();
        }
        try {
            return Optional.of(new DayCursor(upcoming, LocalDate.parse(parts[1]), Long.parseLong(parts[2]),
                    Long.parseLong(parts[3]), Long.parseLong(parts[4]), Instant.ofEpochSecond(Long.parseLong(parts[5])),
                    Instant.ofEpochSecond(Long.parseLong(parts[6]))));
        } catch (RuntimeException e) {   // NumberFormatException, DateTimeParseException, DateTimeException
            return Optional.empty();
        }
    }
}
