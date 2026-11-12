package in.apnidukan.helpdesk.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A ticket's row (tickets, V46).
 * @param number         shown as CLZ-1001 ({@link #label})
 * @param requesterId    their account, when known; null for an email from an unknown address
 * @param requesterEmail where replies go; null once they deleted their account
 * @param requesterName  an unknown sender's display name from the email (null for an account: its name is current)
 * @param assigneeId     the support person handling it; null = Unassigned
 */
public record Ticket(UUID id, long number, TicketSource source, TicketStatus status, String subject,
                     UUID requesterId, String requesterEmail, String requesterName, UUID assigneeId,
                     Instant createdAt, Instant lastMessageAt, Instant resolvedAt, long version) {

    /** The prefix of every ticket number, in the apps and in email subjects. */
    public static final String PREFIX = "CLZ-";

    public String label() {
        return label(number);
    }

    public static String label(long number) {
        return PREFIX + number;
    }
}
