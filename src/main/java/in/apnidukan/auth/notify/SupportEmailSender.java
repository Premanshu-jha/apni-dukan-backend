package in.apnidukan.auth.notify;

import in.apnidukan.common.notify.SupportEmails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Support emails (see {@link SupportEmails}) through the same provider as the sign-in codes, from the support
 * address (app.helpdesk.from), with replies going to the support inbox (app.helpdesk.reply-to), where they become
 * part of the ticket again.
 */
@Component
public class SupportEmailSender implements SupportEmails {

    private final EmailSender email;
    private final String from;
    private final String replyTo;

    public SupportEmailSender(EmailSender email, @Value("${app.helpdesk.from}") String from,
                              @Value("${app.helpdesk.reply-to}") String replyTo) {
        if (from.isBlank() || replyTo.isBlank()) {
            throw new IllegalStateException("app.helpdesk.from and app.helpdesk.reply-to must be set");
        }
        this.email = email;
        this.from = from;
        this.replyTo = replyTo;
    }

    @Override
    public void send(String emailId, String to, String subject, String body, String logAs) {
        email.send(new EmailSender.Email(to, subject, body, from, replyTo, logAs, emailId));
    }
}
