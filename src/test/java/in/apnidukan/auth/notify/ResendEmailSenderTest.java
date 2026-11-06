package in.apnidukan.auth.notify;

import com.sun.net.httpserver.HttpServer;
import in.apnidukan.common.error.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Checks the exact request Resend receives, against a local fake of its API (nothing is really sent). */
class ResendEmailSenderTest {

    private final Map<String, String> seen = new ConcurrentHashMap<>();
    private volatile int answer = 200;
    /** Answers to give first, before {@code answer} (one per request). */
    private final java.util.Deque<Integer> firstAnswers = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
    private volatile String errorBody = "";
    private HttpServer fake;

    private URI start() throws IOException {
        fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/emails", exchange -> {
            seen.put("method", exchange.getRequestMethod());
            seen.put("auth", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            seen.put("type", String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")));
            seen.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.incrementAndGet();
            Integer first = firstAnswers.poll();
            int status = first != null ? first : answer;
            if (status == 429) {
                exchange.getResponseHeaders().add("Retry-After", "1");
            }
            byte[] out = (status >= 400 ? errorBody : "").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                exchange.getResponseBody().write(out);
            }
            exchange.close();
        });
        fake.start();
        return URI.create("http://127.0.0.1:" + fake.getAddress().getPort() + "/emails");
    }

    @AfterEach
    void stop() {
        if (fake != null) {
            fake.stop(0);
        }
    }

    @Test
    void postsTheEmailAsJsonWithTheKeyAsBearer() throws IOException {
        new ResendEmailSender("re_test_key", "Apni Dukan <no-reply@apnidukan.in>", start())
                .send("owner@gmail.com", "Your code", "Your code is 123456.\n\n\"Ignore\" it if not you.");
        assertThat(seen.get("method")).isEqualTo("POST");
        assertThat(seen.get("auth")).isEqualTo("Bearer re_test_key");
        assertThat(seen.get("type")).isEqualTo("application/json");
        assertThat(seen.get("body")).isEqualTo("{\"from\":\"Apni Dukan <no-reply@apnidukan.in>\","
                + "\"to\":[\"owner@gmail.com\"],\"subject\":\"Your code\","
                + "\"text\":\"Your code is 123456.\\n\\n\\\"Ignore\\\" it if not you.\"}");
    }

    @Test
    void tooManyAtOnceIsTriedOnceMoreAfterRetryAfter() throws IOException {
        firstAnswers.add(429);
        new ResendEmailSender("re_test_key", "a@apnidukan.in", start())
                .send("owner@gmail.com", "Your code", "Your code is 123456");
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    void stillTooManyAfterTheRetryTellsTheAppToTryAgain() throws IOException {
        answer = 429;
        ResendEmailSender resend = new ResendEmailSender("re_test_key", "a@apnidukan.in", start());
        assertThatThrownBy(() -> resend.send("owner@gmail.com", "Your code", "Your code is 123456"))
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("EMAIL_FAILED"));
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    void anAddressResendCantSendToIsTheUsersToFix() throws IOException {
        answer = 422;
        errorBody = "{\"statusCode\":422,\"name\":\"validation_error\",\"message\":\"Invalid `to` field\"}";
        ResendEmailSender resend = new ResendEmailSender("re_test_key", "a@apnidukan.in", start());
        assertThatThrownBy(() -> resend.send("owner@gmail", "Your code", "Your code is 123456"))
                .satisfies(e -> {
                    assertThat(((ApiException) e).getCode()).isEqualTo("INVALID_EMAIL");
                    assertThat(((ApiException) e).getStatus().value()).isEqualTo(400);
                });
        assertThat(requests.get()).isEqualTo(1);
        assertThat(ResendEmailSender.errorName(errorBody)).isEqualTo("validation_error");
        assertThat(ResendEmailSender.errorName("not json")).isEmpty();
    }

    @Test
    void aBadKeyIsNotTheUsersFault() throws IOException {
        answer = 401;
        ResendEmailSender resend = new ResendEmailSender("re_test_key", "a@apnidukan.in", start());
        assertThatThrownBy(() -> resend.send("owner@gmail.com", "Your code", "Your code is 123456"))
                .satisfies(e -> {
                    assertThat(((ApiException) e).getCode()).isEqualTo("EMAIL_FAILED");
                    assertThat(((ApiException) e).getStatus().value()).isEqualTo(503);
                });
    }

    @Test
    void aRefusalTellsTheAppToTryAgain() throws IOException {
        answer = 500;
        ResendEmailSender resend = new ResendEmailSender("re_test_key", "a@apnidukan.in", start());
        assertThatThrownBy(() -> resend.send("owner@gmail.com", "Your code", "Your code is 123456"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("EMAIL_FAILED"));
    }

    @Test
    void resendUnreachableTellsTheAppToTryAgain() throws IOException {
        URI gone = start();
        fake.stop(0);
        fake = null;
        ResendEmailSender resend = new ResendEmailSender("re_test_key", "a@apnidukan.in", gone);
        assertThatThrownBy(() -> resend.send("owner@gmail.com", "Your code", "Your code is 123456"))
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("EMAIL_FAILED"));
    }

    @Test
    void controlCharactersAreEscaped() {
        assertThat(ResendEmailSender.json("a\\b\tc\r\u0001")).isEqualTo("\"a\\\\b\\tc\\r\\u0001\"");
    }

    @Test
    void resendIsUsedOnceItsKeyIsSetOtherwiseSmtpAsBefore() {
        EmailSender smtp = new SmtpEmailSender(mock(JavaMailSender.class), "shop@gmail.com", "pw", "");
        assertThat(EmailConfig.choose(true, "re_key", "a@apnidukan.in", () -> smtp))
                .isInstanceOf(ResendEmailSender.class);
        assertThat(EmailConfig.choose(true, "  ", "a@apnidukan.in", () -> smtp)).isSameAs(smtp);
        assertThat(EmailConfig.choose(false, "re_key", "a@apnidukan.in", () -> smtp))
                .isInstanceOf(LoggingEmailSender.class);
        assertThatThrownBy(() -> new ResendEmailSender("re_key", "", ResendEmailSender.RESEND))
                .hasMessageContaining("EMAIL_FROM");
        assertThat(new ResendEmailSender("k", "Apni Dukan <onboarding@resend.dev>", ResendEmailSender.RESEND)
                .usesTestSender()).isTrue();
    }
}
