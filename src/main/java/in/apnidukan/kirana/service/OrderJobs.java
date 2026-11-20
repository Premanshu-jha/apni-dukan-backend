package in.apnidukan.kirana.service;

import in.apnidukan.common.context.ShopDirectory;
import in.apnidukan.common.context.ShopMemberships;
import in.apnidukan.common.jobs.JobLeases;
import in.apnidukan.common.notify.Notifications;
import in.apnidukan.common.notify.Notifications.Notice;
import in.apnidukan.kirana.repo.KiranaSettingsStore;
import in.apnidukan.kirana.repo.MoneyStore;
import in.apnidukan.kirana.repo.MoneyStore.Payout;
import in.apnidukan.kirana.repo.MoneyStore.Refund;
import in.apnidukan.kirana.repo.OnlineStore;
import in.apnidukan.kirana.repo.OrderStore;
import in.apnidukan.kirana.repo.OrderStore.Checkout;
import in.apnidukan.kirana.repo.OrderStore.Order;
import in.apnidukan.kirana.repo.OrderStore.Part;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The jobs of kirana online orders, each on one backend at a time (JobLeases), each order in its own short
 * transaction, Razorpay always called outside one:
 * <ul>
 *   <li>every 15 s: parts not accepted within 3 minutes time out (refunded; the delivering shop's own part cancels
 *       its order)</li>
 *   <li>every minute: checkouts not paid within 30 minutes expire</li>
 *   <li>every 30 s: refunds are sent to Razorpay; one whose answer was lost is looked up by our id before asking
 *       again, so nothing is refunded twice</li>
 *   <li>every 10 minutes (once RazorpayX is set up): each shop's earnings due are gathered into one payout to its UPI
 *       ID; a shop whose UPI ID waits for the field team's re-check is skipped; a payout whose answer was lost is asked
 *       again with the same idempotency key</li>
 * </ul>
 */
@Component
public class OrderJobs {

    private static final Logger log = LoggerFactory.getLogger(OrderJobs.class);
    private static final int BATCH = 100;
    /** How long a refund claimed by a run is left to it before another run may look it up (a call takes &lt;= 30 s). */
    private static final Duration CLAIM = Duration.ofMinutes(5);

    private final OrderStore orders;
    private final OrderFlow flow;
    private final CheckoutService checkouts;
    private final MoneyStore money;
    private final OnlineStore online;
    private final KiranaSettingsStore settings;
    private final KiranaRazorpay razorpay;
    private final KiranaPayoutGateway payouts;
    private final ShopDirectory shops;
    private final ShopMemberships memberships;
    private final Notifications notifications;
    private final JobLeases leases;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public OrderJobs(OrderStore orders, OrderFlow flow, CheckoutService checkouts, MoneyStore money,
                     OnlineStore online, KiranaSettingsStore settings, KiranaRazorpay razorpay,
                     KiranaPayoutGateway payouts, ShopDirectory shops, ShopMemberships memberships,
                     Notifications notifications, JobLeases leases, TransactionTemplate transactions, Clock clock) {
        this.orders = orders;
        this.flow = flow;
        this.checkouts = checkouts;
        this.money = money;
        this.online = online;
        this.settings = settings;
        this.razorpay = razorpay;
        this.payouts = payouts;
        this.shops = shops;
        this.memberships = memberships;
        this.notifications = notifications;
        this.leases = leases;
        this.transactions = transactions;
        this.clock = clock;
    }

    // ---------- 3 minutes to accept ----------

    @Scheduled(fixedDelayString = "${app.kirana.timeouts-ms:15000}",
            initialDelayString = "${app.kirana.jobs-delay-ms:20000}")
    public void timeouts() {
        leases.runExclusively("kirana-order-timeouts", Duration.ofMinutes(2), () -> {
            for (UUID id : orders.overdueParts(clock.instant(), BATCH)) {
                timeOut(id);
            }
        });
    }

