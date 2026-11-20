package in.apnidukan.shops.domain;

/**
 * Which registration document a shop added: a salon's municipal trade licence; a kirana's trade licence or its Shops
 * and Establishments certificate. UDYAM is only in old rows (never accepted any more).
 */
public enum RegistrationDocType {
    /** The state's Shops and Establishments registration certificate. */
    SHOPS_ESTABLISHMENT("Shops and Establishments certificate"),
    /** The municipal trade licence (GHMC in Hyderabad). */
    TRADE_LICENCE("Trade licence"),
    /** The Udyam (MSME) registration certificate. */
    UDYAM("Udyam certificate");

    private final String label;

    RegistrationDocType(String label) {
        this.label = label;
    }

    /** How the field team's page names it. */
    public String label() {
        return label;
    }
}
