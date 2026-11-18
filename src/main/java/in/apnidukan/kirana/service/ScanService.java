package in.apnidukan.kirana.service;

import in.apnidukan.common.error.ApiException;
import in.apnidukan.kirana.api.KiranaRequests.NewBarcode;
import in.apnidukan.kirana.api.KiranaResponses.ProductView;
import in.apnidukan.kirana.api.StockRequests.LabelPhoto;
import in.apnidukan.kirana.api.StockRequests.LinkUnknown;
import in.apnidukan.kirana.api.StockRequests.Scan;
import in.apnidukan.kirana.api.StockRequests.ScanBatch;
import in.apnidukan.kirana.api.StockResponses.LabelView;
import in.apnidukan.kirana.api.StockResponses.LinkResult;
import in.apnidukan.kirana.api.StockResponses.ScanBatchResult;
import in.apnidukan.kirana.api.StockResponses.ScanResult;
import in.apnidukan.kirana.api.StockResponses.ScanStatsView;
import in.apnidukan.kirana.api.StockResponses.SuggestionView;
import in.apnidukan.kirana.api.StockResponses.UnknownPage;
import in.apnidukan.kirana.api.StockResponses.UnknownView;
import in.apnidukan.kirana.domain.Barcode;
import in.apnidukan.kirana.domain.Cursors;
import in.apnidukan.kirana.domain.NameMatcher;
import in.apnidukan.kirana.domain.Product;
import in.apnidukan.kirana.domain.Quantities;
import in.apnidukan.kirana.domain.Unit;
import in.apnidukan.kirana.repo.CatalogueStore;
import in.apnidukan.kirana.repo.CatalogueStore.Known;
import in.apnidukan.kirana.repo.ProductStore;
import in.apnidukan.kirana.repo.PurchaseStore;
import in.apnidukan.kirana.repo.PurchaseStore.Purchase;
import in.apnidukan.kirana.repo.ReadStore;
import in.apnidukan.kirana.repo.ReadStore.Read;
import in.apnidukan.kirana.repo.ScanStore;
import in.apnidukan.kirana.repo.ScanStore.Unknown;
import in.apnidukan.kirana.service.KiranaAccess.Caller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

import static in.apnidukan.kirana.repo.ScanStore.Candidate;
import static in.apnidukan.kirana.service.RazorpayHttp.JSON;

/**
 * Scanning while shelving (owner or helper). The phone reads barcodes and sends them in batches: a known barcode adds
 * its pack's pieces to stock (or, with the distributor's bill being shelved still a draft, ticks its line: the bill
 * adds the stock, never twice); a misread (wrong check digit) is refused; a shop-printed loose-item label (20–23)
 * counts its weight. A barcode no product has goes to "Needs a tap", with what the shared catalogue knows of it, its
 * pack's label read by AI when nobody knows it (once across all shops), and up to 3 likely products matched by name in
 * code: one tap links it and its scans go into stock. Counts per shop and day show the team how it goes.
 */
@Service
public class ScanService {

    private static final Logger log = LoggerFactory.getLogger(ScanService.class);

    /** The most pieces (or kg) one scan line, or one product on one bill, may count. */
    static final BigDecimal MAX_SCAN = new BigDecimal("99999");

    /** Unknown barcodes on one page of "Needs a tap". */
    static final int UNKNOWN_PAGE = 20;

    private final KiranaAccess access;
    private final ProductStore products;
    private final ScanStore scans;
    private final PurchaseStore purchases;
    private final CatalogueStore catalogue;
    private final CatalogueService catalogueService;
    private final ProductService productService;
    private final MatchService matcher;
    private final PhotoReads photoReads;
    private final ReadStore reads;
    private final KiranaFiles kiranaFiles;
    private final Clock clock;

    public ScanService(KiranaAccess access, ProductStore products, ScanStore scans, PurchaseStore purchases,
                       CatalogueStore catalogue, CatalogueService catalogueService, ProductService productService,
                       MatchService matcher, PhotoReads photoReads, ReadStore reads, KiranaFiles kiranaFiles,
                       Clock clock) {
        this.access = access;
        this.products = products;
        this.scans = scans;
        this.purchases = purchases;
        this.catalogue = catalogue;
        this.catalogueService = catalogueService;
        this.productService = productService;
        this.matcher = matcher;
        this.photoReads = photoReads;
        this.reads = reads;
        this.kiranaFiles = kiranaFiles;
        this.clock = clock;
    }

