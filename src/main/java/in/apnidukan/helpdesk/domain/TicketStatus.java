package in.apnidukan.helpdesk.domain;

/**
 * OPEN: waiting for support (new, or the person wrote again). WAITING: support replied and waits for the person.
 * RESOLVED: support answered it; the person replying opens it again.
 */
public enum TicketStatus {
    OPEN,
    WAITING,
    RESOLVED
}
