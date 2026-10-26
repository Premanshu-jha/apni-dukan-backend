package in.apnidukan.auth.notify;

import in.apnidukan.common.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Checks the exact request MSG91 receives, against a fake MSG91 (nothing is really sent). */
class Msg91SmsSenderTest {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer msg91 = MockRestServiceServer.bindTo(builder).build();

    private Msg91SmsSender sender() {
        return new Msg91SmsSender(builder, "https://control.msg91.com", "test-key", "otp-template", "moved-template");
    }

    @Test
    void sendsTheCodeWithTheOtpTemplateLikeOmr() {
        Msg91SmsSender sms = sender();
        msg91.expect(requestTo("https://control.msg91.com/api/v5/flow"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("authkey", "test-key"))
                .andExpect(content().json("""
                        {"template_id":"otp-template","short_url":"0",
                         "recipients":[{"mobiles":"919848012345","OTP":"123456"}]}"""))
                .andRespond(withSuccess("{\"type\":\"success\",\"message\":\"3463\"}", MediaType.APPLICATION_JSON));

        sms.sendOtp("9848012345", "123456");
        msg91.verify();
    }

    @Test
    void refusalFromMsg91TellsTheAppToTryAgain() {
        Msg91SmsSender sms = sender();
        msg91.expect(requestTo("https://control.msg91.com/api/v5/flow"))
                .andRespond(withSuccess("{\"type\":\"error\",\"message\":\"Insufficient balance\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> sms.sendOtp("9848012345", "123456"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("SMS_FAILED"));
    }

    @Test
    void msg91DownTellsTheAppToTryAgain() {
        Msg91SmsSender sms = sender();
        msg91.expect(requestTo("https://control.msg91.com/api/v5/flow")).andRespond(withServerError());

        assertThatThrownBy(() -> sms.sendOtp("9848012345", "123456"))
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("SMS_FAILED"));
    }

    @Test
    void accountMovedNoticeUsesItsOwnTemplate() {
        msg91.expect(requestTo("https://control.msg91.com/api/v5/flow"))
                .andExpect(header("authkey", "test-key"))
                .andExpect(content().json("""
                        {"template_id":"moved-template","short_url":"0",
                         "recipients":[{"mobiles":"919848012345","ENDING":"0002"}]}"""))
                .andRespond(withSuccess("{\"type\":\"success\"}", MediaType.APPLICATION_JSON));

        sender().sendAccountMoved("9848012345", "0002");
        msg91.verify();
    }

    @Test
    void missingSettingsStopTheAppWithAClearMessage() {
        assertThatThrownBy(() -> new Msg91SmsSender(builder, "https://control.msg91.com", "", "", ""))
                .hasMessageContaining("MSG91_AUTH_KEY");
        assertThatThrownBy(() -> new Msg91SmsSender(builder, "https://control.msg91.com", "key", "otp", ""))
                .hasMessageContaining("MSG91_ACCOUNT_MOVED_TEMPLATE_ID");
    }
}
