package in.apnidukan.kirana.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * RazorpayX: Clozest's payouts to each kirana's UPI ID (its share of delivered online orders). Off until its keys and
 * account number are set (RAZORPAYX_*): payouts are then only recorded as due, and go out once they are set.
 * <ul>
 *   <li>{@link #contact} and {@link #upiAccount}: POST /v1/contacts and /v1/fund_accounts (account_type vpa), made
 *       once per shop and UPI ID and kept</li>
 *   <li>{@link #pay}: POST /v1/payouts, mode UPI, with our payout's id as the X-Payout-Idempotency key, so asking again
 *       after a lost answer never pays twice</li>
 *   <li>{@link #webhookSigned}: a RazorpayX webhook is genuine (HMAC-SHA256 of the raw body with its own secret)</li>
 * </ul>
 */
@Component
public class KiranaPayoutGateway {

    private final String keyId;
    private final String keySecret;
    private final String accountNumber;
    private final String webhookSecret;
    private final RazorpayHttp http;

    public KiranaPayoutGateway(@Value("${app.razorpayx.key-id:}") String keyId,
                               @Value("${app.razorpayx.key-secret:}") String keySecret,
                               @Value("${app.razorpayx.account-number:}") String accountNumber,
                               @Value("${app.razorpayx.webhook-secret:}") String webhookSecret,
                               @Value("${app.razorpayx.endpoint:https://api.razorpay.com}") URI endpoint) {
        this.keyId = keyId;
        this.keySecret = keySecret;
        this.accountNumber = accountNumber;
        this.webhookSecret = webhookSecret;
        this.http = new RazorpayHttp(endpoint, keyId, keySecret);
    }

    /** A payout RazorpayX took: its id and status (queued, pending, processing, processed, ...). */
    public record Sent(String id, String status) {
    }

    public boolean configured() {
        return !keyId.isBlank() && !keySecret.isBlank() && !accountNumber.isBlank();
    }

    /** A RazorpayX contact for the shop (vendor); its id. */
    public String contact(UUID shopId, String shopName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", shopName.length() > 50 ? shopName.substring(0, 50) : shopName);
        body.put("type", "vendor");
        body.put("reference_id", shopId.toString());
        return id(http.post("/v1/contacts", body, Map.of()), "cont_");
    }

    /** A fund account for the contact's UPI ID; its id. RazorpayX refuses a malformed UPI ID ({@code Refused}). */
    public String upiAccount(String contactId, String upiId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contact_id", contactId);
        body.put("account_type", "vpa");
        body.put("vpa", Map.of("address", upiId));
        return id(http.post("/v1/fund_accounts", body, Map.of()), "fa_");
    }

    /** Pays the amount to the fund account by UPI; asking again with the same payout id never pays twice. */
    public Sent pay(UUID payoutId, String fundAccountId, long amountPaise) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("account_number", accountNumber);
        body.put("fund_account_id", fundAccountId);
        body.put("amount", amountPaise);
        body.put("currency", "INR");
        body.put("mode", "UPI");
        body.put("purpose", "payout");
        body.put("queue_if_low_balance", true);
        body.put("reference_id", payoutId.toString());
        body.put("narration", "Clozest orders");
        JsonNode r = http.post("/v1/payouts", body, Map.of("X-Payout-Idempotency", payoutId.toString()));
        return new Sent(id(r, "pout_"), r.path("status").asString(""));
    }

    /** Whether a RazorpayX webhook is genuine. */
    public boolean webhookSigned(byte[] body, String signature) {
        return !webhookSecret.isBlank() && RazorpayHttp.matches(RazorpayHttp.hmac(webhookSecret, body), signature);
    }

    private static String id(JsonNode answer, String prefix) {
        String id = answer.path("id").asString("");
        if (!id.startsWith(prefix) || id.length() > 40) {
            throw new RazorpayHttp.Silent("no id");
        }
        return id;
    }
}
