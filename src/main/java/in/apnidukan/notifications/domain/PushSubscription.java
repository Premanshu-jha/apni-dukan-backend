package in.apnidukan.notifications.domain;

import java.util.UUID;

/**
 * One device's Web Push address, as the browser gave it.
 * @param endpoint the push service URL for this device (a secret: anyone with it can send to the device)
 * @param p256dh   the device's public key, base64url (65 bytes decoded)
 * @param auth     the device's secret, base64url (16 bytes decoded)
 */
public record PushSubscription(UUID id, UUID userId, String endpoint, String p256dh, String auth) {
}
