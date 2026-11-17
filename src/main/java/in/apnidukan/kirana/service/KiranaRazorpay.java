package in.apnidukan.kirana.service;

import in.apnidukan.common.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Razorpay payments for kirana online orders, on Clozest's own account (the same keys as the salons' subscriptions):
 * <ul>
 *   <li>{@link #createOrder}: POST /v1/orders, the amount the customer's checkout collects (UPI or Pay Later); the
 *       checkout's id goes in its notes</li>
 *   <li>{@link #checkoutSigned}: the checkout's answer to the app is genuine (HMAC-SHA256 of order_id|payment_id with
 *       the key secret)</li>
 *   <li>{@link #capture}: POST /v1/payments/{id}/capture, for an account that doesn't capture on its own</li>
 *   <li>{@link #refund} and {@link #refunds}: POST and GET /v1/payments/{id}/refunds; our refund's id goes in its
 *       notes, so an answer lost on the way is found again instead of refunding twice</li>
 * </ul>
 * Webhooks reach the same URL as the subscriptions' (RazorpayWebhookController checks their signature) and come here
 * through {@code KiranaPaymentEvents}.
 */
@Component
public class KiranaRazorpay {

    private final String keyId;
    private final String keySecret;
    private final RazorpayHttp http;

    public KiranaRazorpay(@Value("${app.razorpay.key-id:}") String keyId,
                          @Value("${app.razorpay.key-secret:}") String keySecret,
                          @Value("${app.razorpay.endpoint:https://api.razorpay.com}") URI endpoint) {
        this.keyId = keyId;
        this.keySecret = keySecret;
        this.http = new RazorpayHttp(endpoint, keyId, keySecret);
    }

    /** A refund Razorpay knows about: its id, its status (pending, processed, failed) and our refund's id. */
    public record KnownRefund(String id, String status, String ourId) {
    }

    public boolean configured() {
        return !keyId.isBlank() && !keySecret.isBlank();
    }

    /** The key id the customer app's checkout opens with (public: it is in every checkout page). */
    public String keyId() {
        return keyId;
    }

    /** A new Razorpay order for this checkout's total; its id. 503 PAYMENTS_UNAVAILABLE when Razorpay doesn't answer. */
    public String createOrder(UUID checkoutId, long amountPaise) {
        requireConfigured();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amountPaise);
        body.put("currency", "INR");
        body.put("receipt", checkoutId.toString().substring(0, 36));
        body.put("notes", Map.of("checkout_id", checkoutId.toString()));
        body.put("payment_capture", 1);   // captured as soon as it is paid: the signed answer means the money is in
        try {
            String id = http.post("/v1/orders", body, Map.of()).path("id").asString("");
            if (!id.startsWith("order_") || id.length() > 40) {
                throw unavailable();
            }
            return id;
        } catch (RazorpayHttp.Refused | RazorpayHttp.Silent e) {
            throw unavailable();
        }
    }

    /** Whether the checkout's answer (razorpay_signature) belongs to this order and payment. */
    public boolean checkoutSigned(String orderId, String paymentId, String signature) {
        return configured() && orderId != null && paymentId != null && RazorpayHttp.matches(
                RazorpayHttp.hmac(keySecret, (orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8)), signature);
    }

    /** Captures an authorized payment (an account that doesn't capture on its own). Refusals (captured already) pass. */
    public void capture(String paymentId, long amountPaise) {
        try {
            http.post("/v1/payments/" + paymentId + "/capture", Map.of("amount", amountPaise, "currency", "INR"),
                    Map.of());
        } catch (RazorpayHttp.Refused e) {
            // Captured already (by Razorpay itself, or an earlier event): nothing to do
        }
    }

    /**
     * Refunds part or all of a payment, normal speed (Pay Later lenders take no instant refunds). Our refund's id goes
     * in its notes. Throws {@link RazorpayHttp.Refused} when Razorpay says no, {@link RazorpayHttp.Silent} when it
     * doesn't answer.
     */
    public KnownRefund refund(String paymentId, long amountPaise, UUID ourId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amountPaise);
        body.put("speed", "normal");
        body.put("notes", Map.of("refund_id", ourId.toString()));
        JsonNode r = http.post("/v1/payments/" + paymentId + "/refund", body, Map.of());
        return new KnownRefund(r.path("id").asString(""), r.path("status").asString("pending"), ourId.toString());
    }

    /** The payment's refunds as Razorpay knows them (to find one whose answer was lost). */
    public List<KnownRefund> refunds(String paymentId) {
        List<KnownRefund> out = new ArrayList<>();
        for (JsonNode r : http.get("/v1/payments/" + paymentId + "/refunds?count=100").path("items")) {
            out.add(new KnownRefund(r.path("id").asString(""), r.path("status").asString(""),
                    r.path("notes").path("refund_id").asString("")));
        }
        return out;
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENTS_OFF",
                    "Online payments aren't set up yet. Please try again later.");
        }
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENTS_UNAVAILABLE",
                "Couldn't reach the payment service. Please try again in a minute.").retryAfter(30);
    }
}
