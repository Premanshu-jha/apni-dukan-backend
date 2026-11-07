package in.apnidukan.notifications.repo;

import in.apnidukan.common.notify.MessageText;
import in.apnidukan.common.notify.Notifications.Notice;
import in.apnidukan.notifications.domain.Audience;
import in.apnidukan.notifications.domain.Channel;
import in.apnidukan.notifications.domain.InboxItem;
import in.apnidukan.notifications.domain.JobStatus;
import in.apnidukan.notifications.domain.NotificationJob;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The notification_jobs table (plain SQL: the claim below needs FOR UPDATE SKIP LOCKED and RETURNING). */
@Repository
public class NotificationStore {

    private final JdbcTemplate jdbc;

    public NotificationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Notice n, Channel channel, Instant now) {
        Instant sendAt = n.sendAt().isBefore(now) ? now : n.sendAt();
        jdbc.update("INSERT INTO notification_jobs (id, user_id, channel, type, ref_id, title, body, url, "
                        + "sms_variables, send_at, status, attempts, next_try_at, created_at, about_user_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)",
                UUID.randomUUID(), n.userId(), channel.name(), n.type(), n.refId(), n.title(), n.body(), n.url(),
                channel == Channel.SMS ? encode(n.smsVariables()) : null, at(sendAt), at(sendAt), at(now),
                n.aboutUserId());
    }

    /** One message on one channel for each pair, in batched statements (a salon suspended with 2,000 bookings). */
    public void insertAll(List<Map.Entry<Notice, Channel>> rows, Instant now) {
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO notification_jobs (id, user_id, channel, type, ref_id, title, body, url, "
                        + "sms_variables, send_at, status, attempts, next_try_at, created_at, about_user_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)",
                rows.stream().map(row -> {
                    Notice n = row.getKey();
                    Channel channel = row.getValue();
                    Instant sendAt = n.sendAt().isBefore(now) ? now : n.sendAt();
                    return new Object[] {UUID.randomUUID(), n.userId(), channel.name(), n.type(), n.refId(),
                        n.title(), n.body(), n.url(), channel == Channel.SMS ? encode(n.smsVariables()) : null,
                        at(sendAt), at(sendAt), at(now), n.aboutUserId()};
                }).toList());
    }

    /** cancelPending for many subjects in one statement. */
    public int cancelPendingAll(java.util.Collection<UUID> refIds, Instant now) {
        if (refIds.isEmpty()) {
            return 0;
        }
        return jdbc.update("UPDATE notification_jobs SET status = 'CANCELLED', finished_at = ? "
                + "WHERE ref_id = ANY (?) AND status = 'PENDING'", ps -> {
                    ps.setObject(1, at(now));
                    ps.setArray(2, ps.getConnection().createArrayOf("uuid", refIds.toArray()));
                });
    }

    public int cancelPending(UUID refId, Instant now) {
        return jdbc.update("UPDATE notification_jobs SET status = 'CANCELLED', finished_at = ? "
                + "WHERE ref_id = ? AND status = 'PENDING'", at(now), refId);
    }

    // ---------- the in-app list ----------

    /**
     * What a person's list shows: the PUSH row of each message (every message has exactly one), already due, not
     * blanked, and not cancelled before it was due (one cancelled after it was due may already have been shown), of the types this app shows (by the first word of the type). The SQL is fixed;
     * the app's type groups go in as one comma-separated parameter.
     */
    private static final String INBOX = " FROM notification_jobs WHERE user_id = ? AND channel = 'PUSH'"
            + " AND (status <> 'CANCELLED' OR finished_at >= send_at) AND send_at <= ? AND title <> ''"
            + " AND split_part(type, '_', 1) = ANY (string_to_array(?, ','))"
            // Salon messages of a salon the person left are hidden at once, before SalonMessagePurgeJob deletes them
            // (the person's purge row is read once per query, not per message)
            + " AND NOT (split_part(type, '_', 1) = 'SALON' AND send_at <= COALESCE((SELECT up_to"
            + " FROM salon_message_purges WHERE user_id = ?), '-infinity'::timestamptz))";

    private static final String INBOX_COLUMNS = "SELECT id, type, title, body, url, send_at, read_at";

    private static String groups(Audience audience) {
        return String.join(",", audience.typeGroups());
    }

    private static InboxItem inboxItem(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new InboxItem(rs.getObject("id", UUID.class), rs.getString("type"), rs.getString("title"),
                rs.getString("body"), rs.getString("url"), rs.getObject("send_at", OffsetDateTime.class).toInstant(),
                rs.getObject("read_at") != null);
    }

    /**
     * Newest first, at most limit. With a cursor (the sentAt and id of the last item already shown), the next page:
     * older than it, ties on the same moment broken by id so nothing is skipped or shown twice.
     */
    public List<InboxItem> inbox(UUID userId, Audience audience, Instant now, Instant beforeAt, UUID beforeId,
                                 int limit) {
        if (beforeAt == null || beforeId == null) {
            return jdbc.query(INBOX_COLUMNS + INBOX + " ORDER BY send_at DESC, id DESC LIMIT ?",
                    (rs, i) -> inboxItem(rs), userId, at(now), groups(audience), userId, limit);
        }
        return jdbc.query(INBOX_COLUMNS + INBOX + " AND (send_at, id) < (?, ?) ORDER BY send_at DESC, id DESC LIMIT ?",
                (rs, i) -> inboxItem(rs), userId, at(now), groups(audience), userId, at(beforeAt), beforeId, limit);
    }

    /**
     * The bell's number stops counting here: a busy salon can have thousands of unread messages, and counting them
     * all on every app open would read all those rows. The apps show "99+" from 100 on.
     */
    public static final int UNREAD_CAP = 100;

    /**
     * How many of this app's messages are unread, counted up to {@link #UNREAD_CAP}: the exact number below it,
     * {@code UNREAD_CAP} for that many or more. Still a plain number for the apps, so their contract is unchanged.
     */
    public long unreadCount(UUID userId, Audience audience, Instant now) {
        return unread(userId, audience, now).count();
    }

    /**
     * The unread count (as unreadCount) and when the person's next message waiting for its time (a reminder) becomes
     * due, in one query: until then the list can change only by a write (InboxVersions).
     * @param nextDue null when nothing of theirs is waiting for a later time
     */
    public record Unread(long count, Instant nextDue) {
    }

    public Unread unread(UUID userId, Audience audience, Instant now) {
        return jdbc.queryForObject("SELECT (SELECT count(*) FROM (SELECT 1" + INBOX + " AND read_at IS NULL LIMIT ?) "
                        + "AS unread), (SELECT min(send_at) FROM notification_jobs WHERE user_id = ? AND channel = 'PUSH'"
                        + " AND status = 'PENDING' AND send_at > ?)",
                (rs, i) -> {
                    OffsetDateTime next = rs.getObject(2, OffsetDateTime.class);
                    return new Unread(rs.getLong(1), next == null ? null : next.toInstant());
                }, userId, at(now), groups(audience), userId, UNREAD_CAP, userId, at(now));
    }

    /** Marks these of the person's messages read (only theirs, only once); returns how many changed. */
    public int markRead(UUID userId, List<UUID> ids, Instant now) {
        if (ids.isEmpty()) {
            return 0;
        }
        String joined = String.join(",", ids.stream().map(UUID::toString).toList());
        return jdbc.update("UPDATE notification_jobs SET read_at = ? WHERE user_id = ? AND channel = 'PUSH' "
                + "AND read_at IS NULL AND id = ANY (string_to_array(?, ',')::uuid[])", at(now), userId, joined);
    }

    /**
     * Marks up to {@code max} unread messages of this app's list read, up to now; returns how many. One short
     * statement (picked once: MATERIALIZED), found through ix_notification_jobs_unread; the caller repeats it while
     * slices come back full, so a salon owner with lakhs of unread messages never makes one huge update.
     */
    public int markAllReadSlice(UUID userId, Audience audience, Instant now, int max) {
        return jdbc.update("WITH pick AS MATERIALIZED (SELECT id" + INBOX + " AND read_at IS NULL LIMIT ?) "
                + "UPDATE notification_jobs SET read_at = ? WHERE id IN (SELECT id FROM pick) AND read_at IS NULL",
                userId, at(now), groups(audience), userId, max, at(now));
    }

    /**
     * Deletes up to {@code max} messages that finished (sent, failed, cancelled, no device) before this moment;
     * returns how many. One short statement (Postgres has no DELETE … LIMIT, hence the MATERIALIZED CTE: picked
     * once, so never more than {@code max} rows), found through ix_notification_jobs_finished; the caller repeats it
     * while slices come back full.
     */
    public int deleteFinishedBefore(Instant before, int max) {
        return jdbc.update("WITH slice AS MATERIALIZED (SELECT id FROM notification_jobs "
                + "WHERE status <> 'PENDING' AND finished_at < ? LIMIT ?) "
                + "DELETE FROM notification_jobs WHERE id IN (SELECT id FROM slice)", at(before), max);
    }

    /** Like deleteFinishedBefore, SMS rows only (ix_notification_jobs_sms_finished): they are kept a shorter time. */
    public int deleteFinishedSmsBefore(Instant before, int max) {
        return jdbc.update("WITH slice AS MATERIALIZED (SELECT id FROM notification_jobs "
                + "WHERE status <> 'PENDING' AND channel = 'SMS' AND finished_at < ? LIMIT ?) "
                + "DELETE FROM notification_jobs WHERE id IN (SELECT id FROM slice)", at(before), max);
    }

    /**
     * Cancels everything still waiting to go to this person, and to others about them (a salon told "Rahul
     * booked" whose push is still being retried): their account was deleted, and a blanked message must not go
     * out empty.
     */
    public int cancelPendingForUser(UUID userId, Instant now) {
        return jdbc.update("UPDATE notification_jobs SET status = 'CANCELLED', finished_at = ? "
                + "WHERE (user_id = ? OR about_user_id = ?) AND status = 'PENDING'", at(now), userId, userId);
    }

    // ---------- salon messages of people who left a salon (SalonMessagePurgeJob) ----------

    /**
     * Queues these people's salon messages (SALON_*) sent up to {@code upTo} for deletion; from this moment their
     * lists no longer show them (INBOX reads the row). Queued again, the newer moment counts and the walk starts over.
     */
    public void queueSalonPurges(java.util.Collection<UUID> userIds, Instant upTo) {
        jdbc.update("INSERT INTO salon_message_purges (user_id, up_to, after_id, queued_at) "
                + "SELECT u, ?, '00000000-0000-0000-0000-000000000000', ? FROM unnest(?) AS u "
                + "ON CONFLICT (user_id) DO UPDATE SET up_to = EXCLUDED.up_to, after_id = EXCLUDED.after_id", ps -> {
                    ps.setObject(1, at(upTo));
                    ps.setObject(2, at(upTo));
                    ps.setArray(3, ps.getConnection().createArrayOf("uuid", userIds.toArray()));
                });
    }

    /**
     * Their salon messages still waiting are not sent (a reminder of a salon they left). Few rows: found through
     * ix_notification_jobs_user_pending.
     */
    public int cancelPendingSalon(java.util.Collection<UUID> userIds, Instant now) {
        return jdbc.update("UPDATE notification_jobs SET status = 'CANCELLED', finished_at = ? WHERE user_id = ANY (?) "
                + "AND status = 'PENDING' AND type LIKE 'SALON\\_%'", ps -> {
                    ps.setObject(1, at(now));
                    ps.setArray(2, ps.getConnection().createArrayOf("uuid", userIds.toArray()));
                });
    }

    /** A person whose salon messages up to {@code upTo} are being deleted, and the last message id already walked. */
    public record Purge(UUID userId, Instant upTo, UUID afterId) {
    }

    /** The oldest purges still to do. */
    public List<Purge> purgesDue(int max) {
        return jdbc.query("SELECT user_id, up_to, after_id FROM salon_message_purges ORDER BY queued_at, user_id "
                + "LIMIT ?", (rs, i) -> new Purge(rs.getObject(1, UUID.class),
                rs.getObject(2, OffsetDateTime.class).toInstant(), rs.getObject(3, UUID.class)), max);
    }

    /**
     * Deletes the salon messages up to {@code upTo} among the next {@code max} of this person's messages after
     * {@code afterId} (in id order, one range of ix_notification_jobs_user). Returns the last id walked, or empty when
     * none were left. A row being sent right now just finds nothing to record its outcome on.
     */
    public Optional<UUID> purgeSlice(UUID userId, Instant upTo, UUID afterId, int max) {
        List<UUID> last = jdbc.queryForList("""
                WITH slice AS MATERIALIZED (
                    SELECT id FROM notification_jobs WHERE user_id = ? AND id > ? ORDER BY id LIMIT ?),
                gone AS (
                    DELETE FROM notification_jobs WHERE id IN (SELECT id FROM slice)
                    AND type LIKE 'SALON\\_%' AND send_at <= ?)
                SELECT id FROM slice ORDER BY id DESC LIMIT 1""", UUID.class, userId, afterId, max, at(upTo));
        return last.stream().findFirst();
    }

    /** Remembers how far a purge got (only while it wasn't queued again with a newer moment). */
    public void purgeProgress(UUID userId, Instant upTo, UUID afterId) {
        jdbc.update("UPDATE salon_message_purges SET after_id = ? WHERE user_id = ? AND up_to = ?", afterId, userId,
                at(upTo));
    }

    /**
     * Ends the purge if none of those messages is left, and returns true. Otherwise (one written while the walk ran,
     * with an id below where it had got: ids are random) it starts over from the first id. Reads their rows once,
     * only at the end.
     */
    public boolean purgeDone(UUID userId, Instant upTo) {
        int ended = jdbc.update("DELETE FROM salon_message_purges WHERE user_id = ? AND up_to = ? AND NOT EXISTS "
                + "(SELECT 1 FROM notification_jobs WHERE user_id = ? AND type LIKE 'SALON\\_%' AND send_at <= ?)",
                userId, at(upTo), userId, at(upTo));
        if (ended == 0) {
            purgeProgress(userId, upTo, new UUID(0, 0));
        }
        return ended == 1;
    }

    /**
     * Empties the messages others got about this person (a salon told "Ravi booked"), at once: they are other people's
     * lists, and a customer is named in only a few messages per booking.
     */
    public List<UUID> blankAbout(UUID userId) {
        // Returns whose lists changed (their bells' versions move)
        return jdbc.queryForList("WITH blanked AS (UPDATE notification_jobs SET title = '', body = '', url = NULL, "
                + "sms_variables = NULL WHERE about_user_id = ? AND title <> '' RETURNING user_id) "
                + "SELECT DISTINCT user_id FROM blanked", UUID.class, userId);
    }

    /** Their own messages are emptied later, a slice at a time (AccountWipeJob): nobody can open them any more. */
    public void queueWipe(UUID userId, Instant now) {
        jdbc.update("INSERT INTO account_wipes (user_id, queued_at) VALUES (?, ?) ON CONFLICT DO NOTHING",
                userId, at(now));
    }

    /** A person still to wipe, and the last message id already emptied. */
    public record Wipe(UUID userId, UUID afterId) {
    }

    /** The oldest people still to wipe. */
    public List<Wipe> wipesDue(int max) {
        return jdbc.query("SELECT user_id, after_id FROM account_wipes ORDER BY queued_at, user_id LIMIT ?",
                (rs, i) -> new Wipe(rs.getObject("user_id", UUID.class), rs.getObject("after_id", UUID.class)), max);
    }

    /** Remembers how far a wipe got, so the next run carries on from there instead of reading it all again. */
    public void wipeProgress(UUID userId, UUID afterId) {
        jdbc.update("UPDATE account_wipes SET after_id = ? WHERE user_id = ?", afterId, userId);
    }

    /**
     * Empties the next {@code max} of this person's own messages after {@code afterId} (in id order, one range of
     * ix_notification_jobs_user, so each slice reads only its own rows). Returns the last id of the slice, or empty
     * when there were none left.
     */
    public Optional<UUID> wipeSlice(UUID userId, UUID afterId, int max) {
        List<UUID> last = jdbc.queryForList("""
                WITH slice AS MATERIALIZED (
                    SELECT id FROM notification_jobs WHERE user_id = ? AND id > ? ORDER BY id LIMIT ?),
                emptied AS (
                    UPDATE notification_jobs SET title = '', body = '', url = NULL, sms_variables = NULL
                    WHERE id IN (SELECT id FROM slice)
                    AND NOT (title = '' AND body = '' AND url IS NULL AND sms_variables IS NULL))
                SELECT id FROM slice ORDER BY id DESC LIMIT 1""", UUID.class, userId, afterId, max);
        return last.stream().findFirst();
    }

    /**
     * Ends the wipe if none of their messages holds text any more, and returns true. Otherwise (a message added while
     * the wipe ran, with an id below where it had got: ids are random) it starts over from the first id and returns
     * false. Reads all their rows once, only at the end.
     */
    public boolean wipeDone(UUID userId) {
        int ended = jdbc.update("DELETE FROM account_wipes WHERE user_id = ? AND NOT EXISTS (SELECT 1 FROM "
                + "notification_jobs WHERE user_id = ? AND NOT (title = '' AND body = '' AND url IS NULL "
                + "AND sms_variables IS NULL))", userId, userId);
        if (ended == 0) {
            wipeProgress(userId, new UUID(0, 0));
        }
        return ended == 1;
    }

    /**
     * New text for one message type about this subject, on the rows still waiting. Their lease and place in the
     * queue (next_try_at) don't change: a server sending one right now keeps it and records its outcome as usual.
     */
    /** rewritePending for many messages, in batched statements. */
    public void rewritePendingAll(List<Notice> notices) {
        if (notices.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("UPDATE notification_jobs SET title = ?, body = ?, url = ?, read_at = NULL, "
                        + "sms_variables = CASE WHEN channel = 'SMS' THEN ? ELSE NULL END "
                        + "WHERE ref_id = ? AND type = ? AND status = 'PENDING'",
                notices.stream().map(n -> new Object[] {n.title(), n.body(), n.url(), encode(n.smsVariables()),
                    n.refId(), n.type()}).toList());
    }

    public int rewritePending(Notice n) {
        // A new text is a new message: unread again in the app list
        return jdbc.update("UPDATE notification_jobs SET title = ?, body = ?, url = ?, read_at = NULL, "
                        + "sms_variables = CASE WHEN channel = 'SMS' THEN ? ELSE NULL END "
                        + "WHERE ref_id = ? AND type = ? AND status = 'PENDING'",
                n.title(), n.body(), n.url(), encode(n.smsVariables()), n.refId(), n.type());
    }

    /**
     * Takes up to {@code max} due messages for this server. Each one's next try is pushed back by {@code lease}
     * and its attempt counted in the same statement, so another server (or the next run) won't take it while it is
     * being sent; if this server dies mid-send, the message becomes due again when the lease runs out.
     * SKIP LOCKED: two servers claiming at once each get different rows, never the same one. The chosen ids are
     * a MATERIALIZED CTE, so they are picked exactly once: as a plain IN (sub-select), Postgres may run the
     * sub-select again and change more than {@code max} rows.
     */
    public List<NotificationJob> claimDue(Instant now, int max, Duration lease, int maxAttempts) {
        return jdbc.query("""
                        WITH due AS MATERIALIZED (
                            SELECT id FROM notification_jobs
                            WHERE status = 'PENDING' AND next_try_at <= ? AND attempts < ?
                            ORDER BY next_try_at LIMIT ? FOR UPDATE SKIP LOCKED)
                        UPDATE notification_jobs SET next_try_at = ?, attempts = attempts + 1
                        WHERE id IN (SELECT id FROM due)
                        RETURNING id, user_id, channel, type, title, body, url, sms_variables, attempts""",
                (rs, i) -> new NotificationJob(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        Channel.valueOf(rs.getString("channel")), rs.getString("type"), rs.getString("title"),
                        rs.getString("body"), rs.getString("url"), decode(rs.getString("sms_variables")),
                        rs.getInt("attempts")),
                at(now), maxAttempts, max, at(now.plus(lease)));
    }

    /**
     * Messages tried too often without an outcome (the server kept failing before it could record one) are given up
     * as FAILED, so they aren't claimed forever.
     */
    public int giveUp(int maxAttempts, Instant now) {
        return jdbc.update("UPDATE notification_jobs SET status = 'FAILED', finished_at = ? "
                + "WHERE status = 'PENDING' AND attempts >= ? AND next_try_at <= ?", at(now), maxAttempts, at(now));
    }

    /**
     * Just before sending: moves this server's lease on one message from {@code lease} to {@code newLease}. False if
     * the message is no longer ours (another server claimed it after our lease ran out, or it was cancelled): then
     * we must not send it, so a message is never sent twice.
     */
    public boolean renew(UUID id, Instant lease, Instant newLease) {
        return jdbc.update("UPDATE notification_jobs SET next_try_at = ? WHERE id = ? AND status = 'PENDING' "
                + "AND next_try_at = ?", at(newLease), id, at(lease)) == 1;
    }

    /**
     * Gives a claimed message back without trying it (its batch ran out of time, or the server is stopping): the
     * attempt counted at claim is taken back and it is due again at {@code dueAt}. Only while still ours.
     */
    public boolean release(UUID id, Instant lease, Instant dueAt) {
        return jdbc.update("UPDATE notification_jobs SET attempts = attempts - 1, next_try_at = ? WHERE id = ? "
                + "AND status = 'PENDING' AND next_try_at = ?", at(dueAt), id, at(lease)) == 1;
    }

    /** Final outcome; only while still PENDING and still ours (lease). A cancel in between wins. */
    public void finish(UUID id, JobStatus status, Instant now, Instant lease) {
        jdbc.update(FINISH, status.name(), at(now), id, at(lease));
    }

    /** Try again later; only while still PENDING and still ours. */
    public void retryAt(UUID id, Instant when, Instant lease) {
        jdbc.update(RETRY, at(when), id, at(lease));
    }

    private static final String FINISH = "UPDATE notification_jobs SET status = ?, finished_at = ? WHERE id = ? "
            + "AND status = 'PENDING' AND next_try_at = ?";
    private static final String RETRY = "UPDATE notification_jobs SET next_try_at = ? WHERE id = ? "
            + "AND status = 'PENDING' AND next_try_at = ?";

    /**
     * Outcomes of many messages in one statement (one round trip, however many), each row changed only while still
     * PENDING and still ours (lease), exactly as {@link #finish} and {@link #retryAt}: a final status sets status and
     * finished_at, a retry moves next_try_at.
     */
    public void record(List<Outcome> outcomes) {
        if (outcomes.isEmpty()) {
            return;
        }
        Object[] ids = new Object[outcomes.size()];
        Object[] statuses = new Object[outcomes.size()];
        Object[] ats = new Object[outcomes.size()];
        Object[] leases = new Object[outcomes.size()];
        for (int i = 0; i < outcomes.size(); i++) {
            Outcome o = outcomes.get(i);
            ids[i] = o.id();
            statuses[i] = o.status() == null ? null : o.status().name();
            ats[i] = at(o.at()).toString();
            leases[i] = at(o.lease()).toString();
        }
        jdbc.update("""
                UPDATE notification_jobs j SET
                    status = COALESCE(o.status, j.status),
                    finished_at = CASE WHEN o.status IS NULL THEN j.finished_at ELSE o.at::timestamptz END,
                    next_try_at = CASE WHEN o.status IS NULL THEN o.at::timestamptz ELSE j.next_try_at END
                FROM unnest(?::uuid[], ?::text[], ?::text[], ?::text[]) AS o(id, status, at, lease)
                WHERE j.id = o.id AND j.status = 'PENDING' AND j.next_try_at = o.lease::timestamptz""", ps -> {
            ps.setArray(1, ps.getConnection().createArrayOf("uuid", ids));
            ps.setArray(2, ps.getConnection().createArrayOf("text", statuses));
            ps.setArray(3, ps.getConnection().createArrayOf("text", ats));
            ps.setArray(4, ps.getConnection().createArrayOf("text", leases));
        });
    }

    /**
     * renew() for a group of messages about to be sent, in one statement: moves the lease of each that is still
     * ours (still PENDING, lease unchanged) to {@code newLease}; returns those. The others are not ours any more
     * (cancelled, or taken by another server after our lease ran out) and must not be sent.
     */
    public java.util.Set<UUID> renewAll(java.util.Collection<UUID> ids, Instant lease, Instant newLease) {
        if (ids.isEmpty()) {
            return java.util.Set.of();
        }
        Object[] array = ids.toArray();
        return new java.util.HashSet<>(jdbc.query("UPDATE notification_jobs SET next_try_at = ? WHERE id = ANY (?) "
                        + "AND status = 'PENDING' AND next_try_at = ? RETURNING id", ps -> {
                    ps.setObject(1, at(newLease));
                    ps.setArray(2, ps.getConnection().createArrayOf("uuid", array));
                    ps.setObject(3, at(lease));
                }, (rs, i) -> rs.getObject(1, UUID.class)));
    }

    /** release() for many messages in one statement (a batch's deadline passed, or the server is stopping). */
    public void releaseAll(java.util.Collection<UUID> ids, Instant lease, Instant dueAt) {
        if (ids.isEmpty()) {
            return;
        }
        Object[] array = ids.toArray();
        jdbc.update("UPDATE notification_jobs SET attempts = attempts - 1, next_try_at = ? WHERE id = ANY (?) "
                + "AND status = 'PENDING' AND next_try_at = ?", ps -> {
            ps.setObject(1, at(dueAt));
            ps.setArray(2, ps.getConnection().createArrayOf("uuid", array));
            ps.setObject(3, at(lease));
        });
    }

    /**
     * What happened to one claimed message.
     * @param status the final status, or null to try again at {@code at}
     * @param at     when it finished, or (status null) when to try again
     * @param lease  the lease it was sent under (it must still be ours)
     */
    public record Outcome(UUID id, JobStatus status, Instant at, Instant lease) {

        public static Outcome finished(UUID id, JobStatus status, Instant at, Instant lease) {
            return new Outcome(id, status, at, lease);
        }

        public static Outcome retry(UUID id, Instant at, Instant lease) {
            return new Outcome(id, null, at, lease);
        }
    }

    /** SMS variables as a query string (SALON=Sharma+Hair&TIME=6%3A30+PM), kept in one text column (MessageText). */
    static String encode(Map<String, String> variables) {
        return MessageText.smsQuery(variables);
    }

    static Map<String, String> decode(String encoded) {
        Map<String, String> out = new LinkedHashMap<>();
        if (encoded == null || encoded.isEmpty()) {
            return out;
        }
        for (String pair : encoded.split("&")) {
            int eq = pair.indexOf('=');
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
