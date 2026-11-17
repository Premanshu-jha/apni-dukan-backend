package in.apnidukan.kirana.service;

import in.apnidukan.common.context.UserDirectory;
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
import in.apnidukan.kirana.domain.Barcode;
import in.apnidukan.kirana.domain.Cursors;
import in.apnidukan.kirana.domain.Product;
import in.apnidukan.kirana.domain.Quantities;
import in.apnidukan.kirana.domain.Unit;
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
 * prices, names, unlinking a barcode and removing a product are the owner's.
 */
@Service
public class ProductService {

    /** Products with this many rows in the "running out" list at most (the shop sees the rest by searching). */
    static final int RUNNING_OUT_MAX = 200;

    /** Barcodes one product may have (MRP batches, pack designs, a carton). */
    static final int MAX_BARCODES = 10;

    private final KiranaAccess access;
    private final ProductStore products;
    private final UserDirectory users;
    private final Clock clock;

    public ProductService(KiranaAccess access, ProductStore products, UserDirectory users, Clock clock) {
        this.access = access;
        this.products = products;
        this.users = users;
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
        Map<UUID, String> names = KiranaChecks.names(users, moves.stream().map(MoveRow::by).distinct().toList());
        return new ProductDetail(view(p), moves.stream()
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
                true, 0, now);
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
        if (stock.signum() != 0) {
            products.addMoves(c.shopId(), List.of(new Move(p.id(), stock, stock, "COUNT", null, "First count", null)),
                    userId, now);
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
                active, old.version() + 1, KiranaChecks.now(clock));
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
        try {
            products.addBarcode(c.shopId(), p.id(), new Barcode(code, pack), userId, KiranaChecks.now(clock));
        } catch (DataIntegrityViolationException e) {
            throw taken(e);
        }
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
        if (change.signum() == 0) {
            return view(p);
        }
        if (after.abs().compareTo(new BigDecimal("999999999")) > 0) {
            throw invalid("quantity", "is too large");
        }
        Instant now = KiranaChecks.now(clock);
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
                p.lowStockAt(), p.active(), p.version(), now));
    }

    // ---------- helpers ----------

    private ProductView view(Product p) {
        return ProductView.of(p, products.barcodes(p.id()));
    }

    /** Products with their barcodes, read in one query for the lot. */
    private List<ProductView> views(List<Product> list) {
        Map<UUID, List<Barcode>> codes = products.barcodes(list.stream().map(Product::id).toList());
        return list.stream().map(p -> ProductView.of(p, codes.getOrDefault(p.id(), List.of()))).toList();
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
            throw nameTaken(name);
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
