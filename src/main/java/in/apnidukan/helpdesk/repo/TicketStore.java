package in.apnidukan.helpdesk.repo;

import in.apnidukan.helpdesk.domain.Ticket;
import in.apnidukan.helpdesk.domain.TicketEvent;
import in.apnidukan.helpdesk.domain.TicketMessage;
import in.apnidukan.helpdesk.domain.TicketSource;
import in.apnidukan.helpdesk.domain.TicketStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The support tickets' tables (V46): tickets, their messages and events, and the emails waiting to be sent. */
@Repository
public class TicketStore {

    private static final String COLUMNS = "id, number, source, status, subject, requester_id, requester_email, "
            + "requester_name, assignee_id, created_at, last_message_at, resolved_at, version, sender_verified";
    private static final RowMapper<Ticket> TICKET = (rs, i) -> new Ticket(rs.getObject("id", UUID.class),
            rs.getLong("number"), TicketSource.valueOf(rs.getString("source")),
            TicketStatus.valueOf(rs.getString("status")), rs.getString("subject"),
            rs.getObject("requester_id", UUID.class), rs.getString("requester_email"),
            rs.getString("requester_name"), rs.getObject("assignee_id", UUID.class),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("last_message_at").toInstant(),
            rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant(),
            rs.getLong("version"), rs.getBoolean("sender_verified"));

    private final JdbcTemplate jdbc;

    public TicketStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Saves a new OPEN ticket and returns it with its number. */
    public Ticket insert(TicketSource source, String subject, UUID requesterId, String requesterEmail,
                         String requesterName, UUID assigneeId, boolean senderVerified, Instant now) {
        UUID id = UUID.randomUUID();
        return jdbc.queryForObject("INSERT INTO tickets (id, source, status, subject, requester_id, requester_email, "
                        + "requester_name, assignee_id, sender_verified, created_at, updated_at, last_message_at) "
                        + "VALUES (?, ?, 'OPEN', ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING " + COLUMNS, TICKET,
                id, source.name(), subject, requesterId, requesterEmail, requesterName, assigneeId, senderVerified,
                ts(now), ts(now), ts(now));
    }

    public Optional<Ticket> find(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM tickets WHERE id = ?", TICKET, id).stream().findFirst();
    }

    /** The ticket, its row locked until the transaction ends. */
    public Optional<Ticket> lock(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM tickets WHERE id = ? FOR UPDATE", TICKET, id).stream()
                .findFirst();
    }

    /** By its number (CLZ-1042 → 1042), its row locked until the transaction ends. */
    public Optional<Ticket> lockByNumber(long number) {
        return jdbc.query("SELECT " + COLUMNS + " FROM tickets WHERE number = ? FOR UPDATE", TICKET, number).stream()
                .findFirst();
    }

    /** Sets what changed and bumps the version. */
    public void update(UUID id, TicketStatus status, UUID assigneeId, Instant lastMessageAt, Instant resolvedAt,
                       Instant now) {
        jdbc.update("UPDATE tickets SET status = ?, assignee_id = ?, last_message_at = ?, resolved_at = ?, "
                        + "updated_at = ?, version = version + 1 WHERE id = ?", status.name(), assigneeId,
                ts(lastMessageAt), ts(resolvedAt), ts(now), id);
    }

    /**
     * Adds a message; returns its seq, or empty when an email with this Message-ID was saved already (the same
     * email delivered twice).
     */
    public Optional<Long> addMessage(UUID ticketId, String direction, UUID authorId, String body, int attachments,
                                     String emailMessageId, boolean senderVerified, Instant now) {
        return jdbc.query("INSERT INTO ticket_messages (id, ticket_id, direction, author_id, body, attachments, "
                        + "email_message_id, sender_verified, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (email_message_id) DO NOTHING RETURNING seq", (rs, i) -> rs.getLong(1),
                UUID.randomUUID(), ticketId, direction, authorId, body, attachments, emailMessageId, senderVerified,
                ts(now)).stream().findFirst();
    }

