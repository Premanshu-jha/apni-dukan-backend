package in.apnidukan.kirana.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A product in a kirana's catalogue (kirana_products); its barcodes are kept apart (kirana_barcodes).
 * @param pricePaise  per piece, or per kg for KG
 * @param stock       pieces or kg now in the shop; may be below 0 when the count is behind the sales
 * @param lowStockAt  "running out" at or below this; null: no alert
 * @param active      false once removed from the catalogue (old bills keep it)
 */
public record Product(UUID id, UUID shopId, String name, Unit unit, long pricePaise,
                      BigDecimal stock, BigDecimal lowStockAt, boolean active, long version, Instant updatedAt) {

    /** At or below its alert (or below zero): shown under "Running out". */
    public boolean runningOut() {
        return stock.signum() < 0 || (lowStockAt != null && stock.compareTo(lowStockAt) <= 0);
    }
}
