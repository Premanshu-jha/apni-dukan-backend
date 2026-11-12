package in.apnidukan.helpdesk.service;

import in.apnidukan.common.jobs.JobLeases;
import in.apnidukan.common.notify.SupportEmails;
import in.apnidukan.helpdesk.repo.TicketStore;
import in.apnidukan.helpdesk.repo.TicketStore.Outgoing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Sends the tickets' emails (acknowledgements, replies) queued with the change that made them, every 15 seconds,
 * on one copy of the backend at a time. A refused or failed send is tried again after 1, 5 and 15 minutes, then
 * marked FAILED, which the ticket shows (support then reaches the person another way). Each email is claimed for 2
 * minutes before sending, so a copy that dies mid-round leaves it to the next round, not to be sent twice by two
 * copies at once. Never logs addresses or text.
 */
@Component
public class TicketEmailJob {

    private static final Logger log = LoggerFactory.getLogger(TicketEmailJob.class);
    /** One email is claimed right before it is sent, for this long (one send takes at most about 20 s). */
    static final Duration CLAIM = Duration.ofMinutes(2);
    /** A round stops starting new sends after this, well inside its 5-minute lease. */
    static final Duration ROUND = Duration.ofMinutes(3);
    /** Wait before the 2nd, 3rd and 4th try; after the 4th it is FAILED. */
    static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5),
            Duration.ofMinutes(15));

    private final TicketStore store;
    private final SupportEmails emails;
    private final JobLeases leases;
    private final Clock clock;
    private final boolean enabled;

    public TicketEmailJob(TicketStore store, SupportEmails emails, JobLeases leases, Clock clock,
                          @Value("${app.jobs.enabled:true}") boolean enabled) {
        this.store = store;
        this.emails = emails;
        this.leases = leases;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 25_000)
    public void scheduled() {
        if (enabled) {
            leases.runExclusively("ticket-emails", Duration.ofMinutes(5), this::runOnce);
        }
    }

    /**
     * One round: sends what is due, one email at a time, each claimed just before it is sent (so no claim runs out
     * while it waits behind others), until none is due or the round's time is up (tests call it directly). Returns how
     * many were sent.
     */
    public int runOnce() {
        int sent = 0;
        long started = System.nanoTime();
        while (System.nanoTime() - started < ROUND.toNanos()) {
            Instant now = clock.instant();
            List<Outgoing> due = store.claimDue(now, now.plus(CLAIM), 1);
            if (due.isEmpty()) {
                break;
            }
            if (send(due.get(0))) {
                sent++;
            }
        }
        return sent;
    }

    private boolean send(Outgoing e) {
        try {
            emails.send(e.id().toString(), e.to(), e.subject(), e.body(), "ticket email");
        } catch (RuntimeException ex) {
            Instant now = clock.instant();
            if (e.attempts() > BACKOFF.size()) {
                if (store.markFailed(e.id())) {
                    store.addEvent(e.ticketId(), null, "EMAIL_FAILED",
                            "Our email \"" + e.subject() + "\" could not be sent", now);
                }
                log.error("Ticket email given up after {} tries: {}", e.attempts(), ex.getClass().getSimpleName());
            } else {
                store.retryAt(e.id(), now.plus(BACKOFF.get(e.attempts() - 1)));
                log.warn("Ticket email failed (try {}), trying again later: {}", e.attempts(),
                        ex.getClass().getSimpleName());
            }
            return false;
        }
        // Sent: recorded outside the failure handling, so a failure to record is never taken for a failed send
        store.markSent(e.id(), clock.instant());
        return true;
    }
}