    // ---------- a batch of scans ----------

    /** One scan understood: the code to look up, how often, and for a loose label the weight (kg) or price. */
    private record Parsed(int index, String code, int times, KiranaChecks.LooseLabel loose, String problem) {
    }

    /**
     * Records a batch of scans (see the class). {@code purchaseId}: the bill being shelved, a draft of this shop (404
     * PURCHASE_NOT_FOUND; 409 PURCHASE_DONE once added or thrown away): its products' scans are held with the bill,
     * never added to stock by the scan (TICKED for a product on one of its lines, HELD for another; adding the bill adds
     * its lines' quantities and what was held; throwing it away adds what was scanned). The same clientRef again (a retry
     * after a lost answer) counts nothing twice: the results are looked up again ({@code repeated}).
     */
    @Transactional
    public ScanBatchResult scan(UUID userId, ScanBatch in) {
        Caller c = access.caller(userId);
        Instant now = KiranaChecks.now(clock);
        // The bill first, then unknown rows (by barcode), then products by id: the order linking and adding take too
        Purchase bill = null;
        Map<UUID, PurchaseStore.Line> billLine = new HashMap<>();   // product → its first line not skipped
        if (in.purchaseId() != null) {
            bill = purchases.lock(c.shopId(), in.purchaseId()).orElseThrow(PurchaseService::notFound);
            if (!"DRAFT".equals(bill.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "PURCHASE_DONE", "ADDED".equals(bill.status())
                        ? "This bill was already added to stock: scan without it." : "This bill was thrown away.",
                        Map.of("status", bill.status()));
            }
            for (PurchaseStore.Line l : purchases.lines(bill.id())) {
                if (l.productId() != null && !"SKIPPED".equals(l.pick())) {
                    billLine.putIfAbsent(l.productId(), l);
                }
            }
        }
        UUID billId = bill == null ? null : bill.id();
        boolean first = scans.firstBatch(c.shopId(), in.clientRef(), now);

        List<Parsed> parsed = new ArrayList<>();
        for (int i = 0; i < in.scans().size(); i++) {
            parsed.add(parse(i, in.scans().get(i)));
        }
        Set<String> lookUp = new HashSet<>();
        for (Parsed p : parsed) {
            if (p.problem() == null) {
                lookUp.add(p.code());
            }
        }
        Map<String, ProductStore.Scan> known = products.findByBarcodes(c.shopId(), lookUp);
        // A loose label whose product is counted in pieces isn't a weight: refused, like a misread
        List<Parsed> checked = new ArrayList<>();
        for (Parsed p : parsed) {
            ProductStore.Scan s = known.get(p.code());
            if (p.loose() != null && s != null && s.product().unit() != Unit.KG) {
                checked.add(new Parsed(p.index(), p.code(), p.times(), null,
                        "This label is for a loose item; \"" + s.product().name() + "\" is sold in pieces."));
            } else {
                checked.add(p);
            }
        }

        ScanResult[] results = new ScanResult[checked.size()];
        int scanned = 0;
        int unknownScans = 0;
        Map<String, Integer> unknownTimes = new TreeMap<>();   // code → times, in barcode order (the lock order)
        Map<String, Boolean> unknownLoose = new HashMap<>();
        Set<UUID> ids = new HashSet<>();
        for (Parsed p : checked) {
            if (p.problem() != null) {
                results[p.index()] = rejected(p.code(), p.problem());
                continue;
            }
            scanned += p.times();
            ProductStore.Scan s = known.get(p.code());
            if (s == null || !s.product().active()) {
                unknownScans += p.times();
                unknownTimes.merge(p.code(), p.times(), Integer::sum);
                unknownLoose.put(p.code(), p.loose() != null);
            } else {
                ids.add(s.product().id());
            }
        }

        // Unknown barcodes: into "Needs a tap" (one entry per barcode and bill), with what the catalogue knows and
        // whether a label photo is wanted
        Map<String, Known> inCatalogue = catalogue.findByCodes(unknownTimes.keySet().stream()
                .filter(code -> !unknownLoose.get(code)).toList());
        Map<String, Unknown> open = new HashMap<>();
        for (Map.Entry<String, Integer> e : unknownTimes.entrySet()) {
            String code = e.getKey();
            BigDecimal times = BigDecimal.valueOf(e.getValue());
            scans.lockKey(c.shopId(), code, billId);
            Optional<Unknown> had = scans.lockOpen(c.shopId(), code, billId);
            Unknown u;
            if (!first) {
                u = had.orElse(null);
            } else if (had.isPresent()) {
                u = had.get();
                scans.addScans(u.id(), times, now);
            } else {
                u = new Unknown(UUID.randomUUID(), c.shopId(), code, times, billId, null, "OPEN", null, userId, now,
                        now);
                if (!scans.insert(u)) {
                    // The same barcode opened by another batch at this moment: add to it
                    u = scans.lockOpen(c.shopId(), code, billId).orElseThrow();
                    scans.addScans(u.id(), times, now);
                }
            }
            if (u != null && first && u.readId() == null && !unknownLoose.get(code) && !inCatalogue.containsKey(code)) {
                // This pack's label is being read or was read (here or in another shop): reused, no new photo needed
                Optional<UUID> shared = scans.networkRead(code);
                if (shared.isPresent()) {
                    scans.setRead(u.id(), shared.get(), now);
                    u = withRead(u, shared.get());
                }
            }
            if (u != null) {
                open.put(code, u);
            }
        }
        for (Parsed p : checked) {
            if (results[p.index()] != null || !unknownTimes.containsKey(p.code())) {
                continue;
            }
            Unknown u = open.get(p.code());
            Known k = inCatalogue.get(p.code());
            boolean loose = unknownLoose.get(p.code());
            results[p.index()] = new ScanResult(p.code(), "UNKNOWN", null, null, null,
                    BigDecimal.valueOf(p.times()), null, null, null, null, u == null ? null : u.id(),
                    k == null ? null : item(k), u != null && !loose && k == null && u.readId() == null, null);
        }

        // Known products: locked in id order, then each scan is held with the bill or added to stock
        Map<UUID, Product> locked = new HashMap<>();
        for (Product x : products.lockAll(c.shopId(), ids)) {
            locked.put(x.id(), x);
        }
        Map<UUID, BigDecimal> stock = new HashMap<>();
        locked.forEach((id, x) -> stock.put(id, x.stock()));
        Map<UUID, BigDecimal> held = new HashMap<>(bill == null ? Map.of() : purchases.scanned(bill.id()));
        Map<UUID, BigDecimal> heldNow = new TreeMap<>();
        Map<String, BigDecimal> added = new TreeMap<>();   // "product|barcode" → quantity, one move each
        for (Parsed p : checked) {
            if (results[p.index()] != null) {
                continue;
            }
            ProductStore.Scan s = known.get(p.code());
            Product x = locked.get(s.product().id());
            if (x == null || !x.active()) {
                results[p.index()] = rejected(p.code(), "This product was removed just now. Scan it again.");
                continue;
            }
            BigDecimal q = Quantities.stored(p.loose() != null ? looseKg(p.loose(), x)
                    : s.pack().multiply(BigDecimal.valueOf(p.times())));
            if (q.compareTo(MAX_SCAN) > 0) {
                results[p.index()] = rejected(p.code(), "Too many at once: scan fewer and send again.");
                continue;
            }
            if (bill != null) {
                BigDecimal total = held.getOrDefault(x.id(), BigDecimal.ZERO).add(first ? q : BigDecimal.ZERO);
                if (total.compareTo(MAX_SCAN) > 0) {
                    results[p.index()] = rejected(p.code(), "Too many of this on one bill.");
                    continue;
                }
                held.put(x.id(), total);
                if (first) {
                    heldNow.merge(x.id(), q, BigDecimal::add);
                }
                PurchaseStore.Line line = billLine.get(x.id());
                results[p.index()] = new ScanResult(p.code(), line != null ? "TICKED" : "HELD", x.id(), x.name(),
                        x.unit(), Quantities.json(q), null, line == null ? null : line.lineNo(),
                        Quantities.json(total), line == null ? null : Quantities.json(line.quantity()), null, null,
                        false, null);
                continue;
            }
            BigDecimal after = first ? Quantities.stored(stock.get(x.id()).add(q)) : stock.get(x.id());
            if (after.abs().compareTo(new BigDecimal("999999999")) > 0) {
                results[p.index()] = rejected(p.code(), "Too many: the stock can't go that high.");
                continue;
            }
            stock.put(x.id(), after);
            if (first) {
                added.merge(x.id() + "|" + p.code(), q, BigDecimal::add);
            }
            results[p.index()] = new ScanResult(p.code(), "ADDED", x.id(), x.name(), x.unit(), Quantities.json(q),
                    Quantities.json(after), null, null, null, null, null, false, null);
        }
        heldNow.forEach((product, q) -> purchases.addScanned(billId, product, q));
        if (!added.isEmpty()) {
            List<ProductStore.StockMove> moves = new ArrayList<>();
            Map<UUID, BigDecimal> running = new HashMap<>();
            locked.forEach((id, x) -> running.put(id, x.stock()));
            for (Map.Entry<String, BigDecimal> e : added.entrySet()) {
                UUID product = UUID.fromString(e.getKey().substring(0, e.getKey().indexOf('|')));
                String code = e.getKey().substring(e.getKey().indexOf('|') + 1);
                BigDecimal after = Quantities.stored(running.get(product).add(e.getValue()));
                running.put(product, after);
                moves.add(new ProductStore.StockMove(product, e.getValue(), after, "RESTOCK", null, code,
                        "Scanned in", null));
            }
            running.forEach((product, after) -> {
                if (after.compareTo(locked.get(product).stock()) != 0) {
                    products.setStock(product, after, now);
                }
            });
            products.addStockMoves(c.shopId(), moves, userId, now);
        }
        if (first) {
            scans.count(c.shopId(), KiranaChecks.today(now), scanned, unknownScans, 0, 0, 0);
        }
        return new ScanBatchResult(List.of(results), !first);
    }

