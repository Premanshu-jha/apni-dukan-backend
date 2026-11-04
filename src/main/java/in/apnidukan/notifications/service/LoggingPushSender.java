package in.apnidukan.notifications.service;

import in.apnidukan.notifications.domain.PushSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Used when no VAPID keys are set (development): logs that a message would go out, without its text or the
 * device address. Refused on the real server by ProductionSafetyCheck.
 */
public class LoggingPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingPushSender.class);

    @Override
    public Result send(PushSubscription device, String jsonPayload) {
        log.info("Web Push not configured: would send a {}-byte message to subscription {}", jsonPayload.length(),
                device.id());
        return Result.SENT;
    }
}
