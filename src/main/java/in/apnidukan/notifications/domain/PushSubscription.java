package in.apnidukan.notifications.domain;

import java.util.UUID;

/**
 * One device that gets notifications: a browser (Web Push) or an installed Android app (Firebase Cloud Messaging).
 * @param endpoint a browser: the push service URL for this device (a secret: anyone with it can send to the device);
 *                 an app: its FCM registration token (a secret too)
 * @param p256dh   a browser's public key, base64url (65 bytes decoded); null for an app
 * @param auth     a browser's secret, base64url (16 bytes decoded); null for an app
 * @param kind     which of the two it is
 */
public record PushSubscription(UUID id, UUID userId, String endpoint, String p256dh, String auth, Kind kind) {

    /** A browser (Web Push), as the website and before the apps; or the Play Store app (FCM). */
    public enum Kind {
        WEB, APP
    }

    /** A browser's subscription (every subscription before the apps). */
    public PushSubscription(UUID id, UUID userId, String endpoint, String p256dh, String auth) {
        this(id, userId, endpoint, p256dh, auth, Kind.WEB);
    }

    public boolean app() {
        return kind == Kind.APP;
    }
}
