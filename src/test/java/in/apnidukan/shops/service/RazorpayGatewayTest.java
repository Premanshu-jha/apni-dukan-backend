package in.apnidukan.shops.service;

import in.apnidukan.common.error.ApiException;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Razorpay's signatures and the "not set up" answer, without the network. */
class RazorpayGatewayTest {

    private static RazorpayGateway gateway(String keyId, String secret, String webhook, String plan) {
        return new RazorpayGateway(keyId, secret, webhook, plan, URI.create("http://127.0.0.1:1"));
    }

    @Test
    void theCheckoutsSignatureIsHmacOfPaymentAndSubscriptionWithTheKeySecret() {
        RazorpayGateway g = gateway("rzp_test_1", "secret", "", "plan_1");
        String good = RazorpayGateway.hmac("secret", "pay_A|sub_B".getBytes(StandardCharsets.UTF_8));
        assertThat(good).hasSize(64).matches("[0-9a-f]+");
        assertThat(g.checkoutSigned("pay_A", "sub_B", good)).isTrue();
        assertThat(g.checkoutSigned("pay_A", "sub_B", " " + good.toUpperCase() + "\n")).isTrue();
        assertThat(g.checkoutSigned("pay_A", "sub_C", good)).isFalse();
        assertThat(g.checkoutSigned("pay_A", "sub_B", null)).isFalse();
        assertThat(g.checkoutSigned("pay_A", "sub_B", "")).isFalse();
    }

    @Test
    void aWebhookIsCheckedWithTheWebhookSecretAndNeverPassesWithoutOne() {
        byte[] body = "{\"event\":\"subscription.charged\"}".getBytes(StandardCharsets.UTF_8);
        String sig = RazorpayGateway.hmac("hook", body);
        assertThat(gateway("k", "s", "hook", "p").webhookSigned(body, sig)).isTrue();
        assertThat(gateway("k", "s", "other", "p").webhookSigned(body, sig)).isFalse();
        // No webhook secret (or only spaces): nothing passes, whatever is sent
        assertThat(gateway("k", "s", "", "p").webhookSigned(body, sig)).isFalse();
        assertThat(gateway("k", "s", "  ", "p").webhookSigned(body, RazorpayGateway.hmac("  ", body))).isFalse();
    }

    @Test
    void withoutKeysOrPlanPaymentsAreOffAndSayWhy() {
        RazorpayGateway off = gateway("rzp_live_1", "s", "h", "");
        assertThat(off.configured()).isFalse();
        assertThatThrownBy(off::keyId).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("SUBSCRIPTIONS_OFF"));
        assertThatThrownBy(() -> off.createSubscription(UUID.randomUUID(), Instant.now()))
                .isInstanceOf(ApiException.class);
        assertThat(gateway("rzp_test_1", "s", "h", "plan_1").testMode()).isTrue();
        assertThat(gateway("rzp_live_1", "s", "h", "plan_1").testMode()).isFalse();
    }

    @Test
    void noRazorpayAnswerIsAShort503NotAHang() {
        RazorpayGateway down = gateway("rzp_test_1", "s", "h", "plan_1");   // nothing listens on port 1
        assertThatThrownBy(() -> down.createSubscription(UUID.randomUUID(), null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("PAYMENTS_UNAVAILABLE"));
    }
}
