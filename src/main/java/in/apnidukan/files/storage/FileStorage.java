package in.apnidukan.files.storage;

import java.io.InputStream;
import java.time.Duration;

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

    void delete(String key);
}
