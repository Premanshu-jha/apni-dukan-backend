package in.apnidukan.shops.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An owner's request to change his verified address, as the owner (GET /shops/me/address-request) and the field team
 * (AdminShopDetail.addressRequest) see it.
 * @param status    PENDING (waiting for the field team), APPROVED (it is the shop's address now), DECLINED (see
 *                  reason) or CANCELLED (the owner took it back, or the field team set the address themselves)
 * @param reason    why the field team declined it, in their words (the note under the reasons); null otherwise, and
 *                  when they picked only reasons
 * @param decidedAt when it stopped waiting; null while PENDING
 * @param statusReasonCodes the fixed reasons it was declined for (ReviewReason names, in the order picked); empty
 *                  when none
 * @param requesterLanguage the language of the owner who asked ("hi", ...: users.language); null when he chose none
 *                  (English) or his account is gone
 */
public record AddressRequestView(UUID id, String addressLine, String area, String city, String pincode,
                                 BigDecimal latitude, BigDecimal longitude, String status, String reason,
                                 Instant createdAt, Instant decidedAt, java.util.List<String> statusReasonCodes,
                                 String requesterLanguage) {

    public AddressRequestView {
        statusReasonCodes = statusReasonCodes == null ? java.util.List.of() : java.util.List.copyOf(statusReasonCodes);
    }
}
