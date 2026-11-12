package in.apnidukan.helpdesk.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * What happened to a ticket: CREATED, ASSIGNED, REASSIGNED, UNASSIGNED, PROFILE_VIEWED, REPLIED, RESOLVED, REOPENED,
 * EMAIL_FAILED.
 * @param actorId who did it; null for the system (an assignment, an email arriving)
 */
public record TicketEvent(long id, UUID actorId, String event, String detail, Instant at) {
}
