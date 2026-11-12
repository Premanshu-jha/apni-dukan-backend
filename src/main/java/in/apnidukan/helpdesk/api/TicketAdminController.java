package in.apnidukan.helpdesk.api;

import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.helpdesk.service.TicketService;
import in.apnidukan.helpdesk.service.TicketService.Counts;
import in.apnidukan.helpdesk.service.TicketService.Detail;
import in.apnidukan.helpdesk.service.TicketService.Stats;
import in.apnidukan.helpdesk.service.TicketService.Summary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Support tickets on the admin website: support people see and answer their own; super admins everyone's, plus
 * Unassigned, reassigning and the counts per person. Field agents: 404.
 */
@RestController
@RequestMapping("/api/v1/admin/tickets")
public class TicketAdminController {

    private final TicketService tickets;
    private final CurrentUserProvider currentUser;

    public TicketAdminController(TicketService tickets, CurrentUserProvider currentUser) {
        this.tickets = tickets;
        this.currentUser = currentUser;
    }

    /**
     * @param view     ACTIVE (default), OPEN, WAITING, RESOLVED or UNASSIGNED (super admins)
     * @param assignee super admins: one person's tickets
     * @param before   the next page: the previous page's last lastMessageAt, exactly as received
     * @param beforeId and its id
     */
    @GetMapping
    public List<Summary> list(@RequestParam(required = false) String view,
                              @RequestParam(required = false) UUID assignee,
                              @RequestParam(required = false) Instant before,
                              @RequestParam(required = false) UUID beforeId) {
        return tickets.list(me(), view, assignee, before, beforeId);
    }

    /**
     * Finds tickets by a ticket number (CLZ-1042), a mobile number or an email ({@code q}), or one account's
     * ({@code requester}). 400 VALIDATION_FAILED for anything else.
     */
    @GetMapping("/search")
    public List<Summary> search(@RequestParam(required = false) String q,
                                @RequestParam(required = false) UUID requester) {
        return tickets.search(me(), q, requester);
    }

    /** Super admins: every open ticket of one person to another (toUserId), or (null) shared out by the rule. */
    @PostMapping("/move")
    public TicketService.Moved move(@Valid @RequestBody Move body) {
        return tickets.moveAll(me(), body.fromUserId(), body.toUserId());
    }

    @GetMapping("/counts")
    public Counts counts() {
        return tickets.counts(me());
    }

    /** Super admins: open, waiting and resolved per person. */
    @GetMapping("/stats")
    public Stats stats() {
        return tickets.stats(me());
    }

    @GetMapping("/{id}")
    public Detail get(@PathVariable UUID id) {
        return tickets.get(me(), id);
    }

    /** Emails the answer; resolve=true also resolves the ticket. See TicketService.reply for the refusals. */
    @PostMapping("/{id}/reply")
    public Detail reply(@PathVariable UUID id, @Valid @RequestBody Reply body) {
        return tickets.reply(me(), id, body.seenSeq(), body.body(), Boolean.TRUE.equals(body.resolve()));
    }

    /** Resolves without emailing anyone (409 NEW_MESSAGE if they wrote after seenSeq). */
    @PostMapping("/{id}/close")
    public Detail close(@PathVariable UUID id, @Valid @RequestBody Close body) {
        return tickets.close(me(), id, body.seenSeq());
    }

    /** Opens a resolved ticket again without emailing anyone. */
    @PostMapping("/{id}/reopen")
    public Detail reopen(@PathVariable UUID id, @Valid @RequestBody Reopen body) {
        return tickets.reopen(me(), id, body.version());
    }

    /** Super admins: gives it to someone else (assigneeId null = Unassigned). */
    @PostMapping("/{id}/assign")
    public Detail assign(@PathVariable UUID id, @Valid @RequestBody Assign body) {
        return tickets.assign(me(), id, body.version(), body.assigneeId());
    }

    private UUID me() {
        return currentUser.get().userId();
    }

    /** @param seenSeq the ticket's lastIncomingSeq when the reply was written */
    public record Reply(@NotNull Long seenSeq, @NotNull @Size(max = 6000) String body, Boolean resolve) {
    }

    public record Assign(@NotNull Long version, UUID assigneeId) {
    }

    public record Move(@NotNull UUID fromUserId, UUID toUserId) {
    }

    public record Reopen(@NotNull Long version) {
    }

    /** @param seenSeq the ticket's lastIncomingSeq when they chose to close it */
    public record Close(@NotNull Long seenSeq) {
    }
}
