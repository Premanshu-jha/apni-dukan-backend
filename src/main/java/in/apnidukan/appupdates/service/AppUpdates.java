package in.apnidukan.appupdates.service;

import in.apnidukan.appupdates.domain.AppName;
import in.apnidukan.appupdates.domain.Release;
import in.apnidukan.appupdates.domain.UpdateSettings;
import in.apnidukan.appupdates.repo.ReleaseStore;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.files.AppBundleFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
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

    /**
     * urgentBuild: the newest urgent release among the screens on offer (at most the bundle's build, newer than the
     * app's own); null when none is urgent. A phone whose screens are older than it shows "A fix is ready" once the
     * bundle is downloaded; one that has it already shows nothing.
     */
    public record Answer(Bundle bundle, boolean updateRequired, Integer urgentBuild) {

        public Answer(Bundle bundle, boolean updateRequired) {
            this(bundle, updateRequired, null);
        }
    }

    private record Known(List<Release> live, UpdateSettings settings) {
    }

    public Answer check(AppName app, Device device) {
        Map<AppName, Known> all = known;
        if (all == null) {
            // Not read yet (just started, the database slow): "busy", so phones keep the screens they have. An answer
            // without a bundle would send them back to the screens their app came with.
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_BUSY",
                    "Please try again in a moment.").retryAfter(30);
        }
        Known k = all.get(app);
        boolean playStore = !device.debug();
        Integer store = k.settings().storeVersionCode();
        boolean inReview = playStore && (store == null || device.versionCode() > store);
        Bundle bundle = null;
        Integer urgentBuild = null;
        if (!inReview) {
            for (Release r : k.live()) {
                if (r.build() <= device.versionCode()) {
                    break;   // newest first: nothing after this is newer than the app's own screens
                }
                if (r.nativeLevel() > device.nativeLevel()) {
                    continue;
                }
                if (bundle == null) {
                    bundle = new Bundle(r.build(), files.downloadLink(r.storageKey()), r.checksum(), r.sessionKey());
                }
                // The newest urgent one the bundle includes (it or an older one this phone may not have yet)
                if (r.urgent()) {
                    urgentBuild = r.build();
                    break;
                }
            }
        }
        Integer min = k.settings().minVersionCode();
        return new Answer(bundle, playStore && min != null && device.versionCode() < min, urgentBuild);
    }

    /**
     * Whether a version number could be a real app of ours: at most a little above the newest build we know of (a
     * test APK of a run that sent no screens), so made-up numbers can't fill the App versions counts. Unknown until
     * the first read: nothing is counted.
     */
    public boolean plausibleVersion(AppName app, int versionCode) {
        Map<AppName, Known> all = known;
        if (all == null || versionCode < 1) {
            return false;
        }
        Known k = all.get(app);
        int newest = k.live().isEmpty() ? 0 : k.live().get(0).build();
        Integer store = k.settings().storeVersionCode();
        if (store != null) {
            newest = Math.max(newest, store);
        }
        return versionCode <= Math.max(newest, MIN_PLAUSIBLE) + PLAUSIBLE_AHEAD;
    }

    /** CI's run numbers: a build this far above the newest known is still an app of ours. */
    static final int PLAUSIBLE_AHEAD = 200;
    /** Before any release or Play Store version is known: the run numbers so far are below this. */
    static final int MIN_PLAUSIBLE = 1_000;

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