    private static ScanResult rejected(String code, String problem) {
        return new ScanResult(code, "REJECTED", null, null, null, null, null, null, null, null, null, null, false,
                problem);
    }

    /** A scan as sent: a misread or a code to look up (a loose label by its item code). */
    private static Parsed parse(int index, Scan s) {
        int times = s.times() == null ? 1 : s.times();
        String code;
        try {
            code = KiranaChecks.barcode("code", s.code());
        } catch (ApiException e) {
            code = null;
        }
        if (code == null) {
            return new Parsed(index, s.code(), times, null, "We couldn't read this barcode. Scan it again.");
        }
        if (!KiranaChecks.checkDigitOk(code)) {
            return new Parsed(index, code, times, null, "This barcode didn't scan right. Scan it again.");
        }
        Optional<KiranaChecks.LooseLabel> loose = KiranaChecks.looseLabel(code);
        if (loose.isPresent()) {
            return new Parsed(index, loose.get().itemCode(), times, loose.get(), null);
        }
        return new Parsed(index, code, times, null, null);
    }

    /** A loose label's weight in kg: written on it in grams, or worked out from its price. */
    private static BigDecimal looseKg(KiranaChecks.LooseLabel l, Product p) {
        if (l.byWeight()) {
            return BigDecimal.valueOf(l.value()).movePointLeft(3);
        }
        return BigDecimal.valueOf(l.value()).divide(BigDecimal.valueOf(p.pricePaise()), 3, RoundingMode.HALF_UP);
    }

