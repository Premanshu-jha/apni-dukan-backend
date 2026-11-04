package in.apnidukan.notifications.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One message in a person's in-app list.
 *
 * @param url    the app screen it is about (for example /bookings/{id}), or null
 * @param sentAt when it was (or was due to be) sent
 * @param read   whether the person has opened it in the app
 */
public record InboxItem(UUID id, String type, String title, String body, String url, Instant sentAt, boolean read) {
}
