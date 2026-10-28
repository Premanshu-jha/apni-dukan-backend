package in.apnidukan.shops.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request bodies for shop registration and My shop. Unknown fields are refused (400 UNKNOWN_FIELD).
 * Text is trimmed and GSTIN/Udyam are checked and upper-cased in ShopService.
 */
public final class ShopRequests {

    private ShopRequests() {
    }

    /**
     * Step 1 of registration.
     * @param latitude  the shop's GPS pin ("Use my location" in the app), inside India
     * @param gstin     optional, 15 characters, e.g. 27AAPFU0939F1ZV
     * @param udyamNumber optional, e.g. UDYAM-TS-02-0012345
     */
    public record CreateShop(
            @NotBlank @Size(min = 2, max = 100) String name,
            @NotBlank @Size(min = 2, max = 100) String ownerName,
            @NotBlank @Size(min = 5, max = 200) String addressLine,
            @NotBlank @Size(min = 2, max = 100) String area,
            @NotBlank @Size(min = 2, max = 60) String city,
            @NotNull @Pattern(regexp = "[1-9][0-9]{5}", message = "must be a 6-digit pincode") String pincode,
            @NotNull @DecimalMin("6.0") @DecimalMax("37.6") BigDecimal latitude,
            @NotNull @DecimalMin("68.0") @DecimalMax("97.5") BigDecimal longitude,
            @Size(max = 20) String gstin,
            @Size(max = 25) String udyamNumber) {
    }

    /**
     * My shop edits. Leave a field out (or null) to keep it; send "" for gstin/udyamNumber to remove it.
     * @param version the version you last read (GET /shops/me); if someone saved in between you get 409 SHOP_CHANGED
     */
    public record UpdateShop(
            @NotNull Long version,
            @Size(min = 2, max = 100) String name,
            @Size(min = 2, max = 100) String ownerName,
            @Size(min = 5, max = 200) String addressLine,
            @Size(min = 2, max = 100) String area,
            @Size(min = 2, max = 60) String city,
            @Pattern(regexp = "[1-9][0-9]{5}", message = "must be a 6-digit pincode") String pincode,
            @DecimalMin("6.0") @DecimalMax("37.6") BigDecimal latitude,
            @DecimalMin("68.0") @DecimalMax("97.5") BigDecimal longitude,
            @Size(max = 20) String gstin,
            @Size(max = 25) String udyamNumber) {
    }
}
