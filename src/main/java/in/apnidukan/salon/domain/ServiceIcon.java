package in.apnidukan.salon.domain;

import java.util.Arrays;
import java.util.Locale;

/**
 * The picture shown next to a salon service, picked by the owner from this fixed set (the apps draw each one; the
 * backend only keeps the name). A service saved before icons existed has none: the apps then suggest one from its
 * name, the same way they suggest it when the owner adds one. The set (V63) passed a blind recognition test: each
 * picture is told apart from the others at phone size; OTHER (a salon chair) is for anything none of them shows.
 */
public enum ServiceIcon {
    HAIRCUT, TRIM, BEARD, MOUSTACHE, SHAVE, HAIR_WASH, HAIR_SPA, HEAD_MASSAGE, HAIR_COLOUR, STYLING, STRAIGHTENING,
    CURLS, BRAIDS, FACIAL, THREADING, WAXING, EYELASH, MAKEUP, BRIDAL, MEHENDI, MANICURE, PEDICURE, MASSAGE, BODY_SPA,
    PIERCING, KIDS, PACKAGE, OTHER;

    /**
     * The icon with this name (any case), or null when there is none such. The names retired in V63 still come from
     * an app that hasn't updated yet: CLEANUP is read as FACIAL and NAIL_ART as MANICURE, the pictures they became.
     */
    public static ServiceIcon parse(String value) {
        if (value == null) {
            return null;
        }
        String v = value.strip().toUpperCase(Locale.ROOT);
        return switch (v) {
            case "CLEANUP" -> FACIAL;
            case "NAIL_ART" -> MANICURE;
            default -> Arrays.stream(values()).filter(i -> i.name().equals(v)).findFirst().orElse(null);
        };
    }
}
