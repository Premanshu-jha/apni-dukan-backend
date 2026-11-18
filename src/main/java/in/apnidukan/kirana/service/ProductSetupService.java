package in.apnidukan.kirana.service;

import in.apnidukan.common.context.ProductReadiness;
import in.apnidukan.common.files.FileRefs;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.kirana.api.StockRequests.LineDecision;
import in.apnidukan.kirana.api.StockResponses.ListLinePage;
import in.apnidukan.kirana.api.StockResponses.ListLineView;
import in.apnidukan.kirana.api.StockResponses.PhotoNeededPage;
import in.apnidukan.kirana.api.StockResponses.PhotoNeededView;
import in.apnidukan.kirana.api.StockResponses.ProductRef;
import in.apnidukan.kirana.api.StockResponses.SetupPageView;
import in.apnidukan.kirana.api.StockResponses.SetupView;
import in.apnidukan.kirana.api.StockResponses.SuggestionView;
import in.apnidukan.kirana.domain.Cursors;
import in.apnidukan.kirana.domain.PhotoRules;
import in.apnidukan.kirana.domain.Product;
import in.apnidukan.kirana.domain.Quantities;
import in.apnidukan.kirana.domain.Unit;
import in.apnidukan.kirana.repo.ProductStore;
import in.apnidukan.kirana.repo.ReadStore;
import in.apnidukan.kirana.repo.ReadStore.Read;
import in.apnidukan.kirana.repo.SetupStore;
import in.apnidukan.kirana.repo.SetupStore.Line;
import in.apnidukan.kirana.repo.SetupStore.Totals;
import in.apnidukan.kirana.service.KiranaAccess.Caller;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static in.apnidukan.kirana.service.KiranaChecks.invalid;

/**
 * A kirana's products set up after Submit (owner or helper; finishing is the owner's): every product the shop sells
 * with a count of what is on the shelf, from photographed pages of its own list (each read by AI into lines to add or
 * skip, {@link ReadJob}) or added one by one. When the owner says all are added, the field visit is booked
 * ({@link ProductReadiness}); the shop can't be approved before. Every line is saved as it is decided, so the setup
 * goes on from where it was left (another phone, a reload) and a line is never added twice.
 */
@Service
public class ProductSetupService {

    /** List pages a shop may keep (failed and deleted pages don't count). */
    public static final int MAX_LIST_PAGES = 60;
    /** List pages a shop may send in a day, deleted and failed ones too (each is an AI read). */
    public static final int LIST_PAGES_PER_DAY = 100;
    /** Lines a page of the list shows at once (each with its suggestions). */
    static final int PAGE = 20;
    private static final int UNCOUNTED_SHOWN = 20;
    /** The prices a product may have (kirana_products' rule): an MRP read outside them is asked for instead. */
    private static final long MAX_PRICE_PAISE = 10_000_000;

    private final KiranaAccess access;
    private final SetupStore setup;
    private final ReadStore reads;
    private final ProductStore products;
    private final ProductService productService;
    private final MatchService matcher;
    private final ProductReadiness readiness;
    private final FileRefs files;
    private final Clock clock;

    public ProductSetupService(KiranaAccess access, SetupStore setup, ReadStore reads, ProductStore products,
                               ProductService productService, MatchService matcher, ProductReadiness readiness,
                               FileRefs files, Clock clock) {
        this.access = access;
        this.setup = setup;
        this.reads = reads;
        this.products = products;
        this.productService = productService;
        this.matcher = matcher;
        this.readiness = readiness;
        this.files = files;
        this.clock = clock;
    }

    // ---------- the reading job ----------

    /** A list page just read: its lines, to be checked (the caller holds a transaction; a page is read once). */
    void fromRead(Read r, KiranaDocumentAi.StockListFacts f, Instant now) {
        List<Line> lines = new ArrayList<>();
        for (KiranaDocumentAi.ListLine l : f.lines()) {
            lines.add(new Line(null, r.shopId(), r.id(), 0, l.wording(), l.brand(), l.name(), l.size(), l.byWeight(),
                    l.mrpPaise(), l.quantity(), "NEW", null, null, now));
        }
        setup.insertLines(SetupStore.numbered(r.shopId(), r.id(), lines));
        if (f.leftOut() > 0) {
            setup.setLeftOut(r.id(), f.leftOut());
        }
    }

