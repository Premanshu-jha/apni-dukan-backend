package in.apnidukan.auth.notify;

import in.apnidukan.common.notify.TextMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Only if you choose it (SMS_PROVIDER=log), e.g. when out of MSG91 credit: prints the code in the console instead
 * of sending it. Refused on the real server (see ProductionSafetyCheck).
 */
@Component
@ConditionalOnProperty(name = "app.sms.provider", havingValue = "log")
public class LoggingSmsSender implements SmsSender, TextMessages {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

    @Override
    public void sendOtp(String phone, String code) {
        log.warn("[DEV SMS, not sent] code for {}: {}", phone, code);
    }

    @Override
    public boolean send(String phone, String templateId, java.util.Map<String, String> variables) {
        // Variable names only: values can include a start code
        log.warn("[DEV SMS, not sent] to {}: template {} with {}", phone, templateId, variables.keySet());
        return true;
    }

    @Override
    public void sendAccountMoved(String phone, String newPhoneEnding) {
        log.warn("[DEV SMS, not sent] to {}: account moved to a new number ending {}", phone, newPhoneEnding);
    }
}
