package in.apnidukan.shops.api;

import in.apnidukan.shops.domain.LicenceCheckReason;
import in.apnidukan.shops.domain.LicenceCheckStatus;
import in.apnidukan.shops.domain.RegistrationDocType;
import in.apnidukan.shops.domain.Shop;
import in.apnidukan.shops.domain.ShopDetails;
import in.apnidukan.shops.domain.ShopRole;
import in.apnidukan.shops.domain.ShopStatus;
import in.apnidukan.shops.domain.ShopType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A shop as the app sees it. myRole is the caller's role in it; type says kirana or salon. A salon's gstin and
 * udyamNumber are always null (a salon has none now: its trade licence counts).
 * @param ownerPhotoFileId the owner's photo, or null until set (needed before Submit for visit)
 * @param shopPhotoFileId  the shop's photo, or null until set (needed before Submit for visit)
 * @param shopVideoFileId  the salon video, or null until set (needed before Submit for visit); the owner's only
 * @param registrationDocType   a salon's registration document type (TRADE_LICENCE; a SHOPS_ESTABLISHMENT or UDYAM
 *                              one added before stays until replaced, FAILED NOT_TRADE_LICENCE), or null if none
 *                              (optional; the owner can add, replace or remove it any time); the owner's only
 * @param registrationDocFileId the document's file, or null if none; the owner's only
 * @param registrationDocCheckedAt when the document passed the automatic check; null if not (yet) or none; the
 *                              owner's only
 * @param licenceCheck the automatic check of the document: PENDING (being checked), PASSED (customers see
 *                     "Licensed" until licenceValidUntil), FAILED (licenceCheckReason says why); null without a
 *                     document; the owner's only
 * @param licenceCheckReason why it FAILED; null otherwise
 * @param licenceValidUntil the licence's last valid day once PASSED (after it: expired, no badge); null otherwise
 * @param licenceCheckSince when the check started (a PENDING one has waited since then); the owner's only
 * @param licenceRead what the check read from it (LicenceExtract's fields: the name, holder, address, pincode the owner
 *                    compares with his salon's when it wasn't accepted); null if not read; the owner's only
 * @param statusReason why the field team sent it back to DRAFT or suspended it, or the fix they asked for on a live
 *                     shop's re-check; null otherwise (and for barbers)
 * @param version send this back with PATCH /shops/me
 * @param recheckSince when the field team's re-check of a live shop's changes started waiting (a photo, the video,
 *                     the GSTIN or Udyam number changed); null when nothing waits. The shop stays live meanwhile. The
 *                     owner's only
 * @param addressLocked the address and pin were verified by the field team: the owner can't change them any more
 *                     (PATCH /shops/me answers 409 ADDRESS_LOCKED); the field team does
 */
public record ShopResponse(UUID id, long shopCode, String name, String ownerName, String addressLine, String area,
                           String city, String pincode, BigDecimal latitude, BigDecimal longitude, String gstin,
                           String udyamNumber, ShopStatus status, String statusReason, ShopType type, ShopRole myRole,
                           UUID ownerPhotoFileId, UUID shopPhotoFileId, UUID shopVideoFileId,
                           RegistrationDocType registrationDocType, UUID registrationDocFileId,
                           Instant registrationDocCheckedAt, LicenceCheckStatus licenceCheck,
                           LicenceCheckReason licenceCheckReason, LocalDate licenceValidUntil,
                           Instant licenceCheckSince, tools.jackson.databind.JsonNode licenceRead, long version,
                           Instant createdAt, Instant updatedAt, Instant recheckSince, boolean addressLocked) {

    private static final tools.jackson.databind.json.JsonMapper READ =
            tools.jackson.databind.json.JsonMapper.builder().build();

    /** The same without GSTIN, Udyam number, the owner's photo, the salon video and the registration document. */
    public ShopResponse forBarber() {
        return new ShopResponse(id, shopCode, name, ownerName, addressLine, area, city, pincode, latitude, longitude,
                null, null, status, null, type, myRole, null, shopPhotoFileId, null, null, null, null, null, null, null,
                null, null, version,
                createdAt, updatedAt, null, addressLocked);
    }

    public static ShopResponse from(Shop shop, ShopRole myRole) {
        ShopDetails d = shop.details();
        boolean salon = shop.getType() == ShopType.SALON;
        boolean owner = myRole == ShopRole.OWNER;
        return new ShopResponse(shop.getId(), shop.getShopCode(), d.name(), d.ownerName(), d.addressLine(), d.area(),
                d.city(), d.pincode(), d.latitude(), d.longitude(), salon ? null : d.gstin(),
                salon ? null : d.udyamNumber(), shop.getStatus(),
                shop.getStatusReason(), shop.getType(), myRole, shop.getOwnerPhotoFileId(), shop.getShopPhotoFileId(),
                myRole == ShopRole.OWNER ? shop.getShopVideoFileId() : null,
                myRole == ShopRole.OWNER ? shop.getRegistrationDocType() : null,
                myRole == ShopRole.OWNER ? shop.getRegistrationDocFileId() : null,
                myRole == ShopRole.OWNER ? shop.getRegistrationDocCheckedAt() : null,
                owner ? shop.getLicenceCheckStatus() : null, owner ? shop.getLicenceCheckReason() : null,
                owner ? shop.getLicenceValidUntil() : null, owner ? shop.getLicenceCheckSince() : null,
                owner && shop.getLicenceExtract() != null ? READ.readTree(shop.getLicenceExtract()) : null,
                shop.getVersion(),
                shop.getCreatedAt(), shop.getUpdatedAt(), myRole == ShopRole.OWNER ? shop.getRecheckSince() : null,
                shop.addressLocked());
    }
}
