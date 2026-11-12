package in.apnidukan.helpdesk.service;

import in.apnidukan.common.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Checks that a support email really comes from our Cloudflare Email Worker: it signs "timestamp.body" with
 * HMAC-SHA256 under the shared secret (app.helpdesk.inbound-secret, the worker's HELPDESK_SECRET) and sends the
 * timestamp (seconds) and the hex signature. Refused (403 BAD_SIGNATURE): no secret set here, a wrong signature, or
 * a timestamp more than 5 minutes off (a recorded request replayed later). Compared in constant time.
 */
@Component
public class InboundSignature {

    static final Duration SKEW = Duration.ofMinutes(5);

    private final byte[] secret;
    private final Clock clock;

    public InboundSignature(@Value("${app.helpdesk.inbound-secret:}") String secret, Clock clock) {
        if (!secret.isEmpty() && secret.length() < 32) {
            throw new IllegalStateException("HELPDESK_INBOUND_SECRET must be at least 32 characters");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    public void check(String timestamp, String signature, byte[] body) {
        if (secret.length == 0 || timestamp == null || signature == null) {
            throw refused();
        }
        long seconds;
        try {
            seconds = Long.parseLong(timestamp.strip());
        } catch (NumberFormatException e) {
            throw refused();
        }
        long now = clock.instant().getEpochSecond();
        if (seconds < now - SKEW.toSeconds() || seconds > now + SKEW.toSeconds()) {
            throw refused();
        }
        byte[] given;
        try {
            given = HexFormat.of().parseHex(signature.strip().toLowerCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw refused();
        }
        if (!MessageDigest.isEqual(sign(seconds, body), given)) {
            throw refused();
        }
    }

    /** The signature of this body at this time (the worker computes the same). */
    byte[] sign(long seconds, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update((seconds + ".").getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException refused() {
        return new ApiException(HttpStatus.FORBIDDEN, "BAD_SIGNATURE", "Not signed by the support inbox.");
    }
}
