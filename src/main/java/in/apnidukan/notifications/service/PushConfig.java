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
 * Picks the senders. Web Push (the browsers): the real one when both VAPID keys are set, otherwise one that only
 * logs (development; the real server refuses to start with it, see ProductionSafetyCheck). The Android apps: see
 * {@link #appPushSender}.
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
                          @Value("${app.push.subject:mailto:support@clozest.in}") String subject,
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

    /**
     * The Android apps' sender: Firebase Cloud Messaging when FIREBASE_SERVICE_ACCOUNT holds the Firebase project's
     * service-account key file (its JSON, or the JSON base64-encoded), otherwise off (the apps can't turn
     * notifications on, and the website is unaffected). A key file that can't be read stops the server at startup,
     * instead of every app message failing later.
     */
    @Bean
    AppPushSender appPushSender(@Value("${app.push.fcm-service-account:}") String serviceAccount,
                                @Value("${app.push.fcm-base-url:https://fcm.googleapis.com}") String fcmBase,
                                Clock clock) throws GeneralSecurityException {
        if (serviceAccount.isBlank()) {
            return AppPushSender.OFF;
        }
        return new FcmSender(FcmSender.readServiceAccount(serviceAccount), java.net.URI.create(fcmBase), clock);
    }
}
