package in.apnidukan.shops.api;

import in.apnidukan.common.context.SalonFacts;
import in.apnidukan.shops.domain.RegistrationDocType;
import in.apnidukan.shops.domain.ShopEventType;
import in.apnidukan.shops.domain.VisitIssue;

import java.time.Instant;
import java.util.List;

/**
 * One shop for the field team: the shop, a salon's size, what changed since it was last approved, and its timeline.
 * @param salon                  null for a kirana
 * @param changes                compared with the last approved version; empty if never approved or unchanged
 * @param pinMovedMetres         how far the pin moved, when it did
 * @param previousOwnerPhotoUrl  the approved owner photo, only when it has been replaced since
 * @param previousShopPhotoUrl   the approved shop photo, only when it has been replaced since
 * @param videoUrl               a link to the salon video (valid at least 15 more minutes); null if none
 * @param registrationDoc        a salon's registration document; null if none
 * @param addressRequest         the owner's address change request waiting for the field team (approve applies it,
 *                               POST .../address-request/decline refuses it); null if none waits
 * @param timeline               newest first, the first 50 entries
 * @param timelineNext           where the next 50 start (GET /admin/shops/{id}/timeline with these); null when the
 *                               timeline has no more
 */
public record AdminShopDetail(AdminShopResponse shop, Salon salon, List<Change> changes, Long pinMovedMetres,
                              String previousOwnerPhotoUrl, String previousShopPhotoUrl, String videoUrl,
                              RegistrationDoc registrationDoc, AddressRequestView addressRequest,
                              List<TimelineEntry> timeline,
                              TimelineNext timelineNext) {

    public AdminShopDetail {
        changes = List.copyOf(changes);
        timeline = List.copyOf(timeline);
    }

    /** The next timeline page's query values: afterAt and afterSeq, exactly as given. */
    public record TimelineNext(Instant afterAt, long afterSeq) {
    }

    /** A page of a shop's timeline, newest first; next is null on the last page. */
    public record TimelinePage(List<TimelineEntry> items, TimelineNext next) {

        public TimelinePage {
            items = List.copyOf(items);
        }
    }

    /**
     * A salon's size and setup.
     * @param serviceList   its active services, in its order
     * @param hours         its open days, Monday first; empty until set
     * @param ratings       how many customers rated it
     * @param ratingAverage average stars to one decimal; null with no ratings
     */
    public record Salon(int barbers, int services, List<SalonFacts.Service> serviceList, List<SalonFacts.Day> hours,
                        int ratings, Double ratingAverage) {

        public Salon {
            serviceList = List.copyOf(serviceList);
            hours = List.copyOf(hours);
        }
    }

    /**
     * A salon's registration document, for the field team only.
     * @param type  SHOPS_ESTABLISHMENT, TRADE_LICENCE or UDYAM
     * @param label how to name it, e.g. "Trade licence"
     * @param url   a link to the photo or PDF (valid at least 15 more minutes); null if the file is gone
     * @param checkedAt when the field team checked this document (customers then see "Registered"); null: not yet
     */
    public record RegistrationDoc(RegistrationDocType type, String label, String url, java.time.Instant checkedAt) {
    }

    /**
     * @param field  name, ownerName, addressLine, area, city, pincode, location, gstin, udyamNumber, ownerPhoto,
     *               shopPhoto, shopVideo, registrationDoc
     * @param before the approved value (null for photos and the video: see the links; the document's type for the
     *               registration document)
     * @param after  the value now
     */
    public record Change(String field, String label, String before, String after) {
    }

    /**
     * @param byName who did it; null for the system or a deleted account
     * @param reason what the owner was told
     * @param note   for admins only
     */
    public record TimelineEntry(Instant at, ShopEventType event, String byName, boolean byAdmin, String reason,
                                String note, List<VisitIssue> checklist) {

        public TimelineEntry {
            checklist = List.copyOf(checklist);
        }
    }
}