    /** One part past its 3 minutes, if still waiting. */
    void timeOut(UUID partId) {
        transactions.executeWithoutResult(s -> {
            Instant now = KiranaChecks.now(clock);
            Part peek = orders.part(partId).orElse(null);
            if (peek == null) {
                return;
            }
            Order orderPeek = orders.order(peek.orderId()).orElseThrow();
            Checkout c = orders.lockCheckout(orderPeek.checkoutId()).orElseThrow();
            Order o = orders.lockOrder(orderPeek.id()).orElseThrow();
            List<Part> parts = orders.lockPartsOf(o.id());
            Part p = parts.stream().filter(x -> x.id().equals(partId)).findFirst().orElseThrow();
            if (!"WAITING".equals(p.status()) || !now.isAfter(p.acceptBy())) {
                return;
            }
            flow.dropPart(c, o, parts, p, "TIMED_OUT", "Not accepted within 3 minutes", null, now);
        });
    }

    // ---------- 30 minutes to pay ----------

    @Scheduled(fixedDelayString = "${app.kirana.expiry-ms:60000}",
            initialDelayString = "${app.kirana.jobs-delay-ms:40000}")
    public void expiry() {
        leases.runExclusively("kirana-checkout-expiry", Duration.ofMinutes(5), () -> {
            // 5 minutes past the 30: a payment made at the last moment and confirmed a little late still counts
            Instant before = clock.instant().minus(CheckoutService.PAY_WITHIN.plusMinutes(5));
            for (UUID id : orders.pendingBefore(before, BATCH)) {
                transactions.executeWithoutResult(s -> checkouts.expire(id, KiranaChecks.now(clock)));
            }
        });
    }

    // ---------- refunds ----------

    @Scheduled(fixedDelayString = "${app.kirana.refunds-ms:30000}",
            initialDelayString = "${app.kirana.jobs-delay-ms:45000}")
    public void refunds() {
        if (!razorpay.configured()) {
            return;
        }
        leases.runExclusively("kirana-refunds", Duration.ofMinutes(10), () -> {
            for (UUID id : money.refundsDue(clock.instant(), BATCH)) {
                sendRefund(id);
            }
        });
    }

    /**
     * Sends one refund. Claimed first (two backends never send it together); a refund that may have been asked before
     * (its answer lost) is looked up at Razorpay by our id in its notes, and asked again only when Razorpay hasn't it.
     * A lookup that fails leaves it SENDING to be looked up again later: never asked twice.
     */
    void sendRefund(UUID id) {
        Instant now = KiranaChecks.now(clock);
        Refund r = transactions.execute(s -> money.claimRefund(id, now.plus(CLAIM), now).orElse(null));
        if (r == null) {
            return;
        }
        if (r.paymentId() == null) {
            transactions.executeWithoutResult(s -> money.refundFailed(r.id(), "No payment to refund", now));
            return;
        }
        if (r.tries() > 0) {
            try {
                Optional<KiranaRazorpay.KnownRefund> known = razorpay.refunds(r.paymentId()).stream()
                        .filter(k -> r.id().toString().equals(k.ourId())).findFirst();
                if (known.isPresent()) {
                    transactions.executeWithoutResult(s -> money.refundSent(r.id(), known.get().id(),
                            refundStatus(known.get().status()), now));
                    return;
                }
            } catch (RazorpayHttp.Silent | RazorpayHttp.Refused e) {
                transactions.executeWithoutResult(s -> money.refundRetry(r.id(), later(r.tries(), now),
                        "Razorpay didn't answer", now));
                return;
            }
        }
        try {
            KiranaRazorpay.KnownRefund k = razorpay.refund(r.paymentId(), r.amountPaise(), r.id());
            transactions.executeWithoutResult(s -> money.refundSent(r.id(), k.id(), refundStatus(k.status()), now));
        } catch (RazorpayHttp.Refused e) {
            log.error("Razorpay refused kirana refund {}: {}", r.id(), e.getMessage());
            transactions.executeWithoutResult(s -> money.refundFailed(r.id(), e.getMessage(), now));
        } catch (RazorpayHttp.Silent e) {
            // Left SENDING: looked up by its id at the next try, before asking again
            log.warn("Kirana refund {} got no answer; it is looked up next time", r.id());
            transactions.executeWithoutResult(s -> money.refundRetry(r.id(), later(r.tries(), now),
                    "Razorpay didn't answer", now));
        }
    }

    static String refundStatus(String razorpayStatus) {
        return switch (razorpayStatus) {
            case "processed" -> "PROCESSED";
            case "failed" -> "FAILED";
            default -> "SENT";
        };
    }

    private static Instant later(int tries, Instant now) {
        return now.plus(Duration.ofMinutes(Math.min(60, 1L << Math.min(tries, 6))));
    }

