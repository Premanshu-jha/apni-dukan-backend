package in.apnidukan.notifications.service;

import in.apnidukan.common.error.ApiException;
import in.apnidukan.notifications.repo.PushSubscriptionStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The Android apps' devices: an installed app saves its Firebase (FCM) registration token after the person allows
 * notifications, and removes it on sign-out. Kept in push_subscriptions next to the browsers (kind APP), so the
 * sender, the 10-devices limit, signing out everywhere and deleting the account treat both alike.
 */
@Service
public class AppDevices {

    /**
     * What an FCM registration token looks like: letters, digits, '-', '_' and ':' (in practice about 150 to 200
     * characters). Never an https address, so it can't be taken for a browser's (PushEndpoints).
     */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_:-]{20,1000}");

    private final PushSubscriptionStore devices;
    private final AppPushSender sender;
    private final Clock clock;

    public AppDevices(PushSubscriptionStore devices, AppPushSender sender, Clock clock) {
        this.devices = devices;
        this.sender = sender;
        this.clock = clock;
    }

    /** Whether the apps can turn notifications on (Firebase is set up on the server). */
    public boolean available() {
        return sender.configured();
    }

    /**
     * Saves this app for this person (a token already saved moves to them: someone else signed in on the phone
     * before). 409 APP_PUSH_OFF while Firebase isn't set up; 400 for something that isn't a token.
     */
    @Transactional
    public void register(UUID userId, String token) {
        if (!sender.configured()) {
            throw new ApiException(HttpStatus.CONFLICT, "APP_PUSH_OFF",
                    "Notifications for the app aren't available yet");
        }
        if (token == null || !TOKEN.matcher(token).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid",
                    Map.of("token", "is not an FCM registration token"));
        }
        devices.saveApp(userId, token, clock.instant());
        devices.trim(userId, NotificationService.MAX_DEVICES);
    }

    /** Stops notifications to this app (sign-out). Also fine if it wasn't saved. */
    @Transactional
    public void unregister(UUID userId, String token) {
        devices.delete(userId, token);
    }
}
