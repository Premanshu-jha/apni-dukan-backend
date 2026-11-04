package in.apnidukan.auth.service;

import in.apnidukan.common.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Against a fake Cloudflare: nothing is really checked. */
class HumanCheckTest {

    private static final String URL = "https://turnstile.example/siteverify";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer cloudflare = MockRestServiceServer.bindTo(builder).build();

    @Test
    void aGenuineTokenPasses() {
        HumanCheck check = new HumanCheck(builder, "test-secret", URL);
        cloudflare.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(content().formDataContains(java.util.Map.of("secret", "test-secret",
                        "response", "tok", "remoteip", "203.0.113.5")))
                .andRespond(withSuccess("{\"success\":true,\"hostname\":\"app\"}", MediaType.APPLICATION_JSON));
        assertThatCode(() -> check.require("tok", "203.0.113.5")).doesNotThrowAnyException();
        cloudflare.verify();
    }

    @Test
    void aFakeTokenOrNoTokenIsRefused() {
        HumanCheck check = new HumanCheck(builder, "test-secret", URL);
        cloudflare.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> check.require("fake", null))
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("CAPTCHA_FAILED"));
        assertThatThrownBy(() -> check.require(" ", null))
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("CAPTCHA_REQUIRED"));
    }

    @Test
    void aRefusalOrAnUnreadableReplyIsAFailedCheckNeverAWayAround() {
        HumanCheck check = new HumanCheck(builder, "test-secret", URL);
        cloudflare.expect(requestTo(URL)).andRespond(org.springframework.test.web.client.response
                .MockRestResponseCreators.withBadRequest());
        cloudflare.expect(requestTo(URL)).andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> check.require("junk", null))
                    .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("CAPTCHA_FAILED"));
        }
    }

    @Test
    void cloudflareDownStillLetsPeopleSignIn() {
        HumanCheck check = new HumanCheck(builder, "test-secret", URL);
        cloudflare.expect(requestTo(URL)).andRespond(withServerError());
        assertThatCode(() -> check.require("tok", null)).doesNotThrowAnyException();
    }

    @Test
    void withoutASecretThereIsNoCheck() {
        HumanCheck check = new HumanCheck(builder, "", URL);
        assertThat(check.enabled()).isFalse();
        assertThatCode(() -> check.require(null, null)).doesNotThrowAnyException();
    }
}
