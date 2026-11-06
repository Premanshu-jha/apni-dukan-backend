package in.apnidukan.auth.notify;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Chooses how email goes out, in this order:
 * <ol>
 *   <li>MAIL_ENABLED=false: printed in the console (LoggingEmailSender; your laptop only, refused in production);</li>
 *   <li>RESEND_API_KEY set: through Resend's HTTP API (ResendEmailSender), from EMAIL_FROM;</li>
 *   <li>otherwise Gmail SMTP as before (SmtpEmailSender, MAIL_USERNAME / MAIL_PASSWORD), so nothing changes until the
 *       Resend account exists.</li>
 * </ol>
 */
@Configuration
public class EmailConfig {

    @Bean
    EmailSender emailSender(@Value("${app.mail.enabled:true}") boolean mailEnabled,
                            @Value("${app.email.resend-api-key:}") String resendApiKey,
                            @Value("${app.email.from:}") String resendFrom,
                            ObjectProvider<JavaMailSender> smtp,
                            @Value("${spring.mail.username:}") String smtpUsername,
                            @Value("${spring.mail.password:}") String smtpPassword,
                            @Value("${app.mail.from:}") String smtpFrom) {
        return choose(mailEnabled, resendApiKey, resendFrom,
                () -> new SmtpEmailSender(smtp.getObject(), smtpUsername, smtpPassword, smtpFrom));
    }

    static EmailSender choose(boolean mailEnabled, String resendApiKey, String resendFrom,
                              java.util.function.Supplier<EmailSender> smtp) {
        if (!mailEnabled) {
            return new LoggingEmailSender();
        }
        if (!resendApiKey.isBlank()) {
            return new ResendEmailSender(resendApiKey.trim(), resendFrom, ResendEmailSender.RESEND);
        }
        return smtp.get();
    }
}
