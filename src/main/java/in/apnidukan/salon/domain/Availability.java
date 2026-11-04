package in.apnidukan.salon.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Pure time arithmetic for one salon day, no database: which start times can be offered online, which barber to
 * give a booking, and when each barber could take a walk-in. The services load each barber's day (his working
 * hours that day, and the appointments holding his time) and ask here.
 */
public final class Availability {

    private static final Duration STEP = Duration.ofMinutes(SalonTime.STEP_MINUTES);

    private Availability() {
    }

    /**
     * One barber's day.
     * @param working       his working hours that day, from his weekly hours
     * @param busy          appointments holding his time (bookings, walk-ins, blocks), from start to planned end
     * @param bookedMinutes minutes of bookings and walk-ins he already has that day (to share work out evenly)
     * @param addedAt       when he was added to the salon (the tie-breaker after bookedMinutes)
     */
    public record BarberDay(UUID staffId, List<Interval> working, List<Interval> busy, long bookedMinutes,
                            Instant addedAt) {

        /** True if the slot lies inside one block of his working hours and touches none of his busy time. */
        public boolean fits(Interval slot) {
            return working.stream().anyMatch(w -> w.contains(slot)) && isFree(slot);
        }

        /** True if none of his busy time overlaps the slot (working hours not considered). */
        public boolean isFree(Interval slot) {
            return busy.stream().noneMatch(b -> b.overlaps(slot));
        }
    }

    /**
     * When a walk-in could start with this barber.
     * @param freeNow true if he can start now (working hours are not checked: staff may still serve someone after
     *                closing); false if start is his earliest later start today
     */
    public record WalkInSlot(UUID staffId, boolean freeNow, Instant start) {
    }

    /** The start of a salon date (midnight in India) as an exact moment. */
    public static Instant dayStart(LocalDate date) {
        return date.atStartOfDay(SalonTime.ZONE).toInstant();
    }

    /** A barber's working hours on a date, from his weekly hours. */
    public static List<Interval> working(List<Shift> week, LocalDate date) {
        Instant midnight = dayStart(date);
        int weekday = date.getDayOfWeek().getValue();
        return week.stream()
                .filter(s -> s.weekday() == weekday)
                .map(s -> new Interval(midnight.plus(Duration.ofMinutes(s.startMin())),
                        midnight.plus(Duration.ofMinutes(s.endMin()))))
                .toList();
    }

    /**
     * Start times to offer: every quarter hour (:00, :15, :30, :45 in India) from {@code from} up to
     * {@code until} where at least one barber fits a slot of this length. Bookings never cross midnight, because
     * working hours end at 24:00 at the latest.
     */
    public static List<Instant> starts(List<BarberDay> barbers, Duration length, Instant from, Instant until) {
        List<Instant> starts = new ArrayList<>();
        for (Instant t = roundUpToStep(from); t.isBefore(until); t = t.plus(STEP)) {
            Interval slot = Interval.of(t, length);
            if (barbers.stream().anyMatch(b -> b.fits(slot))) {
                starts.add(t);
            }
        }
        return starts;
    }

    /**
     * The barbers who fit this slot, in the order to try them: fewest booked minutes that day first, then the one
     * added to the salon first, then by id (so the order is always the same).
     */
    public static List<UUID> barbersFor(List<BarberDay> barbers, Interval slot) {
        return barbers.stream()
                .filter(b -> b.fits(slot))
                .sorted(LOAD_ORDER)
                .map(BarberDay::staffId)
                .toList();
    }

    /**
     * When this barber could take a walk-in of this length: now if nothing of his overlaps [now, now + length);
     * otherwise the first moment after his current and booked appointments where the whole length fits before his
     * next appointment and before his working block ends (exact minutes, not quarter hours). Empty if it doesn't
     * fit anywhere in the rest of today ({@code dayEnd}).
     */
    public static Optional<WalkInSlot> walkIn(BarberDay barber, Duration length, Instant now, Instant dayEnd) {
        Instant nowMinute = now.truncatedTo(ChronoUnit.MINUTES);
        if (barber.isFree(Interval.of(nowMinute, length))) {
            return Optional.of(new WalkInSlot(barber.staffId(), true, nowMinute));
        }
        List<Interval> blocks = barber.working().stream()
                .sorted(Comparator.comparing(Interval::start))
                .toList();
        for (Interval block : blocks) {
            if (!block.end().isAfter(nowMinute) || !block.start().isBefore(dayEnd)) {
                continue;
            }
            Instant t = block.start().isAfter(nowMinute) ? block.start() : nowMinute;
            t = afterBusy(barber.busy(), t, length);
            if (!t.plus(length).isAfter(block.end())) {
                return Optional.of(new WalkInSlot(barber.staffId(), false, t));
            }
        }
        return Optional.empty();
    }

    /** Pushes t past every busy stretch that would overlap [t, t + length), until nothing does. */
    private static Instant afterBusy(List<Interval> busy, Instant start, Duration length) {
        Instant t = start;
        boolean moved = true;
        while (moved) {
            moved = false;
            Interval slot = Interval.of(t, length);
            for (Interval b : busy) {
                if (b.overlaps(slot)) {
                    t = b.end();
                    moved = true;
                    break;
                }
            }
        }
        return t;
    }

    /** Up to {@code count} offered starts nearest to the one asked for (for "that time was just taken"). */
    public static List<Instant> nearest(List<Instant> starts, Instant wanted, int count) {
        return starts.stream()
                .sorted(Comparator.comparing((Instant s) -> Duration.between(s, wanted).abs()).thenComparing(s -> s))
                .limit(count)
                .sorted()
                .toList();
    }

    /** True if the moment is on the quarter-hour grid in India (India's offset, +5:30, is itself on it). */
    public static boolean onGrid(Instant t) {
        return t.getEpochSecond() % STEP.toSeconds() == 0 && t.getNano() == 0;
    }

    static Instant roundUpToStep(Instant t) {
        long step = STEP.toSeconds();
        long seconds = t.getEpochSecond() + (t.getNano() > 0 ? 1 : 0);
        long rounded = Math.floorDiv(seconds + step - 1, step) * step;
        return Instant.ofEpochSecond(rounded);
    }

    private static final Comparator<BarberDay> LOAD_ORDER = Comparator.comparingLong(BarberDay::bookedMinutes)
            .thenComparing(BarberDay::addedAt)
            .thenComparing(BarberDay::staffId);
}
