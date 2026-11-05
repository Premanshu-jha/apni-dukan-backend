package in.apnidukan.shops.api;

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
 * @param timeline               newest first
 */
public record AdminShopDetail(AdminShopResponse shop, Salon salon, List<Change> changes, Long pinMovedMetres,
                              String previousOwnerPhotoUrl, String previousShopPhotoUrl, List<TimelineEntry> timeline) {

    public AdminShopDetail {
        changes = List.copyOf(changes);
        timeline = List.copyOf(timeline);
    }

    public record Salon(int barbers, int services) {
    }

    /**
     * @param field  name, ownerName, addressLine, area, city, pincode, location, gstin, udyamNumber, ownerPhoto,
     *               shopPhoto
     * @param before the approved value (null for photos: see the photo links)
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
