package in.apnidukan.leads.service;

import in.apnidukan.leads.repo.LeadStore.Salon;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which Clozest salon a business found on Mapbox (or a saved lead) is: the same phone as the salon's owner, or a
 * similar name within {@link #NEAR_M} metres. Each salon is the match of at most one business (the nearest, a phone
 * match first). Pure functions.
 */
public final class ClozestMatch {

    /** How far apart a business and a salon with a similar name may be and still be the same place. */
    static final double NEAR_M = 120;

    /** Words every salon has, ignored when comparing names. */
    private static final Set<String> COMMON = Set.of("salon", "saloon", "salons", "parlour", "parlor", "beauty",
            "unisex", "family", "the", "and", "hair", "spa", "studio", "mens", "men", "ladies", "women", "womens",
            "makeup", "care", "lounge", "academy", "only", "for", "n");

    /** Something to match: an id, a name, where it is, and a phone (any form, or null). */
    public record Candidate(String id, String name, double latitude, double longitude, String phone) {
    }

    private ClozestMatch() {
    }

    /** The salon each candidate is (by candidate id); candidates with no match aren't in the map. */
    public static Map<String, Salon> match(List<Candidate> candidates, List<Salon> salons) {
        record Pair(Candidate c, Salon s, boolean phone, double metres) {
        }
        List<Pair> pairs = new ArrayList<>();
        for (Candidate c : candidates) {
            String phone = phone10(c.phone());
            for (Salon s : salons) {
                double m = metres(c.latitude(), c.longitude(), s.latitude(), s.longitude());
                boolean samePhone = phone != null && phone.equals(phone10(s.ownerPhone()));
                if (samePhone || (m <= NEAR_M && similar(c.name(), s.name()))) {
                    pairs.add(new Pair(c, s, samePhone, m));
                }
            }
        }
        pairs.sort(Comparator.comparing((Pair p) -> !p.phone()).thenComparingDouble(Pair::metres));
        Map<String, Salon> out = new HashMap<>();
        Set<java.util.UUID> taken = new HashSet<>();
        for (Pair p : pairs) {
            if (!out.containsKey(p.c().id()) && taken.add(p.s().shopId())) {
                out.put(p.c().id(), p.s());
            }
        }
        return out;
    }

    /** The last 10 digits of an Indian mobile number in any form ("+91 91335 76278", "09133576278"), or null. */
    public static String phone10(String phone) {
        if (phone == null) {
            return null;
        }
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() < 10) {
            return null;
        }
        String last = digits.substring(digits.length() - 10);
        return last.charAt(0) >= '6' ? last : null;
    }

    /**
     * Whether two names are the same business: their distinctive words (without the {@link #COMMON} ones) mostly
     * agree (all of the shorter's words in the longer, or half of all words shared); names made only of common words
     * must be the same.
     */
    static boolean similar(String a, String b) {
        Set<String> x = words(a);
        Set<String> y = words(b);
        if (x.isEmpty() || y.isEmpty()) {
            return normal(a).equals(normal(b));
        }
        Set<String> small = x.size() <= y.size() ? x : y;
        Set<String> big = small == x ? y : x;
        if (big.containsAll(small)) {
            return true;
        }
        Set<String> both = new HashSet<>(x);
        both.retainAll(y);
        Set<String> all = new HashSet<>(x);
        all.addAll(y);
        return both.size() * 2 >= all.size();
    }

    private static Set<String> words(String s) {
        return Arrays.stream(normal(s).split(" "))
                .filter(w -> !w.isEmpty() && !COMMON.contains(w))
                .collect(Collectors.toSet());
    }

    private static String normal(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("['’]s\\b", "").replaceAll("['’]", "").replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    static double metres(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6_371_000 * 2 * Math.asin(Math.sqrt(h));
    }
}