    // ---------- payouts ----------

    @Scheduled(fixedDelayString = "${app.kirana.payouts-ms:600000}",
            initialDelayString = "${app.kirana.jobs-delay-ms:60000}")
    public void payouts() {
        if (!payouts.configured()) {
            return;
        }
        leases.runExclusively("kirana-payouts", Duration.ofMinutes(30), () -> {
            Instant now = clock.instant();
            for (UUID id : money.sendingBefore(now.minus(Duration.ofMinutes(10)), BATCH)) {
                send(id);   // answers lost earlier: asked again with the same idempotency key
            }
            LocalDate today = KiranaChecks.today(now);
            for (MoneyStore.Due d : money.dueByShop(today, BATCH)) {
                gatherAndSend(d.shopId(), today);
            }
        });
    }

    /** Gathers a shop's earnings due into one payout and sends it (skipped while its UPI ID waits for a re-check). */
    void gatherAndSend(UUID shopId, LocalDate today) {
        String upi = settings.find(shopId).upiId();
        boolean held = online.find(shopId).map(OnlineStore.Online::upiRecheck).orElse(false);
        if (upi == null || held) {
            return;
        }
        UUID payoutId = UUID.randomUUID();
        Long amount = transactions.execute(s -> money.gather(payoutId, shopId, today, upi,
                KiranaChecks.now(clock)));
        if (amount != null && amount > 0) {
            send(payoutId);
        }
    }

    /** Sends one payout to RazorpayX (contact and fund account made once per shop and UPI ID). */
    void send(UUID payoutId) {
        Payout p = money.payout(payoutId).orElse(null);
        if (p == null || !"SENDING".equals(p.status())) {
            return;
        }
        Instant now = KiranaChecks.now(clock);
        try {
            MoneyStore.PayoutAccount account = money.payoutAccount(p.shopId(), p.upiId()).orElse(null);
            if (account == null) {
                String name = shops.find(p.shopId()).map(ShopDirectory.ShopCard::name).orElse("Clozest shop");
                String contact = payouts.contact(p.shopId(), name);
                String fund = payouts.upiAccount(contact, p.upiId());
                MoneyStore.PayoutAccount made = new MoneyStore.PayoutAccount(contact, fund);
                transactions.executeWithoutResult(s -> money.savePayoutAccount(p.shopId(), p.upiId(), made, now));
                account = money.payoutAccount(p.shopId(), p.upiId()).orElse(made);
            }
            KiranaPayoutGateway.Sent sent = payouts.pay(p.id(), account.fundAccountId(), p.amountPaise());
            String status = payoutStatus(sent.status());
            transactions.executeWithoutResult(s -> {
                boolean changed = money.payoutStatus(p.id(), status, sent.id(), null, now);
                if (changed && ("FAILED".equals(status) || "REVERSED".equals(status))) {
                    tellOwner(p, now);   // in the same transaction (notices are queued with the change)
                }
            });
        } catch (RazorpayHttp.Refused e) {
            log.error("RazorpayX refused kirana payout {}: {}", p.id(), e.getMessage());
            transactions.executeWithoutResult(s -> {
                if (money.payoutStatus(p.id(), "FAILED", null, e.getMessage(), now)) {
                    tellOwner(p, now);
                }
            });
        } catch (RazorpayHttp.Silent e) {
            transactions.executeWithoutResult(s -> money.touchPayout(p.id(), now));
        }
    }

    static String payoutStatus(String razorpayStatus) {
        return switch (razorpayStatus) {
            case "processed" -> "PROCESSED";
            case "reversed" -> "REVERSED";
            case "failed", "rejected", "cancelled" -> "FAILED";
            default -> "SENT";
        };
    }

    void tellOwner(Payout p, Instant now) {
        memberships.members(p.shopId()).stream().filter(m -> "OWNER".equals(m.role())).forEach(m ->
                notifications.queue(new Notice(m.userId(), "KIRANA_PAYOUT_FAILED", p.id(), now,
                        "Payment to your UPI ID failed", "₹" + KhataService.rupeesOf(p.amountPaise()) + " couldn't be sent "
                        + "to " + p.upiId() + ". Check your UPI ID in Settings. Once you change it and our team checks it, "
                        + "we send the money again.", "/kirana/online",
                        null)));
    }
}
