package in.apnidukan.common.web;

import in.apnidukan.common.config.KiranaSwitch;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Which paths the kirana switch closes, and what it answers. */
class KiranaSwitchFilterTest {

    @Test
    void theKiranaPrefixesAndEverythingUnderThemMatch() {
        assertThat(KiranaSwitchFilter.kiranaPath("/api/v1/kirana")).isTrue();
        assertThat(KiranaSwitchFilter.kiranaPath("/api/v1/kirana/")).isTrue();
        assertThat(KiranaSwitchFilter.kiranaPath("/api/v1/kirana/products/barcode/8901262150019")).isTrue();
        assertThat(KiranaSwitchFilter.kiranaPath("/api/v1/kirana/nearby")).isTrue();
        assertThat(KiranaSwitchFilter.kiranaPath("/api/v1/admin/kirana")).isTrue();
        assertThat(KiranaSwitchFilter.kiranaPath("/api/v1/admin/kirana/catalogue/import")).isTrue();
        assertThat(KiranaSwitchFilter.kiranaPath("/api/v1/payments/razorpayx/webhook")).isTrue();
    }

    @Test
    void onlyWholeSegmentsMatch() {
        assertThat(KiranaSwitchFilter.kiranaPath("/api/v1/kiranaX")).isFalse();
        assertThat(KiranaSwitchFilter.kiranaPath("/api/v1/kiranaX/products")).isFalse();
        assertThat(KiranaSwitchFilter.kiranaPath("/api/v1/kiranas")).isFalse();
        assertThat(KiranaSwitchFilter.kiranaPath("/api/v1/admin/kiranaX")).isFalse();
        assertThat(KiranaSwitchFilter.kiranaPath("/api/v1/payments/razorpay/webhook")).isFalse();
        assertThat(KiranaSwitchFilter.kiranaPath("/api/v1/payments/razorpayxy/webhook")).isFalse();
        assertThat(KiranaSwitchFilter.kiranaPath("/api/v1/kira")).isFalse();
    }

    @Test
    void salonAndSharedPathsDontMatch() {
        assertThat(KiranaSwitchFilter.kiranaPath("/api/v1/shops")).isFalse();
        assertThat(KiranaSwitchFilter.kiranaPath("/api/v1/shops/me/type")).isFalse();
        assertThat(KiranaSwitchFilter.kiranaPath("/api/v1/salon/day")).isFalse();
        assertThat(KiranaSwitchFilter.kiranaPath("/api/v1/salons")).isFalse();
        assertThat(KiranaSwitchFilter.kiranaPath("/api/v1/admin/salon/strikes")).isFalse();
        assertThat(KiranaSwitchFilter.kiranaPath("/api/v1/admin/shops")).isFalse();
        assertThat(KiranaSwitchFilter.kiranaPath("/api/v1/files")).isFalse();
        assertThat(KiranaSwitchFilter.kiranaPath("/actuator/health")).isFalse();
        assertThat(KiranaSwitchFilter.kiranaPath("/")).isFalse();
        assertThat(KiranaSwitchFilter.kiranaPath("")).isFalse();
        assertThat(KiranaSwitchFilter.kiranaPath(null)).isFalse();
        // Kirana further down someone else's path is theirs
        assertThat(KiranaSwitchFilter.kiranaPath("/api/v1/salon/kirana")).isFalse();
        assertThat(KiranaSwitchFilter.kiranaPath("/kirana")).isFalse();
    }

    @Test
    void pathsWrittenDifferentlyAreReadAsTheControllersWouldReadThem() {
        assertThat(KiranaSwitchFilter.kiranaPath("/api/v1//kirana/products")).isTrue();
        assertThat(KiranaSwitchFilter.kiranaPath("/api/v1/./kirana")).isTrue();
        assertThat(KiranaSwitchFilter.kiranaPath("/api/v1/salon/../kirana/bills")).isTrue();
        assertThat(KiranaSwitchFilter.kiranaPath("/api/v1/%6Birana/products")).isTrue();
        assertThat(KiranaSwitchFilter.kiranaPath("/api/v1/kirana;x=1/products")).isTrue();
        assertThat(KiranaSwitchFilter.kiranaPath("/API/V1/KIRANA")).isTrue();
        assertThat(KiranaSwitchFilter.kiranaPath("/api/v1/kirana%")).isFalse();   // a broken escape: not the prefix
    }

    @Test
    void offAKiranaPathAnswers404KiranaOffAndGoesNoFurther() throws Exception {
        KiranaSwitchFilter filter = new KiranaSwitchFilter(new KiranaSwitch(false));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/kirana/settings");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString())
                .isEqualTo("{\"code\":\"KIRANA_OFF\",\"message\":\"Kirana shops aren't on Clozest yet.\",\"details\":{}}");
        assertThat(chain.getRequest()).isNull();   // nothing after it ran
    }

    @Test
    void offAnyOtherPathGoesOnAsBefore() throws Exception {
        KiranaSwitchFilter filter = new KiranaSwitchFilter(new KiranaSwitch(false));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/kiranaX");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    void onEveryPathGoesOnAsBefore() throws Exception {
        KiranaSwitchFilter filter = new KiranaSwitchFilter(new KiranaSwitch(true));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/kirana/bills");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    void theContextPathIsLeftOut() throws Exception {
        KiranaSwitchFilter filter = new KiranaSwitchFilter(new KiranaSwitch(false));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/api/v1/kirana/nearby");
        request.setContextPath("/app");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(chain.getRequest()).isNull();
    }
}
