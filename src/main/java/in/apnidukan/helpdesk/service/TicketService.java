package in.apnidukan.helpdesk.service;

import in.apnidukan.common.context.ProfileFacts;
import in.apnidukan.common.context.TeamRole;
import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.files.FileRefs;
import in.apnidukan.common.notify.Notifications;
import in.apnidukan.helpdesk.domain.Ticket;
import in.apnidukan.helpdesk.domain.TicketEvent;
import in.apnidukan.helpdesk.domain.TicketMessage;
import in.apnidukan.helpdesk.domain.TicketSource;
import in.apnidukan.helpdesk.domain.TicketStatus;
import in.apnidukan.helpdesk.repo.TicketStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Support tickets: made from "Need help" in the apps or from an email to the support address; answered on the admin
 * website by the support person who has it (or any super admin). Every new ticket and every reply sends an email
 * (queued in the same transaction, sent by TicketEmailJob), whose subject carries the ticket number, so the person's
 * reply comes back into the same ticket.
 *
 * <p>Who sees what: a support person only the tickets assigned to them (others answer 404); a super admin all of
 * them, Unassigned too, and the counts per person. Field agents and everyone else: 404, as for any page that isn't
 * theirs.
 */
@Service
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);
    /** A list page. */
    static final int PAGE = 50;
    /** "My requests" in the apps: how many. */
    static final int MINE = 30;
    /** Messages one person may add from the apps in an hour. */
    static final int APP_MESSAGES_PER_HOUR = 20;
    /** How a person whose account was deleted is shown. */
    static final String DELETED = "Deleted account";
    /** A message from the app. */
    static final int MESSAGE_MIN = 10;
    /** An email on an account's ticket from an address the account gave up (verified by someone else since). */
    static final String SOMEONE_ELSE = "Someone else (from an email address they no longer use)";
    static final int MESSAGE_MAX = 5000;
    /** A support person's reply. */
    static final int REPLY_MAX = 5000;
    /** Photos, videos and PDFs one message may carry. */
    static final int MAX_FILES = 5;
    /** A ticket's files are deleted this long after it was resolved (its words stay). */
    static final Duration FILES_KEPT = Duration.ofDays(182);
    static final int SUBJECT_MAX = 100;
    /** Opening a ticket again within this long isn't logged again as a profile view. */
    static final Duration VIEW_LOGGED_FOR = Duration.ofMinutes(30);

    private final Notifications notifications;
    private final FileRefs files;
    private final TicketStore store;
    private final TicketAssigner assigner;
    private final UserDirectory users;
    private final List<ProfileFacts> facts;
    private final Clock clock;
    private final int newPerHour;
    private final int emailsPerHour;

    public TicketService(TicketStore store, TicketAssigner assigner, UserDirectory users, List<ProfileFacts> facts,
                         Notifications notifications, FileRefs files,
                         Clock clock, @Value("${app.helpdesk.new-per-hour}") int newPerHour,
                         @Value("${app.helpdesk.emails-per-hour}") int emailsPerHour) {
        if (newPerHour < 1 || emailsPerHour < 1) {
            throw new IllegalStateException("app.helpdesk.new-per-hour and emails-per-hour must be at least 1");
        }
        this.emailsPerHour = emailsPerHour;
        this.store = store;
        this.assigner = assigner;
        this.users = users;
        this.facts = facts;
        this.notifications = notifications;
        this.files = files;
        this.clock = clock;
        this.newPerHour = newPerHour;
    }

    // ---------- from the apps ----------

    /**
     * "Need help" in the customer or partner app: a new ticket from this person, sent to their verified email.
     * 403 PROFILE_INCOMPLETE (details.missing) until their email is verified and their name given; 400
     * VALIDATION_FAILED (message 10 to 5,000 characters, subject at most 100); 429 TOO_MANY_TICKETS after
     * app.helpdesk.new-per-hour new tickets in an hour.
     * @param subject optional; the message's first words when blank
     */
    @Transactional
    public Created ask(UUID userId, TicketSource source, String subject, String message, List<UUID> fileIds) {
        if (source == TicketSource.EMAIL) {
            throw ApiException.badRequest("VALIDATION_FAILED", "app must be CUSTOMER or PARTNER");
        }
        List<UUID> sent = checkFiles(fileIds);
        String text = MessageText.clean("message", message, MESSAGE_MIN, MESSAGE_MAX);
        String typed = subject == null ? "" : MessageText.line("subject", subject, 0, SUBJECT_MAX);
        // A subject of one letter (or none) says nothing: the message's first words instead
        String title = typed.length() < 2 ? MessageText.subjectFrom(text, SUBJECT_MAX) : typed;
        users.requireCompleteProfile(userId);
        String email = users.verifiedEmail(userId).orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN,
                "PROFILE_INCOMPLETE", "Verify your email first.", Map.of("missing", List.of("email"))));
        Instant now = clock.instant();
        assigner.lock();
        // The answer to their send was lost and they sent it again (its files are on a question of theirs already)
        Optional<Ticket> same = sameQuestion(userId, text, sent, now);
        if (same.isPresent()) {
            return new Created(same.get().id(), same.get().label(), email);
        }
        if (store.openedSince(userId, email, now.minus(Duration.ofHours(1))) >= newPerHour) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_TICKETS",
                    "You've sent several requests in the last hour. We'll answer those first; add anything more by "
                            + "replying to our email.");
        }
        Ticket t = open(source, title, userId, email, null, text, 0, null, true, now);
        attach(t, userId, sent);
        return new Created(t.id(), t.label(), email);
    }

    // ---------- from the support inbox ----------

    /**
     * An email to the support address. Ignored (nothing saved, nothing sent): an auto-reply, bounce or our own
     * email, and the same email from the same sender a second time. A reply to one of our emails (its CLZ number in
     * the subject, from the address the ticket's replies go to, or proven from the account's current email) goes into
     * that ticket and opens it again if it was resolved. Anything else is a new ticket, linked to the sender's account
     * only when Cloudflare proved the From (InboundEmail.senderVerified); past app.helpdesk.new-per-hour new tickets
     * in an hour from one sender, it goes into their latest ticket instead (reopened), never a new one. An unproven
     * message is never written as an account's own. Its files kept from the email ({@code fileIds}, stored by
     * InboundMail beforehand) go with its message; when nothing is saved they are deleted after a day as unsent.
     */
    @Transactional
    public Received receive(InboundEmail e, List<Emailed> fileIds) {
        if (e.automated()) {
            log.info("Support email ignored: automated");
            return Received.IGNORED;
        }
        if (store.hasEmail(e.messageId())) {
            return Received.DUPLICATE;
        }
        Instant now = clock.instant();
        assigner.lock();
        if (store.hasEmail(e.messageId())) {
            return Received.DUPLICATE;   // the same email delivered twice at once: the other one saved it first
        }
        // Their account only when Cloudflare proved the From address: a forged From never shows someone's profile
        UUID requesterId = e.senderVerified() ? users.findByVerifiedEmail(e.fromAddress()).orElse(null) : null;
        if (e.ticketNumber() != null) {
            Ticket t = store.lockByNumber(e.ticketNumber()).orElse(null);
            if (t != null && fromRequester(t, e.fromAddress(), requesterId)) {
                // Written as the account's own only when it is proven from that account's verified email now: an
                // address they gave up (and someone else verified since) is never them
                UUID author = e.senderVerified() && t.requesterId() != null && t.requesterId().equals(requesterId)
                        ? t.requesterId() : null;
                if (!incoming(t, author, e.text(), e.attachments(), e.messageId(), e.senderVerified(), now)) {
                    return Received.DUPLICATE;
                }
                attachEmailed(t, fileIds);
                return Received.ADDED;
            }
        }
        if (store.openedSince(requesterId, e.fromAddress(), now.minus(Duration.ofHours(1))) >= newPerHour) {
            // Past the hourly limit: into their latest ticket (opened again if resolved), never a new one
            Ticket latest = store.lockLatestOf(requesterId, e.fromAddress()).orElse(null);
            if (latest == null || latest.requesterEmail() == null) {
                log.warn("Support email dropped: over the hourly limit");
                return Received.IGNORED;
            }
            if (!incoming(latest, requesterId, e.text(), e.attachments(), e.messageId(), e.senderVerified(), now)) {
                return Received.DUPLICATE;
            }
            attachEmailed(latest, fileIds);
            return Received.ADDED;
        }
        Ticket t = open(TicketSource.EMAIL, e.subject().isBlank() ? "(no subject)" : e.subject(), requesterId,
                e.fromAddress(), requesterId == null && !e.fromName().isBlank() ? e.fromName() : null,
                e.text(), e.attachments(), e.messageId(), e.senderVerified(), now);
        attachEmailed(t, fileIds);
        return Received.CREATED;
    }

    /** Whether this email (by InboundEmail.messageId) was saved already: its files needn't even be stored. */
    @Transactional(readOnly = true)
    public boolean hasEmail(String messageId) {
        return store.hasEmail(messageId);
    }

    /**
     * An email's files go with its message (the ticket's newest): ticket files stored from that email (no uploader,
     * not sent yet). One deleted meanwhile is left out: the email is never refused for a file.
     */
    private void attachEmailed(Ticket t, List<Emailed> emailed) {
        if (emailed == null || emailed.isEmpty()) {
            return;
        }
        TicketMessage message = store.lastMessage(t.id()).orElseThrow();
        // A reply quoting our earlier email sends its pasted photos (and a signature banner) again: kept once
        Set<String> had = new HashSet<>(store.fileFingerprints(t.id()));
        int position = 0;
        for (Emailed e : emailed.stream().limit(InboundMail.MAX_FILES).toList()) {
            Optional<FileRefs.FileRef> f = files.find(e.fileId()).filter(x -> "TICKET_FILE".equals(x.purpose())
                    && x.shopId() == null && x.uploadedBy() == null);
            if (f.isEmpty() || had.contains(e.sha256()) || store.fileSent(e.fileId()) || !files.tryKeep(e.fileId())) {
                continue;   // a copy is deleted after a day as unsent
            }
            if (store.addFile(e.fileId(), message.id(), t.id(), position, f.get().contentType(), f.get().sizeBytes(),
                    f.get().name(), e.sha256())) {
                had.add(e.sha256());
                position++;
            }
        }
    }

    /**
     * A file kept from a support email, to go with its message.
     * @param sha256 of the file as it came in the email (before a photo was cleaned): the same photo again is one
     */
    public record Emailed(UUID fileId, String sha256) {
    }

    /**
     * Whether an email from this address may join the ticket: from the address our replies go to, or (proven) from
     * the requester's account's current verified email (they changed it since asking).
     */
    private static boolean fromRequester(Ticket t, String from, UUID fromAccount) {
        if (t.requesterEmail() == null) {
            return false;   // they deleted their account
        }
        return t.requesterEmail().equalsIgnoreCase(from)
                || (t.requesterId() != null && t.requesterId().equals(fromAccount));
    }

    /** A new ticket with its first message, given to someone, its acknowledgement queued. Holds the assigner's locks. */
    private Ticket open(TicketSource source, String subject, UUID requesterId, String email, String name, String text,
                        int attachments, String messageId, boolean verified, Instant now) {
        UUID to = assigner.next(requesterId);
        Ticket t = store.insert(source, subject, requesterId, email, name, to, verified, now);
        store.addMessage(t.id(), "IN", requesterId, text, attachments, messageId, verified, now);
        store.addEvent(t.id(), null, "CREATED", switch (source) {
            case EMAIL -> "By email";
            case CUSTOMER -> "From the customer app";
            case PARTNER -> "From the partner app";
        }, now);
        store.addEvent(t.id(), null, to == null ? "UNASSIGNED" : "ASSIGNED",
                to == null ? "Nobody is in support yet" : "Given to " + name(to), now);
        if (source == TicketSource.EMAIL && store.emailsQueuedSince(now.minus(Duration.ofHours(1))) >= emailsPerHour) {
            // A flood (many senders at once): the ticket is kept, but we don't answer every one by email
            store.addEvent(t.id(), null, "EMAIL_SKIPPED", "Too many emails this hour: no acknowledgement sent", now);
            log.warn("Support acknowledgement skipped: over app.helpdesk.emails-per-hour");
        } else {
            // A From we couldn't prove may be someone else's address: their own words are never quoted back to it
            store.queueEmail(t.id(), email, TicketEmails.ackSubject(t),
                    TicketEmails.ackBody(t, requesterName(t), verified ? text : null), now);
        }
        return t;
    }

    /**
     * The person wrote again: the message is added, the ticket is waiting on us again (reopened if resolved), and
     * goes to someone if nobody who may hold it has it. False when this email was saved already.
     */
    private boolean incoming(Ticket t, UUID authorId, String text, int attachments, String messageId, boolean verified,
                             Instant now) {
        if (store.addMessage(t.id(), "IN", authorId, text, attachments, messageId, verified, now).isEmpty()) {
            return false;
        }
        UUID assignee = t.assigneeId();
        // Coming back to someone no longer in support, or away: it goes to whoever is here with fewest
        if (!assigner.mayReceive(assignee)) {
            assignee = assigner.next(t.requesterId());
            if (!Objects.equals(assignee, t.assigneeId())) {
                store.addEvent(t.id(), null, assignee == null ? "UNASSIGNED" : "ASSIGNED",
                        assignee == null ? "Nobody is in support" : "Given to " + name(assignee), now);
            }
        }
        if (t.status() == TicketStatus.RESOLVED) {
            store.addEvent(t.id(), null, "REOPENED", "They wrote again", now);
        }
        store.update(t.id(), TicketStatus.OPEN, assignee, now, null, now);
        return true;
    }

    // ---------- the admin website ----------

    /**
     * A page of tickets, newest activity first. A support person sees only their own; a super admin everyone's
     * ({@code assignee} narrows it to one person) and the Unassigned view.
     * @param view   ACTIVE (open or waiting; the default), OPEN (waiting on us), WAITING (on them), RESOLVED, or
     *               UNASSIGNED (super admins; open or waiting with nobody)
     * @param before   the previous page's last lastMessageAt, for the next page
     * @param beforeId the previous page's last id (tickets with the same lastMessageAt come after it by id)
     */
    @Transactional(readOnly = true)
    public List<Summary> list(UUID adminId, String view, UUID assignee, Instant before, UUID beforeId) {
        TeamRole role = requireHandles(adminId);
        String v = view == null || view.isBlank() ? "ACTIVE" : view.toUpperCase(Locale.ROOT);
        Set<TicketStatus> statuses = switch (v) {
            case "ACTIVE", "UNASSIGNED" -> EnumSet.of(TicketStatus.OPEN, TicketStatus.WAITING);
            case "OPEN" -> EnumSet.of(TicketStatus.OPEN);
            case "WAITING" -> EnumSet.of(TicketStatus.WAITING);
            case "RESOLVED" -> EnumSet.of(TicketStatus.RESOLVED);
            default -> throw ApiException.badRequest("VALIDATION_FAILED",
                    "view must be ACTIVE, OPEN, WAITING, RESOLVED or UNASSIGNED");
        };
        boolean unassigned = v.equals("UNASSIGNED");
        if (role == TeamRole.SUPPORT) {
            if (unassigned) {
                throw new ApiException(HttpStatus.FORBIDDEN, "SUPER_ADMIN_ONLY", "Only a super admin can do this.");
            }
            assignee = adminId;
        }
        List<Ticket> tickets = store.list(unassigned ? null : assignee, statuses, unassigned, before, beforeId, PAGE);
        Map<UUID, String> names = names(tickets);
        return tickets.stream().map(t -> summary(t, names)).toList();
    }

    /**
     * Finds tickets by what someone quotes: a ticket number ("CLZ-1042" or "1042"), a phone number (their account's
     * tickets) or an email address; or {@code requester}, one account's tickets ("N other tickets from them"). A
     * support person finds only their own. Newest activity first, at most 50. 400 VALIDATION_FAILED for anything else.
     */
    @Transactional(readOnly = true)
    public List<Summary> search(UUID adminId, String q, UUID requester) {
        TeamRole role = requireHandles(adminId);
        UUID only = role == TeamRole.SUPPORT ? adminId : null;
        List<Ticket> found;
        if (requester != null) {
            found = store.search(null, requester, null, only, PAGE);
        } else {
            String text = q == null ? "" : q.strip();
            Long number = text.contains("@") ? null : ticketNumber(text);
            if (number != null) {
                found = store.search(number, null, null, only, PAGE);
            } else if (text.contains("@") && text.length() <= 254) {
                found = store.searchByEmail(text, users.findByVerifiedEmail(text).orElse(null), only, PAGE);
            } else {
                UUID account;
                try {
                    account = users.findByPhone(text).orElse(null);
                } catch (ApiException e) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid",
                            Map.of("q", "type a ticket number (CLZ-1042), a mobile number or an email"));
                }
                found = account == null ? List.of() : store.search(null, account, null, only, PAGE);
            }
        }
        Map<UUID, String> names = names(found);
        return found.stream().map(t -> summary(t, names)).toList();
    }

    /**
     * The ticket number someone typed or pasted: "1042", "CLZ-1042", "clz 1042", "CLZ - 1042", or anywhere in a pasted
     * email subject ("Re: [CLZ-1042] My booking"); null when there is none. A plain scan, no regular expression (one
     * pass, whatever is typed).
     */
    static Long ticketNumber(String text) {
        if (!text.isEmpty() && text.length() <= 9 && text.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return Long.valueOf(text);
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        for (int at = lower.indexOf("clz"); at >= 0; at = lower.indexOf("clz", at + 1)) {
            if (at > 0 && Character.isLetterOrDigit(lower.charAt(at - 1))) {
                continue;   // part of a word ("xclz")
            }
            int i = skipSpaces(lower, at + 3);
            if (i < lower.length() && lower.charAt(i) == '-') {
                i = skipSpaces(lower, i + 1);
            }
            int start = i;
            while (i < lower.length() && i - start < 10 && lower.charAt(i) >= '0' && lower.charAt(i) <= '9') {
                i++;
            }
            boolean ends = i == lower.length() || !Character.isLetterOrDigit(lower.charAt(i));
            if (i > start && i - start <= 9 && ends) {
                return Long.valueOf(lower.substring(start, i));
            }
        }
        return null;
    }

    private static int skipSpaces(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /**
     * Super admins: every open ticket of one person goes to another support person ({@code to}), or (null) to the
     * other support people by the usual rule (someone away or on leave). Returns how many moved. 400 NOT_SUPPORT.
     */
    @Transactional
    public Moved moveAll(UUID superAdminId, UUID from, UUID to) {
        requireSuperAdmin(superAdminId);
        assigner.lock();
        requireSuperAdmin(superAdminId);   // again, under the team lock
        if (to != null && (to.equals(from) || !assigner.mayReceive(to))) {
            throw ApiException.badRequest("NOT_SUPPORT", "Choose another support person (not someone away).");
        }
        int moved = assigner.moveAll(from, to, superAdminId);
        return new Moved(moved, store.notResolvedOf(from));
    }

    /**
     * Opens a resolved ticket again without emailing anyone (closed by mistake). 409 TICKET_CHANGED on a stale
     * {@code version}; NOT_YOURS as for a reply. A ticket not resolved: nothing changes.
     */
    @Transactional
    public Detail reopen(UUID adminId, UUID ticketId, long version) {
        TeamRole role = requireHandles(adminId);
        // The helpdesk lock first (as every change of holder): a reopen and "move all" or someone leaving support
        // never pass each other, and the holder is checked against the team as it is now
        assigner.lock();
        Ticket t = store.lock(ticketId).orElseThrow(() -> ApiException.notFound("Ticket"));
        requireMayAnswer(role, adminId, t);
        if (t.version() != version) {
            throw new ApiException(HttpStatus.CONFLICT, "TICKET_CHANGED",
                    "This ticket changed meanwhile. Check it again, then choose.");
        }
        if (t.status() == TicketStatus.RESOLVED) {
            Instant now = clock.instant();
            UUID assignee = t.assigneeId();
            // Resolved by someone no longer in support, or away: it goes to whoever is here with fewest (someone away
            // who opens their own ticket again keeps it: they chose to)
            if (!assigner.mayReceive(assignee) && !(adminId.equals(assignee) && assigner.mayHold(adminId))) {
                assignee = assigner.next(t.requesterId());
                if (!Objects.equals(assignee, t.assigneeId())) {
                    store.addEvent(t.id(), null, assignee == null ? "UNASSIGNED" : "ASSIGNED",
                            assignee == null ? "Nobody is in support" : "Given to " + name(assignee), now);
                }
            }
            // Its activity is now: it comes to the top of the lists, not below two months of others
            store.update(t.id(), TicketStatus.OPEN, assignee, now, null, now);
            store.addEvent(t.id(), adminId, "REOPENED", "Opened again by " + name(adminId) + " (nobody was emailed)",
                    now);
        }
        return get(adminId, ticketId);
    }

    /**
     * "Support replied" in the app's list and as a push to their phones (the email goes too): in the app the
     * question came from (an emailed one: the customer app). Each reply is in the list; a push not sent yet is
     * replaced by the newest (quick replies make one push). Only the ticket number shows: never the reply's or the
     * subject's words (a phone's lock screen; a subject can be the first words of their message).
     */
    private void notifyReplied(Ticket t, boolean resolved, Instant now) {
        if (t.requesterId() == null) {
            return;   // an email from someone without an account: the email is all
        }
        String type = t.source() == TicketSource.PARTNER ? PARTNER_REPLIED : CUSTOMER_REPLIED;
        notifications.cancelPending(t.id(), type);
        notifications.queue(new Notifications.Notice(t.requesterId(), type, t.id(), now,
                resolved ? "Support resolved " + t.label() : "Support replied on " + t.label(),
                (resolved ? "We answered" : "We replied to") + " your question. Tap to read it.",
                "/help/requests/" + t.id(), Map.of()));
    }


    /** The notification types (their first word puts them in the right app's list). */
    static final String CUSTOMER_REPLIED = "CUSTOMERHELP_REPLIED";
    static final String PARTNER_REPLIED = "PARTNERHELP_REPLIED";

    /** Who is away (a super admin: everyone; a support person: themselves). */
    @Transactional(readOnly = true)
    public List<Away> away(UUID adminId) {
        TeamRole role = requireHandles(adminId);
        Map<UUID, Instant> away = role == TeamRole.SUPER_ADMIN ? store.awaySince()
                : store.awaySince(List.of(adminId));
        return away.entrySet().stream().map(e -> new Away(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(Away::since)).toList();
    }

    /**
     * A support person is away (leave, sick), set by a super admin or by themselves: their open tickets go to the
     * others by the usual rule (what nobody else can take stays), and no new ones come to them until they are back.
     * Back: they take their share of what waits in Unassigned. 400 NOT_SUPPORT when that person isn't in support;
     * 404 for someone else's switch (a support person).
     */
    @Transactional
    public AwayChange setAway(UUID adminId, UUID userId, boolean away) {
        TeamRole role = requireHandles(adminId);
        if (role != TeamRole.SUPER_ADMIN && !adminId.equals(userId)) {
            throw ApiException.notFound("Page");
        }
        assigner.lock();
        // Again, under the team lock: a super admin demoted meanwhile no longer sets anyone else's
        TeamRole roleNow = requireHandles(adminId);
        if (roleNow != TeamRole.SUPER_ADMIN && !adminId.equals(userId)) {
            throw ApiException.notFound("Page");
        }
        boolean marked = store.isAway(userId);
        // Being back always works for someone marked (even if they have become a super admin meanwhile)
        if (users.teamRole(userId) != TeamRole.SUPPORT && (away || !marked)) {
            throw ApiException.badRequest("NOT_SUPPORT", "Only a support person can be away.");
        }
        Instant now = clock.instant();
        if (away) {
            store.setAway(userId, now, adminId);
            int moved = assigner.moveAll(userId, null, adminId);
            return new AwayChange(true, moved, store.notResolvedOf(userId));
        }
        store.clearAway(userId);
        if (users.teamRole(userId) == TeamRole.SUPPORT) {
            assigner.joinedSupport(userId, adminId);
        }
        return new AwayChange(false, 0, store.notResolvedOf(userId));
    }

    /** @param since when they were marked away */
    public record Away(UUID userId, Instant since) {
    }

    /**
     * @param moved  their open tickets given to others (marked away)
     * @param stayed open ones they still hold (nobody else could take them)
     */
    public record AwayChange(boolean away, int moved, long stayed) {
    }

    // ---------- the apps' own requests ----------

    /** "My requests" in the apps: this person's tickets, newest activity first (at most 30). */
    @Transactional(readOnly = true)
    public List<MyTicket> mine(UUID userId) {
        return store.ofRequester(userId, MINE).stream().map(MyTicket::of).toList();
    }

    /** One of this person's own tickets with its messages (no internal history). 404 when it isn't theirs. */
    @Transactional(readOnly = true)
    public MyDetail myTicket(UUID userId, UUID ticketId) {
        Ticket t = store.find(ticketId).filter(x -> userId.equals(x.requesterId()))
                .orElseThrow(() -> ApiException.notFound("Ticket"));
        Map<UUID, String> names = new HashMap<>();
        List<TicketMessage> messages = store.messages(t.id());
        Set<UUID> authors = new HashSet<>();
        messages.forEach(m -> addIfSet(authors, m.authorId()));
        users.find(authors).forEach((id, p) -> {
            if (p.name() != null) {
                names.put(id, p.name());
            }
        });
        Map<UUID, List<TicketStore.MessageFile>> attached = store.files(t.id());
        Map<UUID, String> links = links(attached);
        return new MyDetail(MyTicket.of(t), messages.stream()
                .filter(m -> m.direction().equals("OUT") || userId.equals(m.authorId()))
                .map(m -> new MyMessage(m.seq(), m.direction().equals("OUT"),
                        m.direction().equals("OUT") ? firstName(names.get(m.authorId())) : null, m.body(),
                        m.attachments(), m.createdAt(), files(attached.get(m.id()), links))).toList());
    }

    /**
     * Adds to one of this person's own tickets from the app; it waits on us again (reopened if resolved). 404 when
     * it isn't theirs; 400 VALIDATION_FAILED (2 to 5,000 characters); 429 TOO_MANY_MESSAGES past 20 an hour.
     */
    @Transactional
    public MyDetail addToMine(UUID userId, UUID ticketId, String message, List<UUID> fileIds) {
        List<UUID> sent = checkFiles(fileIds);
        // With files the words may be left out ("here is the photo" is optional)
        String text = MessageText.clean("message", message, sent.isEmpty() ? 2 : 0, MESSAGE_MAX);
        // Whose it is, before any lock (who asked never changes): someone else's id never waits on the helpdesk lock
        if (store.find(ticketId).filter(x -> userId.equals(x.requesterId())).isEmpty()) {
            throw ApiException.notFound("Ticket");
        }
        assigner.lock();
        Ticket t = store.lock(ticketId).orElseThrow(() -> ApiException.notFound("Ticket"));
        Instant now = clock.instant();   // after the locks: never earlier than a reply saved while we waited
        // The answer to their send was lost and they sent it again: the same words, still the newest message (nobody
        // answered since), within two minutes, and not resolved since. A "yes" after support's question is new.
        // With files: the same files too (each can be sent once, so sending them again means the same send).
        boolean repeated = t.status() != TicketStatus.RESOLVED && store.lastMessage(t.id())
                .filter(m -> m.direction().equals("IN") && userId.equals(m.authorId()) && m.body().equals(text)
                        && m.createdAt().isAfter(now.minus(Duration.ofMinutes(2)))
                        && sameFiles(store.files(t.id()).get(m.id()), sent))
                .isPresent();
        if (repeated) {
            return myTicket(userId, ticketId);
        }
        if (store.messagesBySince(userId, now.minus(Duration.ofHours(1))) >= APP_MESSAGES_PER_HOUR) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_MESSAGES",
                    "You've written a lot in the last hour. We'll read those first.");
        }
        incoming(t, userId, text, 0, null, true, now);
        attach(t, userId, sent);
        return myTicket(userId, ticketId);
    }

    /** Whether a message carries exactly these files (none when there are none). */
    private static boolean sameFiles(List<TicketStore.MessageFile> on, List<UUID> sent) {
        Set<UUID> had = on == null ? Set.of() : on.stream().map(TicketStore.MessageFile::fileId)
                .collect(java.util.stream.Collectors.toSet());
        return had.equals(Set.copyOf(sent));
    }

    /**
     * Their question asked a moment ago with these same words and files (the answer was lost and they sent it
     * again): only with files, which can go with one message each, so a second send can only be the same one.
     */
    private Optional<Ticket> sameQuestion(UUID userId, String text, List<UUID> sent, Instant now) {
        if (sent.isEmpty()) {
            return Optional.empty();
        }
        return store.sentWith(sent.get(0)).flatMap(w -> store.find(w.ticketId())
                .filter(t -> userId.equals(t.requesterId()))
                .flatMap(t -> store.messages(t.id()).stream().findFirst()
                        .filter(m -> m.id().equals(w.messageId()) && userId.equals(m.authorId())
                                && m.body().equals(text) && m.createdAt().isAfter(now.minus(Duration.ofMinutes(2)))
                                && sameFiles(store.files(t.id()).get(m.id()), sent))
                        .map(m -> t)));
    }

    /** The file ids a message comes with: at most {@value #MAX_FILES}, each once. 400 VALIDATION_FAILED. */
    private static List<UUID> checkFiles(List<UUID> fileIds) {
        List<UUID> ids = fileIds == null ? List.of() : List.copyOf(fileIds);
        if (ids.size() > MAX_FILES || new HashSet<>(ids).size() != ids.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid",
                    Map.of("fileIds", "at most " + MAX_FILES + " files, each once"));
        }
        return ids;
    }

    /**
     * The files go with the message just added (the ticket's newest): each must be a ticket file this person sent
     * and not on a message yet. It is then kept (taken off the deletion queue). 400 FILE_NOT_FOUND (gone after a
     * day unsent, or someone else's), 409 FILE_ALREADY_SENT, 409 UPLOAD_GONE (deleted at this very moment).
     */
    private void attach(Ticket t, UUID authorId, List<UUID> fileIds) {
        if (fileIds.isEmpty()) {
            return;
        }
        TicketMessage message = store.lastMessage(t.id()).orElseThrow();
        int position = 0;
        for (UUID id : fileIds) {
            FileRefs.FileRef f = files.find(id)
                    .filter(x -> "TICKET_FILE".equals(x.purpose()) && x.shopId() == null
                            && authorId.equals(x.uploadedBy()))
                    .orElseThrow(TicketService::fileGone);
            if (store.fileSent(id)) {
                throw alreadySent(f);
            }
            try {
                files.keep(id);
            } catch (ApiException e) {
                if ("UPLOAD_GONE".equals(e.getCode())) {   // deleted (a day unsent) at this very moment
                    throw fileGone();
                }
                throw e;
            }
            if (!store.addFile(id, message.id(), t.id(), position++, f.contentType(), f.sizeBytes(), f.name())) {
                throw alreadySent(f);   // sent with another message at the same moment
            }
        }
    }

    private static ApiException fileGone() {
        return ApiException.badRequest("FILE_NOT_FOUND",
                "A file you added is no longer there (files not sent within a day are deleted). Remove it and add it "
                        + "again.");
    }

    private static ApiException alreadySent(FileRefs.FileRef f) {
        String which = f.name() == null ? "A file you added" : "\"" + f.name() + "\"";
        return new ApiException(HttpStatus.CONFLICT, "FILE_ALREADY_SENT",
                which + " was sent already. Remove it and add it again to send it once more.");
    }

    /** Links (valid at least 15 minutes) for the files still kept. */
    private Map<UUID, String> links(Map<UUID, List<TicketStore.MessageFile>> attached) {
        List<UUID> kept = attached.values().stream().flatMap(List::stream).filter(f -> f.removedAt() == null)
                .map(TicketStore.MessageFile::fileId).toList();
        return kept.isEmpty() ? Map.of() : files.links(kept);
    }

    private static List<MessageFile> files(List<TicketStore.MessageFile> of, Map<UUID, String> links) {
        if (of == null) {
            return List.of();
        }
        return of.stream().map(f -> new MessageFile(f.fileId(), f.contentType(), f.sizeBytes(), f.name(),
                links.get(f.fileId()), f.removedAt() != null || !links.containsKey(f.fileId()))).toList();
    }

    private static String firstName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        int space = name.strip().indexOf(' ');
        return space > 0 ? name.strip().substring(0, space) : name.strip();
    }

    /** The counts the admin website shows on its menu: my tickets waiting on us, and (super admins) Unassigned. */
    @Transactional(readOnly = true)
    public Counts counts(UUID adminId) {
        TeamRole role = requireHandles(adminId);
        // Two small index lookups (asked every 30 seconds by every open admin page), not a count over every ticket
        return new Counts(store.openOf(adminId), role == TeamRole.SUPER_ADMIN ? store.unassignedCount() : 0);
    }

    /**
     * One ticket with its conversation, history and the person's profile (each module's facts about them). Opening
     * it logs PROFILE_VIEWED (once per person per half hour). 404 for a support person when it isn't theirs.
     */
    @Transactional
    public Detail get(UUID adminId, UUID ticketId) {
        TeamRole role = requireHandles(adminId);
        Ticket t = store.find(ticketId).orElseThrow(() -> ApiException.notFound("Ticket"));
        if (role == TeamRole.SUPPORT && !adminId.equals(t.assigneeId())) {
            throw ApiException.notFound("Ticket");
        }
        Instant now = clock.instant();
        List<ProfileFacts.Section> profile = List.of();
        if (t.requesterId() != null) {
            profile = facts.stream().map(f -> f.about(t.requesterId())).filter(Objects::nonNull)
                    .sorted(Comparator.comparingInt(ProfileFacts.Section::order)).toList();
            if (!store.viewedSince(t.id(), adminId, now.minus(VIEW_LOGGED_FOR))) {
                store.addEvent(t.id(), adminId, "PROFILE_VIEWED", null, now);
            }
        }
        List<TicketMessage> messages = store.messages(t.id());
        List<TicketEvent> events = store.events(t.id());
        Set<UUID> people = new HashSet<>();
        messages.forEach(m -> addIfSet(people, m.authorId()));
        events.forEach(e -> addIfSet(people, e.actorId()));
        addIfSet(people, t.assigneeId());
        addIfSet(people, t.requesterId());
        Map<UUID, String> names = names(people);
        Map<String, Long> emails = store.emailCounts(t.id());
        // A support person's search by this account shows only their own: the count says the same
        long earlier = t.requesterId() == null ? 0 : Math.max(0, (role == TeamRole.SUPPORT
                ? store.countByRequester(t.requesterId(), adminId) : store.countByRequester(t.requesterId())) - 1);
        Map<UUID, List<TicketStore.MessageFile>> attached = store.files(t.id());
        Map<UUID, String> links = links(attached);
        return new Detail(summary(t, names), t.requesterId(), t.requesterEmail(),
                messages.stream().map(m -> new Message(m.seq(), m.direction(), m.direction().equals("OUT")
                        ? names.getOrDefault(m.authorId(), "Support")
                        : notThem(t, m) && m.senderVerified() ? SOMEONE_ELSE : displayName(t, names),
                        m.body(), m.attachments(), m.senderVerified() && !notThem(t, m), m.createdAt(),
                        files(attached.get(m.id()), links))).toList(),
                events.stream().map(e -> new Event(e.event(), e.actorId() == null ? null
                        : names.getOrDefault(e.actorId(), DELETED), e.detail(), e.at())).toList(),
                profile, earlier, emails.getOrDefault("PENDING", 0L), emails.getOrDefault("FAILED", 0L),
                store.lastIncomingSeq(t.id()), t.version(), t.senderVerified());
    }

    /**
     * Answers the person by email (and keeps the answer on the ticket): the ticket then waits on them, or is
     * resolved. 409 NEW_MESSAGE when they wrote again after {@code seenSeq} (read it first); 409 NOT_YOURS when it
     * was given to someone else meanwhile; 409 REQUESTER_GONE when they deleted their account (no address to send
     * to); 400 VALIDATION_FAILED (1 to 5,000 characters).
     */
    @Transactional
    public Detail reply(UUID adminId, UUID ticketId, long seenSeq, String body, boolean resolve, List<UUID> fileIds) {
        TeamRole role = requireHandles(adminId);
        List<UUID> sent = checkFiles(fileIds);
        String text = MessageText.clean("body", body, sent.isEmpty() ? 1 : 0, REPLY_MAX);
        Ticket t = store.lock(ticketId).orElseThrow(() -> ApiException.notFound("Ticket"));
        requireMayAnswer(role, adminId, t);
        // An account's ticket goes to its current verified email (they may have changed it since asking)
        String to = t.requesterEmail() == null ? null
                : t.requesterId() == null ? t.requesterEmail() : users.verifiedEmail(t.requesterId()).orElse(t.requesterEmail());
        if (to == null) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUESTER_GONE",
                    "They deleted their account, so there is no address to send a reply to. Close the ticket instead.");
        }
        if (!sent.isEmpty() && t.requesterId() == null) {
            throw ApiException.badRequest("FILES_NEED_APP",
                    "They wrote by email and have no Clozest account, so files can't reach them. Describe it in words.");
        }
        if (store.lastIncomingSeq(t.id()) > seenSeq) {
            throw new ApiException(HttpStatus.CONFLICT, "NEW_MESSAGE",
                    "They wrote again while you were typing. Read their new message, then send your reply.");
        }
        Instant now = clock.instant();
        // A super admin answering Unassigned takes it (never their own question)
        UUID assignee = t.assigneeId() == null && !adminId.equals(t.requesterId()) ? adminId : t.assigneeId();
        store.addMessage(t.id(), "OUT", adminId, text, 0, null, true, now);
        attach(t, adminId, sent);
        store.update(t.id(), resolve ? TicketStatus.RESOLVED : TicketStatus.WAITING, assignee, now,
                resolve ? now : null, now);
        if (!Objects.equals(assignee, t.assigneeId())) {
            store.addEvent(t.id(), adminId, "ASSIGNED", "Taken by " + name(adminId), now);
        }
        store.addEvent(t.id(), adminId, resolve ? "RESOLVED" : "REPLIED", resolve ? "With a reply" : null, now);
        UserDirectory.Person me = users.find(List.of(adminId)).get(adminId);
        // Signed with the support person's name only (never their phone)
        // The files themselves stay in the app (an email link would expire): the email says where they are
        String emailed = sent.isEmpty() ? text : (text.isBlank() ? "" : text + "\n\n") + "(We sent " + sent.size()
                + (sent.size() == 1 ? " file" : " files") + " with this reply. "
                + "See them in the Clozest app: Need help, then My requests.)";
        store.queueEmail(t.id(), to, TicketEmails.replySubject(t), TicketEmails.replyBody(t, requesterName(t), emailed,
                resolve, me == null ? null : me.name(), lastIncoming(t)), now);
        notifyReplied(t, resolve, now);
        return get(adminId, ticketId);
    }

    /**
     * Resolves without emailing anyone (spam, a mistaken email, a person who deleted their account). 409 NOT_YOURS
     * as for a reply; 409 NEW_MESSAGE when they wrote after {@code seenSeq} (read it first); already resolved: nothing
     * changes.
     */
    @Transactional
    public Detail close(UUID adminId, UUID ticketId, long seenSeq, Long version) {
        TeamRole role = requireHandles(adminId);
        Ticket t = store.lock(ticketId).orElseThrow(() -> ApiException.notFound("Ticket"));
        requireMayAnswer(role, adminId, t);
        // Changed meanwhile (opened again by someone else, given to another): look again first. Older admin pages
        // send no version and are checked only for new messages.
        if (version != null && t.version() != version) {
            throw new ApiException(HttpStatus.CONFLICT, "TICKET_CHANGED",
                    "This ticket changed meanwhile. Check it again, then choose.");
        }
        if (store.lastIncomingSeq(t.id()) > seenSeq) {
            throw new ApiException(HttpStatus.CONFLICT, "NEW_MESSAGE",
                    "They wrote again. Read their new message before closing the ticket.");
        }
        if (t.status() != TicketStatus.RESOLVED) {
            Instant now = clock.instant();
            store.update(t.id(), TicketStatus.RESOLVED,
                    t.assigneeId() == null && !adminId.equals(t.requesterId()) ? adminId : t.assigneeId(),
                    t.lastMessageAt(), now, now);
            store.addEvent(t.id(), adminId, "RESOLVED", "Closed without a reply", now);
        }
        return get(adminId, ticketId);
    }

    /**
     * Super admins: gives the ticket to another support person or super admin (null = Unassigned). 409
     * TICKET_CHANGED when {@code version} isn't the current one (someone changed it meanwhile); 400 NOT_SUPPORT when
     * that person can't hold tickets.
     */
    @Transactional
    public Detail assign(UUID superAdminId, UUID ticketId, long version, UUID assigneeId) {
        requireSuperAdmin(superAdminId);
        assigner.lock();
        requireSuperAdmin(superAdminId);   // again, under the team lock: demoted meanwhile
        if (assigneeId != null && !assigner.mayHold(assigneeId)) {
            throw ApiException.badRequest("NOT_SUPPORT", "That person isn't in support. Choose a support person.");
        }
        if (assigneeId != null && !assigner.mayReceive(assigneeId)) {
            // It would leave them on the customer's next message anyway: say so now
            throw ApiException.badRequest("AWAY", "That person is away. Choose someone who is here.");
        }
        Ticket t = store.lock(ticketId).orElseThrow(() -> ApiException.notFound("Ticket"));
        if (assigneeId != null && assigneeId.equals(t.requesterId())) {
            throw ApiException.badRequest("OWN_TICKET", "That's their own question: give it to someone else.");
        }
        if (t.version() != version) {
            throw new ApiException(HttpStatus.CONFLICT, "TICKET_CHANGED",
                    "This ticket changed meanwhile. Check it again, then choose.");
        }
        if (!Objects.equals(t.assigneeId(), assigneeId)) {
            Instant now = clock.instant();
            store.reassign(t.id(), assigneeId, now);
            store.addEvent(t.id(), superAdminId, assigneeId == null ? "UNASSIGNED" : "REASSIGNED",
                    (t.assigneeId() == null ? "From Unassigned" : "From " + name(t.assigneeId()))
                            + (assigneeId == null ? " to Unassigned" : " to " + name(assigneeId)), now);
        }
        return get(superAdminId, ticketId);
    }

    /**
     * Super admins: per person who handles tickets (every support person, and anyone else holding some), how many
     * are open, waiting and resolved; and Unassigned's.
     */
    @Transactional(readOnly = true)
    public Stats stats(UUID superAdminId) {
        requireSuperAdmin(superAdminId);
        Map<UUID, Map<TicketStatus, Long>> all = store.countsByAssignee();
        List<UUID> people = new ArrayList<>(users.supportStaff());
        all.keySet().stream().filter(Objects::nonNull).filter(id -> !people.contains(id)).sorted().forEach(people::add);
        Map<UUID, String> found = names(people);
        Set<UUID> support = new HashSet<>(users.supportStaff());
        List<PersonStats> rows = people.stream().map(id -> {
            Map<TicketStatus, Long> c = all.getOrDefault(id, Map.of());
            return new PersonStats(id, found.getOrDefault(id, DELETED),
                    support.contains(id), c.getOrDefault(TicketStatus.OPEN, 0L),
                    c.getOrDefault(TicketStatus.WAITING, 0L), c.getOrDefault(TicketStatus.RESOLVED, 0L));
        }).toList();
        Map<TicketStatus, Long> nobody = all.getOrDefault(null, Map.of());
        return new Stats(rows, nobody.getOrDefault(TicketStatus.OPEN, 0L) + nobody.getOrDefault(TicketStatus.WAITING, 0L));
    }

    // ---------- helpers ----------

    private TeamRole requireHandles(UUID adminId) {
        TeamRole role = users.teamRole(adminId);
        if (!role.handlesTickets()) {
            throw ApiException.notFound("Page");
        }
        return role;
    }

    private void requireSuperAdmin(UUID userId) {
        TeamRole role = requireHandles(userId);
        if (role != TeamRole.SUPER_ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SUPER_ADMIN_ONLY", "Only a super admin can do this.");
        }
    }

    private static void requireMayAnswer(TeamRole role, UUID adminId, Ticket t) {
        if (role == TeamRole.SUPPORT && !adminId.equals(t.assigneeId())) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_YOURS",
                    "This ticket was given to someone else meanwhile.");
        }
    }

    private Summary summary(Ticket t, Map<UUID, String> names) {
        return new Summary(t.id(), t.label(), t.subject(), t.status(), t.source(), displayName(t, names),
                t.assigneeId(), t.assigneeId() == null ? null : names.getOrDefault(t.assigneeId(), DELETED),
                t.createdAt(), t.lastMessageAt());
    }

    /** The account's name; an unknown sender's display name or address; "Deleted account" once forgotten. */
    private static String displayName(Ticket t, Map<UUID, String> names) {
        if (t.requesterId() != null) {
            return names.getOrDefault(t.requesterId(), DELETED);
        }
        if (t.requesterName() != null) {
            return t.requesterName();
        }
        return t.requesterEmail() == null ? DELETED : t.requesterEmail();
    }

    /**
     * The person's latest message, quoted under our reply so they remember what it was about; null when it came from
     * a sender we couldn't prove (never sent on to the real person's inbox).
     */
    private String lastIncoming(Ticket t) {
        List<TicketMessage> all = store.messages(t.id());
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).direction().equals("IN")) {
                return all.get(i).senderVerified() && !notThem(t, all.get(i)) ? all.get(i).body() : null;
            }
        }
        return null;
    }

    /**
     * An email on an account's ticket that isn't written as the account's: from an address they gave up (someone
     * else verified it since), or one we couldn't prove. Never shown or quoted as theirs.
     */
    private static boolean notThem(Ticket t, TicketMessage m) {
        return m.direction().equals("IN") && t.requesterId() != null && m.authorId() == null;
    }

    /** The name to greet the person with in our emails (null: just "Hi,"). */
    private String requesterName(Ticket t) {
        if (t.requesterId() != null) {
            UserDirectory.Person p = users.find(List.of(t.requesterId())).get(t.requesterId());
            return p == null ? null : p.name();
        }
        return t.requesterName();
    }

    private Map<UUID, String> names(List<Ticket> tickets) {
        Set<UUID> ids = new HashSet<>();
        tickets.forEach(t -> {
            addIfSet(ids, t.assigneeId());
            addIfSet(ids, t.requesterId());
        });
        return names(ids);
    }

    /** Name (or phone) of each live person; a deleted account (no name, no phone) is left out: shown as DELETED. */
    private Map<UUID, String> names(java.util.Collection<UUID> ids) {
        Map<UUID, String> out = new HashMap<>();
        users.find(ids).forEach((id, p) -> {
            String shown = p.name() != null ? p.name() : p.phone();
            if (shown != null) {
                out.put(id, shown);
            }
        });
        return out;
    }

    private String name(UUID userId) {
        return names(List.of(userId)).getOrDefault(userId, DELETED);
    }

    private static void addIfSet(Set<UUID> ids, UUID id) {
        if (id != null) {
            ids.add(id);
        }
    }

    /** What happened to a support email. */
    public enum Received {
        CREATED, ADDED, DUPLICATE, IGNORED
    }

    /** @param sentTo the verified email the acknowledgement goes to */
    public record Created(UUID id, String number, String sentTo) {
    }

    /**
     * @param requester who asked: the account's name, an unknown sender's name or address, or "Deleted account"
     * @param assigneeName null when Unassigned
     */
    public record Summary(UUID id, String number, String subject, TicketStatus status, TicketSource source,
                          String requester, UUID assigneeId, String assigneeName, Instant createdAt,
                          Instant lastMessageAt) {
    }

    /**
     * @param requesterId    their account (null for an unknown sender)
     * @param requesterEmail where replies go (null once they deleted their account)
     * @param profile        what each module knows about them (empty for an unknown sender)
     * @param earlierTickets other tickets from the same account
     * @param emailsWaiting  our emails not sent yet (tried again for a while)
     * @param emailsFailed   our emails that could not be sent (tell them some other way)
     * @param lastIncomingSeq send it back with a reply (409 NEW_MESSAGE if they wrote after it)
     * @param version        send it back with an assignment
     * @param senderVerified false when the first email's From couldn't be proven: confirm who it is before acting
     */
    public record Detail(Summary ticket, UUID requesterId, String requesterEmail, List<Message> messages,
                         List<Event> events, List<ProfileFacts.Section> profile, long earlierTickets,
                         long emailsWaiting, long emailsFailed, long lastIncomingSeq, long version,
                         boolean senderVerified) {
        public Detail {
            messages = List.copyOf(messages);
            events = List.copyOf(events);
            profile = List.copyOf(profile);
        }
    }

    /**
     * @param direction IN (from the person) or OUT (our reply)
     * @param senderVerified false for an email whose From couldn't be proven (it may not be from them)
     */
    public record Message(long seq, String direction, String author, String body, int attachments,
                          boolean senderVerified, Instant at, List<MessageFile> files) {
        public Message {
            files = List.copyOf(files);
        }
    }

    /**
     * A photo, video or PDF on a message.
     * @param url     a link that opens it for at least 15 minutes; null once removed
     * @param removed deleted (6 months after the ticket was resolved, or the account was deleted)
     */
    public record MessageFile(UUID id, String contentType, long sizeBytes, String name, String url, boolean removed) {
    }

    /** @param actor null for the system */
    public record Event(String event, String actor, String detail, Instant at) {
    }

    public record Counts(long mineOpen, long unassigned) {
    }

    /**
     * @param moved  how many went to someone else
     * @param stayed how many open ones they still hold (nobody else could take them: their own question, or nobody
     *               else is in support)
     */
    public record Moved(int moved, long stayed) {
    }

    /**
     * A ticket as the person who asked sees it.
     * @param status OPEN (with us), WAITING (we answered), RESOLVED
     */
    public record MyTicket(UUID id, String number, String subject, TicketStatus status, Instant createdAt,
                           Instant lastMessageAt) {
        static MyTicket of(Ticket t) {
            return new MyTicket(t.id(), t.label(), t.subject(), t.status(), t.createdAt(), t.lastMessageAt());
        }
    }

    public record MyDetail(MyTicket ticket, List<MyMessage> messages) {
        public MyDetail {
            messages = List.copyOf(messages);
        }
    }

    /**
     * @param fromSupport our reply (true) or theirs
     * @param supportName the support person's first name on our replies (null when unknown)
     */
    public record MyMessage(long seq, boolean fromSupport, String supportName, String body, int attachments,
                            Instant at, List<MessageFile> files) {
        public MyMessage {
            files = List.copyOf(files);
        }
    }

    public record Stats(List<PersonStats> people, long unassigned) {
        public Stats {
            people = List.copyOf(people);
        }
    }

    /** @param support false for a super admin holding tickets */
    public record PersonStats(UUID userId, String name, boolean support, long open, long waiting, long resolved) {
    }
}
