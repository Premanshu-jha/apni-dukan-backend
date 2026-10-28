package in.apnidukan.shops.api;

import in.apnidukan.shops.domain.Shop;
import in.apnidukan.shops.domain.ShopDetails;
import in.apnidukan.shops.domain.ShopRole;
import in.apnidukan.shops.domain.ShopStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A shop as the app sees it. myRole is the caller's role in it.
 * @param version send this back with PATCH /shops/me
 */
public record ShopResponse(UUID id, long shopCode, String name, String ownerName, String addressLine, String area,
                           String city, String pincode, BigDecimal latitude, BigDecimal longitude, String gstin,
                           String udyamNumber, ShopStatus status, ShopRole myRole, long version, Instant createdAt,
                           Instant updatedAt) {

    public static ShopResponse from(Shop shop, ShopRole myRole) {
        ShopDetails d = shop.details();
        return new ShopResponse(shop.getId(), shop.getShopCode(), d.name(), d.ownerName(), d.addressLine(), d.area(),
                d.city(), d.pincode(), d.latitude(), d.longitude(), d.gstin(), d.udyamNumber(), shop.getStatus(),
                myRole, shop.getVersion(), shop.getCreatedAt(), shop.getUpdatedAt());
    }
}
