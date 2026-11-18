package in.apnidukan.kirana.service;

import in.apnidukan.kirana.domain.NameMatcher;
import in.apnidukan.kirana.domain.NameMatcher.Parsed;
import in.apnidukan.kirana.domain.NameMatcher.Ranked;
import in.apnidukan.kirana.repo.CatalogueStore;
import in.apnidukan.kirana.repo.ScanStore;
import in.apnidukan.kirana.repo.ScanStore.Candidate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Finds which product a bill line, a paper bill's line or a pack's label is: the shop's products first, then the
 * shared catalogue's items (a product the shop doesn't have yet), compared by {@link NameMatcher} in code. The AI only
 * read the picture; this decides what to offer: one clear match (one tap), a few to choose from, or nothing.
 */
@Service
public class MatchService {

    /** Suggestions shown at most. */
    static final int MAX = 3;
    /** A small lift for the likelier candidates (a bill's unlinked products for a new barcode, …). */
    private static final double PREFERRED = 0.05;

    private final ScanStore scans;
    private final CatalogueStore catalogue;

    public MatchService(ScanStore scans, CatalogueStore catalogue) {
        this.scans = scans;
        this.catalogue = catalogue;
    }

    /**
     * What was read.
     * @param wording as printed or written
     * @param brand   the AI's tidy reading (or the catalogue's), if any
     * @param name    the product's name with its variant, without brand and size, if read
     * @param size    "500 ml", if read
     */
    public record Want(String wording, String brand, String name, String size) {

        Parsed parsed() {
            String text = name != null ? (brand == null ? name : brand + " " + name) : wording;
            Parsed p = NameMatcher.parse(text, brand, size);
            // A tidy name with no size may still carry one in the wording ("… 500ML")
            if (p.size() == null && wording != null && name != null) {
                Parsed w = NameMatcher.parse(wording, null, null);
                if (w.size() != null) {
                    p = new Parsed(p.words(), p.brand(), w.size(), p.variants());
                }
            }
            return p;
        }
    }

    /**
     * A suggestion: a shop's product, or a catalogue item the shop can add as a new product.
     * @param productId null for a catalogue item the shop doesn't have
     */
    public record Suggestion(UUID productId, UUID itemId, String name, String brand, String size, Long pricePaise,
                             double score) {
    }

    /** What to offer: the suggestions, best first, and whether the first is a clear winner (one tap). */
    public record Result(List<Suggestion> suggestions, boolean sure) {

        public Result {
            suggestions = List.copyOf(suggestions);
        }

        static final Result NONE = new Result(List.of(), false);

        public Suggestion best() {
            return suggestions.isEmpty() ? null : suggestions.get(0);
        }
    }

    /** A shop's products, read once and parsed once, for many matches (a bill's lines, a page of unknown scans). */
    public final class Shop {
        private final List<Candidate> products;
        private final Map<Candidate, Parsed> parsed = new HashMap<>();

        private Shop(List<Candidate> products) {
            this.products = List.copyOf(products);
            for (Candidate c : products) {
                parsed.put(c, NameMatcher.parse(c.name(), c.brand(), c.size()));
            }
        }

        public List<Candidate> products() {
            return products;
        }
    }

    public Shop shop(UUID shopId) {
        return new Shop(scans.products(shopId));
    }

    /**
     * Matches what was read against the shop's products, and (when {@code catalogueToo} and the shop has no clear
     * match) the catalogue's items it doesn't have. {@code preferred}: products likelier to be it (lifted a little).
     */
    public Result match(Shop shop, Want want, Set<UUID> preferred, boolean catalogueToo) {
        Parsed p = want.parsed();
        if (p.empty()) {
            return Result.NONE;
        }
        List<Ranked<Candidate>> ranked = new ArrayList<>(NameMatcher.rank(p, shop.products, shop.parsed::get,
                MAX * 3));
        if (!preferred.isEmpty()) {
            ranked.replaceAll(r -> preferred.contains(r.candidate().productId())
                    ? new Ranked<>(r.candidate(), Math.min(1, r.score() + PREFERRED), r.variantDiffers()) : r);
            ranked.sort((a, b) -> Double.compare(b.score(), a.score()));
        }
        boolean sure = NameMatcher.sure(ranked);
        if (catalogueToo && !sure) {
            Set<UUID> shopItems = new HashSet<>();
            for (Candidate c : shop.products) {
                if (c.itemId() != null) {
                    shopItems.add(c.itemId());
                }
            }
            String search = want.name() != null ? (want.brand() == null ? want.name() : want.brand() + " " + want.name())
                    : want.wording();
            List<Candidate> items = scans.catalogue(search, 20).stream()
                    .filter(c -> !shopItems.contains(c.itemId())).toList();
            ranked.addAll(NameMatcher.rank(p, items, c -> NameMatcher.parse(c.name(), c.brand(), c.size()), MAX));
            ranked.sort((a, b) -> Double.compare(b.score(), a.score()));
            sure = NameMatcher.sure(ranked);
        }
        List<Suggestion> out = new ArrayList<>();
        for (Ranked<Candidate> r : ranked) {
            if (out.size() == MAX) {
                break;
            }
            Candidate c = r.candidate();
            out.add(new Suggestion(c.productId(), c.itemId(), c.name(), c.brand(), c.size(), c.pricePaise(),
                    Math.round(r.score() * 100) / 100.0));
        }
        return new Result(out, sure);
    }

    /**
     * The brand a barcode most likely has, from other catalogue barcodes with the same company prefix (an Indian
     * barcode's first 8 digits: 890 and most of the company's number); null when none is known.
     */
    public String brandOfBarcode(String code) {
        if (code == null || code.length() < 12 || !code.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return catalogue.brandByPrefix(code.substring(0, 8));
    }
}
