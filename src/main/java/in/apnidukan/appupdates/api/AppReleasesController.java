package in.apnidukan.appupdates.api;

import in.apnidukan.appupdates.domain.AppName;
import in.apnidukan.appupdates.service.AppReleases;
import in.apnidukan.appupdates.service.AppReleases.Incoming;
import in.apnidukan.appupdates.service.AppReleases.UploadTicket;
import in.apnidukan.common.error.ApiException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Called by the partner and customer repos' CI after a merge to main (open in the security config; the app's own
 * release token in X-Release-Token is checked by AppReleases): first {@code POST /api/v1/app-releases/{app}} with the
 * bundle's details gives an upload link (15 minutes), CI PUTs the zip there, then {@code POST .../{build}/publish} makes it
 * live. See "App updates" in the README.
 */
@RestController
public class AppReleasesController {

    static final String TOKEN_HEADER = "X-Release-Token";

    private final AppReleases releases;

    public AppReleasesController(AppReleases releases) {
        this.releases = releases;
    }

    @PostMapping("/api/v1/app-releases/{app}")
    public UploadTicket start(@PathVariable String app, @RequestHeader(value = TOKEN_HEADER, required = false) String token,
                              @RequestBody Incoming body) {
        return releases.start(name(app), token, body);
    }

    @PostMapping("/api/v1/app-releases/{app}/{build}/publish")
    public Published publish(@PathVariable String app, @PathVariable int build,
                             @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        return new Published(releases.publish(name(app), token, build).status().name());
    }

    public record Published(String status) {
    }

    private static AppName name(String app) {
        return AppName.of(app).orElseThrow(() -> ApiException.notFound("App"));
    }
}