    /** A shop-printed loose item's code (the first 7 digits of its 20–23 label), as kept in "Needs a tap". */
    static boolean looseItemCode(String code) {
        return code.length() == 7 && code.charAt(0) == '2' && code.charAt(1) <= '3'
                && code.chars().allMatch(Character::isDigit);
    }

    private static Unknown withRead(Unknown u, UUID readId) {
        return new Unknown(u.id(), u.shopId(), u.code(), u.scans(), u.purchaseId(), readId, u.status(), u.productId(),
                u.createdBy(), u.createdAt(), u.updatedAt());
    }

    // ---------- "Needs a tap" ----------

    /**
     * The shop's unknown barcodes, newest first, 20 a page, each with its likely products; {@code dismissed}: the ones
     * put away instead (to bring one back).
     */
    @Transactional(readOnly = true)
    public UnknownPage unknowns(UUID userId, boolean dismissed, String cursor) {
        Caller c = access.caller(userId);
        Optional<String[]> before = Cursors.decode(cursor);
        Instant beforeAt = before.flatMap(b -> PhotoReads.instant(b[0])).orElse(null);
        List<Unknown> rows = scans.page(c.shopId(), dismissed ? "DISMISSED" : "OPEN", beforeAt,
                beforeAt == null ? null : ProductService.uuid(before.get()[1]), UNKNOWN_PAGE);
        boolean more = rows.size() > UNKNOWN_PAGE;
        List<Unknown> page = more ? rows.subList(0, UNKNOWN_PAGE) : rows;
        Unknown last = page.isEmpty() ? null : page.get(page.size() - 1);
        return new UnknownPage(views(c.shopId(), page),
                more ? Cursors.encode(last.createdAt().toString(), last.id().toString()) : null);
    }

