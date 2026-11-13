package in.apnidukan.helpdesk.service;

import in.apnidukan.common.context.AccountDeletionListener;
import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.files.FileRefs;
import in.apnidukan.helpdesk.repo.TicketStore;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Someone deleted their account: their tickets stay (support's history) but forget the address and name they gave
 * (also on tickets they emailed from their verified address), the emails waiting to go to them are cancelled, and
 * no email to them keeps its address or text. The photos, videos and PDFs they sent with tickets are deleted ("removed"
 * shows in their place).
 */
@Component
public class HelpdeskAccountDeletion implements AccountDeletionListener {

    private final TicketStore store;
    private final UserDirectory users;
    private final FileRefs files;

    public HelpdeskAccountDeletion(TicketStore store, UserDirectory users, FileRefs files) {
        this.store = store;
        this.users = users;
        this.files = files;
    }

    /** Called before the account's email is wiped, so the tickets they emailed from it are found too. */
    @Override
    public void accountDeleted(UUID userId, Instant now) {
        store.forgetRequester(userId, users.verifiedEmail(userId).orElse(null), now);
        for (UUID fileId : store.filesSentBy(userId)) {
            files.release(fileId);   // the row now, the bytes queued (deleted after this commits)
            store.fileRemoved(fileId, now);
        }
    }
}
