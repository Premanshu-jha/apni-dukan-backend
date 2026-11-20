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

    /** queue() for many notices, written in batches (one round trip per few hundred rows, not one per row). */
    void queueAll(java.util.List<Notice> notices);

    /** cancelPending() for many subjects, in one statement. */
    void cancelPendingAll(java.util.Collection<UUID> refIds);

    /** replacePending() for many notices, in batches. */
    void replacePendingAll(java.util.List<Notice> notices);

    /** Cancels every message about this subject (e.g. a booking) that hasn't gone out yet. */
    void cancelPending(UUID refId);

    /** cancelPending() for the messages of one type only (e.g. a booking's earlier-slot offer, not its reminder). */
    void cancelPending(UUID refId, String type);

    /**
     * Rewrites a message that hasn't gone out yet (same subject and type, e.g. a booking's reminder after the salon
     * moved): the text of each channel still waiting is replaced in place, keeping its place in the queue. Channels
     * already sent are left alone, and nothing new is queued, so nobody gets the same message twice. (One being sent
     * at this very moment goes out with the text it had.)
     */
    void replacePending(Notice notice);

    /**
     * Forgets this person's salon messages (types starting SALON_): the ones still waiting aren't sent, and the app's
     * list no longer shows them, from this moment (they are deleted in the background). For someone who left a salon or was removed from it: its customers' names and
     * booking numbers aren't theirs to keep, and its day links would open another salon's day if they join one.
     */
    void forgetSalonMessages(UUID userId);

    /** forgetSalonMessages for several people at once (everyone left a closed salon). */
    void forgetSalonMessages(java.util.Collection<UUID> userIds);

    /**
     * @param type   what kind of message, e.g. BOOKING_CONFIRMED (for the log and the history)
     * @param refId  what it is about (e.g. the booking id), so pending messages can be cancelled; may be null
     * @param sendAt when to send it; now or in the past means as soon as possible
     * @param title  up to 100 characters
     * @param body   up to 500 characters
     * @param url    the app page to open when tapped (a path like /bookings/...); may be null
     * @param smsVariables the values for the SMS template's variables (MSG91 names, e.g. SALON), at most 30
     *                     characters each; the SMS text itself is the DLT-approved template
     * @param text   the same title and body as a text of the notices/*.properties files (key and values), so a
     *               person who reads another language gets it in theirs (see {@link NoticeTexts}); null: everyone
     *               gets title and body as they are. title and body stay the English: what English readers get,
     *               and what anyone gets when their language has no such text
     */
    record Notice(UUID userId, String type, UUID refId, Instant sendAt, String title, String body, String url,
                  Map<String, String> smsVariables, UUID aboutUserId, Text text) {

        /** A notice that carries only the receiver's own details. */
        public Notice(UUID userId, String type, UUID refId, Instant sendAt, String title, String body, String url,
                      Map<String, String> smsVariables) {
            this(userId, type, refId, sendAt, title, body, url, smsVariables, null, null);
        }

        public Notice(UUID userId, String type, UUID refId, Instant sendAt, String title, String body, String url,
                      Map<String, String> smsVariables, UUID aboutUserId) {
            this(userId, type, refId, sendAt, title, body, url, smsVariables, aboutUserId, null);
        }

        /** Its own copy of the SMS variables (none = empty), so nobody can change a queued notice. */
        public Notice {
            smsVariables = smsVariables == null ? Map.of() : Map.copyOf(smsVariables);
        }

        /**
         * The same notice, also as the text {@code key} (its "key.title" and "key.body" in notices/*.properties)
         * with these values, for readers of other languages.
         */
        public Notice in(String key, Map<String, ?> values) {
            return new Notice(userId, type, refId, sendAt, title, body, url, smsVariables, aboutUserId,
                    new Text(key, values));
        }

        /** The same notice with another title and body (written in the reader's language), the rest as it was. */
        public Notice withWords(String newTitle, String newBody) {
            return new Notice(userId, type, refId, sendAt, newTitle, newBody, url, smsVariables, aboutUserId, text);
        }

        @Override
        public Map<String, String> smsVariables() {
            return Map.copyOf(smsVariables);   // already immutable: returns the same map, no copying
        }
    }

    /**
     * A text of the notices/*.properties files: {@code key} and the values of its {blanks}. A value is data (a
     * String or a number: names, codes, amounts, a time like "6:30 PM", never translated), another Text (a piece
     * written in the reader's language too), a {@link DayTime}, a {@link Day}, an {@link AndList}, or a List of such
     * values (joined with ", ").
     */
    record Text(String key, Map<String, ?> values) {
        public Text {
            values = values == null ? Map.of() : Map.copyOf(values);
        }

        public static Text of(String key) {
            return new Text(key, Map.of());
        }

        public static Text of(String key, Map<String, ?> values) {
            return new Text(key, values);
        }
    }

    /** "Fri 7 Nov, 4:30 PM" in India time: the day's names in the reader's language, the time as in English. */
    record DayTime(Instant at) {
    }

    /** A date with a java.time pattern ("d MMM yyyy"), its day and month names in the reader's language. */
    record Day(java.time.LocalDate date, String pattern) {
    }

    /** "A", "A and B", "A, B and C" (names as they are; the "and" in the reader's language). */
    record AndList(java.util.List<String> items) {
        public AndList {
            items = java.util.List.copyOf(items);
        }
    }
}
