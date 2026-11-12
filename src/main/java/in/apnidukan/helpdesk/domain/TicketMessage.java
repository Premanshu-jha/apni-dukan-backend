package in.apnidukan.helpdesk.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One message of a ticket's conversation.
 * @param seq         the order they came in (a reply checks the support person saw up to here)
 * @param direction   IN (from the person) or OUT (our reply)
 * @param authorId    the support person for OUT; the person's account for IN, when known
 * @param attachments the email's attachments, kept only in the Gmail copy
 * @param senderVerified false for an email whose From couldn't be proven
 */
public record TicketMessage(UUID id, long seq, String direction, UUID authorId, String body, int attachments,
                            boolean senderVerified, Instant createdAt) {
}
