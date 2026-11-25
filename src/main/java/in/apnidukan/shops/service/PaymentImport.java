package in.apnidukan.shops.service;

import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.jobs.JobLeases;
import in.apnidukan.shops.repo.PaymentLedger;
import in.apnidukan.shops.repo.PaymentLedger.KnownSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The one-time import of past subscription payments from Razorpay into the payments ledger (analytics), so the money
 * history starts complete: payments made before the ledger existed were only seen as a moved paid-up end.
 *
 * For every Razorpay subscription a salon has had that we still know of (current, the app's, cancelled ones, ones
 * reported to Google), its paid invoices are read, then each invoice's payment (amount, fee) and refunds. Rows are
 * added like a webhook's, marked imported; one already there (a webhook since) is left as it is. Runs once, with
 * live keys only (test-mode payments aren't money): tried an hour after start and every hour until it has gone through
 * every subscription without Razorpay failing once, then marked done for good. One copy of the backend at a time.
 */
@Service
public class PaymentImport {

    private static final Logger log = LoggerFactory.getLogger(PaymentImport.class);

    static final String JOB = "razorpay-import";
    private static final Duration MAX_RUN = Duration.ofMinutes(30);
    /** A round stops starting new subscriptions after this, well inside the lease (the next round goes on). */
    private static final Duration ROUND = Duration.ofMinutes(20);
    /** Razorpay's API allows a few requests a second: a pause between them keeps the import well under it. */
    private static final Duration PAUSE = Duration.ofMillis(250);

    private final PaymentLedger ledger;
    private final RazorpayGateway razorpay;
    private final JobLeases leases;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final boolean enabled;

    public PaymentImport(PaymentLedger ledger, RazorpayGateway razorpay, JobLeases leases,
                         PlatformTransactionManager transactions, Clock clock,
                         @Value("${app.jobs.enabled:true}") boolean enabled) {
        this.ledger = ledger;
        this.razorpay = razorpay;
        this.leases = leases;
        this.transactions = new TransactionTemplate(transactions);
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
    public void scheduled() {
        if (enabled) {
            leases.runExclusively(JOB, MAX_RUN, this::runOnce);
        }
    }

    /**
     * The import, if it is due: live Razorpay keys and not done yet. Returns how many rows it added (0 when not due).
     *
     * The subscriptions: every one on our plan as Razorpay lists them (replaced and cancelled ones too, the salon from
     * their notes), and every one we still have stored. Each subscription done is marked (analytics_jobs
     * "razorpay-import:sub_…"), so a round cut short (Razorpay busy, the round's time up) goes on where it stopped.
     * One Razorpay doesn't know (400/404: made with the test keys, say) is skipped for good.
     */
    public int runOnce() {
        if (!razorpay.configured() || razorpay.testMode() || ledger.done(JOB)) {
            return 0;
        }
        Instant until = clock.instant().plus(ROUND);
        List<KnownSubscription> all;
        try {
            all = subscriptions();
        } catch (ApiException e) {
            log.warn("Couldn't list the plan's subscriptions from Razorpay for the import: {}", e.getCode());
            return 0;
        } catch (RazorpayGateway.UnknownToRazorpay e) {
            // Razorpay doesn't know our plan id (a test-mode plan with live keys): the stored subscriptions still
            log.warn("Razorpay doesn't know the plan for the import: importing the stored subscriptions only");
            all = ledger.knownRazorpaySubscriptions();
        }
        int added = 0;
        boolean complete = true;
        for (KnownSubscription known : all) {
            String mark = JOB + ":" + known.subscriptionId();
            if (ledger.done(mark)) {
                continue;
            }
            if (clock.instant().isAfter(until)) {
                complete = false;   // the next round goes on from here
                break;
            }
            try {
                added += importSubscription(known);
                Instant now = clock.instant();
                transactions.executeWithoutResult(st -> ledger.markDone(mark, now));
            } catch (RazorpayGateway.UnknownToRazorpay e) {
                log.warn("Skipping subscription {} in the import: {}", known.subscriptionId(), e.getMessage());
                Instant now = clock.instant();
                transactions.executeWithoutResult(st -> ledger.markDone(mark, now));
            } catch (ApiException e) {
                // Razorpay not answering (or too many requests): the rest goes on; the next round tries this one again
                complete = false;
                log.warn("Couldn't read subscription {} from Razorpay for the import: {}", known.subscriptionId(),
                        e.getCode());
            } catch (IllegalArgumentException e) {
                log.warn("Skipping a stored subscription id that isn't Razorpay's: {}", known.subscriptionId());
            }
        }
        if (complete) {
            Instant now = clock.instant();
            transactions.executeWithoutResult(st -> ledger.markDone(JOB, now));
            log.info("Imported {} past subscription payments and refunds from Razorpay", added);
        }
        return added;
    }

    /** Razorpay's list for our plan (with a salon we have), then the stored ones it didn't name; each id once. */
    private List<KnownSubscription> subscriptions() {
        java.util.Map<String, KnownSubscription> byId = new java.util.LinkedHashMap<>();
        for (int skip = 0; ; skip += 100) {
            List<RazorpayGateway.PlanSubscription> page = razorpay.planSubscriptions(skip);
            pause();
            for (RazorpayGateway.PlanSubscription p : page) {
                UUID shop = uuid(p.shopId());
                if (shop != null && ledger.shopExists(shop)) {
                    byId.putIfAbsent(p.id(), new KnownSubscription(shop, p.id()));
                }
            }
            if (page.size() < 100 || skip > 1_000_000) {
                break;
            }
        }
        for (KnownSubscription k : ledger.knownRazorpaySubscriptions()) {
            byId.putIfAbsent(k.subscriptionId(), k);
        }
        return List.copyOf(byId.values());
    }

    private static UUID uuid(String text) {
        try {
            return text == null ? null : UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private int importSubscription(KnownSubscription known) {
        int added = 0;
        for (int skip = 0; ; skip += 100) {
            List<RazorpayGateway.PaidInvoice> invoices = razorpay.paidInvoices(known.subscriptionId(), skip);
            pause();
            for (RazorpayGateway.PaidInvoice invoice : invoices) {
                RazorpayGateway.Payment payment;
                try {
                    payment = razorpay.payment(invoice.paymentId());
                } catch (RazorpayGateway.UnknownToRazorpay e) {
                    log.warn("Skipping payment {} in the import: Razorpay doesn't know it", invoice.paymentId());
                    pause();
                    continue;   // only this one: the subscription's other payments still come
                }
                pause();
                List<RazorpayGateway.Refund> refunds = payment.refundedPaise() > 0
                        ? razorpay.refunds(payment.id()) : List.of();
                if (payment.refundedPaise() > 0) {
                    pause();
                }
                added += save(known, invoice, payment, refunds);
            }
            if (invoices.size() < 100) {
                return added;
            }
        }
    }

    private int save(KnownSubscription known, RazorpayGateway.PaidInvoice invoice, RazorpayGateway.Payment payment,
                     List<RazorpayGateway.Refund> refunds) {
        Instant now = clock.instant();
        Integer added = transactions.execute(s -> {
            int n = 0;
            if (ledger.charge(new PaymentLedger.Charge(known.shopId(),
                    ledger.razorpaySource(known.shopId(), known.subscriptionId()), payment.id(),
                    payment.amountPaise(), payment.feePaise(), payment.createdAt(), invoice.periodEnd(), false, true),
                    now)) {
                n++;
            }
            for (RazorpayGateway.Refund refund : refunds) {
                if ("processed".equals(refund.status())
                        && ledger.refund(payment.id(), refund.id(), refund.amountPaise(), refund.createdAt(), true,
                        now)) {
                    n++;
                }
            }
            return n;
        });
        return added == null ? 0 : added;
    }

    private static void pause() {
        try {
            Thread.sleep(PAUSE.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
