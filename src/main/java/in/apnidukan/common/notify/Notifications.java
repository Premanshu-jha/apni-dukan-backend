package in.apnidukan.common.notify;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Queues a message to a person's devices (Web Push) and, when an SMS template is set for its type, as an SMS too.
 * Implemented by the notifications module. queue() writes rows in the caller's database transaction: if the change
 * being reported rolls back, the message is never sent; a failed send never undoes the change. A job delivers due
 * messages afterwards.
 */
public interface Notifications {

    void queue(Notice notice);

    /** Cancels every message about this subject (e.g. a booking) that hasn't gone out yet. */
    void cancelPending(UUID refId);

    /**
     * Rewrites a message that hasn't gone out yet (same subject and type, e.g. a booking's reminder after the salon
     * moved): the text of each channel still waiting is replaced in place, keeping its place in the queue. Channels
     * already sent are left alone, and nothing new is queued, so nobody gets the same message twice. (One being sent
     * at this very moment goes out with the text it had.)
     */
    void replacePending(Notice notice);

    /**
     * @param type   what kind of message, e.g. BOOKING_CONFIRMED (for the log and the history)
     * @param refId  what it is about (e.g. the booking id), so pending messages can be cancelled; may be null
     * @param sendAt when to send it; now or in the past means as soon as possible
     * @param title  up to 100 characters
     * @param body   up to 500 characters
     * @param url    the app page to open when tapped (a path like /bookings/...); may be null
     * @param smsVariables the values for the SMS template's variables (MSG91 names, e.g. SALON), at most 30
     *                     characters each; the SMS text itself is the DLT-approved template
     */
    record Notice(UUID userId, String type, UUID refId, Instant sendAt, String title, String body, String url,
                  Map<String, String> smsVariables) {

        /** Its own copy of the SMS variables (none = empty), so nobody can change a queued notice. */
        public Notice {
            smsVariables = smsVariables == null ? Map.of() : Map.copyOf(smsVariables);
        }

        @Override
        public Map<String, String> smsVariables() {
            return Map.copyOf(smsVariables);   // already immutable: returns the same map, no copying
        }
    }
}
