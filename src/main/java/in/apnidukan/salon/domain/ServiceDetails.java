package in.apnidukan.salon.domain;

/**
 * The editable parts of a salon service, already checked and cleaned.
 * @param durationMin minutes, including any clean-up time the salon wants to allow
 * @param pricePaise  whole paise: ₹150 is 15000
 * @param priceIsFrom shown as "from ₹150" when the final price can vary (e.g. hair colour by length)
 * @param sortOrder   position in the salon's list, lowest first
 * @param returnAfterDays days after a visit to remind the customer to come back for it (1-180), or 0 for never
 * @param icon        the picture shown next to it ({@link ServiceIcon}); null for a service saved before icons
 */
public record ServiceDetails(String name, int durationMin, long pricePaise, boolean priceIsFrom, boolean active,
                             int sortOrder, int returnAfterDays, ServiceIcon icon) {

    /** Used when the owner doesn't say: two weeks. */
    public static final int DEFAULT_RETURN_AFTER_DAYS = 14;
    public static final int MAX_RETURN_AFTER_DAYS = 180;
}
