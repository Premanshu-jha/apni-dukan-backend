package in.apnidukan.salon.service;

import in.apnidukan.common.context.ShopDirectory;
import in.apnidukan.common.text.TextCleaner;
import in.apnidukan.salon.domain.ServiceIcon;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a customer typed in the salon list's search box (GET /salons?q=), checked and turned into the database's
 * search (ShopDirectory.Search): a salon is found when its name, or the name of one of its services that a working
 * barber does, contains the words in part, in any case ("acia" finds "Facial", "RAJU" finds "Raju Hair Salon"), or
 * when one of those services carries the picture the words name ("facial" finds a "Face clean-up" shown with the
 * FACIAL picture). A % or _ typed is just that character, never a wildcard.
 *
 * Small spelling slips are forgiven word by word, for words of at least TYPO_FROM letters ("facal", "mehndi",
 * "waxng", "threding"): each such word must be in the name or a near spelling of one of its words, and each shorter
 * word in it (ShopDirectory.Search.textMatches), so one common word shared ("hair", "salon") never finds a salon by
 * itself. Only for letters and spaces, so "50%" or "a_b" stay exact; two or three letters are too few to guess from.
 *
 * The text is first tidied as saved names were (TextCleaner.tidy: full-width letters, a one-character "फ़", a
 * no-break space between words), so what looks the same matches.
 */
final class SalonSearch {

    /** Fewer characters than this (after trimming) is refused: one letter would match nearly every salon. */
    static final int MIN_LENGTH = 2;
    /** More than this is refused: no salon or service name is longer (both are at most 60). */
    static final int MAX_LENGTH = 60;
    /** From this many characters, all letters or spaces, small spelling slips are forgiven too. */
    static final int TYPO_FROM = 4;

    /**
     * Other words people use for what a picture shows, besides its own name ("hair colour" for HAIR_COLOUR): the
     * American and Indian spellings that customers type. A word matches a picture when the picture's name or one of
     * these contains it. OTHER (anything none of the pictures shows) is never matched: "other" isn't a service.
     */
    private static final Map<ServiceIcon, List<String>> ALSO_CALLED = Map.ofEntries(
            Map.entry(ServiceIcon.HAIRCUT, List.of("hair cut", "cutting")),
            Map.entry(ServiceIcon.SHAVE, List.of("shaving")),
            Map.entry(ServiceIcon.MOUSTACHE, List.of("mustache", "mooch", "mooche")),
            Map.entry(ServiceIcon.HAIR_COLOUR, List.of("hair color", "color", "dye", "henna")),
            Map.entry(ServiceIcon.HEAD_MASSAGE, List.of("champi", "maalish", "malish", "oil massage")),
            Map.entry(ServiceIcon.FACIAL, List.of("face", "clean up", "cleanup", "clean-up")),
            Map.entry(ServiceIcon.THREADING, List.of("eyebrow")),
            Map.entry(ServiceIcon.WAXING, List.of("wax")),
            Map.entry(ServiceIcon.MEHENDI, List.of("mehndi", "mehandi", "henna")),
            Map.entry(ServiceIcon.BRIDAL, List.of("bride", "dulhan", "wedding")),
            Map.entry(ServiceIcon.KIDS, List.of("kid", "child", "children", "baby")),
            Map.entry(ServiceIcon.MANICURE, List.of("nail")),
            Map.entry(ServiceIcon.PEDICURE, List.of("feet", "foot")),
            Map.entry(ServiceIcon.MAKEUP, List.of("make up", "make-up")));

    private SalonSearch() {
    }

    /**
     * The database's search for this text, or null when there is nothing to search for (no q, or only spaces: the
     * list as without a search). Tidied as saved names are (TextCleaner.tidy: look-alike letters made normal, any
     * kind of space, at the ends dropped and runs inside made one), then counted and lower-cased.
     * @throws in.apnidukan.common.error.ApiException 400 VALIDATION_FAILED on "q" when shorter than MIN_LENGTH or
     *         longer than MAX_LENGTH characters
     */
    static ShopDirectory.Search parse(String q) {
        if (q == null) {
            return null;
        }
        String text = TextCleaner.tidy(q);
        if (text.isEmpty()) {
            return null;
        }
        int length = text.codePointCount(0, text.length());
        if (length < MIN_LENGTH) {
            throw SalonCalendar.invalid("q", "type at least " + MIN_LENGTH + " letters");
        }
        if (length > MAX_LENGTH) {
            throw SalonCalendar.invalid("q", "at most " + MAX_LENGTH + " letters");
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return new ShopDirectory.Search("%" + escapeLike(lower) + "%", forgivesTypos(text, length) ? lower : null,
                icons(lower));
    }

    /** The text for LIKE with its own backslash, % and _ escaped (backslash is LIKE's escape character). */
    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * Long enough and only letters (any script, with their vowel signs: Hindi and Telugu words have them) and spaces.
     */
    private static boolean forgivesTypos(String text, int length) {
        return length >= TYPO_FROM && text.codePoints().allMatch(c -> c == ' ' || Character.isLetter(c)
                || Character.getType(c) == Character.NON_SPACING_MARK
                || Character.getType(c) == Character.COMBINING_SPACING_MARK);
    }

    /**
     * The pictures whose name ("hair colour" for HAIR_COLOUR) or another word for them has a word starting with the
     * text ("ha" and "hair col" name HAIR_COLOUR; "olour" names nothing, and "ha" doesn't name SHAVE or BRIDAL's
     * "dulhan"): a picture stands for a whole service, so a piece from inside a word would find salons that offer
     * something else. The names themselves still match anywhere in a word.
     */
    static List<String> icons(String lower) {
        List<String> out = new ArrayList<>();
        for (ServiceIcon icon : ServiceIcon.values()) {
            if (icon == ServiceIcon.OTHER) {
                continue;
            }
            String own = icon.name().toLowerCase(Locale.ROOT).replace('_', ' ');
            boolean named = startsAWord(own, lower)
                    || ALSO_CALLED.getOrDefault(icon, List.of()).stream().anyMatch(w -> startsAWord(w, lower));
            if (named) {
                out.add(icon.name());
            }
        }
        return out;
    }

    private static boolean startsAWord(String words, String text) {
        return words.startsWith(text) || words.contains(" " + text) || words.contains("-" + text);
    }
}
