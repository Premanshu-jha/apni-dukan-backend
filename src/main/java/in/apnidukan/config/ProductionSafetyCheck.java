package in.apnidukan.config;

import in.apnidukan.auth.notify.EmailSender;
import in.apnidukan.auth.notify.LoggingEmailSender;
import in.apnidukan.auth.notify.LoggingSmsSender;
import in.apnidukan.auth.notify.SmsSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * App-level startup check (it looks at several modules, so it lives outside common).
 * On the real server (APP_PRODUCTION=true) the app refuses to start with development-only settings:
 * codes printed in the log (anyone reading logs could sign in as anyone) or the shop app's localhost address in CORS.
 */
@Component
public class ProductionSafetyCheck {

    public ProductionSafetyCheck(@Value("${app.production:false}") boolean production, SmsSender sms,
                                 EmailSender email, @Value("${app.cors.allowed-origins}") String corsOrigins,
                                 @Value("${app.sms.account-moved.enabled:false}") boolean accountMovedSms) {
        check(production, sms, email, corsOrigins, accountMovedSms);
    }

    static void check(boolean production, SmsSender sms, EmailSender email, String corsOrigins,
                      boolean accountMovedSms) {
        if (!production) {
            return;
        }
        if (sms instanceof LoggingSmsSender) {
            throw new IllegalStateException("APP_PRODUCTION=true but SMS_PROVIDER=log: OTPs would be printed in "
                    + "the log instead of sent. Configure a real SMS provider.");
        }
        if (email instanceof LoggingEmailSender) {
            throw new IllegalStateException("APP_PRODUCTION=true but MAIL_ENABLED=false: email codes would be "
                    + "printed in the log. Configure SMTP.");
        }
        if (!accountMovedSms) {
            throw new IllegalStateException("APP_PRODUCTION=true but SMS_ACCOUNT_MOVED_ENABLED=false: the old phone "
                    + "must be told when an account is moved. Add the DLT template and turn it on.");
        }
        if (corsOrigins.contains("localhost") || corsOrigins.contains("127.0.0.1") || corsOrigins.contains("*")) {
            throw new IllegalStateException("APP_PRODUCTION=true but CORS_ALLOWED_ORIGINS allows " + corsOrigins
                    + ". Set it to the shop app's real address.");
        }
    }
}
