package in.apnidukan.salon.domain;

/**
 * The editable parts of a salon service, already checked and cleaned.
 * @param durationMin minutes, including any clean-up time the salon wants to allow
 * @param pricePaise  whole paise: ₹150 is 15000
 * @param priceIsFrom shown as "from ₹150" when the final price can vary (e.g. hair colour by length)
 * @param sortOrder   position in the salon's list, lowest first
 */
public record ServiceDetails(String name, int durationMin, long pricePaise, boolean priceIsFrom, boolean active,
                             int sortOrder) {
}
