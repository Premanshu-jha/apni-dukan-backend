package in.apnidukan.notifications.service;

import in.apnidukan.notifications.domain.PushSubscription;

/** Delivers one message to one device. Replaced by a recording fake in tests. */
public interface PushSender {

    Result send(PushSubscription device, String jsonPayload);

    enum Result {
        /** The push service accepted it. */
        SENT,
        /** The device unsubscribed or the address expired (404 or 410): delete the subscription. */
        GONE,
        /** Anything else (network, 429, 5xx, a bad key): try again later. */
        FAILED
    }
}
