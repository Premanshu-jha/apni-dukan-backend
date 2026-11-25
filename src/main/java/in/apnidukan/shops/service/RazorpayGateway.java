package in.apnidukan.shops.service;

import in.apnidukan.common.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Razorpay, for the salons' subscription (the regular payment gateway, not RazorpayX):
 * <ul>
 *   <li>{@link #createSubscription}: POST https://api.razorpay.com/v1/subscriptions with the ₹500 monthly plan
 *       (RAZORPAY_PLAN_ID), signed in with the key id and secret (HTTP Basic);</li>
 *   <li>{@link #checkoutSigned}: the checkout's answer to the app is genuine (HMAC-SHA256 of
 *       "payment_id|subscription_id" with the key secret);</li>
 *   <li>{@link #webhookSigned}: a webhook is genuine (HMAC-SHA256 of the raw body with the webhook secret, in the
 *       X-Razorpay-Signature header).</li>
 * </ul>
 * Off (503 SUBSCRIPTIONS_OFF) until RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET and RAZORPAY_PLAN_ID are set; the test-mode
 * keys (rzp_test_...) work the same way, with nothing charged. Short timeouts (3 s to connect, 8 s for the answer) and
 * no retries: the owner is waiting on the payment screen and can tap again. The secrets are never logged: logs carry
 * Razorpay's HTTP status or the exception's class only.
 */
@Component
public class RazorpayGateway {

    private static final Logger log = LoggerFactory.getLogger(RazorpayGateway.class);
    public static final URI RAZORPAY = URI.create("https://api.razorpay.com");
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration ANSWER_TIMEOUT = Duration.ofSeconds(8);
    /** Monthly charges a subscription runs for at most (10 years); the owner cancels long before that. */
    static final int TOTAL_COUNT = 120;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Razorpay's answer when it made a subscription: its id (sub_...) and status (CREATED). */
    public record Created(String id, String status) {
    }

    private final String keyId;
    private final String keySecret;
    private final String webhookSecret;
    private final String planId;
    private final URI endpoint;
    private final HttpClient http;

    public RazorpayGateway(@Value("${app.razorpay.key-id:}") String keyId,
                           @Value("${app.razorpay.key-secret:}") String keySecret,
                           @Value("${app.razorpay.webhook-secret:}") String webhookSecret,
                           @Value("${app.razorpay.plan-id:}") String planId,
                           @Value("${app.razorpay.endpoint:https://api.razorpay.com}") URI endpoint) {
        this.keyId = keyId.strip();
        this.keySecret = keySecret.strip();
        this.webhookSecret = webhookSecret.strip();
        this.planId = planId.strip();
        this.endpoint = endpoint;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    /** Payments can be taken: the key id, its secret and the plan are set. */
    public boolean configured() {
        return !keyId.isBlank() && !keySecret.isBlank() && !planId.isBlank();
    }

    /** Webhooks can be checked: the webhook secret is set. */
    public boolean webhooksConfigured() {
        return !webhookSecret.isBlank();
    }

    /** The key id the app's checkout opens with (public: it is in every checkout page). */
    public String keyId() {
        requireConfigured();
        return keyId;
    }

    /** Test-mode keys (rzp_test_...): nothing real is charged. */
    public boolean testMode() {
        return keyId.startsWith("rzp_test_");
    }

    /**
     * A new subscription to the monthly plan for this salon. {@code startAt}: the first charge's day (the trial's
     * end), or null to charge the first month as soon as the owner approves AutoPay. The salon's id goes in its notes,
     * so every webhook about it says which salon it is.
     */
    public Created createSubscription(UUID shopId, Instant startAt) {
        requireConfigured();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plan_id", planId);
        body.put("total_count", TOTAL_COUNT);
        body.put("quantity", 1);
        body.put("customer_notify", 0);   // the app opens the checkout itself; Razorpay sends no links of its own
        if (startAt != null) {
            body.put("start_at", startAt.getEpochSecond());
        }
        body.put("notes", Map.of("shop_id", shopId.toString()));
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint.resolve("/v1/subscriptions"))
                    .timeout(ANSWER_TIMEOUT)
                    .header("Authorization", authorization())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)))
                    .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                log.warn("Razorpay refused a new subscription: HTTP {}", response.statusCode());
                throw unavailable();
            }
            JsonNode answer = JSON.readTree(response.body());
            String id = answer.path("id").asString("");
            if (!id.startsWith("sub_") || id.length() > 40) {
                log.warn("Razorpay's answer to a new subscription had no usable id");
                throw unavailable();
            }
            return new Created(id, answer.path("status").asString("created").toUpperCase(java.util.Locale.ROOT));
        } catch (JacksonException e) {
            log.warn("Razorpay's answer to a new subscription wasn't readable");
            throw unavailable();
        } catch (IOException e) {
            log.warn("Razorpay didn't answer a new subscription: {}", e.getClass().getSimpleName());
            throw unavailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
    }

    /**
     * Cancels a subscription at once (no more charges, retries stopped). True once it is cancelled, or Razorpay says
     * it can't be (already cancelled, completed or expired: Razorpay answers 400 BAD_REQUEST_ERROR). 503
     * PAYMENTS_UNAVAILABLE when Razorpay doesn't answer; the caller tries again later.
     */
    public boolean cancelSubscription(String subscriptionId) {
        requireConfigured();
        if (!subscriptionId.matches("sub_[A-Za-z0-9]{1,36}")) {
            throw new IllegalArgumentException("Not a Razorpay subscription id");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint.resolve("/v1/subscriptions/" + subscriptionId
                            + "/cancel"))
                    .timeout(ANSWER_TIMEOUT)
                    .header("Authorization", authorization())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"cancel_at_cycle_end\":0}"))
                    .build();
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status / 100 == 2 || status == 400) {
                return true;
            }
            log.warn("Razorpay didn't cancel a subscription: HTTP {}", status);
            throw unavailable();
        } catch (IOException e) {
            log.warn("Razorpay didn't answer a cancel: {}", e.getClass().getSimpleName());
            throw unavailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
    }

    /** A paid invoice of a subscription: the payment that paid it and the end of the time it paid for. */
    public record PaidInvoice(String paymentId, Instant periodEnd) {
    }

    /** A payment as Razorpay has it: amount, fee (with GST), when, and how much of it was refunded. */
    public record Payment(String id, long amountPaise, Long feePaise, Instant createdAt, String status,
                          long refundedPaise) {
    }

    /** A refund of a payment. */
    public record Refund(String id, long amountPaise, Instant createdAt, String status) {
    }

    /**
     * The paid invoices of a subscription, 100 at a time ({@code skip}: how many to pass over), for the one-time
     * import of past payments (PaymentImport). 503 PAYMENTS_UNAVAILABLE when Razorpay doesn't answer.
     */
    public List<PaidInvoice> paidInvoices(String subscriptionId, int skip) {
        if (!subscriptionId.matches("sub_[A-Za-z0-9]{1,36}")) {
            throw new IllegalArgumentException("Not a Razorpay subscription id");
        }
        JsonNode answer = get("/v1/invoices?subscription_id=" + subscriptionId + "&count=100&skip=" + skip);
        List<PaidInvoice> paid = new ArrayList<>();
        for (JsonNode invoice : answer.path("items")) {
            String paymentId = invoice.path("payment_id").asString("");
            if ("paid".equals(invoice.path("status").asString("")) && paymentId.matches("pay_[A-Za-z0-9]{1,36}")) {
                long end = invoice.path("billing_end").asLong(0);
                paid.add(new PaidInvoice(paymentId, end > 0 ? Instant.ofEpochSecond(end) : null));
            }
        }
        return paid;
    }

    /** A read about an id Razorpay doesn't have (HTTP 400 or 404): skip it, trying again won't help. */
    public static final class UnknownToRazorpay extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UnknownToRazorpay(String path) {
            super("Razorpay doesn't know " + path.replaceAll("\\?.*", ""));
        }
    }

    /** A subscription on our plan, as Razorpay lists it: the salon from its notes (null when it has none). */
    public record PlanSubscription(String id, String shopId) {
    }

    /**
     * Every subscription ever made on our plan, 100 at a time from {@code skip} (Razorpay keeps them all, replaced and
     * cancelled ones too). 503 PAYMENTS_UNAVAILABLE when Razorpay doesn't answer.
     */
    public List<PlanSubscription> planSubscriptions(int skip) {
        List<PlanSubscription> out = new ArrayList<>();
        JsonNode answer = get("/v1/subscriptions?plan_id=" + java.net.URLEncoder.encode(planId,
                StandardCharsets.UTF_8) + "&count=100&skip=" + skip);
        for (JsonNode sub : answer.path("items")) {
            String id = sub.path("id").asString("");
            if (id.matches("sub_[A-Za-z0-9]{1,36}")) {
                String shop = sub.path("notes").path("shop_id").asString("");
                out.add(new PlanSubscription(id, shop.isBlank() ? null : shop));
            }
        }
        return out;
    }

    /** One payment. 503 PAYMENTS_UNAVAILABLE when Razorpay doesn't answer. */
    public Payment payment(String paymentId) {
        if (!paymentId.matches("pay_[A-Za-z0-9]{1,36}")) {
            throw new IllegalArgumentException("Not a Razorpay payment id");
        }
        JsonNode p = get("/v1/payments/" + paymentId);
        JsonNode fee = p.path("fee");
        return new Payment(paymentId, p.path("amount").asLong(0), fee.isNumber() ? fee.asLong() : null,
                Instant.ofEpochSecond(p.path("created_at").asLong(0)), p.path("status").asString(""),
                p.path("amount_refunded").asLong(0));
    }

    /** A payment's refunds. 503 PAYMENTS_UNAVAILABLE when Razorpay doesn't answer. */
    public List<Refund> refunds(String paymentId) {
        if (!paymentId.matches("pay_[A-Za-z0-9]{1,36}")) {
            throw new IllegalArgumentException("Not a Razorpay payment id");
        }
        List<Refund> refunds = new ArrayList<>();
        for (JsonNode r : get("/v1/payments/" + paymentId + "/refunds?count=100").path("items")) {
            String id = r.path("id").asString("");
            if (id.matches("rfnd_[A-Za-z0-9]{1,36}")) {
                refunds.add(new Refund(id, r.path("amount").asLong(0),
                        Instant.ofEpochSecond(r.path("created_at").asLong(0)), r.path("status").asString("")));
            }
        }
        return refunds;
    }

    private JsonNode get(String path) {
        requireConfigured();
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint.resolve(path))
                    .timeout(ANSWER_TIMEOUT)
                    .header("Authorization", authorization())
                    .GET()
                    .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status == 400 || status == 404) {
                // Not Razorpay being down: it doesn't know this id (one made with the test keys, say)
                throw new UnknownToRazorpay(path);
            }
            if (status / 100 != 2) {
                log.warn("Razorpay didn't answer a read: HTTP {}", status);
                throw unavailable();
            }
            return JSON.readTree(response.body());
        } catch (JacksonException e) {
            log.warn("Razorpay's answer to a read wasn't readable");
            throw unavailable();
        } catch (IOException e) {
            log.warn("Razorpay didn't answer a read: {}", e.getClass().getSimpleName());
            throw unavailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
    }

    private String authorization() {
        return "Basic " + Base64.getEncoder().encodeToString((keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
    }

    /** The checkout's answer (razorpay_signature) belongs to this payment and subscription. */
    public boolean checkoutSigned(String paymentId, String subscriptionId, String signature) {
        requireConfigured();
        return matches(hmac(keySecret, (paymentId + "|" + subscriptionId).getBytes(StandardCharsets.UTF_8)),
                signature);
    }

    /** A webhook's body came from Razorpay (X-Razorpay-Signature). False without a webhook secret. */
    public boolean webhookSigned(byte[] body, String signature) {
        return webhooksConfigured() && matches(hmac(webhookSecret, body), signature);
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SUBSCRIPTIONS_OFF",
                    "Payments aren't set up yet. Please try again later.");
        }
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENTS_UNAVAILABLE",
                "Couldn't reach the payment service. Please try again in a minute.").retryAfter(30);
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

    private static boolean matches(String expected, String given) {
        return given != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                given.strip().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }
}
