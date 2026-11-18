package in.apnidukan.kirana.service;

import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.files.FileRefs;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.error.DatabaseErrors;
import in.apnidukan.common.text.TextCleaner;
import in.apnidukan.kirana.api.KiranaRequests.EditProduct;
import in.apnidukan.kirana.api.KiranaRequests.NewBarcode;
import in.apnidukan.kirana.api.KiranaRequests.NewProduct;
import in.apnidukan.kirana.api.KiranaRequests.StockChange;
import in.apnidukan.kirana.api.KiranaRequests.StockReason;
import in.apnidukan.kirana.api.KiranaResponses.MoveView;
import in.apnidukan.kirana.api.KiranaResponses.ProductDetail;
import in.apnidukan.kirana.api.KiranaResponses.ProductPage;
import in.apnidukan.kirana.api.KiranaResponses.ProductView;
import in.apnidukan.kirana.api.KiranaResponses.ScanView;
import in.apnidukan.kirana.api.StockResponses.BarcodeMoved;
import in.apnidukan.kirana.api.StockResponses.StockMoved;
import in.apnidukan.kirana.domain.Barcode;
import in.apnidukan.kirana.domain.Cursors;
import in.apnidukan.kirana.domain.Product;
import in.apnidukan.kirana.domain.Quantities;
import in.apnidukan.kirana.domain.Unit;
import in.apnidukan.kirana.repo.CatalogueStore;
import in.apnidukan.kirana.repo.ProductStore;
import in.apnidukan.kirana.repo.ProductStore.Move;
import in.apnidukan.kirana.repo.ProductStore.MoveRow;
import in.apnidukan.kirana.service.KiranaAccess.Caller;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static in.apnidukan.kirana.service.KiranaChecks.PAGE;
import static in.apnidukan.kirana.service.KiranaChecks.invalid;

/**
 * The kirana's catalogue and stock: products and their barcodes (several each, a carton's barcode counting its
 * pieces), finding one by a scan at the counter, stock that comes in, damaged pieces, and counts by hand. Every stock
 * change is recorded with why (kirana_stock_moves). The owner and helpers add products and stock and link barcodes;
 * prices, names, unlinking a barcode and removing a product are the owner's. The owner corrects mix-ups any time: a
 * barcode moved to the right product (the shared catalogue follows or the team reviews it), stock moved between
 * products, the cost price; owner or helper set a product's photo.
 */
@Service
public class ProductService {

    /** Products with this many rows in the "running out" list at most (the shop sees the rest by searching). */
    static final int RUNNING_OUT_MAX = 200;

    /** Barcodes one product may have (MRP batches, pack designs, a carton). */
    static final int MAX_BARCODES = 10;

    private final KiranaAccess access;
    private final ProductStore products;
    private final CatalogueService catalogue;
    private final UserDirectory users;
    private final FileRefs files;
    private final PhotoReads reads;
    private final CatalogueStore catalogueItems;
    private final KiranaFiles kiranaFiles;
    private final Clock clock;

    public ProductService(KiranaAccess access, ProductStore products, CatalogueService catalogue, UserDirectory users,
                          FileRefs files, PhotoReads reads, CatalogueStore catalogueItems,
                          KiranaFiles kiranaFiles, Clock clock) {
        this.access = access;
        this.products = products;
        this.catalogue = catalogue;
        this.users = users;
        this.files = files;
        this.reads = reads;
        this.catalogueItems = catalogueItems;
        this.kiranaFiles = kiranaFiles;
        this.clock = clock;
    }

    /**
     * One page of the catalogue in name order; {@code q}: names containing it anywhere ("salt" finds "Tata Salt 1 kg"),
     * or barcodes starting with it (digits typed from a pack that won't scan).
     */
    @Transactional(readOnly = true)
    public ProductPage list(UUID userId, String q, String cursor) {
        Caller c = access.caller(userId);
        Optional<String[]> after = Cursors.decode(cursor);
        List<ProductStore.Row> rows = products.page(c.shopId(), KiranaChecks.prefix(q),
                after.map(a -> a[0]).orElse(null), after.map(a -> uuid(a[1])).orElse(null), PAGE);
        boolean more = rows.size() > PAGE;
        List<ProductStore.Row> page = more ? rows.subList(0, PAGE) : rows;
        ProductStore.Row last = page.isEmpty() ? null : page.get(page.size() - 1);
        List<Product> found = page.stream().map(ProductStore.Row::product).toList();
        return new ProductPage(views(found),
                more ? Cursors.encode(last.sortKey(), last.product().id().toString()) : null);
    }

