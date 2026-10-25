package in.apnidukan.files.domain;

/** Why a file was uploaded. Also decides its folder in R2. */
public enum FilePurpose {
    SHOP_PHOTO("profile"),
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