    // ---------- the shop ----------

    /** Where the setup stands (owner or helper). */
    @Transactional(readOnly = true)
    public SetupView get(UUID userId) {
        Caller c = access.caller(userId);
        return view(c);
    }

    /**
     * The list's lines in one status (NEW, to check, by default; SKIPPED to bring one back; ADDED to see what was
     * counted), page by page in the order the pages were sent, 20 at a time; lines to check come with what they may be
     * (the shop's products first, then catalogue items). {@code cursor}: from the last answer's {@code next}. 400
     * naming status otherwise.
     */
    @Transactional(readOnly = true)
    public ListLinePage lines(UUID userId, String status, String cursor) {
        Caller c = access.caller(userId);
        String want = status == null || status.isBlank() ? "NEW" : status.strip().toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("NEW", "SKIPPED", "ADDED").contains(want)) {
            throw invalid("status", "Use NEW, SKIPPED or ADDED");
        }
        // The cursor: "<page sent at>|<page id>" and the line number
        Optional<String[]> after = Cursors.decode(cursor).map(a -> new String[] {a[0].split("\\|", 2)[0],
                a[0].contains("|") ? a[0].split("\\|", 2)[1] : "", a[1]});
        Instant afterAt = after.flatMap(a -> PhotoReads.instant(a[0])).orElse(null);
        UUID afterRead = afterAt == null ? null : ProductService.uuid(after.get()[1]);
        Integer afterLine = afterAt == null ? null : lineNo(after.get()[2]);
        if (afterRead == null || afterLine == null) {
            afterAt = null;   // no cursor, or one that doesn't parse: from the start
        }
        List<Line> rows = setup.lines(c.shopId(), want, afterAt, afterRead, afterLine, PAGE);
        boolean more = rows.size() > PAGE;
        List<Line> page = more ? rows.subList(0, PAGE) : rows;
        Map<UUID, Integer> pageNos = pageNos(c.shopId());
        MatchService.Shop shop = "NEW".equals(want) && !page.isEmpty() ? matcher.shop(c.shopId()) : null;
        List<ListLineView> out = new ArrayList<>();
        for (Line l : page) {
            out.add(view(l, pageNos, shop == null ? MatchService.Result.NONE : suggestions(shop, l)));
        }
        String next = null;
        if (more) {
            Line last = page.get(page.size() - 1);
            next = Cursors.encode(setup.pageSentAt(last.readId()) + "|" + last.readId(),
                    Integer.toString(last.lineNo()));
        }
        return new ListLinePage(out, next);
    }

    /**
     * The shop's decision on a line (owner or helper):
     * <ul>
     * <li>ADD: a product the shop has ({@code productId}: its stock becomes the count; 409 ALREADY_COUNTED with the
     * product and its stock when another line was added onto it, until {@code addToCount} says add or replace), or a
     * new one (from a catalogue item and/or the name, unit and price: 400 naming what is missing; 409 NAME_TAKEN
     * naming the product that has the name, so the app offers it instead). {@code quantity}: counted now, 0 or more
     * (400 naming it). Added again (a retry after a lost answer): the line as it is, nothing counted twice.</li>
     * <li>SKIP: not a product to add (a heading, something not sold any more). UNDO: a skipped line back to check.</li>
     * </ul>
     * 404 LINE_NOT_FOUND (also on a page deleted); 409 LINE_ADDED for SKIP or UNDO on a line already added (the
     * product itself is changed instead).
     */
    @Transactional
    public ListLineView decide(UUID userId, UUID lineId, LineDecision in) {
        Caller c = access.caller(userId);
        // The shop's setup lock first (as finish takes it): a line can't change while the owner finishes
        reads.lockListPages(c.shopId());
        Line line = setup.lock(c.shopId(), lineId).orElseThrow(ProductSetupService::notFound);
        Instant now = KiranaChecks.now(clock);
        switch (in.action()) {
            case ADD -> {
                if ("ADDED".equals(line.status())) {
                    return view(line, pageNos(c.shopId()), MatchService.Result.NONE);
                }
                if (in.quantity() == null || in.quantity().signum() < 0) {
                    throw invalid("quantity", "Count how many are on the shelf (0 if none)");
                }
                String note = "Product setup: from your list";
                UUID productId;
                BigDecimal counted = in.quantity();   // this line's count (the agent compares it with the list's)
                if (in.productId() != null) {
                    Product p = products.find(c.shopId(), in.productId()).filter(Product::active)
                            .orElseThrow(() -> invalid("productId", "Pick one of your products"));
                    productId = p.id();
                    if (setup.addedElsewhere(c.shopId(), productId, line.id())) {
                        if (in.addToCount() == null) {
                            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_COUNTED", p.name() + " was already "
                                    + "counted from your list: " + p.stock().stripTrailingZeros().toPlainString()
                                    + ". Add this count to it, or replace it?", Map.of("productId", productId,
                                    "name", p.name(), "stock", p.stock()));
                        }
                    }
                    productService.countFor(c.shopId(), userId, productId, in.quantity(),
                            Boolean.TRUE.equals(in.addToCount()), note);
                } else {
                    // Left out: the line's own reading (loose goods by kg, packs by piece) unless from the catalogue
                    Unit unit = in.unit() != null ? in.unit() : in.itemId() != null ? null
                            : line.byWeight() ? Unit.KG : Unit.PIECE;
                    Long price = in.pricePaise() != null ? in.pricePaise()
                            : in.itemId() == null ? usablePrice(line.mrpPaise()) : null;
                    productId = productService.newFor(userId, in.itemId(), in.name() != null ? in.name()
                            : in.itemId() == null ? productName(line) : null, unit, price, in.quantity());
                }
                setup.setStatus(line.id(), "ADDED", productId, counted, now);
            }
            case SKIP -> {
                requireNotAdded(line);
                setup.setStatus(line.id(), "SKIPPED", null, null, now);
            }
            case UNDO -> {
                requireNotAdded(line);
                setup.setStatus(line.id(), "NEW", null, null, now);
            }
        }
        Line after = setup.lock(c.shopId(), lineId).orElseThrow();
        return view(after, pageNos(c.shopId()), "NEW".equals(after.status())
                ? suggestions(matcher.shop(c.shopId()), after) : MatchService.Result.NONE);
    }

    /**
     * The shop's products still needing a picture (owner or helper), by name, 20 at a time, each with what its photo
     * must show (for the camera screen's guidance) and how its photo's check stands. {@code cursor}: from the last
     * answer's {@code next}.
     */
    @Transactional(readOnly = true)
    public PhotoNeededPage photos(UUID userId, String cursor) {
        Caller c = access.caller(userId);
        // The cursor: "<rank>|<name>" and the id
        Optional<String[]> after = Cursors.decode(cursor);
        UUID afterId = after.map(a -> ProductService.uuid(a[1])).orElse(null);
        Integer afterRank = null;
        String afterName = null;
        if (afterId != null && after.get()[0].contains("|")) {
            afterRank = lineNo(after.get()[0].substring(0, after.get()[0].indexOf('|')));
            afterName = after.get()[0].substring(after.get()[0].indexOf('|') + 1);
        }
        if (afterRank == null) {
            afterName = null;   // no cursor, or one that doesn't parse: from the start
        }
        List<ProductStore.NeedsPhoto> rows = products.needingPhotos(c.shopId(), afterRank, afterName,
                afterName == null ? null : afterId, PAGE);
        boolean more = rows.size() > PAGE;
        List<ProductStore.NeedsPhoto> page = more ? rows.subList(0, PAGE) : rows;
        Map<UUID, String> links = files.links(page.stream().map(ProductStore.NeedsPhoto::newFileId)
                .filter(java.util.Objects::nonNull).toList());
        List<PhotoNeededView> out = page.stream().map(n -> {
            boolean loose = Unit.KG.name().equals(n.unit());
            return new PhotoNeededView(n.id(), n.name(), loose, loose ? null : PhotoRules.sizeToShow(n.name()),
                    n.check(), n.code(), n.reason(), n.seen(), n.newFileId() == null ? null : links.get(n.newFileId()));
        }).toList();
        ProductStore.NeedsPhoto last = page.isEmpty() ? null : page.get(page.size() - 1);
        return new PhotoNeededPage(out, more ? Cursors.encode(last.rank() + "|" + last.sortKey(),
                last.id().toString()) : null);
    }

    /**
     * "I've added all my products" (owner only: 403 OWNER_ONLY). Needs at least one product (409 NO_PRODUCTS), no list
     * page still being read and no line left to check (409 LINES_TO_CHECK), no page that couldn't be read (409
     * PAGES_FAILED: take it again or delete it), every product counted (409 PRODUCTS_NOT_COUNTED naming up to 20) and
     * every product with a picture (409 PHOTOS_NEEDED: missing, checking, refused).
     * Then the field visit is booked if the shop was submitted. Said again: as it is. Checked under the shop's setup
     * lock, so a line or page can't change meanwhile.
     */
    @Transactional
    public SetupView finish(UUID userId) {
        Caller c = access.owner(userId);
        reads.lockListPages(c.shopId());
        if (readiness.productsReadyAt(c.shopId()).isPresent()) {
            return view(c);
        }
        long[] counts = products.countedTotals(c.shopId());
        if (counts[0] == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_PRODUCTS", "Add your products first: everything you sell, "
                    + "with how many are on the shelf.");
        }
        Totals t = setup.totals(c.shopId());
        if (t.toCheck() > 0 || t.reading() > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "LINES_TO_CHECK", t.reading() > 0
                    ? "A page of your list is still being read. Check its products when it's ready."
                    : "Check the " + t.toCheck() + " products left from your list first: add them or skip them.",
                    Map.of("lines", t.toCheck(), "pagesReading", t.reading()));
        }
        if (t.failed() > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "PAGES_FAILED", t.failed() == 1
                    ? "One page of your list couldn't be read. Take its photo again, or delete it if its products "
                    + "are added." : t.failed() + " pages of your list couldn't be read. Take their photos again, or "
                    + "delete them if their products are added.", Map.of("pages", t.failed()));
        }
        ProductStore.PhotoTotals photos = products.photoTotals(c.shopId());
        if (counts[1] == 0 && photos.missing() > 0) {
            long toTake = photos.missing() - photos.checking();
            String take = toTake == 0 ? "" : (toTake == 1 ? "One product needs" : toTake + " products need")
                    + " a photo";
            String wait = photos.checking() == 0 ? "" : (photos.checking() == 1 ? "one photo is" : photos.checking()
                    + " photos are") + " still being checked";
            String message = toTake == 0 ? Character.toUpperCase(wait.charAt(0)) + wait.substring(1)
                    + ". Come back a little later." : take + (wait.isEmpty() ? "" : ", and " + wait) + ".";
            throw new ApiException(HttpStatus.CONFLICT, "PHOTOS_NEEDED", message, Map.of("missing", photos.missing(),
                    "checking", photos.checking(), "refused", photos.refused()));
        }
        if (counts[1] > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "PRODUCTS_NOT_COUNTED", counts[1] == 1
                    ? "One product has no count yet: count it first."
                    : counts[1] + " products have no count yet: count them first.",
                    Map.of("uncounted", counts[1], "products", refs(products.uncounted(c.shopId(), UNCOUNTED_SHOWN))));
        }
        readiness.markProductsReady(c.shopId(), userId, counts[0]);
        return view(c);
    }

    private SetupView view(Caller c) {
        UUID shopId = c.shopId();
        long[] counts = products.countedTotals(shopId);
        Totals t = setup.totals(shopId);
        Instant ready = readiness.productsReadyAt(shopId).orElse(null);
        List<ProductRef> uncounted = counts[1] == 0 ? List.of() : refs(products.uncounted(shopId, UNCOUNTED_SHOWN));
        int left = Math.max(0, MAX_LIST_PAGES - reads.listPages(shopId));
        List<SetupPageView> pages = new ArrayList<>();
        int n = 0;
        for (SetupStore.Page p : setup.pages(shopId)) {
            pages.add(new SetupPageView(p.readId(), ++n, p.fileId(), p.status(), p.failure(), p.linesLeftOut(),
                    p.toCheck(), p.added(), p.skipped(), p.sentAt()));
        }
        ProductStore.PhotoTotals photos = products.photoTotals(shopId);
        boolean canFinish = ready == null && counts[0] > 0 && counts[1] == 0 && t.toCheck() == 0 && t.reading() == 0
                && t.failed() == 0 && photos.missing() == 0;
        String next = ready != null ? "DONE" : counts[0] == 0 && t.toCheck() == 0 && t.reading() == 0 ? "ADD"
                : t.toCheck() > 0 || t.reading() > 0 || t.failed() > 0 ? "LIST" : counts[1] > 0 ? "COUNT"
                : photos.missing() > photos.checking() ? "PHOTOS" : photos.checking() > 0 ? "WAIT" : "FINISH";
        return new SetupView(ready, counts[0], counts[1], uncounted, t.toCheck(), t.added(), t.skipped(), t.reading(),
                t.failed(), left, pages, photos.missing(), photos.checking(), photos.refused(),
                productService.photosLeftToday(shopId), next, canFinish, canFinish && c.isOwner());
    }

    /** Each kept page's place, 1 = first sent. */
    private Map<UUID, Integer> pageNos(UUID shopId) {
        Map<UUID, Integer> out = new java.util.HashMap<>();
        for (SetupStore.Page p : setup.pages(shopId)) {
            out.put(p.readId(), out.size() + 1);
        }
        return out;
    }

    private MatchService.Result suggestions(MatchService.Shop shop, Line l) {
        return matcher.match(shop, new MatchService.Want(l.wording(), l.brand(), l.name(), l.size()), Set.of(), true);
    }

    private static List<ProductRef> refs(List<ProductStore.Named> named) {
        return named.stream().map(x -> new ProductRef(x.id(), x.name())).toList();
    }

    private static ListLineView view(Line l, Map<UUID, Integer> pageNos, MatchService.Result m) {
        List<SuggestionView> s = m.suggestions().stream().map(x -> new SuggestionView(x.productId(), x.itemId(),
                x.name(), x.brand(), x.size(), x.pricePaise(), x.score())).toList();
        return new ListLineView(l.id(), l.readId(), pageNos.get(l.readId()), l.lineNo(), l.wording(), l.brand(),
                l.name(), l.size(), l.byWeight(), l.mrpPaise(), Quantities.json(l.quantity()), l.status(),
                l.productId(), Quantities.json(l.counted()), s, m.sure());
    }

    /** An MRP read from the list, if a product may have it as its price (else the shop is asked). */
    static Long usablePrice(Long mrpPaise) {
        return mrpPaise != null && mrpPaise >= 1 && mrpPaise <= MAX_PRICE_PAISE ? mrpPaise : null;
    }

    /** The name a new product gets from a line: brand, name and size as read, else the wording (100 letters at most). */
    static String productName(Line l) {
        if (l.name() == null) {
            return l.wording().length() > 100 ? l.wording().substring(0, 100).strip() : l.wording();
        }
        StringBuilder n = new StringBuilder();
        if (l.brand() != null && !l.name().toLowerCase(java.util.Locale.ROOT)
                .contains(l.brand().toLowerCase(java.util.Locale.ROOT))) {
            n.append(l.brand()).append(' ');
        }
        n.append(l.name());
        if (l.size() != null) {
            n.append(' ').append(l.size());
        }
        return n.length() > 100 ? n.substring(0, 100).strip() : n.toString();
    }

    private static void requireNotAdded(Line line) {
        if ("ADDED".equals(line.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "LINE_ADDED", "This one is already added. To fix it, open the "
                    + "product and change it there.", Map.of("productId", line.productId()));
        }
    }

    private static Integer lineNo(String s) {
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "LINE_NOT_FOUND", "This line of your list isn't there any more. "
                + "Open your list again.", Map.of());
    }
}
