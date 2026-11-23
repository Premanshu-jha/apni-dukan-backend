package in.apnidukan.shops.api;

import in.apnidukan.shops.service.PlaySubscriptions;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Google Play's Real-time developer notifications for the Play Store app's subscriptions, pushed by Google Cloud
 * Pub/Sub. Open to the internet without a sign-in: the push subscription's address carries
 * ?secret=GOOGLE_PLAY_NOTIFICATION_SECRET, checked by PlaySubscriptions (403 BAD_SECRET). Answers 200
 * {"result": UPDATED | UNKNOWN | TEST | IGNORED}; Google is asked again about the purchase, so a forged or replayed
 * notification can only make us read Google's own truth again.
 */
@RestController
@RequestMapping("/api/v1/payments/google-play")
public class GooglePlayNotificationsController {

    private final PlaySubscriptions play;

    public GooglePlayNotificationsController(PlaySubscriptions play) {
        this.play = play;
    }

    @PostMapping(value = "/notifications", consumes = "application/json")
    public Map<String, String> receive(@RequestParam(value = "secret", required = false) String secret,
                                       @RequestBody byte[] body) {
        return Map.of("result", play.notification(secret, body));
    }
}
