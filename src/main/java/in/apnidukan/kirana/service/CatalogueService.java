package in.apnidukan.kirana.service;

import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.files.FileRefs;
import in.apnidukan.common.context.TeamRole;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.text.TextCleaner;
import in.apnidukan.common.text.TextCleaner.TextField;
import in.apnidukan.kirana.api.OrderRequests.CatalogueEdit;
import in.apnidukan.kirana.api.OrderResponses.CataloguePage;
import in.apnidukan.kirana.api.OrderResponses.CatalogueView;
import in.apnidukan.kirana.domain.Cursors;
import in.apnidukan.kirana.domain.Product;
import in.apnidukan.kirana.domain.Quantities;
import in.apnidukan.kirana.repo.CatalogueStore;
import in.apnidukan.kirana.repo.CatalogueStore.Item;
import in.apnidukan.kirana.repo.ProductStore;
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

import static in.apnidukan.kirana.service.KiranaChecks.PAGE;

/**
 * The shared catalogue: one entry per real product, keyed by its barcodes, so the same item is known in every shop
 * (online orders fill a shop's gaps from the shops near it by it) and a scan fills in a new product's name and MRP.
 * It grows from the shops: the first shop to add a barcode makes the entry from its product; super admins correct it.
 */
@Service
public class CatalogueService {

    private static final TextField NAME = new TextField("name", 2, 100);
    private static final TextField BRAND = new TextField("brand", 1, 60);
    private static final TextField SIZE = new TextField("size", 1, 30);
    private static final TextField CATEGORY = new TextField("category", 2, 40);

    private final KiranaAccess access;
    private final CatalogueStore catalogue;
    private final ProductStore products;
    private final UserDirectory users;
    private final FileRefs files;
    private final Clock clock;

    public CatalogueService(KiranaAccess access, CatalogueStore catalogue, ProductStore products, UserDirectory users,
                            FileRefs files, Clock clock) {
        this.access = access;
        this.catalogue = catalogue;
        this.products = products;
        this.users = users;
        this.files = files;
        this.clock = clock;
    }

