package in.apnidukan.kirana.service;

import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.text.TextCleaner.TextField;
import in.apnidukan.kirana.api.StockRequests;
import in.apnidukan.kirana.api.StockResponses.HeldView;
import in.apnidukan.kirana.api.StockResponses.LineView;
import in.apnidukan.kirana.api.StockResponses.PurchasePage;
import in.apnidukan.kirana.api.StockResponses.PurchaseSummary;
import in.apnidukan.kirana.api.StockResponses.PurchaseView;
import in.apnidukan.kirana.api.StockResponses.SuggestionView;
import in.apnidukan.kirana.domain.Cursors;
import in.apnidukan.kirana.domain.NameMatcher;
import in.apnidukan.kirana.domain.Product;
import in.apnidukan.kirana.domain.Quantities;
import in.apnidukan.kirana.domain.Unit;
import in.apnidukan.kirana.repo.ProductStore;
import in.apnidukan.kirana.repo.PurchaseStore;
import in.apnidukan.kirana.repo.PurchaseStore.Line;
import in.apnidukan.kirana.repo.PurchaseStore.Purchase;
import in.apnidukan.kirana.repo.ReadStore;
import in.apnidukan.kirana.repo.ReadStore.Read;
import in.apnidukan.kirana.repo.ScanStore;
import in.apnidukan.kirana.service.KiranaAccess.Caller;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static in.apnidukan.kirana.service.KiranaChecks.PAGE;
import static in.apnidukan.kirana.service.KiranaChecks.invalid;

/**
 * Distributors' bills, from their photos to stock (owner or helper). The bill is read by AI into a draft: each line's
 * product found by the distributor's wording matched before, else by {@link MatchService} (one clear match is set; a
 * few likely ones are offered). The shop checks every line (the product, the quantity, or skip it) and adds the bill to
 * stock in one go: each product's stock goes up, its cost becomes the rate paid (owner only sees it), and the wordings
 * are remembered for the next bill. Nothing changes stock until then.
 */
@Service
public class PurchaseService {

    private static final TextField WORDING = new TextField("wording", 2, 150);
    private static final TextField DISTRIBUTOR = new TextField("distributor", 2, 100);
    private static final TextField BILL_NO = new TextField("billNo", 1, 40);

    private final KiranaAccess access;
    private final PurchaseStore purchases;
    private final ReadStore reads;
    private final ProductStore products;
    private final MatchService matcher;
    private final ProductService productService;
    private final BillShelving shelving;
    private final ScanStore scans;
    private final UserDirectory users;
    private final Clock clock;

    public PurchaseService(KiranaAccess access, PurchaseStore purchases, ReadStore reads, ProductStore products,
                           MatchService matcher, ProductService productService, BillShelving shelving, ScanStore scans,
                           UserDirectory users, Clock clock) {
        this.access = access;
        this.purchases = purchases;
        this.reads = reads;
        this.products = products;
        this.matcher = matcher;
        this.productService = productService;
        this.shelving = shelving;
        this.scans = scans;
        this.users = users;
        this.clock = clock;
    }

    // ---------- the reading job ----------

    /**
     * Makes the draft from a bill just read: each line's product by this distributor's wording matched before
     * (REMEMBERED), else by name when one product clearly fits (SURE); the rest are the shop's to check. The caller
     * holds a transaction.
     */
    void fromRead(Read r, KiranaDocumentAi.PurchaseFacts f, Instant now) {
        String distributorKey = key(f.distributor());
        MatchService.Shop shop = matcher.shop(r.shopId());
        List<String> keys = f.lines().stream().map(l -> NameMatcher.wordingKey(l.wording())).toList();
        Map<String, UUID> remembered = purchases.wordings(r.shopId(), distributorKey, keys);
        List<Line> lines = new ArrayList<>();
        UUID purchaseId = UUID.randomUUID();
        int no = 0;
        for (KiranaDocumentAi.BillLine l : f.lines()) {
            no++;
            UUID product = remembered.get(NameMatcher.wordingKey(l.wording()));
            String pick = product != null ? "REMEMBERED" : "NONE";
            if (product == null) {
                MatchService.Result m = matcher.match(shop, new MatchService.Want(l.wording(), l.brand(), l.name(),
                        l.size()), Set.of(), false);
                if (m.sure()) {
                    product = m.best().productId();
                    pick = "SURE";
                }
            }
            lines.add(new Line(purchaseId, no, l.wording(), l.brand(), l.name(), l.size(),
                    l.quantity() == null ? BigDecimal.ZERO : l.quantity(), l.ratePaise(), l.mrpPaise(),
                    l.amountPaise(), product, pick));
        }
        purchases.insert(new Purchase(purchaseId, r.shopId(), r.id(), "DRAFT", f.distributor(), distributorKey,
                f.billNo(), f.billDate(), f.totalPaise(), 0, r.createdBy(), now, now, null, null), lines);
    }

