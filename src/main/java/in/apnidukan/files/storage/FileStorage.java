package in.apnidukan.files.storage;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;

/**
 * Where file bytes live. The rest of the app only talks to this interface,
 * so switching from R2 to Amazon S3 (or anything else) means one new class.
 */
public interface FileStorage {

    /** Saves the bytes under the given key. The stream is read once, never fully held in memory. */
    void put(String key, InputStream content, long sizeBytes, String contentType);

    /**
     * A temporary link that opens the file directly from storage.
     * @param downloadName if not null, the browser saves the file under this name instead of showing it.
     */
    String signedLink(String key, Duration validFor, String downloadName);

    /**
     * A temporary link signed as if at {@code signedAt} (not now): every copy of the backend signing the same key for
     * the same moment gives the very same link, so a phone's browser keeps the photo it already has.
     */
    String signedLinkAt(String key, Instant signedAt, Duration validFor);

    void delete(String key);

    /**
     * A one-time link the phone sends a big file to itself (HTTP PUT), so the bytes never pass through the backend.
     * Only a PUT of exactly this type and size, until the link runs out, is accepted by storage.
     */
    String uploadLink(String key, String contentType, long sizeBytes, Duration validFor);

    /** Copies an object to another key inside storage (no bytes pass through the backend). */
    void copy(String fromKey, String toKey);

    /** The stored object's size, or empty if there is no object under this key (yet). */
    OptionalLong size(String key);

    /** The object's first bytes (fewer if it is shorter), to tell what it really is. */
    byte[] firstBytes(String key, int count);
}
