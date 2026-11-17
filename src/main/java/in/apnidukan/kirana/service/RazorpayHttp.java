package in.apnidukan.kirana.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP to Razorpay's API (payments and RazorpayX) with Basic auth: a JSON body in, a JSON answer out. Razorpay's
 * refusals (4xx) and its silence (5xx, a timeout) are told apart, because the callers treat them differently: a refusal
 * is final, silence is tried again. Logs only the status or the exception's class, never keys or bodies.
 */
final class RazorpayHttp {

    private static final Logger log = LoggerFactory.getLogger(RazorpayHttp.class);
    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration ANSWER_TIMEOUT = Duration.ofSeconds(15);

    /** Razorpay said no (4xx): its description, in Razorpay's words, for the log and the admin page. */
    static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message);
        }
    }

    /** Razorpay didn't answer usefully (5xx, a timeout, an unreadable answer): try again later. */
    static final class Silent extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Silent(String message) {
            super(message);
        }
    }

    private final URI endpoint;
    private final String authorization;
    private final HttpClient http;

    RazorpayHttp(URI endpoint, String keyId, String keySecret) {
        this.endpoint = endpoint;
        this.authorization = "Basic " + Base64.getEncoder()
                .encodeToString((keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    JsonNode post(String path, Map<String, ?> body, Map<String, String> headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(endpoint.resolve(path)).timeout(ANSWER_TIMEOUT)
                    .header("Authorization", authorization).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
            headers.forEach(b::header);
            return answer(path, http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray()));
        } catch (JacksonException e) {
            throw new Silent("unreadable");
        } catch (IOException e) {
            log.warn("Razorpay didn't answer {}: {}", path.replaceAll("/[a-z]+_[A-Za-z0-9]+", "/…"),
                    e.getClass().getSimpleName());
            throw new Silent(e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Silent("interrupted");
        }
    }

    JsonNode get(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint.resolve(path)).timeout(ANSWER_TIMEOUT)
                    .header("Authorization", authorization).GET().build();
            return answer(path, http.send(request, HttpResponse.BodyHandlers.ofByteArray()));
        } catch (IOException e) {
            throw new Silent(e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Silent("interrupted");
        }
    }

    private static JsonNode answer(String path, HttpResponse<byte[]> response) {
        int status = response.statusCode();
        JsonNode body;
        try {
            body = JSON.readTree(response.body());
        } catch (JacksonException e) {
            body = JSON.createObjectNode();
        }
        if (status / 100 == 2) {
            return body;
        }
        String where = path.replaceAll("/[a-z]+_[A-Za-z0-9]+", "/…");
        if (status / 100 == 4 && status != 429 && status != 408) {
            String description = body.path("error").path("description").asString("refused");
            log.warn("Razorpay refused {}: HTTP {} {}", where, status, description);
            throw new Refused(description.length() > 280 ? description.substring(0, 280) : description);
        }
        log.warn("Razorpay failed {}: HTTP {}", where, status);
        throw new Silent("HTTP " + status);
    }

    static String hmac(String secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is missing", e);
        }
    }

    /** Whether {@code given} is the expected hex signature (compared in constant time). */
    static boolean matches(String expected, String given) {
        return given != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                given.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }
}
