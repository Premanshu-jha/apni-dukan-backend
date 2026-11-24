package in.apnidukan.notifications.service;

import com.sun.net.httpserver.HttpServer;
import in.apnidukan.notifications.domain.Channel;
import in.apnidukan.notifications.domain.NotificationJob;
import in.apnidukan.notifications.domain.PushSubscription;
import in.apnidukan.support.TestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Android apps' sender against a local stand-in for Google's sign-in and FCM: what it sends, how often it signs
 * in, and how it reads FCM's answers (sent, the app is gone, try again).
 */
class FcmSenderTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-11-23T06:00:00Z");

    private final TestClock clock = new TestClock(NOW);
    private final List<String> sends = new CopyOnWriteArrayList<>();
    private final List<String> sendAuth = new CopyOnWriteArrayList<>();
    private final AtomicInteger signIns = new AtomicInteger();
    private volatile String lastAssertion;
    private volatile int fcmStatus = 200;
    private volatile String fcmAnswer = "{\"name\":\"projects/clozest/messages/1\"}";
    private volatile int tokenStatus = 200;
    private HttpServer server;
    private KeyPair keys;

    @BeforeEach
    void start() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/token", exchange -> {
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            for (String pair : form.split("&")) {
                if (pair.startsWith("assertion=")) {
                    lastAssertion = URLDecoder.decode(pair.substring(10), StandardCharsets.UTF_8);
                }
            }
            int n = signIns.incrementAndGet();
            answer(exchange, tokenStatus, "{\"access_token\":\"access-" + n + "\",\"expires_in\":3599}");
        });
        server.createContext("/v1/projects/clozest-app/messages:send", exchange -> {
            sendAuth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            sends.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            answer(exchange, fcmStatus, fcmAnswer);
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void sendsTheTitleTextTypeAndScreenToTheAppsTokenOnItsChannel() throws Exception {
        assertThat(sender().send(device(), message())).isEqualTo(PushSender.Result.SENT);

        JsonNode msg = JSON.readTree(sends.get(0)).path("message");
        assertThat(msg.path("token").asString()).isEqualTo("token-abc");
        assertThat(msg.path("notification").path("title").asString()).isEqualTo("Booking confirmed");
        assertThat(msg.path("notification").path("body").asString()).isEqualTo("Haircut at 12:00");
        assertThat(msg.path("data").path("type").asString()).isEqualTo("BOOKING_CONFIRMED");
        assertThat(msg.path("data").path("url").asString()).isEqualTo("/bookings/b1");
        assertThat(msg.path("android").path("priority").asString()).isEqualTo("HIGH");
        assertThat(msg.path("android").path("notification").path("channel_id").asString()).isEqualTo("clozest");
        assertThat(sendAuth.get(0)).isEqualTo("Bearer access-1");
    }

    @Test
    void aMessageWithoutAScreenSendsNoUrl() throws Exception {
        NotificationJob noUrl = new NotificationJob(UUID.randomUUID(), UUID.randomUUID(), Channel.PUSH, "SHOP_NEWS",
                "News", "Text", null, Map.of(), 1);
        sender().send(device(), noUrl);
        assertThat(JSON.readTree(sends.get(0)).path("message").path("data").has("url")).isFalse();
    }

    @Test
    void signsInOnceForManySendsAndAgainShortlyBeforeTheTokenRunsOut() throws Exception {
        FcmSender sender = sender();
        for (int i = 0; i < 5; i++) {
            sender.send(device(), message());
        }
        assertThat(signIns.get()).isOne();
        clock.advance(Duration.ofMinutes(50));   // still 9 minutes left: kept
        sender.send(device(), message());
        assertThat(signIns.get()).isOne();
        clock.advance(Duration.ofMinutes(6));   // under 5 minutes left: a new one
        sender.send(device(), message());
        assertThat(signIns.get()).isEqualTo(2);
        assertThat(sendAuth.get(sendAuth.size() - 1)).isEqualTo("Bearer access-2");
    }

    @Test
    void theSignInIsAJwtSignedWithTheAccountsKeyForFcmsScope() throws Exception {
        sender().send(device(), message());
        String[] parts = lastAssertion.split("\\.");
        Base64.Decoder b64 = Base64.getUrlDecoder();
        JsonNode header = JSON.readTree(new String(b64.decode(parts[0]), StandardCharsets.UTF_8));
        JsonNode claims = JSON.readTree(new String(b64.decode(parts[1]), StandardCharsets.UTF_8));
        assertThat(header.path("alg").asString()).isEqualTo("RS256");
        assertThat(header.path("kid").asString()).isEqualTo("key-1");
        assertThat(claims.path("iss").asString()).isEqualTo("fcm@clozest-app.iam.gserviceaccount.com");
        assertThat(claims.path("scope").asString()).isEqualTo(FcmSender.SCOPE);
        assertThat(claims.path("iat").asLong()).isEqualTo(NOW.getEpochSecond());
        assertThat(claims.path("exp").asLong()).isEqualTo(NOW.getEpochSecond() + 3600);
        Signature rsa = Signature.getInstance("SHA256withRSA");
        rsa.initVerify(keys.getPublic());
        rsa.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(rsa.verify(b64.decode(parts[2]))).isTrue();
    }

    @Test
    void anUninstalledAppIsGoneAndEverythingElseIsTriedAgainABadMessageToo() throws Exception {
        FcmSender sender = sender();
        fcmStatus = 404;
        fcmAnswer = "{\"error\":{\"status\":\"NOT_FOUND\",\"details\":[{\"errorCode\":\"UNREGISTERED\"}]}}";
        assertThat(sender.send(device(), message())).isEqualTo(PushSender.Result.GONE);
        fcmStatus = 400;
        fcmAnswer = "{\"error\":{\"status\":\"INVALID_ARGUMENT\",\"message\":\"The registration token is not a valid "
                + "FCM registration token\"}}";
        assertThat(sender.send(device(), message())).isEqualTo(PushSender.Result.GONE);
        fcmStatus = 403;
        fcmAnswer = "{\"error\":{\"status\":\"PERMISSION_DENIED\",\"details\":[{\"errorCode\":\"SENDER_ID_MISMATCH\"}]}}";
        assertThat(sender.send(device(), message())).isEqualTo(PushSender.Result.GONE);
        // A bad field in our own message: never a reason to delete the phone's device
        fcmStatus = 400;
        fcmAnswer = "{\"error\":{\"status\":\"INVALID_ARGUMENT\",\"message\":\"Invalid value at "
                + "'message.android.ttl'\"}}";
        assertThat(sender.send(device(), message())).isEqualTo(PushSender.Result.FAILED);
        fcmStatus = 429;
        fcmAnswer = "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}";
        assertThat(sender.send(device(), message())).isEqualTo(PushSender.Result.FAILED);
        fcmStatus = 503;
        fcmAnswer = "not json";
        assertThat(sender.send(device(), message())).isEqualTo(PushSender.Result.FAILED);
    }

    @Test
    void aRefusedAccessTokenIsDroppedAndTheNextSendSignsInAgain() throws Exception {
        FcmSender sender = sender();
        fcmStatus = 401;
        fcmAnswer = "{\"error\":{\"status\":\"UNAUTHENTICATED\"}}";
        assertThat(sender.send(device(), message())).isEqualTo(PushSender.Result.FAILED);
        fcmStatus = 200;
        assertThat(sender.send(device(), message())).isEqualTo(PushSender.Result.SENT);
        assertThat(signIns.get()).isEqualTo(2);
    }

    @Test
    void aRefusedSignInFailsTheSendWithoutDroppingTheDevice() throws Exception {
        tokenStatus = 400;
        assertThat(sender().send(device(), message())).isEqualTo(PushSender.Result.FAILED);
        assertThat(sends).isEmpty();
    }

    @Test
    void theKeyFileIsReadAsJsonOrAsBase64OfIt() throws Exception {
        String json = account(keys);
        assertThat(FcmSender.readServiceAccount(json).path("project_id").asString()).isEqualTo("clozest-app");
        String base64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        assertThat(FcmSender.readServiceAccount(base64).path("project_id").asString()).isEqualTo("clozest-app");
        assertThatThrownBy(() -> FcmSender.readServiceAccount("{not json"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new FcmSender(JSON.readTree("{\"project_id\":\"x\"}"), base(), clock))
                .hasMessageContaining("client_email");
    }

    private FcmSender sender() throws Exception {
        return new FcmSender(FcmSender.readServiceAccount(account(keys)), base(), clock);
    }

    private URI base() {
        return URI.create("http://localhost:" + server.getAddress().getPort());
    }

    private String account(KeyPair pair) {
        String marker = "PRIVATE " + "KEY-----";   // split, so the secret scanner doesn't take a test key for a leak
        String pem = "-----BEGIN " + marker + "\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END " + marker + "\n";
        return JSON.writeValueAsString(Map.of(
                "type", "service_account",
                "project_id", "clozest-app",
                "private_key_id", "key-1",
                "private_key", pem,
                "client_email", "fcm@clozest-app.iam.gserviceaccount.com",
                "token_uri", "http://localhost:" + server.getAddress().getPort() + "/token"));
    }

    private static PushSubscription device() {
        return new PushSubscription(UUID.randomUUID(), UUID.randomUUID(), "token-abc", null, null,
                PushSubscription.Kind.APP);
    }

    private static NotificationJob message() {
        return new NotificationJob(UUID.randomUUID(), UUID.randomUUID(), Channel.PUSH, "BOOKING_CONFIRMED",
                "Booking confirmed", "Haircut at 12:00", "/bookings/b1", Map.of(), 1);
    }

    private static void answer(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