    /** One unknown barcode. 404 UNKNOWN_NOT_FOUND. */
    @Transactional(readOnly = true)
    public UnknownView unknown(UUID userId, UUID unknownId) {
        Caller c = access.caller(userId);
        Unknown u = scans.find(c.shopId(), unknownId).orElseThrow(ScanService::unknownNotFound);
        return views(c.shopId(), List.of(u)).get(0);
    }

    /**
     * A photo of the unknown pack's front (a PRODUCT_PHOTO this shop uploaded; 400 naming fileId otherwise), to be
     * read by AI. Nothing is read when the catalogue knows the barcode, or this shop's read of it is being read or was
     * read; another shop's read is used when there is one, unless this shop sends its own photo over it (that read
     * may be of the wrong pack). 409 UNKNOWN_DONE once linked or dismissed.
     */
    @Transactional
    public UnknownView label(UUID userId, UUID unknownId, LabelPhoto in) {
        Caller c = access.caller(userId);
        Unknown u = scans.lock(c.shopId(), unknownId).orElseThrow(ScanService::unknownNotFound);
        if (!"OPEN".equals(u.status())) {
            throw unknownDone();
        }
        Instant now = KiranaChecks.now(clock);
        boolean needed = catalogue.findByCode(u.code()).isEmpty();
        boolean retake = false;
        if (needed && u.readId() != null) {
            Read had = reads.find(u.readId()).orElse(null);
            // Sent again over a read of this pack: another shop's (it may be of the wrong pack) or one that failed
            retake = had != null && !had.shopId().equals(c.shopId());
            needed = had == null || retake || !List.of("READING", "READ", "DONE").contains(had.status());
        }
        if (needed) {
            Optional<UUID> shared = retake ? Optional.empty() : scans.networkRead(u.code());
            if (shared.isPresent()) {
                scans.setRead(u.id(), shared.get(), now);
            } else {
                Read r = photoReads.queue(c.shopId(), userId, "LABEL", in.clientRef(), List.of(in.fileId()), null,
                        now);
                scans.setRead(u.id(), r.id(), now);
            }
        }
        return views(c.shopId(), List.of(scans.find(c.shopId(), u.id()).orElseThrow())).get(0);
    }

