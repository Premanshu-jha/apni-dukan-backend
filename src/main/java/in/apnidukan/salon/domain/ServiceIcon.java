package in.apnidukan.salon.domain;

import java.util.Arrays;
import java.util.Locale;

/**
 * The picture shown next to a salon service, picked by the owner from this fixed set (the apps draw each one; the
 * backend only keeps the name). A service saved before icons existed has none: the apps then suggest one from its
 * name, the same way they suggest it when the owner adds one.
 */
public enum ServiceIcon {
    HAIRCUT, BEARD, SHAVE, HAIR_COLOUR, STYLING, HAIR_SPA, HAIR_WASH, FACIAL, CLEANUP, MASSAGE, THREADING, WAXING,
    MANICURE, PEDICURE, MAKEUP, BRIDAL, KIDS, MEHENDI, STRAIGHTENING, CURLS, NAIL_ART, EYELASH, BODY_SPA, PIERCING,
    PACKAGE, OTHER;

    /** The icon with this name (any case), or null when there is none such. */
    public static ServiceIcon parse(String value) {
        if (value == null) {
            return null;
        }
        String v = value.strip().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(i -> i.name().equals(v)).findFirst().orElse(null);
    }
}
