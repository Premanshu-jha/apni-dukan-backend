package in.apnidukan.kirana.api;

import in.apnidukan.kirana.service.KiranaPaymentEvents;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * RazorpayX's webhooks about kirana payouts (payout.processed, .failed, .reversed). Open to the internet without a
 * sign-in: the X-Razorpay-Signature (HMAC-SHA256 of the raw body with RAZORPAYX_WEBHOOK_SECRET) is the check, 403
 * BAD_SIGNATURE without it. Answers 200 {"result": UPDATED | DUPLICATE | UNKNOWN | IGNORED}.
 */
@RestController
@RequestMapping("/api/v1/payments/razorpayx")
public class RazorpayXWebhookController {

    private final KiranaPaymentEvents events;

    public RazorpayXWebhookController(KiranaPaymentEvents events) {
        this.events = events;
    }

    @PostMapping(value = "/webhook", consumes = "application/json")
    public Map<String, String> receive(@RequestHeader(value = "X-Razorpay-Signature", required = false) String sig,
                                       @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId,
                                       @RequestBody byte[] body) {
        return Map.of("result", events.payoutEvent(body, sig, eventId));
    }
}
