package in.apnidukan.shops.service;

import in.apnidukan.shops.domain.LicenceCheckReason;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Decides, in code, whether what the AI read from a trade licence passes for this salon: it looks genuine (a readable
 * trade licence for a salon, nothing visibly edited, a number, still valid) and it matches the salon (its business or
 * owner name, and its pincode or area). One salon per licence number is checked by the database (DUPLICATE). A
 * provisional licence (issued on payment, before the inspection) passes: it is a licence until its last day.
 * What can't be told from the page alone (a neighbour's real licence from the same pincode and a similar name) is
 * left to the field team's visit and the admin's Remove.
 *
 * The checks run in a fixed order and the first that fails is the reason the owner sees, so the most useful fix comes
 * first (a blurred photo before a name mismatch).
 */
public final class LicenceRules {

    /** What the salon says about itself (its registration details). */
    public record Salon(String name, String ownerName, String addressLine, String area, String city, String pincode) {
    }

    /** The decision: passed with the licence number and last valid day, or failed with the reason. */
    public record Outcome(boolean passed, LicenceCheckReason reason, String licenceNumber, LocalDate validUntil) {

        static Outcome fail(LicenceCheckReason reason) {
            return new Outcome(false, reason, null, null);
        }
    }

    /** Words that say "salon" in a name: they don't make two names the same business. */
    private static final Set<String> NAME_NOISE = Set.of("salon", "saloon", "salons", "hair", "hairs", "cut", "cuts",
            "style", "styles", "studio", "unisex", "men", "mens", "gents", "ladies", "beauty", "parlour", "parlor",
            "spa", "shop", "the", "and", "new", "family", "care", "point", "zone", "center", "centre", "barber",
            "barbers", "makeover", "looks", "academy", "sri", "shri", "m/s", "ms");

    /**
     * Names and surnames shared by many unrelated people in Hyderabad: one of them alone doesn't make two names the
     * same person ("Syed Ali" and "Syed Rafi"); two shared words do.
     */
    private static final Set<String> COMMON_NAMES = Set.of("kumar", "reddy", "rao", "rau", "shaik", "shaikh", "sheikh",
            "mohammed", "mohammad", "muhammad", "mohd", "syed", "sayyed", "khan", "ali", "singh", "naidu", "goud",
            "gowda", "yadav", "babu", "devi", "begum", "bee", "chary", "chari", "raju", "murthy", "prasad", "swamy",
            "lal", "nath", "sri", "shri", "smt", "mr", "mrs", "md");

    /** Words in nearly every Hyderabad address: they don't make two addresses the same place. */
    private static final Set<String> ADDRESS_NOISE = Set.of("hyderabad", "secunderabad", "telangana", "india",
            "road", "roads", "street", "colony", "nagar", "main", "cross", "lane", "near", "opp", "opposite", "beside",
            "behind", "floor", "ground", "first", "second", "plot", "house", "door", "shop", "building", "complex",
            "district", "mandal", "circle", "ward", "zone", "municipal", "corporation", "greater", "cyberabad",
            "malkajgiri", "rangareddy", "medchal", "village", "post", "area", "phase", "block", "sector", "hills",
            "layout", "enclave", "gardens", "garden", "residency", "towers", "tower", "apartments", "apartment",
            "plaza", "market", "center", "centre", "line", "lines", "bazar", "bazaar", "basti", "nagaram");

    private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    /** A pincode on its own: 6 digits (one space allowed after the third), not part of a longer number. */
    private static final Pattern PINCODE = Pattern.compile("(?<![0-9])([1-9][0-9]{2}) ?([0-9]{3})(?![0-9])");
    /**
     * The licence number kept: letters and digits only, upper-case, at most 40. "TR-0934-422-0003", "TR 0934 422 0003"
     * and "tr/0934/422/0003" are the same licence.
     */
    private static final Pattern NUMBER_DROP = Pattern.compile("[^A-Z0-9]");
    /** A last valid day further than this is a misreading (licences run for a year, a few at most). */
    static final int MAX_YEARS_VALID = 10;

    private LicenceRules() {
    }

