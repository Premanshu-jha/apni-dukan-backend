package in.apnidukan.kirana.service;

import in.apnidukan.common.config.KiranaSwitch;
import in.apnidukan.common.context.RazorpayEvents;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.kirana.repo.MoneyStore;
import in.apnidukan.kirana.repo.OrderStore;
import in.apnidukan.kirana.repo.OrderStore.Checkout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static in.apnidukan.kirana.service.RazorpayHttp.JSON;

/**
 * Razorpay's and RazorpayX's webhooks about kirana online orders, each handled once (kirana_razorpay_events):
 * <ul>
 *   <li>payment.authorized: captured (for an account that doesn't capture on its own)</li>
 *   <li>payment.captured, order.paid: the checkout is paid, its orders go to their shops (CheckoutService.markPaid)</li>
 *   <li>refund.processed, refund.failed: the refund's outcome (found by our id in its notes, or Razorpay's id)</li>
 *   <li>payout.processed, payout.failed, payout.reversed (RazorpayX): the payout's outcome; the owner is told of a
 *       failed one</li>
 * </ul>
 * The payments' events come through the subscriptions' webhook (its signature checked there); RazorpayX's come to
 * their own URL, checked here with their own secret.
 */
@Component
public class KiranaPaymentEvents implements RazorpayEvents {

    private static final Logger log = LoggerFactory.getLogger(KiranaPaymentEvents.class);

    private final CheckoutService checkouts;
    private final OrderJobs jobs;
    private final MoneyStore money;
    private final OrderStore orders;
    private final KiranaRazorpay razorpay;
    private final KiranaPayoutGateway payouts;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final KiranaSwitch kirana;

