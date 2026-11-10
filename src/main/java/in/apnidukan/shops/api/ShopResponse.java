package in.apnidukan.shops.api;

import in.apnidukan.shops.domain.RegistrationDocType;
import in.apnidukan.shops.domain.Shop;
import in.apnidukan.shops.domain.ShopDetails;
import in.apnidukan.shops.domain.ShopRole;
import in.apnidukan.shops.domain.ShopStatus;
import in.apnidukan.shops.domain.ShopType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A shop as the app sees it. myRole is the caller's role in it; type says kirana or salon.
 * @param ownerPhotoFileId the owner's photo, or null until set (needed before Submit for visit)
 * @param shopPhotoFileId  the shop's photo, or null until set (needed before Submit for visit)
 * @param shopVideoFileId  the salon video, or null until set (needed before Submit for visit); the owner's only
 * @param registrationDocType   a salon's registration document type (SHOPS_ESTABLISHMENT, TRADE_LICENCE, UDYAM), or
 *                              null if none (optional; the owner can add, replace or remove it any time); the
 *                              owner's only
 * @param registrationDocFileId the document's file, or null if none; the owner's only
 * @param registrationDocCheckedAt when the field team checked the document now there (customers then see
 *                              "Registered"); null if not yet or none; the owner's only
 * @param statusReason why the field team sent it back to DRAFT or suspended it; null otherwise (and for barbers)
 * @param version send this back with PATCH /shops/me
 */
public record ShopResponse(UUID id, long shopCode, String name, String ownerName, String addressLine, String area,
                           String city, String pincode, BigDecimal latitude, BigDecimal longitude, String gstin,
                           String udyamNumber, ShopStatus status, String statusReason, ShopType type, ShopRole myRole,
                           UUID ownerPhotoFileId, UUID shopPhotoFileId, UUID shopVideoFileId,
                           RegistrationDocType registrationDocType, UUID registrationDocFileId,
                           Instant registrationDocCheckedAt, long version,
                           Instant createdAt, Instant updatedAt) {

    /** The same without GSTIN, Udyam number, the owner's photo, the salon video and the registration document. */
    public ShopResponse forBarber() {
        return new ShopResponse(id, shopCode, name, ownerName, addressLine, area, city, pincode, latitude, longitude,
                null, null, status, null, type, myRole, null, shopPhotoFileId, null, null, null, null, version,
                createdAt, updatedAt);
    }

    public static ShopResponse from(Shop shop, ShopRole myRole) {
        ShopDetails d = shop.details();
        return new ShopResponse(shop.getId(), shop.getShopCode(), d.name(), d.ownerName(), d.addressLine(), d.area(),
                d.city(), d.pincode(), d.latitude(), d.longitude(), d.gstin(), d.udyamNumber(), shop.getStatus(),
                shop.getStatusReason(), shop.getType(), myRole, shop.getOwnerPhotoFileId(), shop.getShopPhotoFileId(),
                myRole == ShopRole.OWNER ? shop.getShopVideoFileId() : null,
                myRole == ShopRole.OWNER ? shop.getRegistrationDocType() : null,
                myRole == ShopRole.OWNER ? shop.getRegistrationDocFileId() : null,
                myRole == ShopRole.OWNER ? shop.getRegistrationDocCheckedAt() : null, shop.getVersion(),
                shop.getCreatedAt(), shop.getUpdatedAt());
    }
}
