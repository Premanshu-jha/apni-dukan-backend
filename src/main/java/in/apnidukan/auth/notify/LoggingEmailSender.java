package in.apnidukan.auth.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** DEVELOPMENT: prints the email (with its code) in the console. Active while app.mail.enabled=false. */
@Component
@ConditionalOnProperty(name = "app.mail.enabled", havingValue = "false", matchIfMissing = true)
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(String to, String subject, String body) {
        log.warn("[DEV EMAIL, not sent] to {} | {} | {}", to, subject, body.replace('\n', ' '));
    }
}
