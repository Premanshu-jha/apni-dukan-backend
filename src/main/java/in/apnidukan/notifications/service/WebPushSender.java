package in.apnidukan.notifications.service;

import in.apnidukan.notifications.domain.PushSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.interfaces.ECPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

/**
 * Sends Web Push messages straight to the browsers' push services (RFC 8030), encrypted for the device and signed
 * with our VAPID key (see {@link WebPushCrypto}).
 *
 * One HTTP client for every message (this sender is a single bean): HTTP/2, so the sends to one push service (FCM,
 * Mozilla, Apple, Microsoft) share a few kept-alive connections and run as parallel streams on them, instead of a new
 * TLS handshake per message. Connections stay open between sends (the JDK client keeps idle ones for 20 minutes by
 * default); NotificationSendJob limits the sends at once per push service.
 */
public class WebPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(WebPushSender.class);
    /** How long the push service keeps a message for a device that is offline. */
    private static final Duration TTL = Duration.ofHours(6);
    private static final Duration TOKEN_LIFETIME = Duration.ofHours(12);
    static final int MAX_TEXT_BYTES = 4096 - 86 - 1 - 16;

    private final String publicKey;
    private final ECPrivateKey privateKey;
    private final String subject;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)   // every push service speaks it; HTTP/1.1 only if one doesn't
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)   // a redirect could lead anywhere: never follow one
            .build();

    /**
     * @param publicKey  the VAPID public key, base64url (65 bytes); the app gives the same one to the browser
     * @param privateKey the matching private key
     * @param subject    contact for the push services, e.g. mailto:support@apnidukan.in
     */
    public WebPushSender(String publicKey, ECPrivateKey privateKey, String subject, Clock clock) {
        this.publicKey = publicKey;
        this.privateKey = privateKey;
        this.subject = subject;
        this.clock = clock;
    }

    @Override
    public Result send(PushSubscription device, String jsonPayload) {
        if (!PushEndpoints.allowed(device.endpoint())) {   // checked when saved too; never send anywhere else
            return Result.GONE;
        }
        String host = URI.create(device.endpoint()).getHost();
        byte[] text = jsonPayload.getBytes(StandardCharsets.UTF_8);
        // Push services take at most 4096 bytes of body: the 86-byte header, the text, a 1-byte delimiter, a 16-byte tag
        if (text.length > MAX_TEXT_BYTES) {
            log.warn("Web Push message too long ({} bytes); not sent", text.length);
            return Result.FAILED;   // our message's fault, not the device's: keep the device
        }
        try {
            Base64.Decoder b64 = Base64.getUrlDecoder();
            byte[] body = WebPushCrypto.encrypt(text, b64.decode(device.p256dh()), b64.decode(device.auth()));
            long expires = clock.instant().plus(TOKEN_LIFETIME).getEpochSecond();
            String token = WebPushCrypto.vapidToken(PushEndpoints.origin(device.endpoint()), subject, expires,
                    privateKey);
            HttpRequest request = HttpRequest.newBuilder(URI.create(device.endpoint()))
                    .timeout(Duration.ofSeconds(10))
                    .header("TTL", Long.toString(TTL.toSeconds()))
                    .header("Urgency", "high")
                    .header("Content-Encoding", "aes128gcm")
                    .header("Content-Type", "application/octet-stream")
                    .header("Authorization", "vapid t=" + token + ", k=" + publicKey)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status >= 200 && status < 300) {
                return Result.SENT;
            }
            // Only the host and the status: the full address is a secret that lets anyone message the device
            log.warn("Web Push to {} answered {}", host, status);
            return status == 404 || status == 410 ? Result.GONE : Result.FAILED;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // The device's keys are broken: retrying won't help
            log.warn("Web Push subscription {} has unusable keys: {}", device.id(), e.getClass().getSimpleName());
            return Result.GONE;
        } catch (IOException e) {
            log.warn("Web Push to {} failed: {}", host, e.getClass().getSimpleName());
            return Result.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.FAILED;
        }
    }
}
