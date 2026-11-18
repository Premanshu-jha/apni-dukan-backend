package in.apnidukan.common.context;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Whether a kirana's products are set up, which books its field visit. Implemented by the shops module (the visit and
 * its assignment are theirs); used by the kirana module's product setup without either depending on the other.
 */
public interface ProductReadiness {

    /** When the owner said all the shop's products are added; empty until then. */
    Optional<Instant> productsReadyAt(UUID shopId);

    /**
     * The owner says all the products are added ({@code products}: how many, for the field team's timeline): the shop
     * is marked ready, and if it was submitted and waits for its first visit, the visit is booked now (assigned to a
     * field agent by the usual rule). Saying it again changes nothing. The caller holds a transaction.
     */
    Instant markProductsReady(UUID shopId, UUID by, long products);
}
