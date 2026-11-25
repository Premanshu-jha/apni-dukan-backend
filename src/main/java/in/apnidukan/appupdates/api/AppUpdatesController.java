package in.apnidukan.appupdates.api;

import in.apnidukan.appupdates.domain.AppName;
import in.apnidukan.appupdates.service.AppUpdates;
import in.apnidukan.appupdates.service.AppUpdates.Answer;
import in.apnidukan.appupdates.service.AppUpdates.Device;
import in.apnidukan.common.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Asked by the Play Store apps on every start, signed in or not (open in the security config): which screens to use,
 * and whether the app is too old to use. Answered from memory (AppUpdates). The website never asks.
 *
 * GET /api/v1/app-updates/{partner|customer}?nativeLevel=2&versionCode=245&debug=false →
 * {"bundle": {"build", "url", "checksum", "sessionKey"} or null (the screens the app came with), "updateRequired"}.
 * 404 for another app name; 400 VALIDATION_FAILED for numbers out of range.
 */
@RestController
public class AppUpdatesController {

    private final AppUpdates updates;

    public AppUpdatesController(AppUpdates updates) {
        this.updates = updates;
    }

    @GetMapping("/api/v1/app-updates/{app}")
    public Answer check(@PathVariable String app, @RequestParam int nativeLevel, @RequestParam int versionCode,
                        @RequestParam(defaultValue = "false") boolean debug) {
        AppName name = AppName.of(app).orElseThrow(() -> ApiException.notFound("App"));
        if (nativeLevel < 1 || nativeLevel > 10_000) {
            throw invalid("nativeLevel");
        }
        if (versionCode < 1) {
            throw invalid("versionCode");
        }
        return updates.check(name, new Device(nativeLevel, versionCode, debug));
    }

    private static ApiException invalid(String field) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid",
                Map.of(field, "not valid"));
    }
}
