package in.apnidukan.config;

import in.apnidukan.auth.service.HumanCheck;
import in.apnidukan.common.web.ClientAddress;

import in.apnidukan.auth.notify.EmailSender;
import in.apnidukan.auth.notify.LoggingEmailSender;
import in.apnidukan.auth.notify.LoggingSmsSender;
import in.apnidukan.auth.notify.ResendEmailSender;
import in.apnidukan.auth.notify.SmtpEmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import in.apnidukan.auth.notify.SmsSender;
import in.apnidukan.notifications.service.LoggingPushSender;
import in.apnidukan.notifications.service.PushSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * App-level startup check (it looks at several modules, so it lives outside common).
 * On the real server (APP_PRODUCTION=true) the app refuses to start with development-only settings:
 * codes printed in the log (anyone reading logs could sign in as anyone), notifications that are only logged, or
 * the shop app's localhost address in CORS.
 */
@Component
public class ProductionSafetyCheck {

    private static final Logger log = LoggerFactory.getLogger(ProductionSafetyCheck.class);

    public ProductionSafetyCheck(@Value("${app.production:false}") boolean production, SmsSender sms,
                                 EmailSender email, @Value("${app.cors.allowed-origins}") String corsOrigins,
                                 @Value("${app.sms.account-moved.enabled:false}") boolean accountMovedSms,
                                 PushSender push, ClientAddress client, HumanCheck humans,
                                 @Value("${spring.flyway.url:}") String migrationUrl,
                                 @Value("${spring.datasource.url:}") String appUrl) {
        check(production, sms, email, corsOrigins, accountMovedSms, push);
        checkSignInAbuse(production, client.configured(), humans.enabled());
        checkMigrationUrl(production, migrationUrl, appUrl);
    }

    /**
     * Migrations need Neon's direct connection (DB_URL_DIRECT): through the pooler (PgBouncer) the "no statement
     * limit" Flyway sets for itself may not stay with its connection, so the role's 5 s default (see the checklist)
     * could cut a migration halfway, and a migration's locks would be held through a pooled connection.
     */
    static void checkMigrationUrl(boolean production, String migrationUrl, String appUrl) {
        if (!production) {
            return;
        }
        if (migrationUrl == null || migrationUrl.isBlank() || migrationUrl.equals(appUrl)
                || migrationUrl.contains("-pooler")) {
            throw new IllegalStateException("APP_PRODUCTION=true but DB_URL_DIRECT is not Neon's direct URL (it is "
                    + "missing, the same as DB_URL, or a -pooler address): migrations must not run through the "
                    + "pooler. Set DB_URL_DIRECT to the connection string without -pooler in the host.");
        }
    }

    /**
     * Sign-in codes cost money, so production needs both guards: the caller's real IP (or every caller shares the
     * proxy's, and one person can use up everyone's limit) and the robot check.
     */
    static void checkSignInAbuse(boolean production, boolean clientIpHeaderSet, boolean robotCheckOn) {
        if (!production) {
            return;
        }
        if (!clientIpHeaderSet) {
            throw new IllegalStateException("APP_PRODUCTION=true but CLIENT_IP_HEADER is not set: every caller would "
                    + "look like the proxy. On Railway set it to X-Forwarded-For; behind Cloudflare's proxy, "
                    + "CF-Connecting-IP.");
        }
        if (!robotCheckOn) {
            throw new IllegalStateException("APP_PRODUCTION=true but TURNSTILE_SECRET_KEY is not set: anyone could "
                    + "script sign-in codes and use up the daily SMS limit. Create a Turnstile widget in Cloudflare "
                    + "and set its secret key.");
        }
    }

    static void check(boolean production, SmsSender sms, EmailSender email, String corsOrigins,
                      boolean accountMovedSms, PushSender push) {
        if (!production) {
            return;
        }
        if (sms instanceof LoggingSmsSender) {
            throw new IllegalStateException("APP_PRODUCTION=true but SMS_PROVIDER=log: OTPs would be printed in "
                    + "the log instead of sent. Configure a real SMS provider.");
        }
        if (email instanceof LoggingEmailSender) {
            throw new IllegalStateException("APP_PRODUCTION=true but MAIL_ENABLED=false: email codes would be "
                    + "printed in the log. Configure Resend (RESEND_API_KEY) or SMTP.");
        }
        if (email instanceof SmtpEmailSender) {
            // Not refused: Gmail works until the Resend account exists; it just can't carry real volume
            log.warn("APP_PRODUCTION=true but email goes through Gmail SMTP (RESEND_API_KEY not set): Gmail sends a "
                    + "few hundred emails a day. Recommended: set RESEND_API_KEY and EMAIL_FROM.");
        }
        if (email instanceof ResendEmailSender resend && resend.usesTestSender()) {
            throw new IllegalStateException("APP_PRODUCTION=true but EMAIL_FROM is Resend's test address: it only "
                    + "reaches the Resend account's own inbox. Verify the app's domain in Resend and send from it.");
        }
        if (!accountMovedSms) {
            throw new IllegalStateException("APP_PRODUCTION=true but SMS_ACCOUNT_MOVED_ENABLED=false: the old phone "
                    + "must be told when an account is moved. Add the DLT template and turn it on.");
        }
        if (push instanceof LoggingPushSender) {
            throw new IllegalStateException("APP_PRODUCTION=true but VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY are not set: "
                    + "booking confirmations and reminders would only be logged. Generate a VAPID key pair once "
                    + "(npx web-push generate-vapid-keys) and set both.");
        }
        if (corsOrigins.contains("localhost") || corsOrigins.contains("127.0.0.1") || corsOrigins.contains("*")) {
            throw new IllegalStateException("APP_PRODUCTION=true but CORS_ALLOWED_ORIGINS allows " + corsOrigins
                    + ". Set it to the real addresses of all three apps, separated by commas: the partner (shop) app, "
                    + "the customer app and the admin website.");
        }
    }
}
