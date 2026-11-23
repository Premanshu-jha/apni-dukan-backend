package in.apnidukan.notifications.domain;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A message waiting in notification_jobs, as the sender sees it after claiming it.
 * @param attempts     how many times sending has been tried, this time included
 * @param smsVariables SMS only: the template's variables
 * @param sentDevices  push only: the devices an earlier try reached (not sent to again)
 */
public record NotificationJob(UUID id, UUID userId, Channel channel, String type, String title, String body,
                              String url, Map<String, String> smsVariables, int attempts, List<UUID> sentDevices) {

    public NotificationJob(UUID id, UUID userId, Channel channel, String type, String title, String body, String url,
                           Map<String, String> smsVariables, int attempts) {
        this(id, userId, channel, type, title, body, url, smsVariables, attempts, List.of());
    }
}
