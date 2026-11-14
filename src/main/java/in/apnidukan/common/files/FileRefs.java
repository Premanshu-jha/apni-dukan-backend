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
     * As {@link #links}, but each downloaded under its file name (Content-Disposition: attachment), never opened in
     * the browser: for a file of a kind we don't show (a Word file, a zip), or to save a PDF.
     */
    java.util.Map<UUID, String> downloadLinks(java.util.Collection<UUID> fileIds);

    /**
     * The shop now uses this file (the salon video it was set to): it is no longer deleted as unused. Joins the
     * caller's transaction and locks the file's deletion entry first; 409 UPLOAD_GONE if the deletion job already
     * purged it (then nothing must be attached).
     */
    void keep(UUID fileId);

    /**
     * As {@link #keep}, but false (nothing changed, the caller's transaction unharmed) when the file is gone, for a
     * caller that goes on without it (a support email's files).
     */
    boolean tryKeep(UUID fileId);

    /**
     * Whether this id was a salon video that no longer exists (purged unused after a day, or replaced): then 409
     * UPLOAD_GONE rather than "not a video".
     */
    boolean expiredVideo(UUID fileId);

    /**
     * The shop stopped using this file (an old salon video replaced): its row goes now and its bytes are queued for
     * deletion due now, in the caller's transaction (nothing happens if that rolls back).
     */
    void release(UUID fileId);

    /**
     * The file's bytes (for reading a registration document), if it exists and is at most {@code maxBytes} long;
     * empty if there is no such file. IllegalStateException if it is longer. Call it outside a transaction: it
     * downloads from storage.
     */
    Optional<byte[]> read(UUID fileId, long maxBytes);

    /**
     * @param shopId      null for a ticket file (it belongs to a person)
     * @param purpose     SHOP_PHOTO, OWNER_PHOTO, STAFF_PHOTO, SHOP_VIDEO, REG_DOC, BILL, RECEIPT or TICKET_FILE
     * @param contentType e.g. image/jpeg
     * @param uploadedBy  who sent it
     * @param name        the name it had on the phone or computer; may be null
     */
    record FileRef(UUID id, UUID shopId, String purpose, String contentType, UUID uploadedBy, long sizeBytes,
                   String name) {

        public boolean isPhoto() {
            return contentType.startsWith("image/");
        }

        public boolean isVideo() {
            return contentType.startsWith("video/");
        }
    }
}
