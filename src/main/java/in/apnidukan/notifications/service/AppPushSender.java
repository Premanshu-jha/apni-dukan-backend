package in.apnidukan.notifications.service;

import in.apnidukan.notifications.domain.NotificationJob;
import in.apnidukan.notifications.domain.PushSubscription;

/**
 * Delivers one message to one installed Android app (a device of kind APP), through Firebase Cloud Messaging. The
 * browsers keep {@link PushSender} (Web Push). Replaced by a recording fake in tests.
 */
public interface AppPushSender {

    /** Whether the apps can get notifications at all (Firebase is set up on the server). */
    boolean configured();

    /** The message's title, text, type and screen go to the app; the answer means what PushSender's does. */
    PushSender.Result send(PushSubscription device, NotificationJob message);

    /** Used while FIREBASE_SERVICE_ACCOUNT isn't set: no app can register, and nothing is sent. */
    AppPushSender OFF = new AppPushSender() {
        @Override
        public boolean configured() {
            return false;
        }

        @Override
        public PushSender.Result send(PushSubscription device, NotificationJob message) {
            // An app registered while Firebase was set up, and the setting was removed since: keep the device (the
            // setting may come back) and let the message retry, then end as FAILED
            return PushSender.Result.FAILED;
        }
    };
}
