package in.apnidukan.auth.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * DEVELOPMENT: prints the SMS (with the OTP) in the console instead of sending it.
 * Active while app.sms.provider=log (SMS_PROVIDER), the default until MSG91 DLT registration is done.
 */
@Component
@ConditionalOnProperty(name = "app.sms.provider", havingValue = "log", matchIfMissing = true)
public class LoggingSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

    @Override
    public void send(String phone, String message) {
        log.warn("[DEV SMS, not sent] to {}: {}", phone, message);
    }
}
