package in.apnidukan.notifications.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.GeneralSecurityException;
import java.security.interfaces.ECPrivateKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

/**
 * Picks the Web Push sender: the real one when both VAPID keys are set, otherwise one that only logs (development;
 * the real server refuses to start with it, see ProductionSafetyCheck).
 *
 * Make a key pair once, for example with {@code npx web-push generate-vapid-keys}, and set VAPID_PUBLIC_KEY and
 * VAPID_PRIVATE_KEY on the server. Changing the pair later stops every saved device from receiving messages until
 * it subscribes again, so keep it.
 */
@Configuration
public class PushConfig {

    @Bean
    PushSender pushSender(@Value("${app.push.vapid-public-key:}") String publicKey,
                          @Value("${app.push.vapid-private-key:}") String privateKey,
                          @Value("${app.push.subject:mailto:support@apnidukan.in}") String subject,
                          Clock clock) throws GeneralSecurityException {
        if (publicKey.isBlank() || privateKey.isBlank()) {
            return new LoggingPushSender();
        }
        Base64.Decoder b64 = Base64.getUrlDecoder();
        ECPrivateKey key = WebPushCrypto.privateKey(b64.decode(privateKey.strip()));
        // A mismatched pair would make every push service refuse every message: stop at startup instead
        String probe = WebPushCrypto.vapidToken("https://check.invalid", subject,
                Instant.now(clock).getEpochSecond() + 60, key);
        if (!WebPushCrypto.verify(probe, WebPushCrypto.publicKey(b64.decode(publicKey.strip())))) {
            throw new IllegalStateException("VAPID_PUBLIC_KEY doesn't match VAPID_PRIVATE_KEY");
        }
        return new WebPushSender(publicKey.strip(), key, subject, clock);
    }
}
