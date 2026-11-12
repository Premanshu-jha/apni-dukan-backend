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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
                .body(tickets.ask(currentUser.get().userId(), body.app(), body.subject(), body.message()));
    }

    /**
     * @param app     CUSTOMER or PARTNER: which app it was sent from
     * @param subject optional, at most 100 characters
     * @param message 10 to 5,000 characters
     */
    public record Ask(@NotNull TicketSource app, @Size(max = 200) String subject,
                      @NotNull @Size(max = 6000) String message) {
    }
}
