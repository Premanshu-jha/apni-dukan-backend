package in.apnidukan.kirana.service;

import in.apnidukan.common.context.ShopDirectory;
import in.apnidukan.kirana.domain.NameMatcher;
import in.apnidukan.kirana.domain.PhotoRules;
import in.apnidukan.kirana.domain.Product;
import in.apnidukan.kirana.domain.Unit;
import in.apnidukan.kirana.repo.CatalogueStore;
import in.apnidukan.kirana.repo.ProductStore;
import in.apnidukan.kirana.repo.ReadStore.Read;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A shop's photo of one of its products, checked (the reading job): accepted as its picture when it shows the
 * product (PhotoRules), else refused with the reason, or asked for the side with the size. An accepted photo of a pack
 * matching its catalogue item goes to the shared catalogue when the item has none, while the shop is approved (a shop
 * still registering keeps its photos to itself until then: KiranaFacts.shareCheckedPhotos at approval). Loose goods are
 * never shared. A check answers for the product as it was when it started: renamed meanwhile, its answer is dropped
 * (the rename checks the photo again).
 */
@Service
public class PhotoChecks {

    /** Why a photo couldn't be checked: the AI couldn't make it out. */
    static final String UNREADABLE = "We couldn't see the product clearly in this photo. Take it again in good light, "
            + "holding the phone steady.";

    private static final Logger log = LoggerFactory.getLogger(PhotoChecks.class);

    private final ProductStore products;
    private final CatalogueStore catalogue;
    private final KiranaFiles kiranaFiles;
    private final ShopDirectory shops;

    public PhotoChecks(ProductStore products, CatalogueStore catalogue, KiranaFiles kiranaFiles, ShopDirectory shops) {
        this.products = products;
        this.catalogue = catalogue;
        this.kiranaFiles = kiranaFiles;
        this.shops = shops;
    }

    /** What the photo should show: the product's name, and whether it is sold loose (by the kg). */
    public record Expected(String name, boolean loose) {
    }

    /** The product a photo check is for, while it still waits for this photo (empty: removed or replaced meanwhile). */
    Optional<Expected> expected(Read r) {
        if (r.productId() == null || r.fileIds().isEmpty()) {
            return Optional.empty();
        }
        return products.find(r.shopId(), r.productId()).filter(Product::active)
                .filter(p -> r.fileIds().get(0).equals(products.photoState(p.id()).newFile()))
                .map(p -> new Expected(p.name(), p.unit() == Unit.KG));
    }

    /** What the AI saw, for the product as {@code asked}: the photo's verdict saved (the caller holds a transaction). */
    void checked(Read r, PhotoRules.Seen seen, Expected asked) {
        Optional<Product> locked = products.lock(r.shopId(), r.productId());
        UUID file = r.fileIds().get(0);
        if (locked.isEmpty()) {
            kiranaFiles.releaseIfUnused(r.fileIds(), r.id());
            return;
        }
        Product p = locked.get();
        ProductStore.PhotoState state = products.photoState(p.id());
        boolean loose = p.unit() == Unit.KG;
        if (!p.active() || !file.equals(state.newFile()) || !p.name().equals(asked.name()) || loose != asked.loose()) {
            // Removed, another photo sent, or renamed meanwhile: this answer is for something else
            if (!file.equals(state.newFile())) {
                kiranaFiles.releaseIfUnused(r.fileIds(), r.id());
            }
            return;
        }
        PhotoRules.Verdict v = PhotoRules.decide(p.name(), loose, seen, r.fileIds().size() > 1);
        if (!v.ok()) {
            products.photoChecked(p.id(), v.status(), v.code(), v.reason(), v.seen());
            return;
        }
        CatalogueStore.Item item = loose ? null : Optional.ofNullable(products.catalogueItemOf(p.id()))
                .flatMap(catalogue::find).orElse(null);
        boolean shareable = item != null && matches(seen, item);
        products.acceptPhoto(p.id(), shareable);
        List<UUID> done = new ArrayList<>();
        if (state.own() != null && !state.own().equals(file)) {
            done.add(state.own());
        }
        if (state.sizeFile() != null && !state.sizeFile().equals(file)) {
            done.add(state.sizeFile());   // only for the check
        }
        kiranaFiles.releaseIfUnused(done, r.id());
        // Shared now only while the shop is approved (its row read under a share lock: an approval being saved is
        // waited for, so its own sharing sees this photo or this sees the approval)
        if (shareable && shops.approvedNow(r.shopId()) && catalogue.setPhotoIfNone(item.id(), file)) {
            log.info("Shared a shop's checked photo as catalogue item {}'s", item.id());
        }
    }

    /** The photo couldn't be checked (unreadable): refused with the reason, to send again. */
    void failed(Read r, String why) {
        if (r.productId() == null || r.fileIds().isEmpty()) {
            return;
        }
        Optional<Product> locked = products.lock(r.shopId(), r.productId());
        ProductStore.PhotoState state = locked.map(p -> products.photoState(p.id())).orElse(null);
        if (state != null && r.fileIds().get(0).equals(state.newFile()) && "CHECKING".equals(state.check())) {
            products.photoChecked(locked.get().id(), "REFUSED", "UNREADABLE", why, null);
        } else {
            kiranaFiles.releaseIfUnused(r.fileIds(), r.id());   // replaced or removed meanwhile
        }
    }

    /** The photo shows the catalogue item (brand, name and size), not only the shop's product. */
    private static boolean matches(PhotoRules.Seen seen, CatalogueStore.Item item) {
        String read = Objects.toString(seen.brand(), "") + " " + Objects.toString(seen.name(), "");
        NameMatcher.Parsed seenName = NameMatcher.parse(read.strip(), seen.brand(), seen.size());
        NameMatcher.Parsed itemName = NameMatcher.parse(item.name(), item.brand(), item.size());
        NameMatcher.Score s = NameMatcher.score(seenName, itemName);
        return !s.never() && s.sizeKnownBoth() && !PhotoRules.variantsContradict(seenName, itemName)
                && s.value() >= NameMatcher.LIKELY;
    }
}
