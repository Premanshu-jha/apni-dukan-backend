package in.apnidukan.shops.api;

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
 * @param version send this back with PATCH /shops/me
 */
public record ShopResponse(UUID id, long shopCode, String name, String ownerName, String addressLine, String area,
                           String city, String pincode, BigDecimal latitude, BigDecimal longitude, String gstin,
                           String udyamNumber, ShopStatus status, ShopType type, ShopRole myRole,
                           UUID ownerPhotoFileId, UUID shopPhotoFileId, long version,
                           Instant createdAt, Instant updatedAt) {

    /** The same without GSTIN, Udyam number and the owner's photo. */
    public ShopResponse forBarber() {
        return new ShopResponse(id, shopCode, name, ownerName, addressLine, area, city, pincode, latitude, longitude,
                null, null, status, type, myRole, null, shopPhotoFileId, version, createdAt, updatedAt);
    }

    public static ShopResponse from(Shop shop, ShopRole myRole) {
        ShopDetails d = shop.details();
        return new ShopResponse(shop.getId(), shop.getShopCode(), d.name(), d.ownerName(), d.addressLine(), d.area(),
                d.city(), d.pincode(), d.latitude(), d.longitude(), d.gstin(), d.udyamNumber(), shop.getStatus(),
                shop.getType(), myRole, shop.getOwnerPhotoFileId(), shop.getShopPhotoFileId(), shop.getVersion(), shop.getCreatedAt(), shop.getUpdatedAt());
    }
}
