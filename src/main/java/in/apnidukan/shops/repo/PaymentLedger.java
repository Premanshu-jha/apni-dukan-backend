package in.apnidukan.shops.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * subscription_payments (V93): every salon subscription payment and refund, with where it came from. Written next to
 * the change it belongs to (the Razorpay webhook, Google Play's notice, the one-time import), in the same transaction;
 * read by the analytics module. A payment or refund told twice is kept once (unique kind + external id).
 */
@Repository
public class PaymentLedger {

    /** Where a payment came from (subscription_payments.source). */
    public enum Source {
        /** Razorpay, from the website */
        WEBSITE,
        /** Razorpay picked in the Play Store app (Google's user choice billing) */
        APP_RAZORPAY,
        /** Google Play's own billing */
        GOOGLE_PLAY
    }

    /** A charge as it is recorded. */
    public record Charge(UUID shopId, Source source, String externalId, long amountPaise, Long feePaise,
                         Instant paidAt, Instant periodEnd, boolean test, boolean imported) {
    }

    private final JdbcTemplate jdbc;

    public PaymentLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records a charge; false if it was there already (a webhook or notice told twice). A refund of it that arrived
     * first (waiting) is recorded with it.
     */
    public boolean charge(Charge c, Instant now) {
        lockPayment(c.externalId());
        boolean added = jdbc.update("INSERT INTO subscription_payments (id, shop_id, source, kind, external_id, amount_paise, "
                        + "fee_paise, paid_at, period_end, test, imported, created_at) "
                        + "VALUES (?, ?, ?, 'CHARGE', ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (kind, external_id) DO NOTHING",
                UUID.randomUUID(), c.shopId(), c.source().name(), c.externalId(), c.amountPaise(), c.feePaise(),
                Timestamp.from(c.paidAt()), c.periodEnd() == null ? null : Timestamp.from(c.periodEnd()), c.test(),
                c.imported(), Timestamp.from(now)) == 1;
        if (added) {
            for (Map<String, Object> w : jdbc.queryForList("DELETE FROM subscription_refunds_waiting "
                    + "WHERE payment_id = ? RETURNING refund_id, amount_paise, refunded_at", c.externalId())) {
                refund(c.externalId(), (String) w.get("refund_id"), ((Number) w.get("amount_paise")).longValue(),
                        ((java.sql.Timestamp) w.get("refunded_at")).toInstant(), false, now);
            }
        }
        return added;
    }

    /**
     * One payment's charge and its early refunds one at a time (until the transaction ends): a refund kept waiting
     * at the same moment its charge is recorded is never missed by both.
     */
    public void lockPayment(String paymentId) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", paymentId);
    }

    /** A refund whose payment isn't recorded (yet): kept until its charge is (or 30 days). */
    public void waitForCharge(String paymentId, String refundId, long amountPaise, Instant at, Instant now) {
        jdbc.update("INSERT INTO subscription_refunds_waiting (refund_id, payment_id, amount_paise, refunded_at, "
                + "created_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT (refund_id) DO NOTHING", refundId, paymentId,
                Math.abs(amountPaise), Timestamp.from(at), Timestamp.from(now));
    }

    /**
     * Records a refund of a recorded charge ({@code chargeId}: its external id), for the same salon and source, as a
     * minus amount. False if that charge isn't one of ours (a kirana order's payment, say) or the refund is there.
     */
    public boolean refund(String chargeId, String refundId, long amountPaise, Instant at, boolean imported,
                          Instant now) {
        return jdbc.update("INSERT INTO subscription_payments (id, shop_id, source, kind, external_id, charge_id, "
                        + "amount_paise, paid_at, test, imported, created_at) "
                        + "SELECT ?, shop_id, source, 'REFUND', ?, external_id, ?, ?, test, ?, ? "
                        + "FROM subscription_payments WHERE kind = 'CHARGE' AND external_id = ? "
                        + "ON CONFLICT (kind, external_id) DO NOTHING",
                UUID.randomUUID(), refundId, -Math.abs(amountPaise), Timestamp.from(at), imported,
                Timestamp.from(now), chargeId) == 1;
    }

    /** A recorded charge's amount (to give it back in full: Google Play's refunds say no amount). */
    public Optional<Long> chargeAmount(String externalId) {
        return jdbc.queryForList("SELECT amount_paise FROM subscription_payments WHERE kind = 'CHARGE' "
                + "AND external_id = ?", Long.class, externalId).stream().findFirst();
    }

    /**
     * Where a Razorpay subscription's payments come from: the Play Store app's (the one opened from Google's choice
     * screen, or one it reported to Google before) or the website's.
     */
    public Source razorpaySource(UUID shopId, String subscriptionId) {
        Boolean app = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM salon_subscriptions WHERE shop_id = ? "
                + "AND app_billing_subscription_id = ?) OR EXISTS (SELECT 1 FROM play_external_transactions "
                + "WHERE razorpay_subscription_id = ?)", Boolean.class, shopId, subscriptionId, subscriptionId);
        return Boolean.TRUE.equals(app) ? Source.APP_RAZORPAY : Source.WEBSITE;
    }

    /** Every Razorpay subscription a salon has had that we still know of, for the one-time import. */
    public record KnownSubscription(UUID shopId, String subscriptionId) {
    }

    public List<KnownSubscription> knownRazorpaySubscriptions() {
        return jdbc.query("SELECT shop_id, razorpay_subscription_id FROM salon_subscriptions "
                        + "WHERE razorpay_subscription_id IS NOT NULL "
                        + "UNION SELECT shop_id, app_billing_subscription_id FROM salon_subscriptions "
                        + "WHERE app_billing_subscription_id IS NOT NULL "
                        + "UNION SELECT shop_id, subscription_id FROM razorpay_cancellations "
                        + "UNION SELECT shop_id, razorpay_subscription_id FROM play_external_transactions",
                (rs, i) -> new KnownSubscription(rs.getObject(1, UUID.class), rs.getString(2)));
    }

    public boolean shopExists(UUID shopId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM shops WHERE id = ?)",
                Boolean.class, shopId));
    }

    /** Whether a one-time job ran to the end already (analytics_jobs). */
    public boolean done(String job) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM analytics_jobs WHERE name = ?)",
                Boolean.class, job));
    }

    public void markDone(String job, Instant now) {
        jdbc.update("INSERT INTO analytics_jobs (name, done_at) VALUES (?, ?) ON CONFLICT (name) DO NOTHING", job,
                Timestamp.from(now));
    }
}
