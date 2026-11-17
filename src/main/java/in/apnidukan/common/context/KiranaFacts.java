package in.apnidukan.common.context;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What registration needs to know about a kirana's own setup. Implemented by the kirana module; used by the shops
 * module (submitting a kirana for the field visit) without either depending on the other.
 */
public interface KiranaFacts {

    /** Whether the kirana's owner has set the shop's UPI ID (its walk-in QR, and later Clozest's payouts). */
    boolean hasUpiId(UUID shopId);

    /** The kirana's UPI ID and when it was last set, for the field team's shop page; empty until set. */
    Optional<UpiId> upiId(UUID shopId);

    record UpiId(String upiId, Instant updatedAt) {
    }
}
