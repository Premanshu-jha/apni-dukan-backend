package in.apnidukan.notifications.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The server only ever sends to the browsers' real push services (no request forgery through the endpoint). */
class PushEndpointsTest {

    @Test
    void realPushServicesAreAllowed() {
        assertThat(PushEndpoints.allowed("https://fcm.googleapis.com/fcm/send/abc:def")).isTrue();
        assertThat(PushEndpoints.allowed("https://updates.push.services.mozilla.com/wpush/v2/gAAA")).isTrue();
        assertThat(PushEndpoints.allowed("https://web.push.apple.com/QGuQyavXut")).isTrue();
        assertThat(PushEndpoints.allowed("https://wns2-bn3p.notify.windows.com/w/?token=x")).isTrue();
        assertThat(PushEndpoints.origin("https://fcm.googleapis.com/fcm/send/abc")).isEqualTo("https://fcm.googleapis.com");
    }

    @Test
    void anythingElseIsRefused() {
        assertThat(PushEndpoints.allowed("http://fcm.googleapis.com/fcm/send/abc")).isFalse();           // not https
        assertThat(PushEndpoints.allowed("https://fcm.googleapis.com:8443/x")).isFalse();                // odd port
        assertThat(PushEndpoints.allowed("https://evil.example/fcm.googleapis.com")).isFalse();
        assertThat(PushEndpoints.allowed("https://fcm.googleapis.com.evil.example/x")).isFalse();
        assertThat(PushEndpoints.allowed("https://user@fcm.googleapis.com/x")).isFalse();
        assertThat(PushEndpoints.allowed("https://169.254.169.254/latest/meta-data")).isFalse();
        assertThat(PushEndpoints.allowed("https://localhost/x")).isFalse();
        assertThat(PushEndpoints.allowed("https://notify.windows.com/x")).isFalse();
        assertThat(PushEndpoints.allowed("not a url at all")).isFalse();
    }
}
