package in.apnidukan.kirana.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares product names the way a shopkeeper would: "AMUL TAZA 500ML TM", "Amul Taaza Toned Milk 500 ml" and "Taaza
 * milk ½ L" are the same product; "Amul Taaza 1 L" and "Amul Gold 500 ml" are not. Plain code, no AI: the same names
 * always give the same answer, fast enough to compare a bill's 50 lines with a shop's thousands of products.
 * <ul>
 *   <li>Cleaned first: case, punctuation and filler words ("new", "pouch", "pkt", a printed "Rs 10") don't count; short
 *   forms are spelled out (TM: toned milk, DW: dishwash); sizes are read as amounts (500ML = 0.5 L = ½ L).</li>
 *   <li>Compared loosely: word order doesn't matter, nor spellings ("Taaza"/"Taza", "Britania"/"Britannia"); the brand
 *   counts more than other words.</li>
 *   <li>Never loosely: a different size is never the same product, and a different variant (toned/full cream, sugar
 *   free) is never a one-tap match.</li>
 * </ul>
 */
public final class NameMatcher {

    /** A clear winner: offered as "Is this …? ✓". */
    public static final double SURE = 0.75;
    /** Worth showing as a choice. */
    public static final double LIKELY = 0.40;
    /** How far ahead of the next one a sure match must be. */
    public static final double LEAD = 0.15;

    private NameMatcher() {
    }

    /**
     * A size read from a name: an amount in grams, millilitres or pieces, and how many packs of it (a 6-pack).
     * @param amount grams for weights, millilitres for volumes, the count for pieces
     */
    public record Size(BigDecimal amount, String dim, int packs) {

        boolean sameAs(Size o) {
            return dim.equals(o.dim) && amount.compareTo(o.amount) == 0 && packs == o.packs;
        }

