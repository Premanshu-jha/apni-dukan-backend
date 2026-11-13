package in.apnidukan.files.domain;

/** Why a file was uploaded. Also decides its folder in R2. */
public enum FilePurpose {
    /** The shop from outside (customers see it). */
    SHOP_PHOTO("profile"),
    /** The owner's own photo, for the field team's visit. */
    OWNER_PHOTO("owners"),
    /** A salon barber's photo (optional). */
    STAFF_PHOTO("staff"),
    /** A short video of the salon for the field team (registration); customers never see it. */
    SHOP_VIDEO("videos"),
    /**
     * A salon's registration document (Shops and Establishments certificate, trade licence or Udyam certificate), a
     * photo or a PDF, for the field team only. Only the owner uploads, opens or deletes it.
     */
    REG_DOC("documents"),
    BILL("bills"),
    RECEIPT("receipts"),
    /**
     * Any file (a photo, video, PDF or anything else) sent with a support ticket (from the apps, the admin website or
     * an email). It belongs to the person who sent it, not to a shop (its row has no shop); the helpdesk decides who may
     * see it.
     */
    TICKET_FILE("tickets");

    private final String folder;

    FilePurpose(String folder) {
        this.folder = folder;
    }

    public String folder() {
        return folder;
    }
}
