package in.apnidukan.appupdates.service;

import in.apnidukan.appupdates.domain.AppName;
import in.apnidukan.appupdates.domain.Release;
import in.apnidukan.appupdates.domain.UpdateSettings;
import in.apnidukan.appupdates.repo.ReleaseStore;
import in.apnidukan.appupdates.repo.ReleaseStore.NewRelease;
import in.apnidukan.common.context.TeamRole;
import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.files.AppBundleFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Releases of the apps' screens: sent by CI after every merge to main (with the app's own release token), and looked
 * after by the super admin on the admin website's App releases page.
 *
 * CI: {@link #start} gives a one-time link to send the signed bundle straight to storage (the bytes never pass
 * through the backend), then {@link #publish} checks storage has exactly that many bytes and makes it LIVE: phones get
 * it the next time they start the app (AppUpdates). Both may be repeated safely (a retried CI step).
 *
 * Super admin: pull back a LIVE release (phones on it go back to the one before at their next start), put a pulled one
 * back, and set per app the version Google has approved and the oldest version still allowed.
 */
@Service
public class AppReleases {

    private static final Logger log = LoggerFactory.getLogger(AppReleases.class);

    public static final long MAX_BYTES = 50L * 1024 * 1024;
    static final int PAGE = 20;
    /** A release token shorter than this is treated as not set: it would be guessable. */
    static final int MIN_TOKEN_LENGTH = 32;

    private static final Pattern VERSION_NAME = Pattern.compile("[0-9A-Za-z.+-]{1,30}");
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{7,40}");
    /** Capgo's signed checksum: hex (or base64 from older CLIs) */
    private static final Pattern CHECKSUM = Pattern.compile("[A-Za-z0-9+/=]{16,1024}");
    /** Capgo's session key: base64 IV ":" base64 encrypted key */
    private static final Pattern SESSION_KEY = Pattern.compile("[A-Za-z0-9+/=]{8,500}:[A-Za-z0-9+/=]{8,500}");

    private final ReleaseStore store;
    private final AppBundleFiles files;
    private final AppUpdates updates;
    private final UserDirectory users;
    private final Clock clock;
    private final Map<AppName, byte[]> tokens = new EnumMap<>(AppName.class);

    public AppReleases(ReleaseStore store, AppBundleFiles files, AppUpdates updates, UserDirectory users, Clock clock,
                       @Value("${app.app-releases.token.partner:}") String partnerToken,
                       @Value("${app.app-releases.token.customer:}") String customerToken) {
        this.store = store;
        this.files = files;
        this.updates = updates;
        this.users = users;
        this.clock = clock;
        keep(AppName.PARTNER, partnerToken);
        keep(AppName.CUSTOMER, customerToken);
    }

    // ---- CI ----------------------------------------------------------------------------------------------------

    /** What CI tells about the bundle it is about to send. */
    public record Incoming(int build, int nativeLevel, String versionName, String commitSha, long sizeBytes,
                           String checksum, String sessionKey) {
    }

    public record UploadTicket(int build, String uploadUrl) {
    }

    /**
     * 401 BAD_RELEASE_TOKEN, 503 RELEASES_OFF (no token set for this app), 400 VALIDATION_FAILED naming the field,
     * 409 RELEASE_EXISTS (this build was already published: a build is never replaced).
     */
    public UploadTicket start(AppName app, String token, Incoming in) {
        checkToken(app, token);
        validate(in);
        String key = store.startUpload(new NewRelease(app, in.build(), in.nativeLevel(), in.versionName(),
                        in.commitSha(), in.sizeBytes(), in.checksum(), in.sessionKey()), now())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "RELEASE_EXISTS",
                        "This build was already published."));
        return new UploadTicket(in.build(), files.uploadLink(key, in.sizeBytes()));
    }

    /**
     * Makes the uploaded build LIVE (again: 200, nothing changes). 404 for a build never started; 409 UPLOAD_MISSING
     * when storage has no bundle (or not exactly the size started with), 409 RELEASE_PULLED for one the super admin
     * pulled back (only they put it back).
     */
    public Release publish(AppName app, String token, int build) {
        checkToken(app, token);
        Release r = store.find(app, build).orElseThrow(() -> ApiException.notFound("Release"));
        if (r.status() == Release.Status.PULLED) {
            throw new ApiException(HttpStatus.CONFLICT, "RELEASE_PULLED", "This build was pulled back.");
        }
        if (r.status() == Release.Status.UPLOADING) {
            OptionalLong size = files.size(r.storageKey());
            if (size.isEmpty() || size.getAsLong() != r.sizeBytes()) {
                throw new ApiException(HttpStatus.CONFLICT, "UPLOAD_MISSING",
                        "Storage doesn't have the whole bundle: send it again.");
            }
            if (store.publish(app, build, r.storageKey(), now())) {
                log.info("App release {} {} is live", app.value(), build);
            } else if (store.find(app, build).map(Release::status).orElse(null) != Release.Status.LIVE) {
                // A retried start gave this build a new upload meanwhile: that attempt publishes its own zip
                throw new ApiException(HttpStatus.CONFLICT, "UPLOAD_MISSING",
                        "This build was started again: send its zip, then publish.");
            }
            updates.refresh();
        }
        return store.find(app, build).orElseThrow();
    }

    // ---- Super admin -----------------------------------------------------------------------------------------

    public record Page(UpdateSettings settings, List<Release> releases, boolean more) {

        public Page {
            releases = List.copyOf(releases);
        }
    }

    /** Newest first, 20 at a time (the next page: {@code before} = the last build shown). */
    public Page list(UUID caller, AppName app, Integer before) {
        requireSuperAdmin(caller);
        List<Release> rows = store.page(app, before, PAGE + 1);
        boolean more = rows.size() > PAGE;
        return new Page(store.settings(app), more ? rows.subList(0, PAGE) : rows, more);
    }

    /** LIVE → PULLED. 409 RELEASE_NOT_LIVE (already pulled, or never published), 404 for an unknown build. */
    @Transactional
    public Release pullBack(UUID caller, AppName app, int build) {
        return change(caller, app, build, Release.Status.LIVE, Release.Status.PULLED, "APP_RELEASE_PULLED",
                "RELEASE_NOT_LIVE", "This build isn't live (any more).");
    }

    /** PULLED → LIVE. 409 RELEASE_NOT_PULLED, 404 for an unknown build. */
    @Transactional
    public Release restore(UUID caller, AppName app, int build) {
        return change(caller, app, build, Release.Status.PULLED, Release.Status.LIVE, "APP_RELEASE_RESTORED",
                "RELEASE_NOT_PULLED", "This build isn't pulled back.");
    }

    /**
     * Saves both numbers. The oldest allowed version can't be above the version on the Play Store (400
     * MIN_ABOVE_STORE): phones would be told to update to a version Google doesn't have. 409 SETTINGS_CHANGED when
     * another super admin saved since {@code version}.
     */
    @Transactional
    public UpdateSettings saveSettings(UUID caller, AppName app, Integer minVersionCode, Integer storeVersionCode,
                                       int version) {
        requireSuperAdmin(caller);
        if (minVersionCode != null && (storeVersionCode == null || minVersionCode > storeVersionCode)) {
            throw ApiException.badRequest("MIN_ABOVE_STORE",
                    "The oldest allowed version can't be newer than the version on the Play Store.");
        }
        if (!store.saveSettings(app, minVersionCode, storeVersionCode, version, caller, now())) {
            throw new ApiException(HttpStatus.CONFLICT, "SETTINGS_CHANGED",
                    "Someone else changed these settings. Look again.");
        }
        store.logEvent(caller, "APP_SETTINGS_CHANGED", app.value() + ": oldest allowed " + minVersionCode
                + ", on Play Store " + storeVersionCode, now());
        refreshAfterCommit();
        return store.settings(app);
    }

    private Release change(UUID caller, AppName app, int build, Release.Status from, Release.Status to, String event,
                           String code, String message) {
        requireSuperAdmin(caller);
        if (store.find(app, build).isEmpty()) {
            throw ApiException.notFound("Release");
        }
        if (!store.changeStatus(app, build, from, to, caller, now())) {
            throw new ApiException(HttpStatus.CONFLICT, code, message);
        }
        store.logEvent(caller, event, app.value() + " build " + build, now());
        refreshAfterCommit();
        return store.find(app, build).orElseThrow();
    }

    // ---- Helpers -----------------------------------------------------------------------------------------------

    private void requireSuperAdmin(UUID caller) {
        TeamRole role = users.teamRole(caller);
        if (role == TeamRole.NONE) {
            throw ApiException.notFound("Page");
        }
        if (role != TeamRole.SUPER_ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SUPER_ADMIN_ONLY", "Only a super admin can do this.");
        }
    }

    private void checkToken(AppName app, String token) {
        byte[] expected = tokens.get(app);
        if (expected == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RELEASES_OFF",
                    "Releases aren't set up for this app.");
        }
        if (token == null || !MessageDigest.isEqual(expected, token.strip().getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_RELEASE_TOKEN", "Wrong release token.");
        }
    }

    private void keep(AppName app, String token) {
        String t = token == null ? "" : token.strip();
        if (t.length() >= MIN_TOKEN_LENGTH) {
            tokens.put(app, t.getBytes(StandardCharsets.UTF_8));
        } else if (!t.isEmpty()) {
            log.warn("The {} app's release token is shorter than {} characters: releases are off", app.value(),
                    MIN_TOKEN_LENGTH);
        }
    }

    private static void validate(Incoming in) {
        if (in.build() < 1) {
            throw invalid("build");
        }
        if (in.nativeLevel() < 1 || in.nativeLevel() > 10_000) {
            throw invalid("nativeLevel");
        }
        if (in.versionName() == null || !VERSION_NAME.matcher(in.versionName()).matches()) {
            throw invalid("versionName");
        }
        if (in.commitSha() == null || !COMMIT.matcher(in.commitSha()).matches()) {
            throw invalid("commitSha");
        }
        if (in.sizeBytes() < 1 || in.sizeBytes() > MAX_BYTES) {
            throw invalid("sizeBytes");
        }
        if (in.checksum() == null || !CHECKSUM.matcher(in.checksum()).matches()) {
            throw invalid("checksum");
        }
        if (in.sessionKey() == null || !SESSION_KEY.matcher(in.sessionKey()).matches()) {
            throw invalid("sessionKey");
        }
    }

    private static ApiException invalid(String field) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid",
                Map.of(field, "not valid"));
    }

    /** Phones see the change at their next start: this copy reads again once the change is saved. */
    private void refreshAfterCommit() {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                updates.refresh();
            }
        });
    }

    private Instant now() {
        return Instant.now(clock);
    }
}