    // ---------- the shop ----------

    /** The shop's bills, newest first, 50 a page. */
    @Transactional(readOnly = true)
    public PurchasePage list(UUID userId, String cursor) {
        Caller c = access.caller(userId);
        Optional<String[]> before = Cursors.decode(cursor);
        Instant beforeAt = before.flatMap(b -> PhotoReads.instant(b[0])).orElse(null);
        List<Purchase> rows = purchases.page(c.shopId(), beforeAt,
                beforeAt == null ? null : ProductService.uuid(before.get()[1]), PAGE);
        boolean more = rows.size() > PAGE;
        List<Purchase> page = more ? rows.subList(0, PAGE) : rows;
        Map<UUID, List<Line>> lines = purchases.lines(page.stream().map(Purchase::id).toList());
        Purchase last = page.isEmpty() ? null : page.get(page.size() - 1);
        return new PurchasePage(page.stream().map(p -> {
            List<Line> ls = lines.getOrDefault(p.id(), List.of());
            return new PurchaseSummary(p.id(), p.status(), p.distributor(), p.billNo(), p.billDate(), p.totalPaise(),
                    ls.size(), (int) ls.stream().filter(PurchaseService::toCheck).count(), p.createdAt(), p.addedAt());
        }).toList(), more ? Cursors.encode(last.createdAt().toString(), last.id().toString()) : null);
    }

    /** One bill with its lines and, for lines still to check, the likely products. 404 PURCHASE_NOT_FOUND. */
    @Transactional(readOnly = true)
    public PurchaseView get(UUID userId, UUID purchaseId) {
        Caller c = access.caller(userId);
        Purchase p = purchases.find(c.shopId(), purchaseId).orElseThrow(PurchaseService::notFound);
        return view(c, p);
    }

    /** One line's change (see {@link #setLines}); leave a field out to keep it. */
    public record LineEdit(int lineNo, UUID productId, StockRequests.NewForLine newProduct, BigDecimal quantity,
                           Long ratePaise, Boolean skip) {
    }

