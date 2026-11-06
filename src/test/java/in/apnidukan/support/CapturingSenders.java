package in.apnidukan.support;

import in.apnidukan.auth.notify.EmailSender;
import in.apnidukan.auth.notify.SmsSender;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Replaces SMS and email sending in tests: keeps the last message per recipient so tests can read the code. */
@TestConfiguration
public class CapturingSenders {

    private static final Pattern CODE = Pattern.compile("(\\d{6})");

    public static final Map<String, String> LAST_SMS = new ConcurrentHashMap<>();
    public static final Map<String, String> LAST_EMAIL = new ConcurrentHashMap<>();
    /** Set to make the email provider refuse (as Resend or SMTP do when down): tests reset it. */
    public static volatile boolean emailDown;

    @Bean
    @Primary
    SmsSender capturingSms() {
        return new SmsSender() {
            @Override
            public void sendOtp(String phone, String code) {
                LAST_SMS.put(phone, "code " + code);
            }

            @Override
            public void sendAccountMoved(String phone, String newPhoneEnding) {
                LAST_SMS.put(phone, "account moved to a new number ending " + newPhoneEnding);
            }
        };
    }

    @Bean
    @Primary
    EmailSender capturingEmail() {
        return (to, subject, body) -> {
            if (emailDown) {
                throw new in.apnidukan.common.error.ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "EMAIL_FAILED", "We couldn't send the email. Please try again in a minute.");
            }
            LAST_EMAIL.put(to, body);
        };
    }

    /** The last message to this recipient; waits up to 3 s, because some messages are sent in the background. */
    public static String messageTo(Map<String, String> box, String recipient) {
        long until = System.nanoTime() + 3_000_000_000L;
        while (System.nanoTime() < until) {
            String message = box.get(recipient);
            if (message != null) {
                return message;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Nothing was sent to " + recipient);
    }

    public static String codeSentTo(Map<String, String> box, String recipient) {
        String message = messageTo(box, recipient);
        Matcher m = CODE.matcher(message);
        if (!m.find()) {
            throw new AssertionError("No 6-digit code in: " + message);
        }
        return m.group(1);
    }
}