    /**
     * Links an unknown barcode (owner or helper): to one of the shop's products, or to a new product made from the
     * catalogue item and/or the name, unit and price sent (400 naming name, unit or pricePaise when missing). The
     * barcode is linked as on the product's screen (409 BARCODE_TAKEN, TOO_MANY_BARCODES, PRODUCT_REMOVED, NAME_TAKEN
     * as there). Every open entry of the barcode (one per bill it was scanned with) is settled with it, its scans times
     * the pack: held with its bill while that is a draft; nothing when the bill was added with this product on it (the
     * bill brought them); else into stock. A shop's loose-item label adds nothing (its weights weren't kept: {@code
     * note}). 409 UNKNOWN_DONE once linked or dismissed.
     */
    @Transactional
    public LinkResult link(UUID userId, UUID unknownId, LinkUnknown in) {
        Caller c = access.caller(userId);
        Unknown seen = scans.find(c.shopId(), unknownId).orElseThrow(ScanService::unknownNotFound);
        if (!"OPEN".equals(seen.status())) {
            throw unknownDone();
        }
        // Bills first (in id order), then the barcode's entries (in id order), then the product: the order scanning takes
        List<Unknown> peers = new ArrayList<>(scans.openFor(c.shopId(), seen.code()));
        Map<UUID, Purchase> bills = new HashMap<>();
        for (UUID b : new java.util.TreeSet<>(peers.stream().map(Unknown::purchaseId)
                .filter(java.util.Objects::nonNull).toList())) {
            purchases.lock(c.shopId(), b).ifPresent(x -> bills.put(x.id(), x));
        }
        List<Unknown> entries = new ArrayList<>();
        for (Unknown peer : peers) {
            scans.lock(c.shopId(), peer.id()).filter(x -> "OPEN".equals(x.status())).ifPresent(entries::add);
        }
        if (entries.stream().noneMatch(x -> x.id().equals(unknownId))) {
            throw unknownDone();
        }
        Instant now = KiranaChecks.now(clock);
        String code = seen.code();
        boolean created = in.productId() == null;
        UUID productId = created ? productService.newFor(userId, in.itemId(), in.name(), in.unit(), in.pricePaise())
                : in.productId();
        productService.addBarcode(userId, productId, new NewBarcode(code, in.pack()));
        Product p = products.lock(c.shopId(), productId).orElseThrow(ProductService::notFound);
        BigDecimal pack = products.barcodes(p.id()).stream().filter(b -> b.code().equals(code)).findFirst()
                .map(Barcode::pack).orElse(BigDecimal.ONE);
        boolean loose = looseItemCode(code);
        String note = loose ? "Loose-item labels don't keep their weights: count it and set the stock." : null;
        BigDecimal toStock = BigDecimal.ZERO;
        BigDecimal held = BigDecimal.ZERO;
        UUID heldOn = null;
        Integer heldLine = null;
        for (Unknown e : entries) {
            BigDecimal q = loose ? BigDecimal.ZERO : Quantities.stored(e.scans().multiply(pack));
            if (p.unit() == Unit.PIECE && q.stripTrailingZeros().scale() > 0) {
                q = q.setScale(0, RoundingMode.DOWN);
                note = "A pack of " + Quantities.plain(pack) + " doesn't make whole pieces: rounded down to "
                        + Quantities.plain(q) + ".";
            }
            Purchase b = e.purchaseId() == null ? null : bills.get(e.purchaseId());
            Optional<PurchaseStore.Line> line = b == null ? Optional.empty() : purchases.lines(b.id()).stream()
                    .filter(l -> p.id().equals(l.productId()) && !"SKIPPED".equals(l.pick())).findFirst();
            if (b != null && "DRAFT".equals(b.status())) {
                if (q.signum() > 0) {
                    purchases.addScanned(b.id(), p.id(), q);
                }
                held = held.add(q);
                heldOn = b.id();
                heldLine = line.map(PurchaseStore.Line::lineNo).orElse(null);
            } else if (b == null || !"ADDED".equals(b.status()) || line.isEmpty()) {
                toStock = toStock.add(q);
            }
            scans.settle(e.id(), "LINKED", p.id(), now);
            letGoOfFrame(c.shopId(), e, now);
        }
        if (toStock.signum() > 0) {
            BigDecimal after = Quantities.stored(p.stock().add(toStock));
            products.setStock(p.id(), after, now);
            products.addStockMoves(c.shopId(), List.of(new ProductStore.StockMove(p.id(), toStock, after, "RESTOCK",
                    null, code, "Scanned in", null)), userId, now);
        }
        boolean oneTap = Boolean.TRUE.equals(in.oneTap());
        scans.count(c.shopId(), KiranaChecks.today(now), 0, 0, !created && oneTap ? 1 : 0,
                !created && !oneTap ? 1 : 0, created ? 1 : 0);
        ProductView view = productService.view(c, products.find(c.shopId(), p.id()).orElseThrow());
        return new LinkResult(view, Quantities.json(toStock), Quantities.json(held), heldOn, heldLine,
                view.photoFileId() == null, note);
    }