        /** As people write it: "500 ml", "1 kg", "6 × 70 g". */
        public String text() {
            String a;
            String d = dim;
            if (dim.equals("g") && amount.compareTo(new BigDecimal("1000")) >= 0) {
                a = amount.divide(new BigDecimal("1000"), 3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
                d = "kg";
            } else if (dim.equals("ml") && amount.compareTo(new BigDecimal("1000")) >= 0) {
                a = amount.divide(new BigDecimal("1000"), 3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
                d = "L";
            } else {
                a = amount.stripTrailingZeros().toPlainString();
            }
            String one = dim.equals("pc") ? a + " pcs" : a + " " + d;
            return packs > 1 ? packs + " × " + one : one;
        }
    }

    /**
     * A name as compared: its words (spelled out, filler dropped), the brand when known, the size when written, and the
     * variant words (toned, sugar free …) it has.
     */
    public record Parsed(List<String> words, String brand, Size size, Set<String> variants) {

        public Parsed {
            words = List.copyOf(words);
            variants = Set.copyOf(variants);
        }

        /** Nothing to compare (no words). */
        public boolean empty() {
            return words.isEmpty();
        }
    }

    /** A candidate's score against what was read: 0 to 1; never for a different size. */
    public record Score(double value, boolean sizeKnownBoth, boolean variantDiffers) {

        /** A different size: never the same product. */
        public static final Score NEVER = new Score(-1, true, false);

        public boolean never() {
            return value < 0;
        }
    }

    // ---------- reading a name ----------

    private static final Set<String> FILLER = Set.of("new", "pack", "packet", "pkt", "pouch", "pch", "rs", "inr",
            "mrp", "offer", "free", "extra", "jar", "bottle", "btl", "tin", "box", "bag", "the", "of", "and", "with",
            "for", "a", "nos", "no", "each", "per", "special", "value", "pc", "pcs", "piece", "pieces", "unit",
            "units", "x", "s");

    /** Words that make a different product of the same brand and size (never a one-tap match when they differ). */
    private static final Set<String> VARIANTS = Set.of("toned", "double", "full", "cream", "skimmed", "skim",
            "sugarfree", "diet", "lite", "light", "low", "salted", "unsalted", "plain", "masala", "classic",
            "gold", "slim", "zero", "brown", "white", "red", "green", "blue", "black", "orange", "lemon", "mint",
            "strawberry", "chocolate", "vanilla", "mango", "elaichi", "kesar", "badam", "honey", "ginger", "tulsi",
            "herbal", "sensitive", "whitening", "matic", "front", "top", "load", "liquid", "powder", "bar", "gel",
            "small", "large", "big", "jumbo", "mini", "family", "refill");

    /** Short forms on bills and labels, spelled out. */
    private static final Map<String, String> SHORT = Map.ofEntries(
            Map.entry("tm", "toned milk"), Map.entry("dtm", "double toned milk"), Map.entry("fcm", "full cream milk"),
            Map.entry("fc", "full cream"), Map.entry("sm", "skimmed milk"), Map.entry("dw", "dishwash"),
            Map.entry("choc", "chocolate"), Map.entry("choco", "chocolate"), Map.entry("bisc", "biscuit"),
            Map.entry("bis", "biscuit"), Map.entry("bisk", "biscuit"), Map.entry("biscuits", "biscuit"),
            Map.entry("exl", "excel"), Map.entry("det", "detergent"), Map.entry("pdr", "powder"),
            Map.entry("pwd", "powder"), Map.entry("pwdr", "powder"), Map.entry("liq", "liquid"),
            Map.entry("shmp", "shampoo"), Map.entry("shamp", "shampoo"), Map.entry("tp", "toothpaste"),
            Map.entry("tpaste", "toothpaste"), Map.entry("paste", "toothpaste"), Map.entry("sug", "sugar"),
            Map.entry("sf", "sugarfree"), Map.entry("noodle", "noodles"), Map.entry("ndls", "noodles"),
            Map.entry("chips", "chips"), Map.entry("chip", "chips"), Map.entry("namk", "namkeen"),
            Map.entry("nmkn", "namkeen"), Map.entry("bhuj", "bhujia"), Map.entry("ckn", "chicken"),
            Map.entry("veg", "vegetable"), Map.entry("hw", "handwash"), Map.entry("bw", "bodywash"),
            Map.entry("fw", "facewash"), Map.entry("oil", "oil"), Map.entry("ref", "refined"),
            Map.entry("rfnd", "refined"), Map.entry("sunfl", "sunflower"), Map.entry("mst", "mustard"),
            Map.entry("mstrd", "mustard"), Map.entry("atta", "atta"), Map.entry("chakki", "chakki"),
            Map.entry("dal", "dal"), Map.entry("daal", "dal"), Map.entry("tea", "tea"), Map.entry("chai", "tea"),
            Map.entry("cof", "coffee"), Map.entry("coff", "coffee"), Map.entry("inst", "instant"),
            Map.entry("ghee", "ghee"), Map.entry("btr", "butter"), Map.entry("chs", "cheese"),
            Map.entry("pnr", "paneer"), Map.entry("crd", "curd"), Map.entry("dahi", "curd"),
            Map.entry("lassi", "lassi"), Map.entry("bev", "beverage"), Map.entry("drnk", "drink"),
            Map.entry("cd", "colddrink"), Map.entry("sft", "soft"), Map.entry("wtr", "water"),
            Map.entry("dtrgnt", "detergent"), Map.entry("clnr", "cleaner"), Map.entry("flr", "floor"),
            Map.entry("tlt", "toilet"), Map.entry("agarbatti", "agarbatti"), Map.entry("agbt", "agarbatti"));

    /** Brand spellings people often write: the usual one. */
    private static final Map<String, String> BRANDS = Map.ofEntries(
            Map.entry("taza", "taaza"), Map.entry("britania", "britannia"), Map.entry("magi", "maggi"),
            Map.entry("colget", "colgate"), Map.entry("surfexcel", "surf excel"), Map.entry("parleg", "parle g"),
            Map.entry("haldiram", "haldirams"), Map.entry("haldirams", "haldirams"), Map.entry("lays", "lays"),
            Map.entry("kurkure", "kurkure"), Map.entry("tatasalt", "tata salt"), Map.entry("aashirvad", "aashirvaad"),
            Map.entry("ashirvad", "aashirvaad"), Map.entry("ashirwad", "aashirvaad"), Map.entry("fortun", "fortune"),
            Map.entry("dettol", "dettol"), Map.entry("lifebouy", "lifebuoy"), Map.entry("lifeboy", "lifebuoy"),
            Map.entry("vimbar", "vim bar"), Map.entry("goodday", "good day"), Map.entry("darkfantasy", "dark fantasy"),
            Map.entry("redlabel", "red label"), Map.entry("tajmahal", "taj mahal"), Map.entry("nescafe", "nescafe"),
            Map.entry("brooke", "brooke bond"), Map.entry("mdh", "mdh"), Map.entry("everest", "everest"));

    private static final Pattern NUMBER_UNIT = Pattern.compile(
            "(\\d+(?:\\.\\d+)?|\\d+/\\d+|½|¼|¾)\\s*(kgs?|kilos?|gms?|grams?|g|gr|grm|ml|mls|l|ltrs?|lt|litres?|liters?|ltr"
                    + "|pcs?|pieces?|nos?)\\b");
    private static final Pattern PACKS = Pattern.compile(
            "(?:\\b(\\d{1,3})\\s*[x×*]\\s*(?=\\d))|(?:\\bpack\\s+of\\s+(\\d{1,3})\\b)|(?:\\b(\\d{1,3})\\s*(?:pack|pk)\\b)");
    private static final Pattern PRICE = Pattern.compile("\\b(?:rs|inr|mrp|₹)\\.?\\s*\\d+(?:\\.\\d+)?\\b|₹\\s*\\d+");

    /**
     * Reads a name as compared. {@code brandHint} and {@code sizeHint} (from a label read, or the shared catalogue)
     * win over what the name itself says; either may be null.
     */
    public static Parsed parse(String name, String brandHint, String sizeHint) {
        String text = clean(name == null ? "" : name);
        Size size = null;
        if (sizeHint != null && !sizeHint.isBlank()) {
            size = size(clean(sizeHint)).orElse(null);
        }
        Optional<Size> inName = size(text);
        if (size == null) {
            size = inName.orElse(null);
        }
        text = NUMBER_UNIT.matcher(PACKS.matcher(PRICE.matcher(text).replaceAll(" ")).replaceAll(" ")).replaceAll(" ");
        List<String> words = new ArrayList<>();
        for (String w : text.split("\\s+")) {
            if (w.isEmpty() || FILLER.contains(w) || w.chars().allMatch(Character::isDigit)) {
                continue;
            }
            String spelled = SHORT.getOrDefault(w, BRANDS.getOrDefault(w, w));
            for (String part : spelled.split(" ")) {
                if (!part.isEmpty() && !FILLER.contains(part)) {
                    words.add(part);
                }
            }
        }
        // "sugar free" is one variant
        for (int i = 0; i + 1 < words.size(); i++) {
            if (words.get(i).equals("sugar") && words.get(i + 1).equals("free")) {
                words.set(i, "sugarfree");
                words.remove(i + 1);
            }
        }
        String brand = null;
        if (brandHint != null && !brandHint.isBlank()) {
            List<String> b = new ArrayList<>();
            for (String w : clean(brandHint).split("\\s+")) {
                if (!w.isEmpty()) {
                    b.add(BRANDS.getOrDefault(w, w));
                }
            }
            brand = String.join(" ", b).strip();
            if (brand.isEmpty()) {
                brand = null;
            }
        }
        Set<String> variants = new HashSet<>();
        for (String w : words) {
            if (VARIANTS.contains(w)) {
                variants.add(w);
            }
        }
        return new Parsed(new ArrayList<>(new LinkedHashSet<>(words)), brand, size, variants);
    }

    /** Lower case, accents and punctuation gone, digits and letters apart ("500ML" → "500 ml"). */
    static String clean(String s) {
        String t = Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replace("½", " ½ ").replace("¼", " ¼ ").replace("¾", " ¾ ").replace("₹", " ₹ ");
        t = t.replaceAll("[^\\p{L}\\p{N}½¼¾₹./×* ]", " ");
        t = t.replaceAll("(?<=\\p{L})(?=\\d)|(?<=\\d)(?=\\p{L})", " ");
        t = t.replaceAll("(?<!\\d)\\.|\\.(?!\\d)", " ");   // a dot only inside a number
        return t.replaceAll("\\s+", " ").strip();
    }

    /** The size written in a cleaned name: the last amount with a unit, times the pack count if one is written. */
    static Optional<Size> size(String cleaned) {
        Matcher m = NUMBER_UNIT.matcher(cleaned);
        Size last = null;
        while (m.find()) {
            BigDecimal n = number(m.group(1));
            if (n == null || n.signum() <= 0) {
                continue;
            }
            String u = m.group(2);
            if (u.startsWith("kg") || u.startsWith("kilo")) {
                last = new Size(n.multiply(new BigDecimal("1000")).stripTrailingZeros(), "g", 1);
            } else if (u.startsWith("g")) {
                last = new Size(n.stripTrailingZeros(), "g", 1);
            } else if (u.startsWith("ml")) {
                last = new Size(n.stripTrailingZeros(), "ml", 1);
            } else if (u.startsWith("l")) {
                last = new Size(n.multiply(new BigDecimal("1000")).stripTrailingZeros(), "ml", 1);
            } else {
                last = new Size(n.stripTrailingZeros(), "pc", 1);
            }
        }
        if (last == null) {
            return Optional.empty();
        }
        Matcher p = PACKS.matcher(cleaned);
        if (p.find()) {
            String g = p.group(1) != null ? p.group(1) : p.group(2) != null ? p.group(2) : p.group(3);
            int packs = Integer.parseInt(g);
            if (packs > 1 && !last.dim().equals("pc")) {
                last = new Size(last.amount(), last.dim(), packs);
            }
        }
        return Optional.of(last);
    }

    private static BigDecimal number(String s) {
        switch (s) {
            case "½":
                return new BigDecimal("0.5");
            case "¼":
                return new BigDecimal("0.25");
            case "¾":
                return new BigDecimal("0.75");
            default:
                break;
        }
        try {
            if (s.contains("/")) {
                String[] f = s.split("/");
                BigDecimal d = new BigDecimal(f[1]);
                return d.signum() == 0 ? null : new BigDecimal(f[0]).divide(d, 3, RoundingMode.HALF_UP);
            }
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------- comparing ----------

    /**
     * How well a candidate (a shop's product, a catalogue item) fits what was read: 0 to 1, or {@link Score#NEVER} for a
     * different size. The brand weighs as much as two other words; a brand that differs when both are known costs much.
     */
    public static Score score(Parsed want, Parsed have) {
        if (want.empty() || have.empty()) {
            return new Score(0, false, false);
        }
        boolean sizesKnown = want.size() != null && have.size() != null;
        if (sizesKnown && !want.size().sameAs(have.size())) {
            return Score.NEVER;
        }
        // The brand's words are left out of the comparison (the brand is weighed below, once): the rest of the name
        // must match too, so "Amul Butter 100 g" is never "Amul Cheese 100 g"
        String brand = want.brand() != null ? want.brand() : have.brand();
        List<String> wantWords = withoutBrand(want.words(), brand);
        List<String> haveWords = withoutBrand(have.words(), brand);
        // What was read is only the brand: nothing tells which of its products it is
        boolean onlyBrand = wantWords.isEmpty();
        if (wantWords.isEmpty() || haveWords.isEmpty()) {
            wantWords = want.words();
            haveWords = have.words();
        }
        double matched = 0;
        double total = 0;
        Set<String> used = new HashSet<>();
        for (String w : wantWords) {
            double weight = weight(w);
            total += weight;
            String best = null;
            double bestSim = 0;
            for (String h : haveWords) {
                if (used.contains(h)) {
                    continue;
                }
                double sim = similar(w, h);
                if (sim > bestSim) {
                    bestSim = sim;
                    best = h;
                }
            }
            if (best != null && bestSim >= 0.8) {
                used.add(best);
                matched += weight * bestSim;
            }
        }
        double haveTotal = 0;
        for (String h : haveWords) {
            haveTotal += weight(h);
        }
        boolean restDiffers = matched == 0 || onlyBrand;
        // Dice-like: what both share, against both names' length (extra words on either side count a little)
        double value = 2 * matched / (total + haveTotal);
        // The brand
        if (want.brand() != null) {
            boolean brandIn = have.brand() != null ? similarPhrase(want.brand(), have.brand())
                    : containsPhrase(have.words(), want.brand());
            if (brandIn) {
                value = Math.min(1, value + 0.15);
            } else if (have.brand() != null) {
                value -= 0.4;
            }
        }
        // The size: the same both ways is good; written on one side only is a small doubt
        if (sizesKnown) {
            value = Math.min(1, value + 0.1);
        } else if (want.size() != null || have.size() != null) {
            value -= 0.05;
        }
        // A variant on one side that the other lacks or contradicts ("Gold" against "Taaza", or nothing): a choice
        boolean variantDiffers = !want.variants().equals(have.variants());
        if (variantDiffers) {
            value = Math.min(value, SURE - 0.01);   // never a one-tap match
        }
        if (restDiffers) {
            value = Math.min(value, LIKELY - 0.05);   // only the brand (and size) in common: not even offered
        }
        return new Score(Math.max(0, Math.min(1, value)), sizesKnown, variantDiffers);
    }

    /** The words without those of the brand (every word similar to one of the brand's). */
    private static List<String> withoutBrand(List<String> words, String brand) {
        if (brand == null) {
            return words;
        }
        String[] parts = brand.split(" ");
        List<String> out = new java.util.ArrayList<>();
        for (String w : words) {
            boolean ofBrand = false;
            for (String p : parts) {
                if (similar(w, p) >= 0.8) {
                    ofBrand = true;
                    break;
                }
            }
            if (!ofBrand) {
                out.add(w);
            }
        }
        return out;
    }

    /** Short words and generic words weigh less than names. */
    private static double weight(String w) {
        if (w.length() <= 2) {
            return 0.5;
        }
        return switch (w) {
            case "milk", "oil", "tea", "biscuit", "soap", "powder", "salt", "sugar", "rice", "atta", "dal",
                 "noodles", "chips", "toothpaste", "shampoo", "detergent", "water", "coffee", "ghee", "butter" -> 0.8;
            default -> 1.0;
        };
    }

    /**
     * How alike two words are, 0 to 1: the same; the same consonants in order ("taaza"/"taza", "maggi"/"magi"); one
     * the start of the other ("choco"/"chocolate"); or few letters apart for longer words.
     */
    static double similar(String a, String b) {
        if (a.equals(b)) {
            return 1;
        }
        if (Character.isDigit(a.charAt(0)) || Character.isDigit(b.charAt(0))) {
            return 0;
        }
        if (a.length() >= 3 && b.length() >= 3 && skeleton(a).equals(skeleton(b)) && !skeleton(a).isEmpty()) {
            return 0.95;
        }
        if (Math.min(a.length(), b.length()) >= 4 && (a.startsWith(b) || b.startsWith(a))) {
            return 0.85;
        }
        int longest = Math.max(a.length(), b.length());
        if (longest < 5) {
            return 0;
        }
        int d = distance(a, b);
        double sim = 1 - (double) d / longest;
        return sim >= 0.8 ? sim : 0;
    }

    /** A word's consonants, doubled letters once: "britannia" → "brtn", "taaza" → "tz". */
    static String skeleton(String w) {
        StringBuilder s = new StringBuilder();
        char prev = 0;
        for (char c : w.toCharArray()) {
            if ("aeiouyhw".indexOf(c) >= 0 || c == prev) {
                prev = c;
                continue;
            }
            s.append(c);
            prev = c;
        }
        return s.toString();
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    private static boolean similarPhrase(String a, String b) {
        String[] x = a.split(" ");
        String[] y = b.split(" ");
        if (x.length != y.length) {
            return similar(a.replace(" ", ""), b.replace(" ", "")) >= 0.8;
        }
        for (int i = 0; i < x.length; i++) {
            if (similar(x[i], y[i]) < 0.8) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsPhrase(List<String> words, String phrase) {
        for (String p : phrase.split(" ")) {
            if (words.stream().noneMatch(w -> similar(p, w) >= 0.8)) {
                return false;
            }
        }
        return true;
    }

    // ---------- choosing ----------

    /** A candidate with its score. */
    public record Ranked<T>(T candidate, double score, boolean variantDiffers) {
    }

    /**
     * The candidates worth showing, best first, at most {@code max}: never one of a different size, none below
     * {@link #LIKELY}. Equal scores keep the given order (the caller's preference: bill lines before other products).
     */
    public static <T> List<Ranked<T>> rank(Parsed want, List<T> candidates, java.util.function.Function<T, Parsed> parsed,
                                           int max) {
        List<Ranked<T>> out = new ArrayList<>();
        for (T c : candidates) {
            Score s = score(want, parsed.apply(c));
            if (!s.never() && s.value() >= LIKELY) {
                out.add(new Ranked<>(c, s.value(), s.variantDiffers()));
            }
        }
        out.sort(Comparator.comparingDouble((Ranked<T> r) -> -r.score()));
        return out.size() > max ? List.copyOf(out.subList(0, max)) : List.copyOf(out);
    }

    /** The first is a clear winner: offered as one tap. */
    public static boolean sure(List<? extends Ranked<?>> ranked) {
        if (ranked.isEmpty() || ranked.get(0).score() < SURE || ranked.get(0).variantDiffers()) {
            return false;
        }
        return ranked.size() == 1 || ranked.get(0).score() - ranked.get(1).score() >= LEAD;
    }

    /**
     * The key a wording is remembered by: its cleaned words in order (so "AMUL TAZA 500ML TM" and "Amul Taza 500 ml
     * tm" are one wording), at most 150 letters.
     */
    public static String wordingKey(String wording) {
        String k = clean(wording == null ? "" : wording);
        return k.length() > 150 ? k.substring(0, 150) : k;
    }
}
