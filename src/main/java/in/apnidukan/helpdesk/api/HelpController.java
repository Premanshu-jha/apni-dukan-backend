package in.apnidukan.helpdesk.api;

import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.helpdesk.domain.TicketSource;
import in.apnidukan.helpdesk.service.TicketService;
import in.apnidukan.helpdesk.service.TicketService.Created;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** "Need help" in the customer and partner apps: a support ticket from the signed-in person. */
@RestController
@RequestMapping("/api/v1/helpdesk")
public class HelpController {

    private final TicketService tickets;
    private final CurrentUserProvider currentUser;

    public HelpController(TicketService tickets, CurrentUserProvider currentUser) {
        this.tickets = tickets;
        this.currentUser = currentUser;
    }

    /**
     * 201 with the ticket's number and the address our emails go to. 403 PROFILE_INCOMPLETE (details.missing) until
     * the email is verified and the name given; 400 VALIDATION_FAILED; 429 TOO_MANY_TICKETS.
     */
    @PostMapping("/tickets")
    public ResponseEntity<Created> ask(@Valid @RequestBody Ask body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(tickets.ask(currentUser.get().userId(), body.app(), body.subject(), body.message(),
                        body.fileIds()));
    }

    /** "My requests": this person's tickets, newest activity first (at most 30). */
    @GetMapping("/tickets")
    public List<TicketService.MyTicket> mine() {
        return tickets.mine(currentUser.get().userId());
    }

    /** One of their own tickets with the conversation. 404 when it isn't theirs. */
    @GetMapping("/tickets/{id}")
    public TicketService.MyDetail one(@PathVariable UUID id) {
        return tickets.myTicket(currentUser.get().userId(), id);
    }

    /** Adds to one of their own tickets (reopens a resolved one). 400 VALIDATION_FAILED; 429 TOO_MANY_MESSAGES. */
    @PostMapping("/tickets/{id}/messages")
    public TicketService.MyDetail add(@PathVariable UUID id, @Valid @RequestBody Add body) {
        return tickets.addToMine(currentUser.get().userId(), id, body.message(), body.fileIds());
    }

    /** @param fileIds photos, videos or PDFs to send with it (POST /files/ticket), at most 5; may be null */
    public record Add(@NotNull @Size(max = 6000) String message, @Size(max = 5) List<UUID> fileIds) {
    }

    /**
     * @param app     CUSTOMER or PARTNER: which app it was sent from
     * @param subject optional, at most 100 characters
     * @param message 10 to 5,000 characters
     */
    public record Ask(@NotNull TicketSource app, @Size(max = 200) String subject,
                      @NotNull @Size(max = 6000) String message, @Size(max = 5) List<UUID> fileIds) {
    }
}