    /** Takes an unknown barcode off "Needs a tap" (not a product the shop sells). 409 UNKNOWN_DONE once linked. */
    @Transactional
    public void dismiss(UUID userId, UUID unknownId) {
        Caller c = access.caller(userId);
        Unknown u = scans.lock(c.shopId(), unknownId).orElseThrow(ScanService::unknownNotFound);
        if ("DISMISSED".equals(u.status())) {
            return;
        }
        if (!"OPEN".equals(u.status())) {
            throw unknownDone();
        }
        Instant now = KiranaChecks.now(clock);
        scans.settle(u.id(), "DISMISSED", null, now);
        letGoOfFrame(c.shopId(), u, now);
    }

    /**
     * Brings a put-away barcode back to "Needs a tap" (it was a product after all), with its scans; added to the open
     * entry of the same barcode and bill when there is one. 409 UNKNOWN_DONE once linked.
     */
    @Transactional
    public UnknownView reopen(UUID userId, UUID unknownId) {
        Caller c = access.caller(userId);
        Unknown seen = scans.find(c.shopId(), unknownId).orElseThrow(ScanService::unknownNotFound);
        scans.lockKey(c.shopId(), seen.code(), seen.purchaseId());
        Optional<Unknown> open = scans.lockOpen(c.shopId(), seen.code(), seen.purchaseId());
        Unknown u = scans.lock(c.shopId(), unknownId).orElseThrow(ScanService::unknownNotFound);
        if ("OPEN".equals(u.status())) {
            return views(c.shopId(), List.of(u)).get(0);
        }
        if (!"DISMISSED".equals(u.status())) {
            throw unknownDone();
        }
        Instant now = KiranaChecks.now(clock);
        if (open.isPresent()) {
            scans.addScans(open.get().id(), u.scans(), now);
            scans.settle(u.id(), "MERGED", null, now);
            return views(c.shopId(), List.of(scans.find(c.shopId(), open.get().id()).orElseThrow())).get(0);
        }
        scans.settle(u.id(), "OPEN", null, now);
        return views(c.shopId(), List.of(scans.find(c.shopId(), u.id()).orElseThrow())).get(0);
    }

    /** The shop's own photo of the pack is no longer needed once read (the read's text stays for other shops). */
    private void letGoOfFrame(UUID shopId, Unknown u, Instant now) {
        if (u.readId() == null) {
            return;
        }
        reads.lock(shopId, u.readId()).filter(r -> "READ".equals(r.status()) || "FAILED".equals(r.status()))
                .ifPresent(r -> {
                    if ("READ".equals(r.status())) {
                        reads.setStatus(r.id(), "DONE", null, now);
                    }
                    kiranaFiles.releaseIfUnused(r.fileIds(), r.id());
                });
    }

    // ---------- the reading job ----------

    /**
     * A pack's label just read. For a product's photo: shared as its catalogue item's photo when the label shows that
     * item (and the shop still has this photo); unknown barcodes need nothing (their suggestions are worked out when
     * shown). The caller holds a transaction.
     */
    void labelRead(Read r, KiranaDocumentAi.LabelFacts f, Instant now) {
        if (r.productId() == null || r.fileIds().isEmpty()) {
            return;
        }
        UUID file = r.fileIds().get(0);
        if (!file.equals(products.ownPhoto(r.productId()))) {
            return;
        }
        UUID itemId = products.catalogueItemOf(r.productId());
        CatalogueStore.Item item = itemId == null ? null : catalogue.find(itemId).orElse(null);
        if (item == null) {
            return;
        }
        NameMatcher.Score s = NameMatcher.score(NameMatcher.parse(join(f.brand(), f.name()), f.brand(), f.size()),
                NameMatcher.parse(item.name(), item.brand(), item.size()));
        if (!s.never() && s.value() >= NameMatcher.LIKELY && catalogueService.sharePhoto(item.id(), file)) {
            log.info("Shared a shop's photo as catalogue item {}'s", item.id());
        }
    }

    /**
     * A photo that couldn't be read: an unknown pack's photo is let go of (the shop may send another); bills' photos
     * and products' own photos stay.
     */
    void labelFailed(Read r, Instant now) {
        if ("LABEL".equals(r.kind()) && r.productId() == null) {
            kiranaFiles.releaseIfUnused(r.fileIds(), r.id());
        }
    }

    // ---------- the admin website ----------

