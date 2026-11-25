package in.apnidukan.leads.service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a business found on Mapbox is, for the Leads page: whether it offers the services of Clozest's salon section at
 * all ({@link #relevant}), which kind it is ({@link #kind}), whether it is a chain ({@link #chain}) and how good a lead
 * it is ({@link #score}). Read from its name and Mapbox's categories only. Pure functions.
 */
public final class PoiKinds {

    /** The kinds, in the order the page lists them. */
    public enum Kind {
        LADIES_PARLOUR, UNISEX_SALON, MENS_SALON, BRIDAL_MAKEUP, MEHENDI, SPA_MASSAGE, NAIL_LASH, SKIN_HAIR_CLINIC,
        TATTOO, COSMETICS_SHOP, OTHER
    }

    /** The kinds that are Clozest's core salon customers (a better lead). */
    static final Set<Kind> CORE = Set.of(Kind.LADIES_PARLOUR, Kind.UNISEX_SALON, Kind.MENS_SALON, Kind.BRIDAL_MAKEUP);

    /** Mapbox categories searched in every area (each with its own tiles). */
    public static final List<String> CATEGORIES = List.of("salon", "hairdresser", "spa", "nail_salon", "massage_shop",
            "beauty_store", "tattoo_parlour");

    /**
     * Words searched in every area as well, one for each service the salon section offers, so a business that offers
     * it without being filed as a salon is found too (a mehendi artist, a makeup studio, a threading place).
     */
    public static final List<String> SERVICE_WORDS = List.of("beauty parlour", "ladies beauty parlour", "unisex salon",
            "mens salon", "barber shop", "hair cut", "beard shave", "hair colour", "hair spa", "keratin hair treatment",
            "facial", "threading", "waxing", "manicure pedicure", "nail art", "head massage", "body massage spa",
            "bridal makeup", "makeup artist", "mehendi artist", "saree draping", "hair extension",
            "eyelash extension", "tattoo studio", "skin clinic");

    private static final Pattern BEAUTY_NAME = Pattern.compile("salon|saloon|parlou?r|beauty|make ?up|makeover|bridal"
            + "|mehe?n?d|mehan|henna|thread|wax|\\bspa\\b|massage|nail|hair|barber|\\bcuts?\\b|styl|skin|derma|cosmet"
            + "|groom|unisex|glow|lash|brow|facial|tattoo|saree|keratin|spalon|wellness");
    private static final Pattern BEAUTY_CATEGORY = Pattern.compile("salon|hairdresser|barber|spa|massage|nail|beauty"
            + "|cosmetic|tattoo");
    private static final Pattern CHAIN = Pattern.compile("lakm[eé]|naturals|green trends|jawed habib|habib'?s"
            + "|toni ?(&|and) ?guy|bounce salon|mirrors luxury|bubbles hair|page ?3|studio ?11|\\bspin (unisex )?salon"
            + "|mane'?a|glam studios|kristane|define salons?|hair crush|enrich salon|\\bylg\\b|juice salon|be you salon"
            + "|autumn salon|femina flaunt|envi salon|lisa ?& ?lars|limelite|bodycraft|o2 spa|tattva spa|vurve"
            + "|pebbles hair|hiwaga|looks salon|b blunt|geetanjali|jean[- ]claude|\\bvlcc\\b|kaya skin|oliva"
            + "|amjad habib|\\bfunk salon|origine salon|\\bwe3\\b|orange hair");

    private PoiKinds() {
    }

    /** Whether the business offers salon-section services: a beauty category on Mapbox, or a beauty word in its name. */
    public static boolean relevant(Poi p) {
        return BEAUTY_CATEGORY.matcher(categories(p)).find() || BEAUTY_NAME.matcher(lower(p.name())).find();
    }

    public static Kind kind(Poi p) {
        String n = lower(p.name());
        String c = categories(p);
        if (find("make ?up|makeover|bridal|\\bmua\\b", n)) {
            return Kind.BRIDAL_MAKEUP;
        }
        if (find("mehe?n?d|mehan|henna", n)) {
            return Kind.MEHENDI;
        }
        if (find("tattoo", n) || find("tattoo", c)) {
            return Kind.TATTOO;
        }
        if (find("nail|lash", n) || (find("nail", c) && !find("salon|parlou?r", n))) {
            return Kind.NAIL_LASH;
        }
        if (find("clinic|derma|skin|laser|cosmetolog|trichol|transplant", n)) {
            return Kind.SKIN_HAIR_CLINIC;
        }
        if (find("unisex|family", n)) {
            return Kind.UNISEX_SALON;
        }
        if (find("\\bmen'?s?\\b|\\bgents\\b|barber|saloon|hair ?(style|cut)|\\bcuts?\\b", n)
                && !find("ladies|women|parlou?r", n)) {
            return Kind.MENS_SALON;
        }
        if (find("parlou?r|ladies|women|herbal|thread|beauty", n)
                && !find("\\bspa\\b", n.replace("salon and spa", "").replace("salon & spa", ""))) {
            return Kind.LADIES_PARLOUR;
        }
        if (find("salon|studio|spalon", n)) {
            return Kind.UNISEX_SALON;
        }
        if (find("\\bspa\\b|massage|wellness|ayurved", n) || find("spa|massage", c)) {
            return Kind.SPA_MASSAGE;
        }
        if (find("hairdresser|barber", c)) {
            return Kind.MENS_SALON;
        }
        if (find("salon", c)) {
            return Kind.UNISEX_SALON;
        }
        if (find("beauty_store|cosmetics", c)) {
            return Kind.COSMETICS_SHOP;
        }
        return Kind.OTHER;
    }

    /** Part of a known salon chain (their franchises decide at head office: a slower lead). */
    public static boolean chain(Poi p) {
        return CHAIN.matcher(lower(p.name())).find();
    }

    /**
     * How good a lead this is, 0 to 100 (our own estimate: Mapbox has no ratings): local over chain (40 or 5), a
     * phone to call (25), open 7 days (10) or 6 (6), no website yet (10: more to gain from Clozest), open 10 hours or
     * more a day (5), a core salon kind (10).
     */
    public static int score(Poi p) {
        Kind kind = kind(p);
        int s = chain(p) ? 5 : 40;
        if (p.phone() != null) {
            s += 25;
        }
        if (p.hours() != null) {
            s += p.hours().days() >= 7 ? 10 : p.hours().days() == 6 ? 6 : 0;
            if (p.hours().averageHours() >= 10) {
                s += 5;
            }
        }
        if (p.website() == null) {
            s += 10;
        }
        if (CORE.contains(kind)) {
            s += 10;
        }
        return Math.min(100, s);
    }

    private static boolean find(String regex, String text) {
        return Pattern.compile(regex).matcher(text).find();
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static String categories(Poi p) {
        return String.join(" ", p.categories()).toLowerCase(Locale.ROOT);
    }
}
