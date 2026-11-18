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
    /**
     * A kirana's bill photo: a distributor's bill (stock coming in) or a paper bill written while the phone was off; a
     * photo or a PDF, stored as it came (read by AI).
     */
    BILL("bills"),
    RECEIPT("receipts"),
    /**
     * Any file (a photo, video, PDF or anything else) sent with a support ticket (from the apps, the admin website or
     * an email). It belongs to the person who sent it, not to a shop (its row has no shop); the helpdesk decides who may
     * see it.
     */
    TICKET_FILE("tickets"),
    /**
     * A kirana product's pack: the label of a barcode no one knows yet (read by AI to suggest the product), or the
     * product's photo (shared with every shop that has the same barcode). Cleaned like other photos.
     */
    PRODUCT_PHOTO("products");

    private final String folder;

    FilePurpose(String folder) {
        this.folder = folder;
    }

    public String folder() {
        return folder;
    }
}
