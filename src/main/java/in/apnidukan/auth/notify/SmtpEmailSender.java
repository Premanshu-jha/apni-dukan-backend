package in.apnidukan.auth.notify;

import in.apnidukan.common.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Sends real email over SMTP, the same setup as Vidyavriti (Gmail: smtp.gmail.com:587 with an app password).
 * Sends while the request waits, so if Gmail refuses, the app is told to try again instead of waiting for a code
 * that never comes. Never logs the email body (it contains the code). Used while RESEND_API_KEY is not set
 * (EmailConfig).
 */
public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailSender(JavaMailSender mailSender, String username, String password, String from) {
        if (username.isBlank() || password.isBlank()) {
            throw new IllegalStateException("Email: set MAIL_USERNAME and MAIL_PASSWORD (the Gmail app password, "
                    + "as in Vidyavriti), RESEND_API_KEY to send through Resend, or MAIL_ENABLED=false to print "
                    + "emails in the console.");
        }
        this.mailSender = mailSender;
        // Gmail only sends "from" the signed-in account, so that is the default sender
        this.from = from.isBlank() ? username : from;
    }

    @Override
    public void send(String to, String subject, String body) {
        send(new Email(to, subject, body, from, null, subject, null));
    }

    @Override
    public void send(String to, String subject, String body, String sender, String replyTo) {
        send(new Email(to, subject, body, sender, replyTo, subject, null));
    }

    /**
     * Gmail sends only from its signed-in account, so the sender is ignored here (and there is no idempotency key);
     * the reply address is set. Logs say {@code logAs}.
     */
    @Override
    public void send(Email email) {
        String to = email.to();
        String subject = email.subject();
        String body = email.body();
        String replyTo = email.replyTo();
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        if (replyTo != null) {
            message.setReplyTo(replyTo);
        }
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        try {
            mailSender.send(message);
        } catch (MailException e) {
            log.error("Could not send email ({}): {}", email.logAs(), e.getClass().getSimpleName());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_FAILED",
                    "We couldn't send the email. Please try again in a minute.");
        }
    }
}