    public static Outcome decide(LicenceExtract read, Salon salon, LocalDate today) {
        if (!read.readable()) {
            return Outcome.fail(LicenceCheckReason.UNREADABLE);
        }
        if (!read.tradeLicence()) {
            return Outcome.fail(LicenceCheckReason.NOT_TRADE_LICENCE);
        }
        if (read.looksEdited()) {
            return Outcome.fail(LicenceCheckReason.LOOKS_EDITED);
        }
        String number = licenceNumber(read.licenceNumber());
        if (number == null) {
            return Outcome.fail(LicenceCheckReason.NO_NUMBER);
        }
        if (!read.salonTrade()) {
            return Outcome.fail(LicenceCheckReason.NOT_A_SALON_TRADE);
        }
        if (read.validUntil() == null) {
            return Outcome.fail(LicenceCheckReason.NO_VALIDITY);
        }
        if (read.validUntil().isBefore(today)) {
            return Outcome.fail(LicenceCheckReason.EXPIRED);
        }
        if (read.validUntil().isAfter(today.plusYears(MAX_YEARS_VALID))) {
            return Outcome.fail(LicenceCheckReason.NO_VALIDITY);
        }
        if (!sameName(read.tradeName(), salon.name()) && !sameName(read.ownerName(), salon.ownerName())) {
            return Outcome.fail(LicenceCheckReason.NAME_MISMATCH);
        }
        if (!samePlace(read, salon)) {
            return Outcome.fail(LicenceCheckReason.ADDRESS_MISMATCH);
        }
        return new Outcome(true, null, number, read.validUntil());
    }

    /** Letters and digits only, upper-case; null if fewer than 4 are left. */
    static String licenceNumber(String printed) {
        if (printed == null) {
            return null;
        }
        String n = NUMBER_DROP.matcher(printed.toUpperCase(Locale.ROOT)).replaceAll("");
        if (n.length() < 4) {
            return null;
        }
        return n.length() > 40 ? n.substring(0, 40) : n;
    }

    /**
     * The same business or person, once the salon words are dropped: one name holds the other (spaces ignored, 4
     * letters or more), or they share a word of 3 letters or more that isn't a common name ("Sharma", "Lakshmi"), or
     * they share two words even if common ("Syed Ali" and "Ali Syed").
     */
    static boolean sameName(String onLicence, String inApp) {
        Set<String> a = words(onLicence, NAME_NOISE, 2);
        Set<String> b = words(inApp, NAME_NOISE, 2);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        String flatA = flat(onLicence, NAME_NOISE);
        String flatB = flat(inApp, NAME_NOISE);
        boolean flatDistinctive = !COMMON_NAMES.contains(flatA) && !COMMON_NAMES.contains(flatB);
        if (flatDistinctive && flatA.length() >= 4 && flatB.length() >= 4
                && (flatA.contains(flatB) || flatB.contains(flatA))) {
            return true;
        }
        long shared = a.stream().filter(w -> w.length() >= 3 && b.contains(w)).count();
        boolean distinctive = a.stream().anyMatch(w -> w.length() >= 3 && b.contains(w) && !COMMON_NAMES.contains(w));
        return distinctive || shared >= 2;
    }

    /**
     * The same place: the licence's pincode (its own field, else the last one in its address) is the salon's; with no
     * pincode on it, its address shares a word of 4 letters or more with the salon's address or area (words every
     * Hyderabad address has left out).
     */
    static boolean samePlace(LicenceExtract read, LicenceRules.Salon salon) {
        String pin = pincode(read.pincode());
        if (pin == null) {
            pin = pincode(read.address());
        }
        if (pin != null) {
            return pin.equals(salon.pincode());
        }
        Set<String> onLicence = words(read.address(), ADDRESS_NOISE, 4);
        Set<String> inApp = words(salon.addressLine() + " " + salon.area(), ADDRESS_NOISE, 4);
        return onLicence.stream().anyMatch(inApp::contains);
    }

    /** The last pincode standing on its own in the text (never part of a phone number or plot number); null if none. */
    static String pincode(String text) {
        if (text == null) {
            return null;
        }
        var m = PINCODE.matcher(text);
        String last = null;
        while (m.find()) {
            last = m.group(1) + m.group(2);
        }
        return last;
    }

    /** Lower-case words of at least {@code minLength} characters, without the noise words. */
    private static Set<String> words(String text, Set<String> noise, int minLength) {
        if (text == null) {
            return Set.of();
        }
        return Arrays.stream(NOT_WORD.split(text.toLowerCase(Locale.ROOT)))
                .filter(w -> w.length() >= minLength && !noise.contains(w))
                .collect(Collectors.toCollection(HashSet::new));
    }

    /** The words (noise dropped) run together, in their order. */
    private static String flat(String text, Set<String> noise) {
        if (text == null) {
            return "";
        }
        return Arrays.stream(NOT_WORD.split(text.toLowerCase(Locale.ROOT)))
                .filter(w -> !w.isEmpty() && !noise.contains(w))
                .collect(Collectors.joining());
    }
}
