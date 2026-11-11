package in.apnidukan.common.context;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Address search for customers' saved places: the same Google search, cache and per-person limits as a salon's
 * registration, with customers' own share of the day's lookups. Implemented by the shops module (AddressLocator), so
 * the places module doesn't depend on it.
 */
public interface AddressSearch {

    /** Where a typed address is. precision: EXACT, STREET or AREA (how close the pin starts). */
    record Found(double latitude, double longitude, String precision, String formattedAddress) {
    }

    /** The address at a point, in the form's fields ("" where Google gave nothing). */
    record AddressAt(String addressLine, String area, String city, String pincode, String formattedAddress) {
    }

    /**
     * 404 ADDRESS_NOT_FOUND, 429 TRY_LATER, 503 GEOCODING_OFF (no Google key), 503 GEOCODING_UNAVAILABLE (Google
     * failed, or the day's lookups are used up).
     */
    Found find(UUID customerId, String addressLine, String area, String city, String pincode);

    /** The same errors as {@link #find}. */
    AddressAt describe(UUID customerId, BigDecimal latitude, BigDecimal longitude);
}
