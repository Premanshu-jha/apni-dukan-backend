package in.apnidukan.files.domain;

/** Why a file was uploaded. Also decides its folder in R2. */
public enum FilePurpose {
    /** The shop from outside (customers see it). */
    SHOP_PHOTO("profile"),
    /** The owner's own photo, for the field team's visit. */
    OWNER_PHOTO("owners"),
    /** A salon barber's photo (optional). */
    STAFF_PHOTO("staff"),
    BILL("bills"),
    RECEIPT("receipts");

    private final String folder;

    FilePurpose(String folder) {
        this.folder = folder;
    }

    public String folder() {
        return folder;
    }
}
