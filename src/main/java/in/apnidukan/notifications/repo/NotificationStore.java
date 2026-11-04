package in.apnidukan.notifications.repo;

import in.apnidukan.common.notify.Notifications.Notice;
import in.apnidukan.notifications.domain.Channel;
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
                        + "sms_variables, send_at, status, attempts, next_try_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)",
                UUID.randomUUID(), n.userId(), channel.name(), n.type(), n.refId(), n.title(), n.body(), n.url(),
                channel == Channel.SMS ? encode(n.smsVariables()) : null, at(sendAt), at(sendAt), at(now));
    }

    public int cancelPending(UUID refId, Instant now) {
        return jdbc.update("UPDATE notification_jobs SET status = 'CANCELLED', finished_at = ? "
                + "WHERE ref_id = ? AND status = 'PENDING'", at(now), refId);
    }

    /** Deletes messages finished (sent, failed, cancelled, no device) before this moment; returns how many. */
    public int deleteFinishedBefore(Instant before) {
        return jdbc.update("DELETE FROM notification_jobs WHERE status <> 'PENDING' AND finished_at < ?", at(before));
    }

    /** Cancels everything still waiting to go to this person (their account was deleted). */
    public int cancelPendingForUser(UUID userId, Instant now) {
        return jdbc.update("UPDATE notification_jobs SET status = 'CANCELLED', finished_at = ? "
                + "WHERE user_id = ? AND status = 'PENDING'", at(now), userId);
    }

    /** Empties the text of every message this person got or would have got (their account was deleted). */
    public int blankForUser(UUID userId) {
        return jdbc.update("UPDATE notification_jobs SET title = '', body = '', url = NULL, sms_variables = NULL "
                + "WHERE user_id = ?", userId);
    }

    /**
     * New text for one message type about this subject, on the rows still waiting. Their lease and place in the
     * queue (next_try_at) don't change: a server sending one right now keeps it and records its outcome as usual.
     */
    public int rewritePending(Notice n) {
        return jdbc.update("UPDATE notification_jobs SET title = ?, body = ?, url = ?, "
                        + "sms_variables = CASE WHEN channel = 'SMS' THEN ? ELSE NULL END "
                        + "WHERE ref_id = ? AND type = ? AND status = 'PENDING'",
                n.title(), n.body(), n.url(), encode(n.smsVariables()), n.refId(), n.type());
    }

    /**
     * Takes up to {@code max} due messages for this server. Each one's next try is pushed back by {@code lease}
     * and its attempt counted in the same statement, so another server (or the next run) won't take it while it is
     * being sent; if this server dies mid-send, the message becomes due again when the lease runs out.
     * SKIP LOCKED: two servers claiming at once each get different rows, never the same one.
     */
    public List<NotificationJob> claimDue(Instant now, int max, Duration lease, int maxAttempts) {
        return jdbc.query("""
                        UPDATE notification_jobs SET next_try_at = ?, attempts = attempts + 1
                        WHERE id IN (SELECT id FROM notification_jobs
                                     WHERE status = 'PENDING' AND next_try_at <= ? AND attempts < ?
                                     ORDER BY next_try_at LIMIT ? FOR UPDATE SKIP LOCKED)
                        RETURNING id, user_id, channel, type, title, body, url, sms_variables, attempts""",
                (rs, i) -> new NotificationJob(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        Channel.valueOf(rs.getString("channel")), rs.getString("type"), rs.getString("title"),
                        rs.getString("body"), rs.getString("url"), decode(rs.getString("sms_variables")),
                        rs.getInt("attempts")),
                at(now.plus(lease)), at(now), maxAttempts, max);
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

    /** Final outcome; only while still PENDING and still ours (lease). A cancel in between wins. */
    public void finish(UUID id, JobStatus status, Instant now, Instant lease) {
        jdbc.update("UPDATE notification_jobs SET status = ?, finished_at = ? WHERE id = ? AND status = 'PENDING' "
                + "AND next_try_at = ?", status.name(), at(now), id, at(lease));
    }

    /** Try again later; only while still PENDING and still ours. */
    public void retryAt(UUID id, Instant when, Instant lease) {
        jdbc.update("UPDATE notification_jobs SET next_try_at = ? WHERE id = ? AND status = 'PENDING' "
                + "AND next_try_at = ?", at(when), id, at(lease));
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
