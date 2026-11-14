package in.apnidukan.shops.api;

import in.apnidukan.shops.domain.ReviewGroup;
import in.apnidukan.shops.domain.Shop;
import in.apnidukan.shops.domain.ShopDetails;
import in.apnidukan.shops.domain.ShopStatus;
import in.apnidukan.shops.domain.ShopType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A shop as the field team sees it in the approval list.
 * @param ownerPhotoUrl a link to the owner's photo, valid for at least 15 more minutes; null if not set
 * @param shopPhotoUrl  a link to the shop's photo, valid for at least 15 more minutes; null if not set
 * @param statusReason  why it was sent back to DRAFT or suspended, or the fix asked for on a live re-check; null
 *                      otherwise
 * @param version       send it back with approve, reject, suspend or reinstate: it proves the decision is for what
 *                      was checked
 * @param group         where it stands for the field team (null for a draft never submitted)
 * @param ownerPhone    to call the owner (null once the owner deleted their account)
 * @param statusSince   when it entered its current status (how long it has been waiting)
 * @param assignedTo    the field agent who visits it; null when unassigned
 * @param addressRequested the owner asked for a new address and it waits for the field team (approve applies it)
 * @param waitingForVisit waiting for the field team: its first visit, a reinstated shop's visit, or a live shop's
 *                      re-check (status APPROVED: it stays live meanwhile). Approve is for these only; Send back for
 *                      PENDING_VISIT, Ask the owner to fix for a live re-check.
 */
public record AdminShopResponse(UUID id, long shopCode, String name, String ownerName, String addressLine,
                                String area, String city, String pincode, BigDecimal latitude, BigDecimal longitude,
                                ShopType type, ShopStatus status, String statusReason, String ownerPhotoUrl,
                                String shopPhotoUrl,
                                long version, Instant createdAt, ReviewGroup group, String ownerPhone,
                                String gstin, String udyamNumber, Instant statusSince, Assignee assignedTo,
                                boolean waitingForVisit, boolean addressRequested) {

    public static AdminShopResponse from(Shop shop, String ownerPhotoUrl, String shopPhotoUrl, boolean everApproved,
                                         String ownerPhone, Instant statusSince, Assignee assignedTo,
                                         boolean addressRequested) {
        ShopDetails d = shop.details();
        return new AdminShopResponse(shop.getId(), shop.getShopCode(), d.name(), d.ownerName(), d.addressLine(),
                d.area(), d.city(), d.pincode(), d.latitude(), d.longitude(), shop.getType(), shop.getStatus(),
                shop.getStatusReason(), ownerPhotoUrl, shopPhotoUrl, shop.getVersion(), shop.getCreatedAt(),
                ReviewGroup.of(shop.getStatus(), shop.getStatusReason(), everApproved, shop.getRecheckSince() != null),
                ownerPhone, shop.getType() == ShopType.SALON ? null : d.gstin(),
                shop.getType() == ShopType.SALON ? null : d.udyamNumber(), statusSince == null ? shop.getCreatedAt() : statusSince,
                assignedTo, shop.waitingForVisit(), addressRequested);
    }

    /** @param name null if they haven't set one (or deleted their account) */
    public record Assignee(UUID id, String name) {
    }
}
