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

    /**
     * The kirana's products still missing a count or a picture (each needs both before its first approval: those
     * added after the owner finished too).
     * @param active    its products (none at all: nothing for the visit to check)
     * @param uncounted products never counted, or whose count no longer holds (sold below zero)
     * @param noPicture products with no picture (no checked photo of the shop's, none in the shared catalogue)
     * @param checking  of noPicture, those whose photo is being checked right now, or whose picture GS1 is still
     *                  looking for (nothing for the owner to do: approving works a little later)
     * @param products  up to 10 of them, by name: what the field agent asks the owner to finish
     */
    ProductGaps productGaps(UUID shopId);

    record ProductGaps(long active, long uncounted, long noPicture, long checking, java.util.List<String> products) {

        public ProductGaps {
            products = java.util.List.copyOf(products);
        }

        public boolean none() {
            return active > 0 && uncounted == 0 && noPicture == 0;
        }
    }
}