    /**
     * A scan of a barcode the shop hasn't linked yet (owner or helper): the catalogue's name, size and MRP to fill in
     * a new product. 404 CATALOGUE_NOT_FOUND when no shop added it yet (the shop types the details).
     */
    @Transactional(readOnly = true)
    public CatalogueView byBarcode(UUID userId, String code) {
        access.caller(userId);
        String b;
        try {
            b = KiranaChecks.barcode("code", code);
        } catch (ApiException e) {
            b = null;
        }
        String scanned = b;
        return Optional.ofNullable(scanned).flatMap(catalogue::findByCode)
                .map(m -> view(m.item(), m.pack(), catalogue.codes(m.item().id()),
                        catalogue.link(scanned).map(CatalogueStore.Link::confirmed).orElse(false)))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CATALOGUE_NOT_FOUND",
                        "This barcode isn't known yet. Type the product's details."));
    }

    // ---------- called by ProductService when a shop adds a product or links a barcode ----------

    /**
     * Links the product to the catalogue entry of this barcode, making the entry from the product when no shop added
     * the barcode yet (two shops adding the same new barcode at once end on one entry). The caller holds the
     * product's lock.
     */
    void linkBarcode(UUID shopId, Product product, UUID linkedItem, String code, BigDecimal pack, Long mrpPaise,
                     Instant now) {
        Optional<CatalogueStore.Link> known = catalogue.lockLink(code);
        if (known.isPresent()) {
            UUID mine = linkedItem;
            if (mine == null) {
                mine = known.get().itemId();
                products.setCatalogueItem(product.id(), mine);
            }
            // a barcode another entry has stays with it: the shop's own link is unchanged; its say is counted
            vote(shopId, known.get(), mine, now);
            return;
        }
        if (linkedItem != null) {
            if (catalogue.addCode(linkedItem, code, Quantities.stored(pack), "SHOP", false, now)) {
                catalogue.vote(code, shopId, linkedItem, now);
            }
            return;
        }
        Item item = new Item(UUID.randomUUID(), product.name(), null, null, product.unit(), mrpPaise, null, 0, now);
        catalogue.insertItem(item, shopId, now);
        if (catalogue.addCode(item.id(), code, Quantities.stored(pack), "SHOP", false, now)) {
            products.setCatalogueItem(product.id(), item.id());
            catalogue.vote(code, shopId, item.id(), now);
            return;
        }
        // Another shop added this barcode at the same moment: its entry wins, ours goes
        catalogue.deleteItem(item.id());
        catalogue.lockLink(code).ifPresent(l -> {
            products.setCatalogueItem(product.id(), l.itemId());
            vote(shopId, l, l.itemId(), now);
        });
    }

    /**
     * A shop says the barcode is {@code itemId}: counted; the link becomes trusted once two shops agree; a shop that
     * disagrees with it opens a review for the team (the shop keeps its own link meanwhile). The caller holds the
     * link's lock.
     */
    private void vote(UUID shopId, CatalogueStore.Link link, UUID itemId, Instant now) {
        catalogue.vote(link.code(), shopId, itemId, now);
        if (itemId.equals(link.itemId())) {
            if (!link.confirmed() && catalogue.votes(link.code(), itemId) >= 2) {
                catalogue.confirm(link.code());
            }
            return;
        }
        catalogue.addReview(new CatalogueStore.Review(UUID.randomUUID(), link.code(), "CONFLICT", link.itemId(),
                itemId, shopId, "A shop linked this barcode to another product", "OPEN", now));
    }

    /**
     * A shop moved a barcode to another of its products (its owner corrected a wrong link): the catalogue follows when
     * the link was only that shop's say (no other shop agreed, not from a product master or the team); else the team
     * reviews it. The product gets a catalogue entry from its name if it has none. The caller holds both products'
     * locks.
     */
    void correct(UUID shopId, Product to, String code, BigDecimal pack, Instant now) {
        UUID target = products.catalogueItemOf(to.id());
        if (target == null) {
            Item item = new Item(UUID.randomUUID(), to.name(), null, null, to.unit(), null, null, 0, now);
            catalogue.insertItem(item, shopId, now);
            products.setCatalogueItem(to.id(), item.id());
            target = item.id();
        }
        Optional<CatalogueStore.Link> link = catalogue.lockLink(code);
        if (link.isEmpty()) {
            if (catalogue.addCode(target, code, Quantities.stored(pack), "SHOP", false, now)) {
                catalogue.vote(code, shopId, target, now);
            }
            return;
        }
        CatalogueStore.Link l = link.get();
        catalogue.vote(code, shopId, target, now);
        if (target.equals(l.itemId())) {
            if (!l.confirmed() && catalogue.votes(code, target) >= 2) {
                catalogue.confirm(code);
            }
            return;
        }
        if ("SHOP".equals(l.source()) && !l.confirmed() && catalogue.votes(code, l.itemId()) == 0) {
            catalogue.relink(code, target, null, "SHOP", false);   // it was only this shop's say: it follows
            return;
        }
        catalogue.addReview(new CatalogueStore.Review(UUID.randomUUID(), code, "CORRECTION", l.itemId(), target,
                shopId, "A shop corrected this barcode to another product", "OPEN", now));
    }

    /** Whether the item has a shared photo. */
    boolean hasPhoto(UUID itemId) {
        return catalogue.photos(List.of(itemId)).containsKey(itemId);
    }

    /** The shared photo for an item, when it has none (a shop's photo whose label matched the item). */
    boolean sharePhoto(UUID itemId, UUID fileId) {
        return catalogue.setPhotoIfNone(itemId, fileId);
    }


    // ---------- the admin website (super admins) ----------

    /** The catalogue in name order, 50 a page; q: names containing it, or a barcode starting with it. */
    @Transactional(readOnly = true)
    public CataloguePage list(UUID adminId, String q, String cursor) {
        requireSuperAdmin(adminId);
        Optional<String[]> after = Cursors.decode(cursor);
        List<CatalogueStore.Row> rows = catalogue.page(KiranaChecks.prefix(q), after.map(a -> a[0]).orElse(null),
                after.map(a -> ProductService.uuid(a[1])).orElse(null), PAGE);
        boolean more = rows.size() > PAGE;
        List<CatalogueStore.Row> page = more ? rows.subList(0, PAGE) : rows;
        CatalogueStore.Row last = page.isEmpty() ? null : page.get(page.size() - 1);
        Map<UUID, List<String>> codes = catalogue.codes(page.stream().map(r -> r.item().id()).toList());
        Map<UUID, UUID> photos = catalogue.photos(page.stream().map(r -> r.item().id()).toList());
        Map<UUID, String> links = files.links(photos.values());
        return new CataloguePage(page.stream().map(r -> new CatalogueView(r.item().id(), r.item().name(),
                        r.item().brand(), r.item().size(), r.item().unit(), r.item().mrpPaise(), r.item().category(),
                        null, codes.getOrDefault(r.item().id(), List.of()), r.item().version(), r.item().updatedAt(),
                        null, photos.get(r.item().id()), links.get(photos.get(r.item().id())))).toList(),
                more ? Cursors.encode(last.sortKey(), last.item().id().toString()) : null);
    }

    /**
     * A super admin corrects an entry (name, brand, size, MRP, category); every shop's scan shows it from then on (the
     * shops' own products keep their names). 409 CATALOGUE_CHANGED for a stale version; 404 CATALOGUE_NOT_FOUND.
     */
    @Transactional
    public CatalogueView edit(UUID adminId, UUID itemId, CatalogueEdit in) {
        requireSuperAdmin(adminId);
        Item old = catalogue.lock(itemId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "CATALOGUE_NOT_FOUND", "No such catalogue item."));
        if (old.version() != in.version()) {
            throw new ApiException(HttpStatus.CONFLICT, "CATALOGUE_CHANGED",
                    "Someone changed this item just now. Reload and try again.");
        }
        Instant now = KiranaChecks.now(clock);
        Item updated = new Item(old.id(), in.name() == null ? old.name() : TextCleaner.clean(NAME, in.name()),
                in.brand() == null ? old.brand() : KiranaChecks.optional(BRAND, in.brand()),
                in.size() == null ? old.size() : KiranaChecks.optional(SIZE, in.size()), old.unit(),
                in.mrpPaise() == null ? old.mrpPaise() : in.mrpPaise(),
                in.category() == null ? old.category() : KiranaChecks.optional(CATEGORY, in.category()),
                old.version() + 1, now);
        if (Objects.equals(updated.name(), old.name()) && Objects.equals(updated.brand(), old.brand())
                && Objects.equals(updated.size(), old.size()) && Objects.equals(updated.mrpPaise(), old.mrpPaise())
                && Objects.equals(updated.category(), old.category())) {
            return view(old, null);
        }
        catalogue.update(updated, now);
        return view(updated, null);
    }

    private CatalogueView view(Item i, BigDecimal pack) {
        return view(i, pack, catalogue.codes(i.id()), null);
    }

    private CatalogueView view(Item i, BigDecimal pack, List<String> codes, Boolean trusted) {
        UUID photo = catalogue.photos(List.of(i.id())).get(i.id());
        return new CatalogueView(i.id(), i.name(), i.brand(), i.size(), i.unit(), i.mrpPaise(), i.category(),
                pack == null ? null : Quantities.json(pack), codes, i.version(), i.updatedAt(), trusted, photo,
                photo == null ? null : files.links(List.of(photo)).get(photo));
    }

    void requireSuperAdmin(UUID adminId) {
        if (users.teamRole(adminId) != TeamRole.SUPER_ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SUPER_ADMIN_ONLY", "Only a super admin can do this.",
                    Map.of());
        }
    }
}