    /** Scanning over every shop per day, newest first, for the last {@code days} (1 to 90). */
    @Transactional(readOnly = true)
    public List<ScanStatsView> stats(UUID adminId, int days) {
        catalogueService.requireSuperAdmin(adminId);
        int d = Math.max(1, Math.min(90, days));
        LocalDate from = KiranaChecks.today(KiranaChecks.now(clock)).minusDays(d - 1L);
        return scans.stats(from).stream().map(s -> new ScanStatsView(s.day(), s.shops(), s.scans(), s.unknown(),
                s.scans() == 0 ? 0 : Math.round(s.unknown() * 1000.0 / s.scans()) / 1000.0, s.sure(), s.picked(),
                s.created())).toList();
    }

    // ---------- helpers ----------

    /** Views of unknown scans, with everything they show read in a few queries for the lot. */
    private List<UnknownView> views(UUID shopId, List<Unknown> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<String, Known> inCatalogue = catalogue.findByCodes(list.stream().map(Unknown::code).toList());
        Set<UUID> readIds = new HashSet<>();
        list.forEach(u -> {
            if (u.readId() != null) {
                readIds.add(u.readId());
            }
        });
        Map<UUID, Read> labels = reads.findAll(readIds);
        MatchService.Shop shop = matcher.shop(shopId);
        List<UnknownView> out = new ArrayList<>();
        for (Unknown u : list) {
            Known k = inCatalogue.get(u.code());
            Read r = u.readId() == null ? null : labels.get(u.readId());
            KiranaDocumentAi.LabelFacts facts = r == null ? null : facts(r);
            LabelView label = r == null ? null : new LabelView("DONE".equals(r.status()) ? "READ" : r.status(),
                    facts == null ? null : facts.name(), facts == null ? null : facts.brand(),
                    facts == null ? null : facts.size(), facts == null ? null : facts.mrpPaise(),
                    facts == null ? null : facts.category(), r.failure());
            String brandGuess = k == null && facts == null ? matcher.brandOfBarcode(u.code()) : null;
            MatchService.Result m = MatchService.Result.NONE;
            if (k != null) {
                Set<UUID> sameItem = new HashSet<>();
                for (Candidate x : shop.products()) {
                    if (k.item().id().equals(x.itemId())) {
                        sameItem.add(x.productId());
                    }
                }
                m = matcher.match(shop, new MatchService.Want(null, k.item().brand(), k.item().name(), k.item().size()),
                        sameItem, true);
            } else if (facts != null) {
                m = matcher.match(shop, new MatchService.Want(null, facts.brand(), facts.name(), facts.size()),
                        Set.of(), true);
            }
            boolean needsLabel = k == null && (r == null || "FAILED".equals(r.status()))
                    && !looseItemCode(u.code());   // a shop's loose-item code has no pack to read
            out.add(new UnknownView(u.id(), u.code(), Quantities.json(u.scans()), u.purchaseId(),
                    k == null ? null : item(k), k != null && k.trusted(), label, needsLabel, brandGuess,
                    m.suggestions().stream().map(PurchaseService::suggestion).toList(), m.sure(), u.status(), u.createdAt(),
                    u.updatedAt()));
        }
        return out;
    }

    private static KiranaDocumentAi.LabelFacts facts(Read r) {
        if (r.result() == null) {
            return null;
        }
        try {
            return JSON.readValue(r.result(), KiranaDocumentAi.LabelFacts.class);
        } catch (RuntimeException e) {
            log.warn("Read {} has a result that isn't a label", r.id());
            return null;
        }
    }

    private static SuggestionView item(Known k) {
        CatalogueStore.Item i = k.item();
        return new SuggestionView(null, i.id(), i.name(), i.brand(), i.size(), i.mrpPaise(), 1);
    }

    private static String join(String brand, String name) {
        if (name == null) {
            return brand;
        }
        return brand == null || name.toLowerCase(java.util.Locale.ROOT).contains(brand.toLowerCase(java.util.Locale.ROOT))
                ? name : brand + " " + name;
    }

    static ApiException unknownNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "UNKNOWN_NOT_FOUND", "No such scan.", Map.of());
    }

    private static ApiException unknownDone() {
        return new ApiException(HttpStatus.CONFLICT, "UNKNOWN_DONE", "This barcode was already linked or put away.",
                Map.of());
    }
}
