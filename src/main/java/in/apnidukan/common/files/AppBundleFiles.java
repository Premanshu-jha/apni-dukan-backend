package in.apnidukan.common.files;

import java.util.OptionalLong;

/**
 * Where the apps' screen updates (zip bundles, see the appupdates module) are kept, as that module sees it.
 * Implemented by the files module, in storage (R2) under {@code app-releases/}. The bytes never pass through the
 * backend: CI sends them straight to storage with an upload link (signed for that size and type), and phones download them with a download link.
 */
public interface AppBundleFiles {

    /** A link CI sends the bundle to (HTTP PUT of exactly this size, type application/zip), good for 15 minutes. */
    String uploadLink(String key, long sizeBytes);

    /**
     * The stored bundle's size, or empty if nothing was sent to this key (yet). Storage not answering: 503
     * SERVICE_BUSY (CI tries again).
     */
    OptionalLong size(String key);

    /**
     * A link phones download the bundle with, good for at least an hour. The same for every phone and every copy of
     * the backend within the same hour, so the answer of the update check can be cached.
     */
    String downloadLink(String key);
}
