package in.apnidukan.common.config;

import in.apnidukan.auth.notify.EmailSender;
import in.apnidukan.auth.notify.LoggingEmailSender;
import in.apnidukan.auth.notify.LoggingSmsSender;
import in.apnidukan.auth.notify.SmsSender;
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

    @Test
    void developmentAllowsConsoleCodesAndLocalhost() {
        assertThatCode(() -> ProductionSafetyCheck.check(false, new LoggingSmsSender(), new LoggingEmailSender(),
                "http://localhost:5173", true)).doesNotThrowAnyException();
    }

    @Test
    void productionRefusesOtpsInTheLog() {
        assertThatThrownBy(() -> ProductionSafetyCheck.check(true, new LoggingSmsSender(), realEmail,
                "https://shop.example.in", true)).hasMessageContaining("SMS_PROVIDER=log");
    }

    @Test
    void productionRefusesEmailCodesInTheLog() {
        assertThatThrownBy(() -> ProductionSafetyCheck.check(true, realSms, new LoggingEmailSender(),
                "https://shop.example.in", true)).hasMessageContaining("MAIL_ENABLED=false");
    }

    @Test
    void productionRefusesLocalhostOrAnyOriginInCors() {
        assertThatThrownBy(() -> ProductionSafetyCheck.check(true, realSms, realEmail, "http://localhost:5173", true))
                .hasMessageContaining("CORS_ALLOWED_ORIGINS");
        assertThatThrownBy(() -> ProductionSafetyCheck.check(true, realSms, realEmail, "*", true))
                .hasMessageContaining("CORS_ALLOWED_ORIGINS");
    }

    @Test
    void productionRefusesWithTheAccountMovedSmsOff() {
        assertThatThrownBy(() -> ProductionSafetyCheck.check(true, realSms, realEmail, "https://shop.example.in", false))
                .hasMessageContaining("SMS_ACCOUNT_MOVED_ENABLED");
    }

    @Test
    void productionWithRealSettingsStarts() {
        assertThatCode(() -> ProductionSafetyCheck.check(true, realSms, realEmail, "https://shop.example.in", true))
                .doesNotThrowAnyException();
    }
}
