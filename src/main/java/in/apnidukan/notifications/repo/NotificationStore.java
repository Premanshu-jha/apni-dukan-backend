package in.apnidukan.notifications.repo;

import in.apnidukan.common.notify.Notifications.Notice;
import in.apnidukan.notifications.domain.Audience;
import in.apnidukan.notifications.domain.Channel;
import in.apnidukan.notifications.domain.InboxItem;
import in.apnidukan.notifications.domain.JobStatus;
import in.apnidukan.notifications.domain.NotificationJob;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
            + " AND split_part(type, '_', 1) = ANY (string_to_array(?, ','))";

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
                    (rs, i) -> inboxItem(rs), userId, at(now), groups(audience), limit);
        }
        return jdbc.query(INBOX_COLUMNS + INBOX + " AND (send_at, id) < (?, ?) ORDER BY send_at DESC, id DESC LIMIT ?",
                (rs, i) -> inboxItem(rs), userId, at(now), groups(audience), at(beforeAt), beforeId, limit);
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
        Long n = jdbc.queryForObject("SELECT count(*) FROM (SELECT 1" + INBOX + " AND read_at IS NULL LIMIT ?) AS unread",
                Long.class, userId, at(now), groups(audience), UNREAD_CAP);
        return n == null ? 0 : n;
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

    /** Marks everything in this app's list read, up to now. */
    public int markAllRead(UUID userId, Audience audience, Instant now) {
        return jdbc.update("UPDATE notification_jobs SET read_at = ? WHERE id IN (SELECT id" + INBOX
                + " AND read_at IS NULL)", at(now), userId, at(now), groups(audience));
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

    /**
     * Deletes this person's salon messages (SALON_*), sent or waiting. A row being sent right now just finds nothing
     * to record its outcome on.
     */
    public int deleteSalonMessages(UUID userId) {
        return jdbc.update("DELETE FROM notification_jobs WHERE user_id = ? AND type LIKE 'SALON\\_%'", userId);
    }

    /** Empties the text of every message this person got or would have got (their account was deleted). */
    public int blankForUser(UUID userId) {
        // Their own messages, and the ones others got about them (a salon told "Rahul booked")
        return jdbc.update("UPDATE notification_jobs SET title = '', body = '', url = NULL, sms_variables = NULL "
                + "WHERE user_id = ? OR about_user_id = ?", userId, userId);
    }

    /**
     * New text for one message type about this subject, on the rows still waiting. Their lease and place in the
     * queue (next_try_at) don't change: a server sending one right now keeps it and records its outcome as usual.
     */
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
     * The outcomes of a whole batch, as two JDBC batches (one round trip each) instead of one statement per message.
     * Each row is changed only while still PENDING and still ours, exactly as {@link #finish} and {@link #retryAt}.
     */
    public void record(List<Outcome> outcomes) {
        List<Object[]> finished = new ArrayList<>();
        List<Object[]> retries = new ArrayList<>();
        for (Outcome o : outcomes) {
            if (o.status() == null) {
                retries.add(new Object[] {at(o.at()), o.id(), at(o.lease())});
            } else {
                finished.add(new Object[] {o.status().name(), at(o.at()), o.id(), at(o.lease())});
            }
        }
        if (!finished.isEmpty()) {
            jdbc.batchUpdate(FINISH, finished);
        }
        if (!retries.isEmpty()) {
            jdbc.batchUpdate(RETRY, retries);
        }
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

    /** SMS variables as a query string (SALON=Sharma%20Hair&TIME=6%3A30%20PM), kept in one text column. */
    static String encode(Map<String, String> variables) {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(variables).forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return sb.toString();
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
