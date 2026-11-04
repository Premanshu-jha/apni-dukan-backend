package in.apnidukan.config;

import in.apnidukan.auth.notify.EmailSender;
import in.apnidukan.auth.notify.LoggingEmailSender;
import in.apnidukan.auth.notify.LoggingSmsSender;
import in.apnidukan.auth.notify.SmsSender;
import in.apnidukan.notifications.service.LoggingPushSender;
import in.apnidukan.notifications.service.PushSender;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionSafetyCheckTest {

    private final SmsSender realSms = new SmsSender() {
        @Override
        public void sendOtp(String phone, String code) { }

        @Override
        public void sendAccountMoved(String phone, String newPhoneEnding) { }
    };
    private final EmailSender realEmail = (to, subject, body) -> { };
    private final PushSender realPush = (device, payload) -> PushSender.Result.SENT;

    @Test
    void productionNeedsTheRealCallerIpAndTheRobotCheck() {
        assertThatThrownBy(() -> ProductionSafetyCheck.checkSignInAbuse(true, false, true))
                .hasMessageContaining("CLIENT_IP_HEADER");
        assertThatThrownBy(() -> ProductionSafetyCheck.checkSignInAbuse(true, true, false))
                .hasMessageContaining("TURNSTILE_SECRET_KEY");
        assertThatCode(() -> ProductionSafetyCheck.checkSignInAbuse(true, true, true)).doesNotThrowAnyException();
        assertThatCode(() -> ProductionSafetyCheck.checkSignInAbuse(false, false, false)).doesNotThrowAnyException();
    }

    @Test
    void developmentAllowsConsoleCodesAndLocalhost() {
        assertThatCode(() -> ProductionSafetyCheck.check(false, new LoggingSmsSender(), new LoggingEmailSender(),
                "http://localhost:5173", true, new LoggingPushSender())).doesNotThrowAnyException();
    }

    @Test
    void productionRefusesOtpsInTheLog() {
        assertThatThrownBy(() -> ProductionSafetyCheck.check(true, new LoggingSmsSender(), realEmail,
                "https://shop.example.in", true, realPush)).hasMessageContaining("SMS_PROVIDER=log");
    }

    @Test
    void productionRefusesEmailCodesInTheLog() {
        assertThatThrownBy(() -> ProductionSafetyCheck.check(true, realSms, new LoggingEmailSender(),
                "https://shop.example.in", true, realPush)).hasMessageContaining("MAIL_ENABLED=false");
    }

    @Test
    void productionRefusesLocalhostOrAnyOriginInCors() {
        assertThatThrownBy(() -> ProductionSafetyCheck.check(true, realSms, realEmail, "http://localhost:5173", true, realPush))
                .hasMessageContaining("CORS_ALLOWED_ORIGINS");
        assertThatThrownBy(() -> ProductionSafetyCheck.check(true, realSms, realEmail, "*", true, realPush))
                .hasMessageContaining("CORS_ALLOWED_ORIGINS");
    }

    @Test
    void productionRefusesWithTheAccountMovedSmsOff() {
        assertThatThrownBy(() -> ProductionSafetyCheck.check(true, realSms, realEmail, "https://shop.example.in", false, realPush))
                .hasMessageContaining("SMS_ACCOUNT_MOVED_ENABLED");
    }

    @Test
    void productionRefusesNotificationsInTheLog() {
        assertThatThrownBy(() -> ProductionSafetyCheck.check(true, realSms, realEmail, "https://shop.example.in", true,
                new LoggingPushSender())).hasMessageContaining("VAPID");
    }

    @Test
    void productionWithRealSettingsStarts() {
        assertThatCode(() -> ProductionSafetyCheck.check(true, realSms, realEmail, "https://shop.example.in", true, realPush))
                .doesNotThrowAnyException();
    }
}
