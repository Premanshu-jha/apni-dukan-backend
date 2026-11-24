package in.apnidukan.files.service;

import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.files.AppBundleFiles;
import in.apnidukan.files.storage.FileStorage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The apps' screen updates in storage (AppBundleFiles). No row in {@code files}: they belong to no shop and nobody
 * uploads them from the app; the appupdates module keeps its own list (app_releases). Links are signed for the start
 * of the hour (as FileRefService does), so every phone and every copy of the backend gets the same link for an hour.
 */
@Service
public class AppBundleFileService implements AppBundleFiles {

    static final String CONTENT_TYPE = "application/zip";
    static final Duration UPLOAD_FOR = Duration.ofMinutes(15);
    /** Signed at the start of the hour for two hours: at least an hour left whenever it is handed out. */
    static final Duration DOWNLOAD_FOR = Duration.ofHours(2);

    private final FileStorage storage;
    private final Clock clock;
    private final Map<String, Signed> signed = new ConcurrentHashMap<>();

    public AppBundleFileService(FileStorage storage, Clock clock) {
        this.storage = storage;
        this.clock = clock;
    }

    @Override
    public String uploadLink(String key, long sizeBytes) {
        return storage.uploadLink(key, CONTENT_TYPE, sizeBytes, UPLOAD_FOR);
    }

    @Override
    public OptionalLong size(String key) {
        try {
            return storage.size(key);
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_BUSY",
                    "Storage didn't answer. Try again in a moment.").retryAfter(30);
        }
    }

    /**
     * Asked on every app start, so each key's link is signed once an hour and kept (a handful of keys: the bundle
     * phones are offered right now).
     */
    @Override
    public String downloadLink(String key) {
        Instant hour = FileRefService.hour(Instant.now(clock));
        Signed s = signed.get(key);
        if (s == null || !s.hour().equals(hour)) {
            if (signed.size() > 200) {
                signed.clear();   // only ever a few in use; old hours' links are simply dropped
            }
            s = new Signed(hour, storage.signedLinkAt(key, hour, DOWNLOAD_FOR));
            signed.put(key, s);
        }
        return s.url();
    }

    private record Signed(Instant hour, String url) {
    }
}
