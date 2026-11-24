package in.apnidukan.appupdates.service;

import in.apnidukan.appupdates.domain.AppName;
import in.apnidukan.appupdates.domain.Release;
import in.apnidukan.appupdates.domain.UpdateSettings;
import in.apnidukan.appupdates.repo.ReleaseStore;
import in.apnidukan.common.files.AppBundleFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The apps' question on every start: which screens to use, and whether this app is too old to use at all.
 *
 * Every phone asks on every start, so the answer comes from memory, never the database: each copy of the backend keeps
 * the newest LIVE releases (per native level) and the settings of both apps, read when it starts, again every 30
 * seconds, and at once after a change made on that copy (reads are one at a time, so an older read never replaces a
 * newer one). A pull-back reaches every copy within about half a minute. Before the first read has worked (the
 * database down at start), phones are told to keep the screens they have.
 *
 * Which release a phone gets ({@link #check}): the newest LIVE one that is newer than the screens its app came with
 * (build above its versionCode: an APK and the bundle of the same CI run share the number) and needs no newer native
 * part than it has. None: the screens the app came with. So a pulled release sends its phones back to the newest
 * LIVE one before it, or to their own screens.
 *
 * An app version Google is still reviewing (versionCode above store_version_code, or none set yet) keeps the screens
 * it was installed with: the reviewer sees exactly what was submitted. Test APKs (debug) always get the newest, so
 * releases can be tried before the Play Store has the app; nobody is ever made to update a test APK.
 */
@Service
public class AppUpdates {

    private static final Logger log = LoggerFactory.getLogger(AppUpdates.class);

    /** How many LIVE releases per app and native level are kept in memory: plenty to fall back through. */
    static final int KEPT = 20;

    private final ReleaseStore store;
    private final AppBundleFiles files;
    private volatile Map<AppName, Known> known;

    public AppUpdates(ReleaseStore store, AppBundleFiles files) {
        this.store = store;
        this.files = files;
    }

    /** What a phone tells about its app. */
    public record Device(int nativeLevel, int versionCode, boolean debug) {
    }

    /** The screens to download (null: use the ones the app came with). */
    public record Bundle(int build, String url, String checksum, String sessionKey) {
    }

    public record Answer(Bundle bundle, boolean updateRequired) {
    }

    private record Known(List<Release> live, UpdateSettings settings) {
    }

    public Answer check(AppName app, Device device) {
        Map<AppName, Known> all = known;
        if (all == null) {
            return new Answer(null, false);
        }
        Known k = all.get(app);
        boolean playStore = !device.debug();
        Integer store = k.settings().storeVersionCode();
        boolean inReview = playStore && (store == null || device.versionCode() > store);
        Bundle bundle = null;
        if (!inReview) {
            for (Release r : k.live()) {
                if (r.build() <= device.versionCode()) {
                    break;   // newest first: nothing after this is newer than the app's own screens
                }
                if (r.nativeLevel() <= device.nativeLevel()) {
                    bundle = new Bundle(r.build(), files.downloadLink(r.storageKey()), r.checksum(), r.sessionKey());
                    break;
                }
            }
        }
        Integer min = k.settings().minVersionCode();
        return new Answer(bundle, playStore && min != null && device.versionCode() < min);
    }

    /** The first read, as soon as this copy is ready (before then, and if it fails, phones keep their screens). */
    @EventListener(ApplicationReadyEvent.class)
    public void loadAtStart() {
        refresh();
    }

    /**
     * Reads the releases and settings again: every 30 seconds, and at once after a change on this copy. One at a time
     * (synchronized): a read that started before a pull-back was saved can't finish after the read that follows it.
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public synchronized void refresh() {
        try {
            known = load();
        } catch (RuntimeException e) {
            // Keep answering from what we have; the next refresh tries again
            log.warn("Couldn't read the app releases: {}", e.toString());
        }
    }

    private Map<AppName, Known> load() {
        Map<AppName, Known> loaded = new EnumMap<>(AppName.class);
        for (AppName app : AppName.values()) {
            loaded.put(app, new Known(List.copyOf(store.live(app, KEPT)), store.settings(app)));
        }
        return Map.copyOf(loaded);
    }
}
