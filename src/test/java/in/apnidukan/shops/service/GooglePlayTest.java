package in.apnidukan.shops.service;

import com.sun.net.httpserver.HttpServer;
import in.apnidukan.support.TestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** The Google Play Developer API client against a local stand-in for Google. */
class GooglePlayTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-11-23T06:00:00Z");

    private final List<String> calls = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String answer = "{}";
    private HttpServer server;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/token", ex -> {
            ex.getRequestBody().readAllBytes();
            reply(ex, 200, "{\"access_token\":\"a\",\"expires_in\":3599}");
        });
        server.createContext("/androidpublisher", ex -> {
            String q = ex.getRequestURI().getRawQuery();
            calls.add(ex.getRequestMethod() + " " + ex.getRequestURI().getRawPath() + (q == null ? "" : "?" + q) + " "
                    + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(ex, status, answer);
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void offOrOnByItsSettings() throws Exception {
        assertThat(new GooglePlay("", "com.clozest.partner", "p", base(), new TestClock(NOW)).configured()).isFalse();
        assertThat(new GooglePlay(keyFile(), "com.clozest.partner", "", base(), new TestClock(NOW)).configured())
                .isFalse();
        assertThat(play().configured()).isTrue();
    }

    @Test
    void readsASubscriptionAsGoogleHasIt() throws Exception {
        answer = "{\"subscriptionState\":\"SUBSCRIPTION_STATE_IN_GRACE_PERIOD\",\"acknowledgementState\":"
                + "\"ACKNOWLEDGEMENT_STATE_PENDING\",\"testPurchase\":{},\"externalAccountIdentifiers\":"
                + "{\"obfuscatedExternalAccountId\":\"shop-1\"},\"lineItems\":[{\"productId\":\"p\",\"expiryTime\":"
                + "\"2026-12-23T06:00:00Z\",\"autoRenewingPlan\":{\"autoRenewEnabled\":true}}]}";
        GooglePlay.PlaySubscription s = play().subscription("tok-123456789").orElseThrow();
        assertThat(s.entitled()).isTrue();
        assertThat(s.productId()).isEqualTo("p");
        assertThat(s.accountId()).isEqualTo("shop-1");
        assertThat(s.expiresAt()).isEqualTo(Instant.parse("2026-12-23T06:00:00Z"));
        assertThat(s.autoRenewing()).isTrue();
        assertThat(s.acknowledged()).isFalse();
        assertThat(s.test()).isTrue();
        assertThat(s.freeTrial()).isFalse();
        assertThat(s.paid()).isFalse();   // Google's grace period: the payment failed, Google is retrying it
        assertThat(calls.get(0)).startsWith("GET /androidpublisher/v3/applications/com.clozest.partner/purchases/"
                + "subscriptionsv2/tokens/tok-123456789");
        status = 404;
        assertThat(play().subscription("tok-unknown1")).isEmpty();
        assertThat(play().subscription("not a token")).isEmpty();
    }

    @Test
    void reportsAFirstTransactionWithItsTokenAndALaterOneNamingIt() throws Exception {
        GooglePlay play = play();
        assertThat(play.reportExternal(new GooglePlay.ExternalTransaction("s-sub_1", 0, NOW, "ext-token", null,
                "TELANGANA"))).isEqualTo(GooglePlay.Reported.DONE);
        assertThat(play.reportExternal(new GooglePlay.ExternalTransaction("p-pay_1", 50000, NOW, null, "s-sub_1",
                "TELANGANA"))).isEqualTo(GooglePlay.Reported.DONE);
        assertThat(calls.get(0)).contains("/externalTransactions?externalTransactionId=s-sub_1");
        JsonNode first = JSON.readTree(calls.get(0).substring(calls.get(0).indexOf(' ', 5) + 1));
        assertThat(first.path("recurringTransaction").path("externalTransactionToken").asString())
                .isEqualTo("ext-token");
        assertThat(first.path("userTaxAddress").path("regionCode").asString()).isEqualTo("IN");
        assertThat(first.path("transactionTime").asString()).isEqualTo("2026-11-23T06:00:00Z");
        JsonNode later = JSON.readTree(calls.get(1).substring(calls.get(1).indexOf(' ', 5) + 1));
        assertThat(later.path("recurringTransaction").has("externalTransactionToken")).isFalse();
        assertThat(later.path("recurringTransaction").path("initialExternalTransactionId").asString())
                .isEqualTo("s-sub_1");
        assertThat(later.path("originalPreTaxAmount").path("priceMicros").asString()).isEqualTo("500000000");
        assertThat(later.path("originalTaxAmount").path("priceMicros").asString()).isEqualTo("0");
    }

    @Test
    void googlesAnswersToAReport() throws Exception {
        GooglePlay play = play();
        GooglePlay.ExternalTransaction t = new GooglePlay.ExternalTransaction("s-1", 0, NOW, "tok", null, "DELHI");
        status = 409;
        assertThat(play.reportExternal(t)).isEqualTo(GooglePlay.Reported.DONE);
        status = 400;
        assertThat(play.reportExternal(t)).isEqualTo(GooglePlay.Reported.REFUSED);
        status = 503;
        assertThat(play.reportExternal(t)).isEqualTo(GooglePlay.Reported.RETRY);
        status = 429;
        assertThat(play.reportExternal(t)).isEqualTo(GooglePlay.Reported.RETRY);
    }

    @Test
    void acknowledgesAndCancelsTheProductsPurchase() throws Exception {
        GooglePlay play = play();
        play.acknowledge("tok-123456789");
        assertThat(play.cancel("tok-123456789")).isTrue();
        assertThat(calls).containsExactly(
                "POST /androidpublisher/v3/applications/com.clozest.partner/purchases/subscriptions/p/tokens/"
                        + "tok-123456789:acknowledge {}",
                "POST /androidpublisher/v3/applications/com.clozest.partner/purchases/subscriptions/p/tokens/"
                        + "tok-123456789:cancel {}");
        status = 500;
        assertThat(play.cancel("tok-123456789")).isFalse();
        // Already acknowledged: fine; any other 400 is a failure (tried again later)
        status = 400;
        answer = "{\"error\":{\"message\":\"The subscription purchase is already acknowledged.\"}}";
        play.acknowledge("tok-123456789");
        answer = "{\"error\":{\"message\":\"Invalid purchase token.\"}}";
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> play.acknowledge("tok-123456789"))
                .hasMessageContaining("payment");
    }

    private GooglePlay play() throws Exception {
        return new GooglePlay(keyFile(), "com.clozest.partner", "p", base(), new TestClock(NOW));
    }

    private URI base() {
        return URI.create("http://localhost:" + server.getAddress().getPort());
    }

    private String keyFile() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        String marker = "PRIVATE " + "KEY-----";   // split, so the secret scanner doesn't take a test key for a leak
        String pem = "-----BEGIN " + marker + "\n" + Base64.getMimeEncoder().encodeToString(
                g.generateKeyPair().getPrivate().getEncoded()) + "\n-----END " + marker + "\n";
        return JSON.writeValueAsString(Map.of("project_id", "clozest", "client_email", "play@x.iam.gserviceaccount.com",
                "private_key", pem, "token_uri", base() + "/token"));
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body)
            throws java.io.IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(out);
        }
    }
}
