package in.apnidukan.notifications.domain;

/** How a queued message goes out. Each channel is its own row, sent and retried on its own. */
public enum Channel {
    /** Push to every device the person allowed notifications on: Web Push to browsers, FCM to the Android apps. */
    PUSH,
    /** One SMS from the DLT template set for the message's type. */
    SMS
}
