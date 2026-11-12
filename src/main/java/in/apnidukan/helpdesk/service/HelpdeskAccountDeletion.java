package in.apnidukan.helpdesk.service;

import in.apnidukan.common.context.AccountDeletionListener;
import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.helpdesk.repo.TicketStore;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Someone deleted their account: their tickets stay (support's history) but forget the address and name they gave
 * (also on tickets they emailed from their verified address), the emails waiting to go to them are cancelled, and
 * no email to them keeps its address or text.
 */
@Component
public class HelpdeskAccountDeletion implements AccountDeletionListener {

    private final TicketStore store;
    private final UserDirectory users;

    public HelpdeskAccountDeletion(TicketStore store, UserDirectory users) {
        this.store = store;
        this.users = users;
    }

    /** Called before the account's email is wiped, so the tickets they emailed from it are found too. */
    @Override
    public void accountDeleted(UUID userId, Instant now) {
        store.forgetRequester(userId, users.verifiedEmail(userId).orElse(null), now);
    }
}
