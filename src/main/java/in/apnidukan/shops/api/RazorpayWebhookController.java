package in.apnidukan.shops.api;

import in.apnidukan.shops.service.SubscriptionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Razorpay's webhooks for the salons' subscriptions (subscription.authenticated, .activated, .charged, .pending,
 * .halted, .cancelled, ...). Open to the internet without a sign-in: the X-Razorpay-Signature (HMAC-SHA256 of the raw
 * body with RAZORPAY_WEBHOOK_SECRET) is the check, 403 BAD_SIGNATURE without it. Answers 200 {"result": UPDATED |
 * OLD | DUPLICATE | UNKNOWN | IGNORED}; a retried event (same x-razorpay-event-id) is handled once.
 */
@RestController
@RequestMapping("/api/v1/payments/razorpay")
public class RazorpayWebhookController {

    private final SubscriptionService subscriptions;

    public RazorpayWebhookController(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    @PostMapping(value = "/webhook", consumes = "application/json")
    public Map<String, String> receive(@RequestHeader(value = "X-Razorpay-Signature", required = false) String sig,
                                       @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId,
                                       @RequestBody byte[] body) {
        return Map.of("result", subscriptions.webhook(body, sig, eventId));
    }
}
