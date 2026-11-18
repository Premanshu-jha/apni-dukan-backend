package in.apnidukan.kirana.domain;

/**
 * Whether a shop's photo of one of its products may be its picture, from what the AI saw in it: the right product
 * (brand and variant), the right size, the front of the pack, sharp, one kind of product, no people. Loose goods (sold
 * by the kg) are checked for being those goods, sharp and without people (they have no front or printed size). Every
 * refusal says why and what to do, in the shopkeeper's words.
 */
public final class PhotoRules {

    private PhotoRules() {
    }

    /** What the AI saw in the photo (and the second photo of the size, when sent). */
    public record Seen(boolean goods, boolean people, boolean oneKind, boolean sharp, boolean front, boolean matches,
                       String brand, String name, String size) {
    }

    /**
     * The verdict: OK, REFUSED or NEED_SIZE (a photo of the side where the size is printed), with the reason in English
     * and as a code the app words in the shop's language: NOT_GOODS, PEOPLE, MANY, BLURRY, NOT_FRONT, OTHER_PRODUCT
     * (what it showed in {@code seen}), WRONG_SIZE (the size it showed in {@code seen}), NEED_SIZE, SIZE_UNREADABLE.
     */
    public record Verdict(String status, String code, String reason, String seen) {

        public boolean ok() {
            return "OK".equals(status);
        }

        static Verdict refused(String code, String why) {
            return new Verdict("REFUSED", code, why, null);
        }

        static final Verdict OK = new Verdict("OK", null, null, null);
    }

    /**
     * @param productName the product as the shop named it (its size read from the name)
     * @param loose       sold by the kg (rice, dal from a sack)
     * @param sizePhoto   a second photo of the size was sent with it
     */
    public static Verdict decide(String productName, boolean loose, Seen s, boolean sizePhoto) {
        if (!s.goods()) {
            return Verdict.refused("NOT_GOODS", loose ? "This photo doesn't show the goods. Take a photo of the " + productName
                    + " in its sack or bin." : "This photo doesn't show a product. Take a photo of the front of the "
                    + "pack.");
        }
        if (s.people()) {
            return Verdict.refused("PEOPLE", "Take the photo without people in it: only the product.");
        }
        if (!s.oneKind()) {
            return Verdict.refused("MANY", "Only one product in the photo, please: " + productName + ".");
        }
        if (!s.sharp()) {
            return Verdict.refused("BLURRY", "The photo is blurry. Hold the phone steady, in good light, and take it again.");
        }
        if (loose) {
            return s.matches() ? Verdict.OK
                    : Verdict.refused("OTHER_PRODUCT", "This doesn't look like " + productName + ". Take a photo of the "
                    + productName + " in its sack or bin.");
        }
        if (!s.front()) {
            return Verdict.refused("NOT_FRONT", "Take the photo of the front of the pack, where its name is.");
        }
        NameMatcher.Parsed want = NameMatcher.parse(productName, null, null);
        NameMatcher.Parsed seen = NameMatcher.parse(join(s.brand(), s.name()), s.brand(), s.size());
        String seenText = seenText(s);
        if (!s.matches() || variantsContradict(seen, want)) {
            return new Verdict("REFUSED", "OTHER_PRODUCT", "This looks like " + seenText + ", not " + productName
                    + ". Take a photo of " + productName + ", or fix the product's name if it is wrong.", seenText);
        }
        if (want.size() == null) {
            return Verdict.OK;   // the shop's name has no size to check
        }
        if (seen.size() == null) {
            return new Verdict("NEED_SIZE", sizePhoto ? "SIZE_UNREADABLE" : "NEED_SIZE", sizePhoto
                    ? "We still can't read the size. Take a clear photo of the side where " + want.size().text()
                    + " is printed."
                    : "We can't see the size on the front. Take a photo of the side where " + want.size().text()
                    + " is printed.", null);
        }
        if (!seen.size().sameAs(want.size())) {
            return new Verdict("REFUSED", "WRONG_SIZE", "This pack says " + seen.size().text() + ", but the product "
                    + "is " + productName + ". Take a photo of the " + want.size().text() + " pack, or fix the size in "
                    + "the product's name if it is wrong.", seen.size().text());
        }
        return Verdict.OK;
    }

    /**
     * Both names say a variant and they differ (toned against full cream, sugar free against regular): another
     * product. A variant on one side only isn't a contradiction (shops often leave it out of their names).
     */
    public static boolean variantsContradict(NameMatcher.Parsed a, NameMatcher.Parsed b) {
        return !a.variants().isEmpty() && !b.variants().isEmpty() && !a.variants().equals(b.variants());
    }

    /** What to make sure the photo shows, for the guidance on the camera screen: the size, or null. */
    public static String sizeToShow(String productName) {
        NameMatcher.Size size = NameMatcher.parse(productName, null, null).size();
        return size == null ? null : size.text();
    }

    private static String seenText(Seen s) {
        String t = join(join(s.brand(), s.name()), s.size());
        return t.isBlank() ? "another product" : t;
    }

    private static String join(String a, String b) {
        if (a == null || a.isBlank()) {
            return b == null ? "" : b.strip();
        }
        if (b == null || b.isBlank() || b.toLowerCase(java.util.Locale.ROOT).contains(a.toLowerCase(
                java.util.Locale.ROOT))) {
            return b == null || b.isBlank() ? a.strip() : b.strip();
        }
        return a.strip() + " " + b.strip();
    }
}