    /** Whether an email with this Message-ID was saved already. */
    public boolean hasEmail(String emailMessageId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM ticket_messages WHERE email_message_id = ?)", Boolean.class,
                emailMessageId));
    }

    public List<TicketMessage> messages(UUID ticketId) {
        return jdbc.query("SELECT id, seq, direction, author_id, body, attachments, sender_verified, created_at "
                        + "FROM ticket_messages WHERE ticket_id = ? ORDER BY seq", MESSAGE, ticketId);
    }

    private static final RowMapper<TicketMessage> MESSAGE = (rs, i) -> new TicketMessage(
            rs.getObject("id", UUID.class), rs.getLong("seq"), rs.getString("direction"),
            rs.getObject("author_id", UUID.class), rs.getString("body"), rs.getInt("attachments"),
            rs.getBoolean("sender_verified"), rs.getTimestamp("created_at").toInstant());

    /** The seq of the person's latest message (0 when none). */
    public long lastIncomingSeq(UUID ticketId) {
        Long seq = jdbc.queryForObject("SELECT max(seq) FROM ticket_messages WHERE ticket_id = ? AND direction = 'IN'",
                Long.class, ticketId);
        return seq == null ? 0 : seq;
    }

    public void addEvent(UUID ticketId, UUID actorId, String event, String detail, Instant now) {
        jdbc.update("INSERT INTO ticket_events (ticket_id, actor_id, event, detail, at) VALUES (?, ?, ?, ?, ?)",
                ticketId, actorId, event, detail == null ? null : cut(detail, 300), ts(now));
    }

    public List<TicketEvent> events(UUID ticketId) {
        return jdbc.query("SELECT id, actor_id, event, detail, at FROM ticket_events WHERE ticket_id = ? ORDER BY id",
                (rs, i) -> new TicketEvent(rs.getLong("id"), rs.getObject("actor_id", UUID.class),
                        rs.getString("event"), rs.getString("detail"), rs.getTimestamp("at").toInstant()), ticketId);
    }

    /** Whether this person opened the ticket's profile since then (so a page reload isn't logged again). */
    public boolean viewedSince(UUID ticketId, UUID actorId, Instant since) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM ticket_events WHERE ticket_id = ? "
                + "AND actor_id = ? AND event = 'PROFILE_VIEWED' AND at >= ?)", Boolean.class, ticketId, actorId,
                ts(since)));
    }

    /** Open (not resolved) tickets per assignee among these people; missing = 0. */
    public Map<UUID, Long> openCounts(Collection<UUID> assignees) {
        Map<UUID, Long> out = new HashMap<>();
        if (assignees.isEmpty()) {
            return out;
        }
        // The ids as one Postgres array literal ({a,b,c}): UUIDs need no quoting
        String ids = "{" + String.join(",", assignees.stream().map(UUID::toString).toList()) + "}";
        jdbc.query("SELECT assignee_id, count(*) FROM tickets WHERE status <> 'RESOLVED' "
                + "AND assignee_id = ANY (CAST(? AS uuid[])) GROUP BY assignee_id", rs -> {
            out.put(rs.getObject(1, UUID.class), rs.getLong(2));
        }, ids);
        return out;
    }

    /** This person's open tickets, oldest activity first, rows locked (they are being moved). */
    public List<Ticket> lockOpenOf(UUID assigneeId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM tickets WHERE assignee_id = ? AND status <> 'RESOLVED' "
                + "ORDER BY last_message_at, id FOR UPDATE", TICKET, assigneeId);
    }

    /** Open tickets nobody has, oldest first, rows locked. */
    public List<Ticket> lockUnassignedOpen() {
        return jdbc.query("SELECT " + COLUMNS + " FROM tickets WHERE assignee_id IS NULL AND status <> 'RESOLVED' "
                + "ORDER BY created_at, id FOR UPDATE", TICKET);
    }

    /**
     * Tickets found by one thing, newest activity first, at most {@code limit}: their number, the asking account, or
     * the address they wrote from (any case).
     * @param assigneeId only this person's (a support person searches their own); null: anyone's
     */
    public List<Ticket> search(Long number, UUID requesterId, String email, UUID assigneeId, int limit) {
        String sql = number != null ? BY_NUMBER : requesterId != null ? BY_REQUESTER : BY_EMAIL;
        Object key = number != null ? number : requesterId != null ? requesterId : email;
        return jdbc.query(sql, TICKET, key, assigneeId, assigneeId, limit);
    }

    /** The searches: each a fixed query (key, then the optional assignee twice, then the limit). */
    private static final String ONLY_THEIRS = " AND (CAST(? AS uuid) IS NULL OR assignee_id = ?)"
            + " ORDER BY last_message_at DESC, id LIMIT ?";
    private static final String BY_NUMBER = "SELECT " + COLUMNS + " FROM tickets WHERE number = ?" + ONLY_THEIRS;
    private static final String BY_REQUESTER = "SELECT " + COLUMNS + " FROM tickets WHERE requester_id = ?"
            + ONLY_THEIRS;
    private static final String BY_EMAIL = "SELECT " + COLUMNS + " FROM tickets WHERE lower(requester_email) = "
            + "lower(?)" + ONLY_THEIRS;
    private static final String BY_EMAIL_OR_ACCOUNT = "SELECT " + COLUMNS + " FROM tickets WHERE "
            + "(lower(requester_email) = lower(?) OR requester_id = ?)" + ONLY_THEIRS;

    /** This account's own tickets, newest activity first, at most {@code limit} (the apps' "My requests"). */
    public List<Ticket> ofRequester(UUID requesterId, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM tickets WHERE requester_id = ? "
                + "ORDER BY last_message_at DESC, id LIMIT ?", TICKET, requesterId, limit);
    }

    /** Messages this person wrote in the apps since then (a limit on replies from the app). */
    public long messagesBySince(UUID authorId, Instant since) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM ticket_messages WHERE author_id = ? AND direction = 'IN' "
                + "AND created_at >= ?", Long.class, authorId, ts(since));
        return n == null ? 0 : n;
    }

    /** Sets the assignee only (moving tickets between people); bumps the version. */
    public void reassign(UUID id, UUID assigneeId, Instant now) {
        jdbc.update("UPDATE tickets SET assignee_id = ?, updated_at = ?, version = version + 1 WHERE id = ?",
                assigneeId, ts(now), id);
    }

    /**
     * A list for the admin website, newest activity first, at most {@code limit}.
     * @param assigneeId only this person's (null: anyone's)
     * @param statuses   only these
     * @param unassigned only tickets nobody has
     * @param before     the previous page's last ticket's lastMessageAt (the next page starts after it, then after
     *                   {@code beforeId} among tickets with that same time); null for the first page
     */
    public List<Ticket> list(UUID assigneeId, Collection<TicketStatus> statuses, boolean unassigned, Instant before,
                             UUID beforeId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM tickets WHERE status IN (");
        List<Object> args = new ArrayList<>();
        String sep = "";
        for (TicketStatus s : statuses) {
            sql.append(sep).append('?');
            args.add(s.name());
            sep = ", ";
        }
        sql.append(')');
        if (assigneeId != null) {
            sql.append(" AND assignee_id = ?");
            args.add(assigneeId);
        }
        if (unassigned) {
            sql.append(" AND assignee_id IS NULL");
        }
        if (before != null && beforeId != null) {
            // The first condition bounds the index scan; the second picks up after the cursor among equal times
            sql.append(" AND last_message_at <= ? AND (last_message_at < ? OR id > ?)");
            args.add(ts(before));
            args.add(ts(before));
            args.add(beforeId);
        } else if (before != null) {
            sql.append(" AND last_message_at < ?");
            args.add(ts(before));
        }
        sql.append(" ORDER BY last_message_at DESC, id LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), TICKET, args.toArray());
    }

    /** This person's tickets not resolved (waiting on us or on them): what "move all" moves. */
    public long notResolvedOf(UUID assigneeId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM tickets WHERE assignee_id = ? AND status <> 'RESOLVED'",
                Long.class, assigneeId);
        return n == null ? 0 : n;
    }

    /** This person's tickets waiting on us (the menu's count). */
    public long openOf(UUID assigneeId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM tickets WHERE assignee_id = ? AND status = 'OPEN'",
                Long.class, assigneeId);
        return n == null ? 0 : n;
    }

    /** Tickets nobody has, not resolved. */
    public long unassignedCount() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM tickets WHERE assignee_id IS NULL AND status <> 'RESOLVED'",
                Long.class);
        return n == null ? 0 : n;
    }

    /** Emails queued since then, all tickets together (a cap on acknowledgements against floods). */
    public long emailsQueuedSince(Instant since) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM ticket_emails WHERE created_at >= ?", Long.class, ts(since));
        return n == null ? 0 : n;
    }

    /** Per assignee (null key = Unassigned): how many tickets in each status. */
    public Map<UUID, Map<TicketStatus, Long>> countsByAssignee() {
        Map<UUID, Map<TicketStatus, Long>> out = new HashMap<>();
        jdbc.query("SELECT assignee_id, status, count(*) FROM tickets GROUP BY assignee_id, status", rs -> {
            out.computeIfAbsent(rs.getObject(1, UUID.class), k -> new HashMap<>())
                    .put(TicketStatus.valueOf(rs.getString(2)), rs.getLong(3));
        });
        return out;
    }

    /** Tickets this sender (by account, else by email address) opened since then. */
    public long openedSince(UUID requesterId, String requesterEmail, Instant since) {
        Long n = requesterId != null
                ? jdbc.queryForObject("SELECT count(*) FROM tickets WHERE requester_id = ? AND created_at >= ?",
                Long.class, requesterId, ts(since))
                : jdbc.queryForObject("SELECT count(*) FROM tickets WHERE lower(requester_email) = lower(?) "
                + "AND created_at >= ?", Long.class, requesterEmail, ts(since));
        return n == null ? 0 : n;
    }

    /**
     * This sender's most recently active ticket (open ones first), its row locked: their account's, or (no proven
     * account) only one emailed from that address by nobody's account.
     */
    public Optional<Ticket> lockLatestOf(UUID requesterId, String requesterEmail) {
        return (requesterId != null
                ? jdbc.query("SELECT " + COLUMNS + " FROM tickets WHERE requester_id = ? "
                + "ORDER BY (status = 'RESOLVED'), last_message_at DESC LIMIT 1 FOR UPDATE", TICKET, requesterId)
                : jdbc.query("SELECT " + COLUMNS + " FROM tickets WHERE requester_id IS NULL "
                + "AND lower(requester_email) = lower(?) "
                + "ORDER BY (status = 'RESOLVED'), last_message_at DESC LIMIT 1 FOR UPDATE", TICKET,
                requesterEmail)).stream().findFirst();
    }

    /** How many tickets this account opened (shown on a ticket: "3 earlier tickets"). */
    public long countByRequester(UUID requesterId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM tickets WHERE requester_id = ?", Long.class, requesterId);
        return n == null ? 0 : n;
    }

    /** How many of this account's tickets one support person holds (what their search by this account shows). */
    public long countByRequester(UUID requesterId, UUID assigneeId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM tickets WHERE requester_id = ? AND assignee_id = ?",
                Long.class, requesterId, assigneeId);
        return n == null ? 0 : n;
    }

    /**
     * Tickets sent from this address, or (when known) by the account whose verified email it is now: someone who
     * changed their email is still found by the new one. Optionally only one support person's; newest first.
     */
    public List<Ticket> searchByEmail(String email, UUID accountId, UUID assigneeId, int limit) {
        if (accountId == null) {
            return search(null, null, email, assigneeId, limit);
        }
        return jdbc.query(BY_EMAIL_OR_ACCOUNT, TICKET, email, accountId, assigneeId, assigneeId, limit);
    }

    /** The newest message on this ticket, if any (a send repeated after a lost answer is the same as it). */
    public Optional<TicketMessage> lastMessage(UUID ticketId) {
        return jdbc.query("SELECT id, seq, direction, author_id, body, attachments, sender_verified, created_at "
                + "FROM ticket_messages WHERE ticket_id = ? ORDER BY seq DESC LIMIT 1", MESSAGE, ticketId)
                .stream().findFirst();
    }

    /**
     * Someone deleted their account: their address and name are forgotten on their tickets (those of their account,
     * and those they emailed from {@code email} before it was matched to it); the emails to them are cancelled if
     * still waiting, and every email to them loses its address and text. The tickets first: that waits for a reply
     * being saved on one of them right now, so its email is cancelled too.
     */
    public void forgetRequester(UUID requesterId, String email, Instant now) {
        List<UUID> ids = jdbc.queryForList("SELECT id FROM tickets WHERE requester_id = ? OR (requester_id IS NULL "
                        + "AND CAST(? AS VARCHAR) IS NOT NULL AND lower(requester_email) = lower(?)) ORDER BY id FOR UPDATE",
                UUID.class, requesterId, email, email);
        if (ids.isEmpty()) {
            return;
        }
        String in = "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}";
        jdbc.update("UPDATE tickets SET requester_email = NULL, requester_name = NULL, updated_at = ?, "
                + "version = version + 1 WHERE id = ANY (CAST(? AS uuid[]))", ts(now), in);
        jdbc.update("UPDATE ticket_emails SET status = CASE WHEN status = 'PENDING' THEN 'CANCELLED' ELSE status END, "
                + "to_address = '', body = '' WHERE ticket_id = ANY (CAST(? AS uuid[]))", in);
    }

    // ---------- emails to send ----------

    public void queueEmail(UUID ticketId, String to, String subject, String body, Instant now) {
        jdbc.update("INSERT INTO ticket_emails (id, ticket_id, to_address, subject, body, status, next_try_at, "
                + "created_at) VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?)", UUID.randomUUID(), ticketId, to,
                cut(subject, 250), body, ts(now), ts(now));
    }

    /**
     * Claims up to {@code limit} due emails for this round (nobody else's claim still running), until
     * {@code claimUntil}; each claim counts as an attempt. An email whose address was forgotten meanwhile (an account
     * deleted) is never claimed.
     */
    public List<Outgoing> claimDue(Instant now, Instant claimUntil, int limit) {
        return jdbc.query("UPDATE ticket_emails SET claimed_until = ?, attempts = attempts + 1 WHERE id IN ("
                        + "SELECT id FROM ticket_emails WHERE status = 'PENDING' AND next_try_at <= ? AND to_address <> '' "
                        + "AND (claimed_until IS NULL OR claimed_until < ?) ORDER BY next_try_at "
                        + "LIMIT ? FOR UPDATE SKIP LOCKED) RETURNING id, ticket_id, to_address, subject, body, attempts",
                (rs, i) -> new Outgoing(rs.getObject("id", UUID.class), rs.getObject("ticket_id", UUID.class),
                        rs.getString("to_address"), rs.getString("subject"), rs.getString("body"),
                        rs.getInt("attempts")), ts(claimUntil), ts(now), ts(now), limit);
    }

    public void markSent(UUID emailId, Instant now) {
        jdbc.update("UPDATE ticket_emails SET status = 'SENT', sent_at = ?, claimed_until = NULL "
                + "WHERE id = ? AND status = 'PENDING'", ts(now), emailId);
    }

    public void retryAt(UUID emailId, Instant next) {
        jdbc.update("UPDATE ticket_emails SET next_try_at = ?, claimed_until = NULL WHERE id = ? AND status = 'PENDING'",
                ts(next), emailId);
    }

    /** Given up; returns whether it was still waiting (so the failure is noted on the ticket once). */
    public boolean markFailed(UUID emailId) {
        return jdbc.update("UPDATE ticket_emails SET status = 'FAILED', claimed_until = NULL "
                + "WHERE id = ? AND status = 'PENDING'", emailId) == 1;
    }

    /** Emails of this ticket by status (the ticket screen shows waiting and failed ones). */
    public Map<String, Long> emailCounts(UUID ticketId) {
        Map<String, Long> out = new HashMap<>();
        jdbc.query("SELECT status, count(*) FROM ticket_emails WHERE ticket_id = ? GROUP BY status",
                rs -> {
                    out.put(rs.getString(1), rs.getLong(2));
                }, ticketId);
        return out;
    }

    /** An email to send. @param attempts including this one */
    public record Outgoing(UUID id, UUID ticketId, String to, String subject, String body, int attempts) {
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
