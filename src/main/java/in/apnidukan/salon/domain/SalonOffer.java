package in.apnidukan.salon.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A discount the salon's owner set (salon_offers, V37): percentOff off the services it covers, for bookings that
 * start on a day from startsOn to endsOn (India dates, both included) and, when fromMin/toMin are set, at a time
 * from fromMin until toMin that day. Ended early (endedAt): no new booking gets it.
 * @param serviceIds the services it covers; empty when allServices
 */
public record SalonOffer(UUID id, UUID shopId, int percentOff, boolean allServices, Set<UUID> serviceIds,
                         LocalDate startsOn, LocalDate endsOn, Integer fromMin, Integer toMin, Instant endedAt,
                         int notified, Instant createdAt) {

    public static final int MIN_PERCENT = 5;
    public static final int MAX_PERCENT = 50;

    public SalonOffer {
        serviceIds = Set.copyOf(serviceIds);
    }

    public boolean ended() {
        return endedAt != null;
    }

    public boolean covers(UUID serviceId) {
        return allServices || serviceIds.contains(serviceId);
    }

    /** A booking starting at this moment gets it (for the services it covers): not ended, inside its days and hours. */
    public boolean appliesAt(Instant start) {
        if (ended()) {
            return false;
        }
        ZonedDateTime t = start.atZone(SalonTime.ZONE);
        LocalDate date = t.toLocalDate();
        if (date.isBefore(startsOn) || date.isAfter(endsOn)) {
            return false;
        }
        int minute = t.getHour() * 60 + t.getMinute();
        return fromMin == null || (minute >= fromMin && minute < toMin);
    }

    /**
     * The offer price of a list price: percentOff off, to the nearest rupee (half a rupee up), never below one rupee
     * unless the service is free.
     */
    public long priceOf(long listPaise) {
        return priceOf(listPaise, percentOff);
    }

    public static long priceOf(long listPaise, int percentOff) {
        if (listPaise <= 0) {
            return listPaise;
        }
        long rupees = (listPaise * (100 - percentOff) + 5_000) / 10_000;   // paise × (100 − p) / 100, then to rupees
        return Math.max(1, rupees) * 100;
    }

    /** Whether the two run at the same time for some service: their days, their hours and their services meet. */
    public boolean overlaps(LocalDate starts, LocalDate ends, Integer from, Integer to, boolean all,
                            Collection<UUID> services) {
        if (ended() || ends.isBefore(startsOn) || starts.isAfter(endsOn)) {
            return false;
        }
        int aFrom = fromMin == null ? 0 : fromMin;
        int aTo = toMin == null ? SalonTime.END_OF_DAY : toMin;
        int bFrom = from == null ? 0 : from;
        int bTo = to == null ? SalonTime.END_OF_DAY : to;
        if (aFrom >= bTo || bFrom >= aTo) {
            return false;
        }
        return allServices || all || services.stream().anyMatch(serviceIds::contains);
    }

    /**
     * These services priced for a booking starting at {@code start}: each covered by one of these offers gets the
     * offer price (the biggest discount if, against the rules, more than one applies); the rest keep their price.
     */
    public static List<BookedService> price(List<BookedService> chosen, Collection<SalonOffer> offers, Instant start) {
        List<SalonOffer> live = offers.stream().filter(o -> o.appliesAt(start)).toList();
        if (live.isEmpty()) {
            return chosen;
        }
        return chosen.stream().map(s -> live.stream().filter(o -> o.covers(s.serviceId()))
                        .max(java.util.Comparator.comparingInt(SalonOffer::percentOff))
                        .map(s::withOffer).orElse(s))
                .toList();
    }
}
