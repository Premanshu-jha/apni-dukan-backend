package in.apnidukan.auth.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Only if you choose it (MAIL_ENABLED=false): prints the email (with its code) in the console. Refused in production.
 * Chosen by EmailConfig.
 */
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(String to, String subject, String body) {
        log.warn("[DEV EMAIL, not sent] to {} | {} | {}", to, subject, body.replace('\n', ' '));
    }
}
