package in.apnidukan.auth.notify;

import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.notify.TextMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Sends SMS through MSG91's Flow API with DLT-approved templates, the same way as the OMR (ScoreLens) project.
 * Until Apni Dukan has its own DLT registration this uses the existing MSG91 template, so the SMS text is that
 * template's text. Each kind of SMS has its own template:
 * - OTP (MSG91_OTP_TEMPLATE_ID): variable OTP, as in OMR
 * - account moved (MSG91_ACCOUNT_MOVED_TEMPLATE_ID): variable ENDING, e.g.
 *   "Your Apni Dukan account was moved to a new number ending ##ENDING##. Not you? Contact support."
 * The OTP template is required. The account-moved SMS is switched off until its template exists
 * (SMS_ACCOUNT_MOVED_ENABLED=true turns it on, and then its template ID is required too).
 * Never logs the code or the auth key.
 */
@Component
@ConditionalOnProperty(name = "app.sms.provider", havingValue = "msg91", matchIfMissing = true)
public class Msg91SmsSender implements SmsSender, TextMessages {

    private static final Logger log = LoggerFactory.getLogger(Msg91SmsSender.class);

    private final RestClient http;
    private final String authKey;
    private final String otpTemplateId;
    private final String accountMovedTemplateId;
    private final boolean accountMovedEnabled;

    @Autowired
    public Msg91SmsSender(@Value("${app.sms.msg91.base-url:https://control.msg91.com}") String baseUrl,
                          @Value("${app.sms.msg91.auth-key:}") String authKey,
                          @Value("${app.sms.msg91.otp-template-id:}") String otpTemplateId,
                          @Value("${app.sms.msg91.account-moved-template-id:}") String accountMovedTemplateId,
                          @Value("${app.sms.account-moved.enabled:false}") boolean accountMovedEnabled) {
        this(RestClient.builder().requestFactory(timeouts()), baseUrl, authKey, otpTemplateId, accountMovedTemplateId,
                accountMovedEnabled);
    }

    /** For tests: a builder bound to a fake MSG91. */
    Msg91SmsSender(RestClient.Builder builder, String baseUrl, String authKey, String otpTemplateId,
                   String accountMovedTemplateId, boolean accountMovedEnabled) {
        if (authKey.isBlank() || otpTemplateId.isBlank()) {
            throw new IllegalStateException("SMS: set MSG91_AUTH_KEY and MSG91_OTP_TEMPLATE_ID "
                    + "(MSG91 → Templates), or SMS_PROVIDER=log to print SMS in the console.");
        }
        if (accountMovedEnabled && accountMovedTemplateId.isBlank()) {
            throw new IllegalStateException("SMS: SMS_ACCOUNT_MOVED_ENABLED=true needs MSG91_ACCOUNT_MOVED_TEMPLATE_ID.");
        }
        this.accountMovedEnabled = accountMovedEnabled;
        this.http = builder.baseUrl(baseUrl).build();
        this.authKey = authKey;
        this.otpTemplateId = otpTemplateId;
        this.accountMovedTemplateId = accountMovedTemplateId;
    }

    private static SimpleClientHttpRequestFactory timeouts() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }

    @Override
    public void sendOtp(String phone, String code) {
        send(otpTemplateId, Map.of("mobiles", "91" + phone, "OTP", code));
    }

    @Override
    public void sendAccountMoved(String phone, String newPhoneEnding) {
        if (!accountMovedEnabled) {
            log.info("Account-moved SMS is switched off (SMS_ACCOUNT_MOVED_ENABLED); only the email notice was sent");
            return;
        }
        send(accountMovedTemplateId, Map.of("mobiles", "91" + phone, "ENDING", newPhoneEnding));
    }

    /** A booking message from its own template; false (retry later) instead of an error if MSG91 refuses. */
    @Override
    public boolean send(String phone, String templateId, Map<String, String> variables) {
        Map<String, String> recipient = new java.util.HashMap<>(variables);
        recipient.put("mobiles", "91" + phone);
        try {
            send(templateId, recipient);
            return true;
        } catch (ApiException e) {
            return false;   // already logged in send()
        }
    }

    private void send(String templateId, Map<String, String> recipient) {
        Map<String, Object> body = Map.of(
                "template_id", templateId,
                "short_url", "0",
                "recipients", List.of(recipient));
        Map<?, ?> reply;
        try {
            reply = http.post().uri("/api/v5/flow")
                    .header("authkey", authKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException e) {
            log.error("MSG91 request failed: {}", e.getClass().getSimpleName());
            throw smsFailed();
        }
        if (reply == null || !"success".equals(reply.get("type"))) {
            // MSG91 explains problems (credit, template, DLT) in "message"; it never contains the code
            log.error("MSG91 refused the SMS: {}", reply == null ? "empty reply" : reply.get("message"));
            throw smsFailed();
        }
    }

    private static ApiException smsFailed() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SMS_FAILED",
                "We couldn't send the SMS. Please try again in a minute.");
    }
}
