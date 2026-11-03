package in.apnidukan.shops.api;

import in.apnidukan.shops.domain.Shop;
import in.apnidukan.shops.domain.ShopDetails;
import in.apnidukan.shops.domain.ShopStatus;
import in.apnidukan.shops.domain.ShopType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A shop as the field team sees it in the approval list.
 * @param ownerPhotoUrl a link to the owner's photo, valid for a few minutes; null if not set
 * @param shopPhotoUrl  a link to the shop's photo, valid for a few minutes; null if not set
 * @param version       send it back with approve: it proves the approval is for what was checked
 */
public record AdminShopResponse(UUID id, long shopCode, String name, String ownerName, String addressLine,
                                String area, String city, String pincode, BigDecimal latitude, BigDecimal longitude,
                                ShopType type, ShopStatus status, String ownerPhotoUrl, String shopPhotoUrl,
                                long version, Instant createdAt) {

    public static AdminShopResponse from(Shop shop, String ownerPhotoUrl, String shopPhotoUrl) {
        ShopDetails d = shop.details();
        return new AdminShopResponse(shop.getId(), shop.getShopCode(), d.name(), d.ownerName(), d.addressLine(),
                d.area(), d.city(), d.pincode(), d.latitude(), d.longitude(), shop.getType(), shop.getStatus(),
                ownerPhotoUrl, shopPhotoUrl, shop.getVersion(), shop.getCreatedAt());
    }
}
