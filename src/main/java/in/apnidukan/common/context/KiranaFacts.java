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

    /**
     * What a kirana still needs before Submit for visit, by field, in the owner's words (empty when ready): its UPI ID;
     * the answer to "do you sell any food?"; and when it does, its FSSAI licence (added, and not refused).
     */
    java.util.Map<String, String> registrationGaps(UUID shopId);

    /** Whether it sells any food (null until answered), for the field team's shop page. */
    Boolean sellsFood(UUID shopId);

    /** The kirana's FSSAI licence as checked so far, for the field team's shop page (status NONE when not added). */
    Fssai fssai(UUID shopId);

    /**
     * @param status NONE, CHECKING, VERIFIED or REJECTED
     * @param reason why it was refused (or what is being done), null otherwise
     */
    record Fssai(String status, String number, java.time.LocalDate validUntil, String reason) {
    }

    /**
     * Whether a kirana still points at this file (its FSSAI certificate, now or kept from before): the shops module
     * then keeps the file when its own document lets go of it (uploads of the same bytes share one file).
     */
    boolean usesFile(UUID fileId);

    /**
     * Whether the kirana already works at its counter (any bill, a product or a khata customer in its book): it then
     * can't become a salon, whose screens would hide all of that.
     */
    boolean hasCounterData(UUID shopId);

    /**
     * The kirana was just approved: its photos checked while it registered, of packs matching their shared catalogue
     * items, become those items' pictures where they have none (a shop still registering keeps its photos to itself).
     * The caller holds a transaction.
     */
    void shareCheckedPhotos(UUID shopId);
}
