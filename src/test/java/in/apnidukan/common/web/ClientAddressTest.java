package in.apnidukan.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientAddressTest {

    private static MockHttpServletRequest request(String header, String value) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr("100.64.0.2");   // the proxy
        if (header != null) {
            r.addHeader(header, value);
        }
        return r;
    }

    @Test
    void forwardedForCountsOnlyItsLastEntry() {
        ClientAddress railway = new ClientAddress("X-Forwarded-For");
        assertThat(railway.of(request("X-Forwarded-For", "1.2.3.4, 203.0.113.5"))).isEqualTo("203.0.113.5");
        assertThat(railway.of(request("X-Forwarded-For", "203.0.113.5"))).isEqualTo("203.0.113.5");
        assertThat(railway.of(request("X-Forwarded-For", "2001:db8::1 , "))).isEqualTo("2001:db8::1");
    }

    @Test
    void anotherHeaderIsTakenAsItIs() {
        assertThat(new ClientAddress("CF-Connecting-IP").of(request("CF-Connecting-IP", "198.51.100.1")))
                .isEqualTo("198.51.100.1");
    }

    @Test
    void withoutTheHeaderOrWithoutASettingItIsTheConnectionsAddress() {
        assertThat(new ClientAddress("X-Forwarded-For").of(request(null, null))).isEqualTo("100.64.0.2");
        assertThat(new ClientAddress("").of(request("X-Forwarded-For", "1.2.3.4"))).isEqualTo("100.64.0.2");
        assertThat(new ClientAddress("").configured()).isFalse();
    }
}
