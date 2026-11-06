package in.apnidukan.auth.service;

import in.apnidukan.common.security.ClientIp;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** IPv4 addresses (thousands of phones behind one) and IPv6 /64s (one phone or home) get their own limits. */
class SignInLimitsTest {

    private final SignInLimits limits = new SignInLimits(200, 20, 600, 60, 50_000);

    @Test
    void ipv6NetworksGetTheStricterLimit() {
        assertThat(limits.sendsPerHour(ClientIp.limitKey("203.0.113.9"))).isEqualTo(200);
        assertThat(limits.sendsPerHour(ClientIp.limitKey("2001:db8::1"))).isEqualTo(20);
        assertThat(limits.verifiesPerHour(ClientIp.limitKey("203.0.113.9"))).isEqualTo(600);
        assertThat(limits.verifiesPerHour(ClientIp.limitKey("2001:db8::1"))).isEqualTo(60);
        assertThat(limits.sendsPerHour(ClientIp.limitKey(null))).isEqualTo(200);
    }

    @Test
    void theWarningComesAt80Percent() {
        assertThat(SignInLimits.warnAt(50_000)).isEqualTo(40_000);
        assertThat(SignInLimits.warnAt(1)).isEqualTo(1);
    }
}
