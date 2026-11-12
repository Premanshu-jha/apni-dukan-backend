package in.apnidukan.shops.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An owner's request to change his verified address, as the owner (GET /shops/me/address-request) and the field team
 * (AdminShopDetail.addressRequest) see it.
 * @param status    PENDING (waiting for the field team), APPROVED (it is the shop's address now), DECLINED (see
 *                  reason) or CANCELLED (the owner took it back, or the field team set the address themselves)
 * @param reason    why the field team declined it; null otherwise
 * @param decidedAt when it stopped waiting; null while PENDING
 */
public record AddressRequestView(UUID id, String addressLine, String area, String city, String pincode,
                                 BigDecimal latitude, BigDecimal longitude, String status, String reason,
                                 Instant createdAt, Instant decidedAt) {
}