    /**
     * The shop's decisions on lines (owner or helper, the draft only), in one go: each line's product (one of the
     * shop's, active; or a new one made from a catalogue suggestion or a name, as {@link ProductService#newFor}), its
     * quantity (0 to 99,999 pieces or kg), skip it (not added to stock), or the rate paid (the owner only: 403
     * OWNER_ONLY). 409 PURCHASE_CHANGED for a stale version; 409 PURCHASE_DONE once added or thrown away; 404
     * LINE_NOT_FOUND naming the line.
     */
    @Transactional
    public PurchaseView setLines(UUID userId, UUID purchaseId, long version, List<LineEdit> edits) {
        Caller c = access.caller(userId);
        Purchase p = draft(c, purchaseId, version);
        Map<Integer, Line> byNo = new HashMap<>();
        for (Line l : purchases.lines(p.id())) {
            byNo.put(l.lineNo(), l);
        }
        for (LineEdit e : edits) {
            Line line = byNo.get(e.lineNo());
            if (line == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "LINE_NOT_FOUND", "No line " + e.lineNo() + ".",
                        Map.of("lineNo", e.lineNo()));
            }
            Line updated = edited(c, userId, line, e);
            purchases.saveLine(updated);
            byNo.put(updated.lineNo(), updated);
        }
        purchases.touch(p.id(), KiranaChecks.now(clock));
        return view(c, purchases.find(c.shopId(), p.id()).orElseThrow());
    }

    private Line edited(Caller c, UUID userId, Line line, LineEdit e) {
        if (e.ratePaise() != null && !c.isOwner()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER_ONLY", "Only the owner can change what was paid.",
                    Map.of());
        }
        boolean skip = Boolean.TRUE.equals(e.skip());
        UUID productId = e.productId();
        if (productId == null && e.newProduct() != null && !skip) {
            productId = productService.newFor(userId, e.newProduct().itemId(), e.newProduct().name(),
                    e.newProduct().unit(), e.newProduct().pricePaise());
        }
        BigDecimal qty = e.quantity() == null ? line.quantity() : e.quantity();
        if (qty.signum() < 0 || qty.compareTo(new BigDecimal("99999")) > 0) {
            throw invalid("lines[" + e.lineNo() + "].quantity", "Enter 0 to 99,999");
        }
        Line updated;
        if (skip) {
            updated = line.with(line.productId(), "SKIPPED", Quantities.stored(qty));
        } else if (productId != null) {
            Product product = products.find(c.shopId(), productId).filter(Product::active)
                    .orElseThrow(() -> invalid("lines[" + e.lineNo() + "].productId", "Pick one of your products"));
            boolean same = productId.equals(line.productId()) && !"SKIPPED".equals(line.pick())
                    && !"NONE".equals(line.pick());
            updated = line.with(product.id(), same ? line.pick() : "PICKED", Quantities.stored(qty));
        } else {
            String pick = "SKIPPED".equals(line.pick()) ? (line.productId() == null ? "NONE" : "PICKED") : line.pick();
            updated = line.with(line.productId(), pick, Quantities.stored(qty));
        }
        return e.ratePaise() == null ? updated : updated.withRate(e.ratePaise());
    }

    /**
     * A line the reading missed (owner or helper, the draft only): as written, how many, and its product (or a new one).
     * 409 TOO_MANY_LINES past 200.
     */
    @Transactional
    public PurchaseView addLine(UUID userId, UUID purchaseId, long version, String wording, BigDecimal quantity,
                                UUID productId, StockRequests.NewForLine newProduct, Long ratePaise) {
        Caller c = access.caller(userId);
        Purchase p = draft(c, purchaseId, version);
        List<Line> lines = purchases.lines(p.id());
        if (lines.size() >= 200) {
            throw new ApiException(HttpStatus.CONFLICT, "TOO_MANY_LINES", "A bill has at most 200 lines.", Map.of());
        }
        int no = lines.stream().mapToInt(Line::lineNo).max().orElse(0) + 1;
        String w = KiranaChecks.optional(WORDING, wording);
        Line blank = new Line(p.id(), no, w == null ? "Added by hand" : w, null, null, null, BigDecimal.ZERO, null, null,
                null, null, "NONE");
        Line line = edited(c, userId, blank, new LineEdit(no, productId, newProduct,
                quantity == null ? BigDecimal.ONE : quantity, ratePaise, false));
        purchases.addLines(p.id(), List.of(line));
        purchases.touch(p.id(), KiranaChecks.now(clock));
        return view(c, purchases.find(c.shopId(), p.id()).orElseThrow());
    }

    /**
     * The bill's distributor, number and date as the shop corrects them (owner or helper, the draft only); leave a field
     * out to keep it, "" to clear it. 400 naming billDate in the future.
     */
    @Transactional
    public PurchaseView setHeader(UUID userId, UUID purchaseId, long version, String distributor, String billNo,
                                  LocalDate billDate) {
        Caller c = access.caller(userId);
        Purchase p = draft(c, purchaseId, version);
        String d = distributor == null ? p.distributor() : KiranaChecks.optional(DISTRIBUTOR, distributor);
        String no = billNo == null ? p.billNo() : KiranaChecks.optional(BILL_NO, billNo);
        if (billDate != null && billDate.isAfter(KiranaChecks.today(KiranaChecks.now(clock)))) {
            throw invalid("billDate", "can't be in the future");
        }
        purchases.setHeader(p.id(), d, key(d), no, billDate == null ? p.billDate() : billDate);
        purchases.touch(p.id(), KiranaChecks.now(clock));
        return view(c, purchases.find(c.shopId(), p.id()).orElseThrow());
    }

    /**
     * Adds the bill to stock (owner or helper): every line not skipped needs its product and a quantity above 0 (409
     * LINES_TO_CHECK naming them); a piece product's quantity whole (400 naming lines[n].quantity). The same
     * distributor, number and date added before (copies added at the same moment go one at a time): 409
     * BILL_ALREADY_ADDED unless {@code addAgain}. Each product's stock goes up in one move (products locked in id
     * order) by its lines' quantities, and by what was scanned with the bill of products on none of its lines; its cost
     * becomes the rate paid; this distributor's wordings are remembered. Adding it again changes nothing (409
     * PURCHASE_DONE).
     */
    @Transactional
    public PurchaseView add(UUID userId, UUID purchaseId, long version, boolean addAgain) {
        Caller c = access.caller(userId);
        Purchase p = draft(c, purchaseId, version);
        List<Line> lines = purchases.lines(p.id());
        List<Integer> toCheck = lines.stream().filter(PurchaseService::toCheck).map(Line::lineNo).toList();
        if (!toCheck.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "LINES_TO_CHECK",
                    toCheck.size() == 1 ? "Check line " + toCheck.get(0) + ": pick its product or skip it."
                            : "Check " + toCheck.size() + " lines: pick their products or skip them.",
                    Map.of("lineNos", toCheck));
        }
        if (p.billNo() != null) {
            purchases.lockBillKey(c.shopId(), p.distributorKey(), p.billNo());
        }
        Optional<Purchase> earlier = purchases.alreadyAdded(c.shopId(), p.distributorKey(), p.billNo(), p.billDate(),
                p.id());
        if (earlier.isPresent() && !addAgain) {
            throw new ApiException(HttpStatus.CONFLICT, "BILL_ALREADY_ADDED", "Bill " + p.billNo()
                    + " from this distributor was already added to stock. Add it again only if it is another delivery.",
                    Map.of("purchaseId", earlier.get().id(), "addedAt", earlier.get().addedAt().toString()));
        }
        // product → the quantity and the last rate paid, products in id order (the lock order)
        Map<UUID, BigDecimal> in = new TreeMap<>();
        Map<UUID, Long> rate = new HashMap<>();
        Map<UUID, List<Integer>> lineNos = new HashMap<>();
        for (Line l : lines) {
            if ("SKIPPED".equals(l.pick()) || l.productId() == null) {
                continue;
            }
            in.merge(l.productId(), l.quantity(), BigDecimal::add);
            if (l.ratePaise() != null && l.ratePaise() > 0) {
                rate.put(l.productId(), l.ratePaise());
            }
            lineNos.computeIfAbsent(l.productId(), k -> new ArrayList<>()).add(l.lineNo());
        }
        // Shelved with the bill though on none of its lines: it came too
        Map<UUID, BigDecimal> extra = new TreeMap<>();
        purchases.scanned(p.id()).forEach((product, q) -> {
            if (!in.containsKey(product)) {
                extra.put(product, q);
            }
        });
        Set<UUID> all = new HashSet<>(in.keySet());
        all.addAll(extra.keySet());
        Map<UUID, Product> locked = new HashMap<>();
        for (Product x : products.lockAll(c.shopId(), all)) {
            locked.put(x.id(), x);
        }
        Instant now = KiranaChecks.now(clock);
        List<ProductStore.StockMove> moves = new ArrayList<>();
        String note = note(p);
        for (Map.Entry<UUID, BigDecimal> e : in.entrySet()) {
            Product x = locked.get(e.getKey());
            int first = lineNos.get(e.getKey()).get(0);
            if (x == null || !x.active()) {
                throw new ApiException(HttpStatus.CONFLICT, "PRODUCT_REMOVED", "The product on line " + first
                        + " was removed from your list. Pick another.", Map.of("lineNos", lineNos.get(e.getKey())));
            }
            if (x.unit() == Unit.PIECE && e.getValue().stripTrailingZeros().scale() > 0) {
                throw invalid("lines[" + first + "].quantity", "\"" + x.name() + "\" is counted in pieces: enter a "
                        + "whole number");
            }
        }
        Map<UUID, BigDecimal> total = new TreeMap<>(in);
        extra.forEach((product, q) -> {
            Product x = locked.get(product);
            if (x != null && x.active()) {
                total.put(product, q);
            }
        });
        for (Map.Entry<UUID, BigDecimal> e : total.entrySet()) {
            Product x = locked.get(e.getKey());
            BigDecimal after = Quantities.stored(x.stock().add(e.getValue()));
            if (after.abs().compareTo(new BigDecimal("999999999")) > 0) {
                throw invalid("lines", "\"" + x.name() + "\": too many");
            }
            products.setStock(x.id(), after, now);
            moves.add(new ProductStore.StockMove(x.id(), Quantities.stored(e.getValue()), after, "RESTOCK", p.id(),
                    null, in.containsKey(x.id()) ? note : cut(note + " (scanned, not on the bill)"), null));
            Long paid = rate.get(x.id());
            if (paid != null) {
                products.setCost(x.id(), paid);
            }
        }
        products.addStockMoves(c.shopId(), moves, userId, now);
        Map<String, UUID> wordings = new HashMap<>();
        for (Line l : lines) {
            if (l.productId() != null && !"SKIPPED".equals(l.pick())) {
                wordings.put(NameMatcher.wordingKey(l.wording()), l.productId());
            }
        }
        purchases.rememberAll(c.shopId(), p.distributorKey(), wordings, now);
        purchases.setStatus(p.id(), "ADDED", userId, now);
        reads.setStatus(p.readId(), "DONE", null, now);
        return view(c, purchases.find(c.shopId(), p.id()).orElseThrow());
    }

    /**
     * A line of a bill already added was the wrong product (owner): its quantity moves from that product to the right
     * one (one MOVE on each; same unit only: 409 UNITS_DIFFER; less what of the right one was scanned with the bill and
     * added already as on none of its lines), the right one's cost becomes the line's rate, and the
     * distributor's wording is remembered for it. 409 PURCHASE_NOT_ADDED for a draft (change the line instead); 400
     * naming productId for the same product.
     */
    @Transactional
    public PurchaseView correctLine(UUID userId, UUID purchaseId, int lineNo, UUID productId,
                                    StockRequests.NewForLine newProduct) {
        Caller c = access.owner(userId);
        Purchase p = purchases.lock(c.shopId(), purchaseId).orElseThrow(PurchaseService::notFound);
        if (!"ADDED".equals(p.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "PURCHASE_NOT_ADDED",
                    "This bill isn't added yet: change the line instead.", Map.of("status", p.status()));
        }
        Line line = purchases.lines(p.id()).stream().filter(l -> l.lineNo() == lineNo).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "LINE_NOT_FOUND", "No such line.",
                        Map.of("lineNo", lineNo)));
        if (line.productId() == null || "SKIPPED".equals(line.pick())) {
            throw new ApiException(HttpStatus.CONFLICT, "LINE_NOT_ADDED", "This line wasn't added to stock.",
                    Map.of());
        }
        UUID to = productId != null ? productId : newProduct == null ? null
                : productService.newFor(userId, newProduct.itemId(), newProduct.name(), newProduct.unit(),
                        newProduct.pricePaise());
        if (to == null || to.equals(line.productId())) {
            throw invalid("productId", "Choose the product it really is");
        }
        Map<UUID, Product> locked = new HashMap<>();
        for (Product x : products.lockAll(c.shopId(), List.of(line.productId(), to))) {
            locked.put(x.id(), x);
        }
        Product from = locked.get(line.productId());
        Product right = locked.get(to);
        if (right == null || !right.active()) {
            throw invalid("productId", "Pick one of your products");
        }
        if (from != null && from.unit() != right.unit()) {
            throw new ApiException(HttpStatus.CONFLICT, "UNITS_DIFFER", "\"" + from.name() + "\" and \""
                    + right.name() + "\" are counted differently (pieces and kg). Set each one's count instead.",
                    Map.of());
        }
        Instant now = KiranaChecks.now(clock);
        BigDecimal q = line.quantity();
        // The right product scanned with this bill while on none of its lines was added already, as it was shelved:
        // that much of the line is in its stock (and is taken off what was held, so it counts once)
        BigDecimal toRight = q;
        BigDecimal held = purchases.scanned(p.id()).get(right.id());
        boolean onAnotherLine = purchases.lines(p.id()).stream().anyMatch(l -> l.lineNo() != lineNo
                && right.id().equals(l.productId()) && !"SKIPPED".equals(l.pick()));
        if (held != null && !onAnotherLine) {
            BigDecimal covered = held.min(q);
            toRight = q.subtract(covered);
            purchases.takeScanned(p.id(), right.id(), covered);
        }
        List<ProductStore.StockMove> moves = new ArrayList<>();
        String why = cut("Bill " + (p.billNo() == null ? "" : p.billNo() + " ") + "line " + lineNo + " corrected");
        if (from != null) {
            BigDecimal fromAfter = Quantities.stored(from.stock().subtract(q));
            products.setStock(from.id(), fromAfter, now);
            moves.add(new ProductStore.StockMove(from.id(), q.negate(), fromAfter, "MOVE", null, null, why, null));
        }
        if (toRight.signum() > 0) {
            BigDecimal toAfter = Quantities.stored(right.stock().add(toRight));
            products.setStock(right.id(), toAfter, now);
            moves.add(new ProductStore.StockMove(right.id(), toRight, toAfter, "MOVE", null, null, why, null));
        }
        products.addStockMoves(c.shopId(), moves, userId, now);
        if (line.ratePaise() != null && line.ratePaise() > 0) {
            products.setCost(right.id(), line.ratePaise());
        }
        purchases.saveLine(line.with(right.id(), "PICKED", q));
        purchases.rememberAll(c.shopId(), p.distributorKey(), Map.of(NameMatcher.wordingKey(line.wording()),
                right.id()), now);
        purchases.touch(p.id(), now);
        return view(c, purchases.find(c.shopId(), p.id()).orElseThrow());
    }

    /**
     * Throws a draft away (nothing of the bill is added; what was scanned while shelving it goes into stock, since it is
     * on the shelves). 409 PURCHASE_DONE once added.
     */
    @Transactional
    public PurchaseView discard(UUID userId, UUID purchaseId) {
        Caller c = access.caller(userId);
        Purchase p = purchases.lock(c.shopId(), purchaseId).orElseThrow(PurchaseService::notFound);
        if ("ADDED".equals(p.status())) {
            throw done(p);
        }
        if ("DRAFT".equals(p.status())) {
            shelving.throwAway(c.shopId(), p, userId, KiranaChecks.now(clock));
        }
        return view(c, purchases.find(c.shopId(), p.id()).orElseThrow());
    }

    // ---------- helpers ----------

    /** A draft locked, at the version the app saw. */
    private Purchase draft(Caller c, UUID purchaseId, long version) {
        Purchase p = purchases.lock(c.shopId(), purchaseId).orElseThrow(PurchaseService::notFound);
        if (!"DRAFT".equals(p.status())) {
            throw done(p);
        }
        if (p.version() != version) {
            throw new ApiException(HttpStatus.CONFLICT, "PURCHASE_CHANGED",
                    "Someone changed this bill just now. Reload it and try again.");
        }
        return p;
    }

    /** A line still to check: not skipped, and no product or no quantity yet. */
    static boolean toCheck(Line l) {
        return !"SKIPPED".equals(l.pick()) && (l.productId() == null || l.quantity().signum() <= 0);
    }

    private PurchaseView view(Caller c, Purchase p) {
        List<Line> lines = purchases.lines(p.id());
        Set<UUID> ids = new HashSet<>();
        lines.forEach(l -> {
            if (l.productId() != null) {
                ids.add(l.productId());
            }
        });
        Map<UUID, Product> named = new HashMap<>();
        for (Product x : products.findAll(c.shopId(), ids)) {
            named.put(x.id(), x);
        }
        // Suggestions for the lines still without a product (the shop's products read once for all of them)
        Map<Integer, MatchService.Result> suggestions = new LinkedHashMap<>();
        if ("DRAFT".equals(p.status()) && lines.stream().anyMatch(l -> l.productId() == null
                && !"SKIPPED".equals(l.pick()))) {
            MatchService.Shop shop = matcher.shop(c.shopId());
            for (Line l : lines) {
                if (l.productId() == null && !"SKIPPED".equals(l.pick())) {
                    suggestions.put(l.lineNo(), matcher.match(shop, new MatchService.Want(l.wording(), l.brand(),
                            l.name(), l.size()), Set.of(), true));
                }
            }
        }
        Map<UUID, String> names = KiranaChecks.names(users, java.util.stream.Stream.of(p.createdBy(), p.addedBy())
                .filter(java.util.Objects::nonNull).distinct().toList());
        // What was scanned with the bill, shown on the first line of each product (the rest: held, on no line)
        Map<UUID, BigDecimal> scanned = new HashMap<>(purchases.scanned(p.id()));
        boolean owner = c.isOwner();   // what was paid is the owner's to see
        List<LineView> views = new ArrayList<>();
        for (Line l : lines) {
            Product x = l.productId() == null ? null : named.get(l.productId());
            MatchService.Result m = suggestions.get(l.lineNo());
            BigDecimal got = l.productId() == null || "SKIPPED".equals(l.pick()) ? null : scanned.remove(l.productId());
            BigDecimal shortBy = got != null && got.compareTo(l.quantity()) < 0 ? l.quantity().subtract(got) : null;
            views.add(new LineView(l.lineNo(), l.wording(), l.brand(), l.name(), l.size(),
                    Quantities.json(l.quantity()), owner ? l.ratePaise() : null, l.mrpPaise(),
                    owner ? l.amountPaise() : null, l.productId(), x == null ? null : x.name(),
                    x == null ? null : x.unit(), l.pick(), got == null ? null : Quantities.json(got),
                    shortBy == null ? null : Quantities.json(shortBy),
                    m == null ? List.of() : m.suggestions().stream().map(PurchaseService::suggestion).toList(),
                    m != null && m.sure(), toCheck(l)));
        }
        Map<UUID, Product> heldProducts = new HashMap<>();
        for (Product x : products.findAll(c.shopId(), scanned.keySet())) {
            heldProducts.put(x.id(), x);
        }
        List<HeldView> held = new ArrayList<>();
        scanned.forEach((product, q) -> {
            Product x = heldProducts.get(product);
            held.add(new HeldView(product, x == null ? null : x.name(), x == null ? null : x.unit(),
                    Quantities.json(q)));
        });
        List<UUID> photos = reads.find(p.readId()).map(Read::fileIds).orElse(List.of());
        return new PurchaseView(p.id(), p.readId(), p.status(), p.distributor(), p.billNo(), p.billDate(),
                owner ? p.totalPaise() : null, p.version(), views, held, scans.openForBill(p.id()), photos,
                names.get(p.createdBy()), p.createdAt(), p.addedBy() == null ? null : names.get(p.addedBy()),
                p.addedAt());
    }

    private static String cut(String note) {
        return note.length() > 200 ? note.substring(0, 200) : note;
    }

    static SuggestionView suggestion(MatchService.Suggestion s) {
        return new SuggestionView(s.productId(), s.itemId(), s.name(), s.brand(), s.size(), s.pricePaise(), s.score());
    }

    private static String note(Purchase p) {
        StringBuilder n = new StringBuilder("Bill");
        if (p.billNo() != null) {
            n.append(' ').append(p.billNo());
        }
        if (p.distributor() != null) {
            n.append(" from ").append(p.distributor());
        }
        String s = n.toString();
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    /** A distributor's name as compared: its cleaned words (so "M/S. Sharma Agencies" and "Sharma Agencies" match). */
    static String key(String distributor) {
        if (distributor == null) {
            return "";
        }
        String k = NameMatcher.wordingKey(distributor).replaceAll("\\b(m s|ms|m/s|the|pvt|ltd|private|limited)\\b", " ")
                .replaceAll("\\s+", " ").strip();
        return k.length() > 100 ? k.substring(0, 100) : k;
    }

    private static ApiException done(Purchase p) {
        return new ApiException(HttpStatus.CONFLICT, "PURCHASE_DONE", "ADDED".equals(p.status())
                ? "This bill was already added to stock." : "This bill was thrown away.",
                Map.of("status", p.status()));
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "PURCHASE_NOT_FOUND", "No such bill.", Map.of());
    }
}