    /** Products at or below their alert (or below zero), for "Running out". */
    @Transactional(readOnly = true)
    public List<ProductView> runningOut(UUID userId) {
        Caller c = access.caller(userId);
        return views(products.runningOut(c.shopId(), RUNNING_OUT_MAX));
    }

    /**
     * The active product with this barcode and how many of it one scan means (a scan at the counter). 404
     * PRODUCT_NOT_FOUND: link it to a product.
     */
    @Transactional(readOnly = true)
    public ScanView byBarcode(UUID userId, String code) {
        Caller c = access.caller(userId);
        String b;
        try {
            b = KiranaChecks.barcode(code);
        } catch (ApiException e) {
            b = null;   // a misread scan is simply "not found": the app offers to link it
        }
        return Optional.ofNullable(b).flatMap(x -> products.findByBarcode(c.shopId(), x))
                .map(s -> new ScanView(view(s.product()), Quantities.json(s.pack())))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND",
                        "No product has this barcode yet.", Map.of("barcode", code == null ? "" : code)));
    }

    /** A product with its latest stock changes. 404 PRODUCT_NOT_FOUND if not this shop's. */
    @Transactional(readOnly = true)
    public ProductDetail detail(UUID userId, UUID productId) {
        Caller c = access.caller(userId);
        Product p = products.find(c.shopId(), productId).orElseThrow(ProductService::notFound);
        List<MoveRow> moves = products.moves(p.id(), PAGE);
        Map<UUID, String> names = KiranaChecks.names(users, moves.stream().map(MoveRow::by).filter(Objects::nonNull)
                .distinct().toList());
        return new ProductDetail(view(c, p), moves.stream()
                .map(m -> new MoveView(Quantities.json(m.change()), Quantities.json(m.stockAfter()),
                        m.reason(), m.billId(), m.billNo(), m.note(), names.get(m.by()), m.at()))
                .toList());
    }

    /**
     * Adds a product (owner or helper), with its first barcode if given. 400 for a bad quantity, price or pack (or a
     * pack without a barcode); 409 NAME_TAKEN or BARCODE_TAKEN (with the product that has it) when another active
     * product of the shop has the same name or barcode. Its first stock is recorded as a count.
     */
    @Transactional
    public ProductView create(UUID userId, NewProduct in) {
        Caller c = access.caller(userId);
        String name = TextCleaner.clean(KiranaChecks.PRODUCT_NAME, in.name());
        String barcode = KiranaChecks.barcode(in.barcode());
        if (barcode != null) {
            products.lockBarcode(c.shopId(), barcode);
        }
        if (barcode == null && in.pack() != null) {
            throw invalid("pack", "Scan the barcode of the pack first");
        }
        BigDecimal pack = barcode == null ? null : pack(in.pack(), in.unit());
        BigDecimal stock = quantity("stock", in.stock() == null ? BigDecimal.ZERO : in.stock(), in.unit(), true);
        BigDecimal low = in.lowStockAt() == null ? null : quantity("lowStockAt", in.lowStockAt(), in.unit(), true);
        Instant now = KiranaChecks.now(clock);
        Product p = new Product(UUID.randomUUID(), c.shopId(), name, in.unit(), in.pricePaise(), stock, low,
                true, 0, now, in.stock() != null ? now : null);
        requireFree(c.shopId(), null, name, barcode);
        List<Barcode> barcodes = barcode == null ? List.of() : List.of(new Barcode(barcode, pack));
        try {
            products.insert(p, userId, now);
            for (Barcode b : barcodes) {
                products.addBarcode(c.shopId(), p.id(), b, userId, now);
            }
        } catch (DataIntegrityViolationException e) {
            throw taken(e);
        }
        for (Barcode b : barcodes) {
            catalogue.linkBarcode(c.shopId(), p, null, b.code(), b.pack(), in.mrpPaise(), now);
        }
        if (stock.signum() != 0) {
            products.addMoves(c.shopId(), List.of(new Move(p.id(), stock, stock, "COUNT", null, "First count", null)),
                    userId, now);
        }
        if (in.stock() != null) {
            products.markCounted(p.id(), now);   // the shelf counted as it was added (0 is a count too)
        }
        return ProductView.of(p, barcodes);
    }

    /**
     * Changes a product (owner only: 403 OWNER_ONLY). 409 PRODUCT_CHANGED for a stale version; 409 NAME_TAKEN. A price
     * change applies to bills from now on; old bills keep theirs. The unit (piece or kg) only until it is first sold
     * (409 PRODUCT_SOLD: add a new product instead); to piece, the stock, alert and packs must be whole (400 naming
     * unit). Removing it frees its barcodes for other products;
     * putting it back brings them back, except any another product has meanwhile (those are dropped from it).
     */
    @Transactional
    public ProductView update(UUID userId, UUID productId, EditProduct in) {
        Caller c = access.owner(userId);
        Product old = products.lock(c.shopId(), productId).orElseThrow(ProductService::notFound);
        if (old.version() != in.version()) {
            throw new ApiException(HttpStatus.CONFLICT, "PRODUCT_CHANGED",
                    "Someone changed this product just now. Reload and try again.");
        }
        String name = in.name() == null ? old.name() : TextCleaner.clean(KiranaChecks.PRODUCT_NAME, in.name());
        Unit unit = in.unit() == null ? old.unit() : in.unit();
        BigDecimal low = Boolean.TRUE.equals(in.noLowStockAlert()) ? null
                : in.lowStockAt() == null ? old.lowStockAt() : quantity("lowStockAt", in.lowStockAt(), unit, true);
        boolean active = in.active() == null ? old.active() : in.active();
        long price = in.pricePaise() == null ? old.pricePaise() : in.pricePaise();
        if (unit != old.unit()) {
            if (products.everSold(old.id())) {
                throw new ApiException(HttpStatus.CONFLICT, "PRODUCT_SOLD", "\"" + old.name() + "\" was already sold "
                        + "by the " + (old.unit() == Unit.KG ? "kg" : "piece") + ". Add a new product for the other way.");
            }
            boolean whole = Stream.concat(Stream.of(old.stock(), low), products.barcodes(old.id()).stream()
                    .map(Barcode::pack)).filter(Objects::nonNull)
                    .allMatch(q -> q.stripTrailingZeros().scale() <= 0);
            if (unit == Unit.PIECE && !whole) {
                throw invalid("unit", "Its stock, alert or a pack isn't a whole number. Count it in pieces first");
            }
        }
        Product updated = new Product(old.id(), old.shopId(), name, unit, price, old.stock(), low,
                active, old.version() + 1, KiranaChecks.now(clock), old.countedAt());
        if (Objects.equals(name, old.name()) && price == old.pricePaise() && unit == old.unit()
                && Objects.equals(low, old.lowStockAt()) && active == old.active()) {
            return view(old);
        }
        if (active) {
            requireFree(c.shopId(), old.id(), name, null);
        }
        try {
            products.update(updated, updated.updatedAt());
        } catch (DataIntegrityViolationException e) {
            throw taken(e);
        }
        if (active != old.active()) {
            // Each of its barcodes waits for anyone linking the same code meanwhile (in code order: no circles)
            products.barcodes(old.id()).stream().map(Barcode::code).sorted()
                    .forEach(code -> products.lockBarcode(c.shopId(), code));
            products.setBarcodesActive(c.shopId(), old.id(), active);
        }
        return view(updated);
    }

    /**
     * Links a barcode to a product (owner or helper: a pack that didn't scan, a carton). Linking one it already has
     * with the same pack changes nothing; with another pack the owner corrects the pack (a helper gets 409
     * BARCODE_TAKEN: ask the owner). 400 naming code or pack; 409 PRODUCT_REMOVED; 409 BARCODE_TAKEN (naming the
     * product) when another active product has it; 409 TOO_MANY_BARCODES past 10.
     */
    @Transactional
    public ProductView addBarcode(UUID userId, UUID productId, NewBarcode in) {
        Caller c = access.caller(userId);
        String code = KiranaChecks.barcode("code", in.code());
        if (code == null) {
            throw invalid("code", "Scan or type the barcode");
        }
        Product p = products.lock(c.shopId(), productId).orElseThrow(ProductService::notFound);
        products.lockBarcode(c.shopId(), code);
        if (!p.active()) {
            throw removed();
        }
        BigDecimal pack = pack(in.pack(), p.unit());
        List<Barcode> has = products.barcodes(p.id());
        Optional<Barcode> same = has.stream().filter(b -> b.code().equals(code)).findFirst();
        if (same.isPresent()) {
            if (same.get().pack().compareTo(pack) == 0) {
                return ProductView.of(p, has);
            }
            if (c.isOwner()) {
                products.setPack(p.id(), code, pack);
                return view(p);
            }
            throw barcodeTaken("This product already has this barcode as a pack of "
                    + Quantities.plain(same.get().pack()) + ". Ask the owner to remove it first.",
                    Map.of("productId", p.id(), "name", p.name()));
        }
        requireFree(c.shopId(), p.id(), null, code);
        if (has.size() >= MAX_BARCODES) {
            throw new ApiException(HttpStatus.CONFLICT, "TOO_MANY_BARCODES",
                    "A product can have at most " + MAX_BARCODES + " barcodes. Ask the owner to remove an old one.");
        }
        Instant now = KiranaChecks.now(clock);
        try {
            products.addBarcode(c.shopId(), p.id(), new Barcode(code, pack), userId, now);
        } catch (DataIntegrityViolationException e) {
            throw taken(e);
        }
        catalogue.linkBarcode(c.shopId(), p, products.catalogueItemOf(p.id()), code, pack, null, now);
        return view(p);
    }

    /** Unlinks a barcode from a product (owner only). 404 BARCODE_NOT_FOUND when the product doesn't have it. */
    @Transactional
    public ProductView removeBarcode(UUID userId, UUID productId, String code) {
        Caller c = access.owner(userId);
        Product p = products.lock(c.shopId(), productId).orElseThrow(ProductService::notFound);
        String b;
        try {
            b = KiranaChecks.barcode(code);
        } catch (ApiException e) {
            b = null;
        }
        if (b == null || !products.removeBarcode(p.id(), b)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "BARCODE_NOT_FOUND", "This product doesn't have this barcode.");
        }
        return view(p);
    }

    /**
     * Records stock that came in (RESTOCK), damaged pieces taken off the shelf (DAMAGED), or a count by hand (COUNT:
     * the stock becomes the number counted). Owner or helper. 400 for a quantity the unit doesn't allow; 409
     * PRODUCT_REMOVED for a removed product. A count equal to the stock records nothing. The same clientRef again (a
     * retry after a lost answer) changes nothing and returns the product as it is.
     */
    @Transactional
    public ProductView changeStock(UUID userId, UUID productId, StockChange in) {
        Caller c = access.caller(userId);
        Product p = products.lock(c.shopId(), productId).orElseThrow(ProductService::notFound);
        // Under the product's lock: a retry sent at the same moment waits for the first and then finds it here
        if (products.moveRecorded(c.shopId(), in.clientRef())) {
            return view(p);
        }
        if (!p.active()) {
            throw removed();
        }
        BigDecimal q = quantity("quantity", in.quantity(), p.unit(), in.reason() == StockReason.COUNT);
        BigDecimal after = switch (in.reason()) {
            case RESTOCK -> p.stock().add(q);
            case DAMAGED -> p.stock().subtract(q);
            case COUNT -> q;
        };
        BigDecimal change = after.subtract(p.stock());
        Instant now = KiranaChecks.now(clock);
        Instant counted = p.countedAt();
        if (in.reason() == StockReason.COUNT) {
            products.markCounted(p.id(), now);   // a count is a count even when it matches the stock
            counted = now;
        }
        if (change.signum() == 0) {
            return view(new Product(p.id(), p.shopId(), p.name(), p.unit(), p.pricePaise(), p.stock(),
                    p.lowStockAt(), p.active(), p.version(), p.updatedAt(), counted));
        }
        if (after.abs().compareTo(new BigDecimal("999999999")) > 0) {
            throw invalid("quantity", "is too large");
        }
        BigDecimal stored = Quantities.stored(after);
        products.setStock(p.id(), stored, now);
        try {
            products.addMoves(c.shopId(), List.of(new Move(p.id(), Quantities.stored(change), stored,
                    in.reason().name(), null, KiranaChecks.optional(KiranaChecks.NOTE, in.note()), in.clientRef())),
                    userId, now);
        } catch (DataIntegrityViolationException e) {
            // The same clientRef sent for another product (the app's mistake): nothing is changed
            if (DatabaseErrors.violatedUnique(e, "ux_kirana_stock_moves_client_ref")) {
                throw new ApiException(HttpStatus.CONFLICT, "STOCK_CHANGE_REPEATED",
                        "This stock change was already recorded on another product. Reload and try again.");
            }
            throw e;
        }
        return view(new Product(p.id(), p.shopId(), p.name(), p.unit(), p.pricePaise(), stored,
                p.lowStockAt(), p.active(), p.version(), now, stored.signum() < 0 ? null : counted));
    }

    /**
     * A new product (owner or helper) for something read or scanned: from a shared catalogue item ({@code itemId}, its
     * name, unit and MRP as the price unless given) and/or the name, unit and price sent (400 naming itemId, name, unit
     * or pricePaise when missing; NAME_TAKEN as when adding). It is linked to the catalogue item. It starts counted at
     * 0: what came in (the bill's or the scan's) is added to it, so its stock is known. Its id.
     */
    UUID newFor(UUID userId, UUID itemId, String name, Unit unit, Long pricePaise) {
        return newFor(userId, itemId, name, unit, pricePaise, BigDecimal.ZERO);
    }

    /** As {@link #newFor(UUID, UUID, String, Unit, Long)}, with this count (null: not counted). */
    UUID newFor(UUID userId, UUID itemId, String name, Unit unit, Long pricePaise, BigDecimal counted) {
        CatalogueStore.Item item = null;
        if (itemId != null) {
            item = catalogueItems.find(itemId).orElseThrow(() -> invalid("itemId",
                    "This catalogue item doesn't exist any more"));
        }
        String n = name != null ? name : item == null ? null : item.name();
        Unit u = unit != null ? unit : item == null ? null : item.unit();
        Long price = pricePaise != null ? pricePaise : item == null ? null : item.mrpPaise();
        if (n == null) {
            throw invalid("name", "Enter the product's name");
        }
        if (u == null) {
            throw invalid("unit", "Choose piece or kg");
        }
        if (price == null) {
            throw invalid("pricePaise", "Enter the price");
        }
        UUID id = create(userId, new NewProduct(n, null, null, null, u, price, counted, null)).id();
        if (item != null) {
            products.setCatalogueItem(id, item.id());
        }
        return id;
    }

    /**
     * The product's stock counted now (the product setup adding a line of its list for a product the shop already has):
     * one COUNT move when it changed, and the product counted. {@code addTo}: the count is more of it kept elsewhere,
     * added to its stock (read under the lock; below zero counts as none). 400 naming quantity; 409 PRODUCT_REMOVED.
     */
    void countFor(UUID shopId, UUID userId, UUID productId, BigDecimal quantity, boolean addTo, String note) {
        Product p = products.lock(shopId, productId).orElseThrow(ProductService::notFound);
        if (!p.active()) {
            throw removed();
        }
        BigDecimal q = quantity("quantity", quantity, p.unit(), true);
        if (addTo) {
            q = p.stock().max(BigDecimal.ZERO).add(q);
        }
        Instant now = KiranaChecks.now(clock);
        products.markCounted(p.id(), now);
        BigDecimal change = q.subtract(p.stock());
        if (change.signum() == 0) {
            return;
        }
        BigDecimal stored = Quantities.stored(q);
        products.setStock(p.id(), stored, now);
        products.addMoves(shopId, List.of(new Move(p.id(), Quantities.stored(change), stored, "COUNT", null, note,
                null)), userId, now);
    }

    // ---------- the owner's corrections ----------

    /**
     * One of this product's barcodes belongs to another product (owner: a wrong link made while shelving). The barcode
     * moves with its pack (it must suit the other product's unit: 400 naming code); the stock already scanned in with
     * it is offered to move too ({@code suggestedQuantity}), not moved. The shared catalogue follows when the link was
     * only this shop's say, else the team reviews it. 404 BARCODE_NOT_FOUND when the product doesn't have it; 409
     * PRODUCT_REMOVED when the other product was removed; 409 TOO_MANY_BARCODES; 400 naming toProductId for the same
     * product.
     */
    @Transactional
    public BarcodeMoved moveBarcode(UUID userId, UUID productId, String code, UUID toProductId) {
        return moveBarcode(userId, productId, code, toProductId, false);
    }

    /**
     * As {@link #moveBarcode(UUID, UUID, String, UUID)}; with {@code moveStock}, the stock scanned in with it moves too,
     * in the same go (one MOVE on each; nothing when the units differ).
     */
    @Transactional
    public BarcodeMoved moveBarcode(UUID userId, UUID productId, String code, UUID toProductId, boolean moveStock) {
        Caller c = access.owner(userId);
        if (productId.equals(toProductId)) {
            throw invalid("toProductId", "Choose another product");
        }
        Map<UUID, Product> locked = new java.util.HashMap<>();
        for (Product x : products.lockAll(c.shopId(), List.of(productId, toProductId))) {
            locked.put(x.id(), x);
        }
        Product from = Optional.ofNullable(locked.get(productId)).orElseThrow(ProductService::notFound);
        Product to = Optional.ofNullable(locked.get(toProductId)).orElseThrow(ProductService::notFound);
        if (!to.active()) {
            throw removed();
        }
        String b;
        try {
            b = KiranaChecks.barcode(code);
        } catch (ApiException e) {
            b = null;
        }
        String scanned = b;
        Barcode moving = scanned == null ? null : products.barcodes(from.id()).stream()
                .filter(x -> x.code().equals(scanned)).findFirst().orElse(null);
        if (moving == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "BARCODE_NOT_FOUND", "This product doesn't have this barcode.");
        }
        if (Quantities.problem(moving.pack(), to.unit(), false).isPresent()) {
            throw invalid("code", "This barcode counts " + Quantities.plain(moving.pack()) + " a scan, which \""
                    + to.name() + "\" can't have (it is counted in pieces). Remove it and link it again");
        }
        if (products.barcodeCount(to.id()) >= MAX_BARCODES) {
            throw new ApiException(HttpStatus.CONFLICT, "TOO_MANY_BARCODES",
                    "\"" + to.name() + "\" has " + MAX_BARCODES + " barcodes already. Remove an old one first.");
        }
        products.moveBarcode(from.id(), to.id(), moving.code());
        Instant now = KiranaChecks.now(clock);
        catalogue.correct(c.shopId(), to, moving.code(), moving.pack(), now);
        BigDecimal in = products.scannedIn(from.id(), moving.code());
        BigDecimal offer = in.min(from.stock()).max(BigDecimal.ZERO);
        if (from.unit() != to.unit()) {
            offer = BigDecimal.ZERO;
        }
        if (moveStock && offer.signum() > 0) {
            BigDecimal fromAfter = Quantities.stored(from.stock().subtract(offer));
            BigDecimal toAfter = Quantities.stored(to.stock().add(offer));
            products.setStock(from.id(), fromAfter, now);
            products.setStock(to.id(), toAfter, now);
            products.addStockMoves(c.shopId(), List.of(
                    new ProductStore.StockMove(from.id(), offer.negate(), fromAfter, "MOVE", null, moving.code(),
                            cut("To " + to.name() + " (barcode moved)"), null),
                    new ProductStore.StockMove(to.id(), offer, toAfter, "MOVE", null, moving.code(),
                            cut("From " + from.name() + " (barcode moved)"), null)), userId, now);
            return new BarcodeMoved(view(c, products.find(c.shopId(), from.id()).orElseThrow()),
                    view(c, products.find(c.shopId(), to.id()).orElseThrow()), BigDecimal.ZERO);
        }
        return new BarcodeMoved(view(c, from), view(c, to), Quantities.json(offer));
    }

    /**
     * Moves stock counted on the wrong product to the right one (owner): one MOVE on each, same unit only (409
     * UNITS_DIFFER). 400 naming quantity (more than 0, as the unit allows); 400 naming toProductId for the same
     * product; 409 PRODUCT_REMOVED when the other was removed. The same clientRef again moves nothing twice.
     */
    @Transactional
    public StockMoved moveStock(UUID userId, UUID productId, UUID clientRef, UUID toProductId, BigDecimal quantity) {
        Caller c = access.owner(userId);
        if (productId.equals(toProductId)) {
            throw invalid("toProductId", "Choose another product");
        }
        Map<UUID, Product> locked = new java.util.HashMap<>();
        for (Product x : products.lockAll(c.shopId(), List.of(productId, toProductId))) {
            locked.put(x.id(), x);
        }
        Product from = Optional.ofNullable(locked.get(productId)).orElseThrow(ProductService::notFound);
        Product to = Optional.ofNullable(locked.get(toProductId)).orElseThrow(ProductService::notFound);
        if (products.moveRecorded(c.shopId(), clientRef)) {
            return new StockMoved(view(c, from), view(c, to));
        }
        if (!to.active()) {
            throw removed();
        }
        if (from.unit() != to.unit()) {
            throw new ApiException(HttpStatus.CONFLICT, "UNITS_DIFFER", "\"" + from.name() + "\" and \"" + to.name()
                    + "\" are counted differently (pieces and kg). Set each one's count instead.");
        }
        BigDecimal q = quantity("quantity", quantity, from.unit(), false);
        if (q.compareTo(from.stock().max(BigDecimal.ZERO)) > 0) {
            throw invalid("quantity", "\"" + from.name() + "\" has only " + Quantities.plain(from.stock().max(
                    BigDecimal.ZERO)) + ": set its count first if it has more");
        }
        Instant now = KiranaChecks.now(clock);
        BigDecimal fromAfter = Quantities.stored(from.stock().subtract(q));
        BigDecimal toAfter = Quantities.stored(to.stock().add(q));
        if (toAfter.abs().compareTo(new BigDecimal("999999999")) > 0) {
            throw invalid("quantity", "is too large");
        }
        products.setStock(from.id(), fromAfter, now);
        products.setStock(to.id(), toAfter, now);
        products.addStockMoves(c.shopId(), List.of(
                new ProductStore.StockMove(from.id(), q.negate(), fromAfter, "MOVE", null, null,
                        cut("To " + to.name()), clientRef),
                new ProductStore.StockMove(to.id(), q, toAfter, "MOVE", null, null, cut("From " + from.name()), null)),
                userId, now);
        return new StockMoved(view(c, products.find(c.shopId(), from.id()).orElseThrow()),
                view(c, products.find(c.shopId(), to.id()).orElseThrow()));
    }

    /**
     * The product's photo (owner or helper): a PRODUCT_PHOTO this shop uploaded (400 naming fileId otherwise), or
     * null to remove the shop's own (the catalogue's shared one shows again). When the product's catalogue item has no
     * shared photo yet, the label is read by AI and the photo shared if it shows that item. The same clientRef again
     * changes nothing.
     */
    @Transactional
    public ProductView setPhoto(UUID userId, UUID productId, UUID clientRef, UUID fileId) {
        Caller c = access.caller(userId);
        Product p = products.lock(c.shopId(), productId).orElseThrow(ProductService::notFound);
        if (!p.active()) {
            throw removed();
        }
        ProductStore.Extra x = products.extras(List.of(p.id())).get(p.id());
        UUID own = products.ownPhoto(p.id());
        if (Objects.equals(own, fileId)) {
            return view(c, p);
        }
        Instant now = KiranaChecks.now(clock);
        if (fileId != null) {
            FileRefs.FileRef f = files.find(fileId).orElse(null);
            if (f == null || !c.shopId().equals(f.shopId()) || !"PRODUCT_PHOTO".equals(f.purpose())) {
                throw invalid("fileId", "Upload the photo first (as PRODUCT_PHOTO)");
            }
            files.keep(fileId);
        }
        products.setPhoto(p.id(), fileId);
        if (own != null) {
            kiranaFiles.releaseIfUnused(List.of(own), null);
        }
        if (fileId != null && x != null && x.catalogueItemId() != null && !catalogue.hasPhoto(x.catalogueItemId())) {
            reads.queue(c.shopId(), userId, "LABEL", clientRef, List.of(fileId), p.id(), now);
        }
        return view(c, p);
    }

    /** The cost price (owner): what is paid per piece or kg; null clears it. Bills already made don't change. */
    @Transactional
    public ProductView setCost(UUID userId, UUID productId, Long costPaise) {
        Caller c = access.owner(userId);
        Product p = products.lock(c.shopId(), productId).orElseThrow(ProductService::notFound);
        products.setCost(p.id(), costPaise);
        return view(c, p);
    }

    private static String cut(String note) {
        return note.length() > 200 ? note.substring(0, 200) : note;
    }

    // ---------- helpers ----------

    /** A product with its barcodes and photo (no cost: see {@link #view(Caller, Product)}). */
    private ProductView view(Product p) {
        ProductStore.Extra x = products.extras(List.of(p.id())).get(p.id());
        UUID photo = x == null ? null : x.photoFileId();
        return ProductView.of(p, products.barcodes(p.id())).with(null, photo, link(photo));
    }

    /** A link to a photo (any shop's: the catalogue shares them), or null. */
    private String link(UUID photo) {
        return photo == null ? null : files.links(List.of(photo)).get(photo);
    }

    /** As {@link #view(Product)}, with the cost price for the owner. */
    ProductView view(Caller c, Product p) {
        ProductStore.Extra x = products.extras(List.of(p.id())).get(p.id());
        UUID photo = x == null ? null : x.photoFileId();
        return ProductView.of(p, products.barcodes(p.id())).with(x == null || !c.isOwner() ? null : x.costPaise(),
                photo, link(photo));
    }

    /** Products with their barcodes and photos, read in two queries for the lot. */
    private List<ProductView> views(List<Product> list) {
        List<UUID> ids = list.stream().map(Product::id).toList();
        Map<UUID, List<Barcode>> codes = products.barcodes(ids);
        Map<UUID, ProductStore.Extra> extras = products.extras(ids);
        Map<UUID, String> links = files.links(extras.values().stream().map(ProductStore.Extra::photoFileId)
                .filter(Objects::nonNull).distinct().toList());
        return list.stream().map(p -> {
            ProductStore.Extra x = extras.get(p.id());
            UUID photo = x == null ? null : x.photoFileId();
            return ProductView.of(p, codes.getOrDefault(p.id(), List.of())).with(null, photo,
                    photo == null ? null : links.get(photo));
        }).toList();
    }

    /** A barcode's pack: 1 when left out; else as a quantity of the unit (400 naming {@code pack}). */
    private static BigDecimal pack(BigDecimal pack, Unit unit) {
        return pack == null ? Quantities.stored(BigDecimal.ONE) : quantity("pack", pack, unit, false);
    }

    private static ApiException removed() {
        return new ApiException(HttpStatus.CONFLICT, "PRODUCT_REMOVED", "This product was removed from your list.");
    }

    /** A quantity checked for the unit (400 naming the field), as stored. */
    static BigDecimal quantity(String field, BigDecimal q, Unit unit, boolean zeroAllowed) {
        Optional<String> problem = Quantities.problem(q, unit, zeroAllowed);
        if (problem.isPresent()) {
            throw invalid(field, problem.get());
        }
        return Quantities.stored(q);
    }

    /**
     * 409 BARCODE_TAKEN (naming the product that has it) or NAME_TAKEN when another active product of the shop has
     * this barcode or name. Checked before writing, because after a refused write the transaction can't read any more;
     * the unique keys still catch two saves at the same moment ({@link #taken}).
     */
    private void requireFree(UUID shopId, UUID self, String name, String barcode) {
        if (barcode != null) {
            Optional<Product> other = products.findByBarcode(shopId, barcode).map(ProductStore.Scan::product)
                    .filter(o -> !o.id().equals(self));
            if (other.isPresent()) {
                throw barcodeTaken("\"" + other.get().name() + "\" already has this barcode.",
                        Map.of("productId", other.get().id(), "name", other.get().name()));
            }
        }
        if (name != null && products.nameTaken(shopId, name, self)) {
            Optional<Product> other = products.findByName(shopId, name).filter(o -> !o.id().equals(self));
            throw other.isPresent() ? new ApiException(HttpStatus.CONFLICT, "NAME_TAKEN", "You already have a product "
                    + "called \"" + other.get().name() + "\".", Map.of("productId", other.get().id(), "name",
                    other.get().name())) : nameTaken(name);
        }
    }

    /** The same refusals when the unique key caught a save made at the same moment as this one. */
    private static RuntimeException taken(DataIntegrityViolationException e) {
        if (DatabaseErrors.violatedUnique(e, "ux_kirana_barcodes_code")) {
            return barcodeTaken("Another product already has this barcode.", Map.of());
        }
        if (DatabaseErrors.violatedUnique(e, "ux_kirana_products_name")) {
            return new ApiException(HttpStatus.CONFLICT, "NAME_TAKEN", "You already have a product with this name.");
        }
        return e;
    }

    private static ApiException barcodeTaken(String message, Map<String, Object> details) {
        return new ApiException(HttpStatus.CONFLICT, "BARCODE_TAKEN", message, details);
    }

    private static ApiException nameTaken(String name) {
        return new ApiException(HttpStatus.CONFLICT, "NAME_TAKEN", "You already have a product called \"" + name + "\".");
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "No such product.");
    }

    /** An id from a page token; a broken one starts from the beginning instead of failing. */
    static UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return new UUID(0, 0);
        }
    }
}
