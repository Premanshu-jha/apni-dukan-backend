package in.apnidukan.common.files;

import java.util.Optional;
import java.util.UUID;

/**
 * Uploaded files as other modules see them: which shop a file belongs to and what it was uploaded for, and a
 * short-lived link to show it. Implemented by the files module. Callers check who may see the file first.
 */
public interface FileRefs {

    Optional<FileRef> find(UUID fileId);

    /** A link that opens the file for at least 15 more minutes, at most an hour (signed R2 URL; FileRefService). */
    String link(UUID fileId);

    /** Links for many files with one database read (lists of shops); files that no longer exist are left out. */
    java.util.Map<UUID, String> links(java.util.Collection<UUID> fileIds);

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
