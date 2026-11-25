package in.apnidukan.appupdates.api;

import in.apnidukan.appupdates.domain.AppName;
import in.apnidukan.appupdates.domain.Release;
import in.apnidukan.appupdates.domain.UpdateSettings;
import in.apnidukan.appupdates.service.AppReleases;
import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.common.error.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * The admin website's App releases page (super admins only: 403 SUPER_ADMIN_ONLY for anyone else on the team): each
 * app's screen releases, pulling one back or putting it back, and the version numbers that decide who must update
 * and who gets screen updates.
 */
@RestController
@RequestMapping("/api/v1/admin/app-releases/{app}")
public class AdminAppReleasesController {

    private final AppReleases releases;
    private final CurrentUserProvider currentUser;

    public AdminAppReleasesController(AppReleases releases, CurrentUserProvider currentUser) {
        this.releases = releases;
        this.currentUser = currentUser;
    }

    /** Newest first, 20 at a time: {@code ?before=} the last build shown for the next page. */
    @GetMapping
    public PageView list(@PathVariable String app, @RequestParam(required = false) Integer before) {
        AppReleases.Page page = releases.list(caller(), name(app), before);
        return new PageView(SettingsView.of(page.settings()), page.releases().stream().map(ReleaseView::of).toList(),
                page.more());
    }

    @PostMapping("/{build}/pull-back")
    public ReleaseView pullBack(@PathVariable String app, @PathVariable int build) {
        return ReleaseView.of(releases.pullBack(caller(), name(app), build));
    }

    @PostMapping("/{build}/restore")
    public ReleaseView restore(@PathVariable String app, @PathVariable int build) {
        return ReleaseView.of(releases.restore(caller(), name(app), build));
    }

    /**
     * Marks a live release urgent ({@code note}: what it fixes, 3 to 120 characters): open phones show "A fix is
     * ready" with Restart once they have it. 409 RELEASE_NOT_LIVE; already urgent: 200, the first note stays.
     */
    @PostMapping("/{build}/urgent")
    public ReleaseView urgent(@PathVariable String app, @PathVariable int build, @RequestBody UrgentBody body) {
        return ReleaseView.of(releases.markUrgent(caller(), name(app), build, body == null ? null : body.note()));
    }

    /** Back to silent (marked by mistake). */
    @PostMapping("/{build}/not-urgent")
    public ReleaseView notUrgent(@PathVariable String app, @PathVariable int build) {
        return ReleaseView.of(releases.notUrgent(caller(), name(app), build));
    }

    public record UrgentBody(String note) {
    }

    /** Send the {@code version} the page showed. 400 MIN_ABOVE_STORE, 409 SETTINGS_CHANGED. */
    @PutMapping("/settings")
    public SettingsView settings(@PathVariable String app, @Valid @RequestBody SettingsBody body) {
        return SettingsView.of(releases.saveSettings(caller(), name(app), body.minVersionCode(),
                body.storeVersionCode(), body.version()));
    }

    public record SettingsBody(@Min(1) @Max(2_100_000_000) Integer minVersionCode,
                               @Min(1) @Max(2_100_000_000) Integer storeVersionCode,
                               @Min(0) int version) {
    }

    public record PageView(SettingsView settings, List<ReleaseView> releases, boolean more) {
    }

    public record SettingsView(Integer minVersionCode, Integer storeVersionCode, int version, Instant changedAt,
                               String changedByName) {
        static SettingsView of(UpdateSettings s) {
            return new SettingsView(s.minVersionCode(), s.storeVersionCode(), s.version(), s.changedAt(),
                    s.changedByName());
        }
    }

    /** Never the storage key, checksum or session key: the page doesn't need them. */
    /** urgent: phones show "A fix is ready"; urgentByName null with urgentAt set: CI marked it ([urgent] PR). */
    public record ReleaseView(int build, int nativeLevel, String versionName, String commitSha, long sizeBytes,
                              String status, Instant createdAt, Instant publishedAt, Instant changedAt,
                              String changedByName, boolean urgent, String urgentNote, Instant urgentAt,
                              String urgentByName) {
        static ReleaseView of(Release r) {
            return new ReleaseView(r.build(), r.nativeLevel(), r.versionName(), r.commitSha(), r.sizeBytes(),
                    r.status().name(), r.createdAt(), r.publishedAt(), r.changedAt(), r.changedByName(), r.urgent(),
                    r.urgent() ? r.urgentNote() : null, r.urgentAt(), r.urgentByName());
        }
    }

    private java.util.UUID caller() {
        return currentUser.get().userId();
    }

    private static AppName name(String app) {
        return AppName.of(app).orElseThrow(() -> ApiException.notFound("App"));
    }
}
