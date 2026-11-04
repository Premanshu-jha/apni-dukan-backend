package in.apnidukan.common.files;

import java.util.Optional;
import java.util.UUID;

/**
 * Uploaded files as other modules see them: which shop a file belongs to and what it was uploaded for, and a
 * short-lived link to show it. Implemented by the files module. Callers check who may see the file first.
 */
public interface FileRefs {

    Optional<FileRef> find(UUID fileId);

    /** A link that opens the file for a few minutes (signed R2 URL). */
    String link(UUID fileId);

    /**
     * @param purpose     SHOP_PHOTO, OWNER_PHOTO, STAFF_PHOTO, BILL or RECEIPT
     * @param contentType e.g. image/jpeg
     */
    record FileRef(UUID id, UUID shopId, String purpose, String contentType) {

        public boolean isPhoto() {
            return contentType.startsWith("image/");
        }
    }
}
