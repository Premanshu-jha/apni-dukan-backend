package in.apnidukan.kirana.service;

import in.apnidukan.kirana.domain.Product;
import in.apnidukan.kirana.domain.Quantities;
import in.apnidukan.kirana.repo.ProductStore;
import in.apnidukan.kirana.repo.PurchaseStore;
import in.apnidukan.kirana.repo.PurchaseStore.Purchase;
import in.apnidukan.kirana.repo.ReadStore;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What happens to a distributor's bill's draft thrown away (from the bill or from its photo): what was scanned while
 * shelving it was put on the shelves, so it goes into stock now; the draft and its photo's read are DISCARDED and the
 * photo is deleted unless something else uses it.
 */
@Component
class BillShelving {

    private final PurchaseStore purchases;
    private final ProductStore products;
    private final ReadStore reads;
    private final KiranaFiles files;

    BillShelving(PurchaseStore purchases, ProductStore products, ReadStore reads, KiranaFiles files) {
        this.purchases = purchases;
        this.products = products;
        this.reads = reads;
        this.files = files;
    }

    /** Throws the draft away (see the class). The caller holds the bill's lock; products are locked here, by id. */
    void throwAway(UUID shopId, Purchase p, UUID userId, Instant now) {
        Map<UUID, BigDecimal> scanned = purchases.scanned(p.id());
        List<ProductStore.StockMove> moves = new ArrayList<>();
        String note = "Shelved from bill " + (p.billNo() == null ? "" : p.billNo() + " ") + "(thrown away)";
        for (Product x : products.lockAll(shopId, scanned.keySet())) {
            BigDecimal q = scanned.get(x.id());
            BigDecimal after = Quantities.stored(x.stock().add(q));
            if (after.abs().compareTo(new BigDecimal("999999999")) > 0) {
                continue;
            }
            products.setStock(x.id(), after, now);
            moves.add(new ProductStore.StockMove(x.id(), q, after, "RESTOCK", p.id(), null,
                    note.length() > 200 ? note.substring(0, 200) : note, null));
        }
        products.addStockMoves(shopId, moves, userId, now);
        purchases.setStatus(p.id(), "DISCARDED", userId, now);
        reads.setStatus(p.readId(), "DISCARDED", null, now);
        reads.find(p.readId()).ifPresent(r -> files.releaseIfUnused(r.fileIds(), r.id()));
    }
}
