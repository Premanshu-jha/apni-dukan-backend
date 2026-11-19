package in.apnidukan.common.files;

import java.util.Optional;
import java.util.UUID;

/**
 * Pictures the platform itself stores, belonging to no shop: a product's official picture fetched for the shared
 * catalogue (GS1 DataKart). Implemented by the files module: cleaned like any photo (drawn again as a JPEG) and kept
 * under its own folder (purpose CATALOGUE_PHOTO, no shop's, no uploader). Links come from {@link FileRefs#links}.
 */
public interface PlatformFiles {

    /** A stored picture: its file, and the bytes as stored (a JPEG), to check before it is used. */
    record Stored(UUID fileId, byte[] jpeg) {

        public Stored {
            jpeg = jpeg.clone();
        }

        @Override
        public byte[] jpeg() {
            return jpeg.clone();
        }
    }

    /**
     * Stores a JPEG or PNG picture (empty: not one, or over 10 MB); the same picture again is the file stored before.
     * Call outside a transaction: the bytes go to storage first.
     */
    Optional<Stored> storePicture(byte[] bytes, String name);

    /** A stored picture that turned out unusable, deleted (if nothing points at it). */
    void discard(UUID fileId);
}