    public KiranaPaymentEvents(CheckoutService checkouts, OrderJobs jobs, MoneyStore money, OrderStore orders,
                               KiranaRazorpay razorpay, KiranaPayoutGateway payouts, TransactionTemplate transactions,
                               Clock clock, KiranaSwitch kirana) {
        this.kirana = kirana;
        this.checkouts = checkouts;
        this.jobs = jobs;
        this.money = money;
        this.orders = orders;
        this.razorpay = razorpay;
        this.payouts = payouts;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public String handle(String event, byte[] body, String eventId) {
        if (kirana.off()) {
            return null;   // kirana switched off: never a kirana's (the subscriptions' webhook goes on as before)
        }
        if (!event.startsWith("payment.") && !event.startsWith("order.") && !event.startsWith("refund.")) {
            return null;
        }
        JsonNode root = parse(body);
        Instant now = KiranaChecks.now(clock);
        JsonNode payment = root.path("payload").path("payment").path("entity");
        String razorpayOrderId = payment.path("order_id").asString(root.path("payload").path("order").path("entity")
                .path("id").asString(""));
        if (event.startsWith("payment.") && (razorpayOrderId.isEmpty()
                || orders.checkoutByRazorpay(razorpayOrderId).isEmpty())) {
            return null;   // not a kirana checkout's payment (a subscription's): someone else's
        }
        if ("payment.authorized".equals(event)) {
            // Orders are made to capture on their own; this covers a payment Razorpay left authorized. Only the
            // checkout's own payment while it waits: any other is left to lapse (Razorpay gives it back)
            String id = payment.path("id").asString("");
            Checkout c = orders.checkoutByRazorpay(razorpayOrderId).orElseThrow();
            boolean ours = "PENDING".equals(c.status()) || id.equals(c.razorpayPaymentId());
            if (!id.isEmpty() && razorpay.configured() && ours) {
                razorpay.capture(id, payment.path("amount").asLong(0));   // outside any transaction
                return "CAPTURE_ASKED";
            }
            return "IGNORED";
        }
        return transactions.execute(s -> {
            if (eventId != null && !eventId.isBlank() && !money.firstTime(eventId.strip(), now)) {
                return "DUPLICATE";
            }
            if ("payment.captured".equals(event) || "order.paid".equals(event)) {
                String paymentId = payment.path("id").asString("");
                if (razorpayOrderId.isEmpty() || paymentId.isEmpty()) {
                    return "IGNORED";
                }
                return checkouts.markPaid(razorpayOrderId, paymentId, emptyToNull(payment.path("method").asString("")),
                        payment.path("amount").asLong(0), now);
            }
            if ("refund.processed".equals(event) || "refund.failed".equals(event)) {
                JsonNode refund = root.path("payload").path("refund").path("entity");
                java.util.Optional<MoneyStore.Refund> ours = ourRefund(refund);
                if (ours.isEmpty()) {
                    return "UNKNOWN";
                }
                if ("refund.processed".equals(event)) {
                    money.refundSent(ours.get().id(), refund.path("id").asString(""), "PROCESSED", now);
                    return "UPDATED";
                }
                log.error("Razorpay couldn't complete kirana refund {}", ours.get().id());
                money.refundFailed(ours.get().id(), "Razorpay couldn't complete the refund", now);
                return "UPDATED";
            }
            return "IGNORED";
        });
    }

    /** A RazorpayX webhook (payouts): 403 BAD_SIGNATURE unless signed with its webhook secret. */
    public String payoutEvent(byte[] body, String signature, String eventId) {
        kirana.requireOn(HttpStatus.NOT_FOUND);   // also refused in front (KiranaSwitchFilter): 404 KIRANA_OFF
        if (!payouts.webhookSigned(body, signature)) {
            log.warn("A RazorpayX webhook with a wrong or missing signature was refused");
            throw new ApiException(HttpStatus.FORBIDDEN, "BAD_SIGNATURE", "Not signed by RazorpayX.");
        }
        JsonNode root = parse(body);
        String event = root.path("event").asString("");
        if (!event.startsWith("payout.")) {
            return "IGNORED";
        }
        JsonNode p = root.path("payload").path("payout").path("entity");
        Instant now = KiranaChecks.now(clock);
        String result = transactions.execute(s -> {
            if (eventId != null && !eventId.isBlank() && !money.firstTime("x:" + eventId.strip(), now)) {
                return "DUPLICATE";
            }
            java.util.Optional<MoneyStore.Payout> ours = money.payoutByRazorpay(p.path("id").asString(""))
                    .or(() -> uuid(p.path("reference_id").asString("")).flatMap(money::payout));
            if (ours.isEmpty()) {
                return "UNKNOWN";
            }
            String status = OrderJobs.payoutStatus(p.path("status").asString(""));
            if ("payout.reversed".equals(event)) {
                status = "REVERSED";
            } else if ("payout.failed".equals(event) || "payout.rejected".equals(event)) {
                status = "FAILED";
            } else if ("payout.processed".equals(event)) {
                status = "PROCESSED";
            }
            String reason = p.path("status_details").path("description").asString(
                    p.path("failure_reason").asString(""));
            boolean changed = money.payoutStatus(ours.get().id(), status, emptyToNull(p.path("id").asString("")),
                    "PROCESSED".equals(status) || "SENT".equals(status) ? null : emptyToNull(reason), now);
            if (changed && ("FAILED".equals(status) || "REVERSED".equals(status))) {
                jobs.tellOwner(ours.get(), now);   // once, with the change
            }
            return "UPDATED";
        });
        return result;
    }

    private java.util.Optional<MoneyStore.Refund> ourRefund(JsonNode refund) {
        java.util.Optional<MoneyStore.Refund> byNote = uuid(refund.path("notes").path("refund_id").asString(""))
                .flatMap(money::refund);
        return byNote.isPresent() ? byNote : money.refundByRazorpay(refund.path("id").asString(""));
    }

    private static JsonNode parse(byte[] body) {
        try {
            return JSON.readTree(body);
        } catch (JacksonException e) {
            throw ApiException.badRequest("NOT_JSON", "That isn't a webhook.");
        }
    }

    private static java.util.Optional<UUID> uuid(String s) {
        try {
            return s.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(UUID.fromString(s));
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
