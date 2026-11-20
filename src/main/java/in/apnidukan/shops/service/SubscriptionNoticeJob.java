package in.apnidukan.shops.service;

import in.apnidukan.common.jobs.JobLeases;
import in.apnidukan.common.notify.Notifications.Text;
import in.apnidukan.shops.repo.SubscriptionStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Tells salon owners without AutoPay about their trial's or paid month's end, each message once per end
 * (salon_subscriptions.notice_level, written in the same transaction as the message):
 * <ol>
 *   <li>{@value #REMIND_DAYS} days before: "your free trial ends on ..., set up AutoPay";</li>
 *   <li>at the end: "ended: pay within 3 days to stay visible";</li>
 *   <li>3 days later: "your salon is hidden from customers until you pay".</li>
 * </ol>
 * A salon that passed several steps between two rounds gets only the latest. Every hour between 9:00 and 21:00 India
 * time, on one server at a time, only while SUBSCRIPTIONS_ENFORCED is on (nothing is hidden, and nobody is told so,
 * before live payments work). Every round, at any hour, it also cancels at Razorpay the subscriptions still waiting
 * for it (razorpay_cancellations: a salon closed or handed over, a replaced checkout) and forgets Razorpay webhook ids
 * older than 30 days.
 */
@Component
public class SubscriptionNoticeJob {

    static final int REMIND_DAYS = 7;
    static final int BATCH = 200;
    static final Duration KEEP_EVENTS = Duration.ofDays(30);
    private static final LocalTime FROM = LocalTime.of(9, 0);
    private static final LocalTime UNTIL = LocalTime.of(21, 0);

    private final SubscriptionStore store;
    private final SubscriptionService subscriptions;
    private final JobLeases leases;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final boolean enabled;

    public SubscriptionNoticeJob(SubscriptionStore store, SubscriptionService subscriptions, JobLeases leases,
                                 PlatformTransactionManager transactions, Clock clock,
                                 @Value("${app.jobs.enabled:true}") boolean enabled) {
        this.store = store;
        this.subscriptions = subscriptions;
        this.leases = leases;
        this.transactions = new TransactionTemplate(transactions);
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 180_000)
    public void scheduled() {
        if (enabled) {
            leases.runExclusively("subscription-notices", Duration.ofMinutes(10), this::runOnce);
        }
    }

    /** One round; returns how many messages it queued. Called by tests too. */
    public int runOnce() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        transactions.executeWithoutResult(s -> store.forgetEvents(now.minus(KEEP_EVENTS), 1000));
        subscriptions.retryCancellations(BATCH);
        LocalTime time = LocalTime.ofInstant(now, SubscriptionService.INDIA);
        if (!subscriptions.enforced() || time.isBefore(FROM) || !time.isBefore(UNTIL)) {
            return 0;
        }
        int sent = 0;
        List<SubscriptionStore.Row> due;
        int before;
        do {
            before = sent;
            due = store.dueForNotice(now, Duration.ofDays(REMIND_DAYS), BATCH);
            for (SubscriptionStore.Row r : due) {
                int level = !now.isBefore(r.bookableUntil()) ? 3 : !now.isBefore(r.accessUntil()) ? 2 : 1;
                Boolean told = transactions.execute(s -> {
                    if (!store.noticeSent(r.shopId(), r.accessUntil(), level, now)) {
                        return false;   // another server, a payment or AutoPay meanwhile
                    }
                    boolean trial = r.paidUntil() == null || !r.paidUntil().isAfter(r.trialEndsAt());
                    String what = trial ? "free trial" : "paid month";
                    String day = SubscriptionService.day(r.accessUntil());
                    // Hidden from midnight starting bookableUntil's day: paying any time the day before is in time
                    String payBy = SubscriptionService.day(r.bookableUntil().minusSeconds(1));
                    String price = "₹" + SubscriptionService.PRICE_RUPEES + " a month";
                    // The same words for an owner who reads another language (notices/*.properties)
                    Text whatText = Text.of(trial ? "sub.freeTrial" : "sub.paidMonth");
                    Object dayValue = SubscriptionService.dayValue(r.accessUntil());
                    Text priceText = Text.of("sub.pricePerMonth", Map.of("price", SubscriptionService.PRICE_RUPEES));
                    switch (level) {
                        case 1 -> subscriptions.tell(r.shopId(), "SUBSCRIPTION_ENDING",
                                "Your " + what + " ends on " + day,
                                "Set up AutoPay (" + price + ") in Clozest so customers can keep booking you. "
                                        + "Nothing is taken before " + day + ".", now,
                                Text.of("SUBSCRIPTION_ENDING", Map.of("what", whatText, "day", dayValue,
                                        "price", priceText)));
                        case 2 -> subscriptions.tell(r.shopId(), "SUBSCRIPTION_ENDED",
                                r.autoPayFailed(now) ? "Your AutoPay payment didn't go through"
                                        : "Your " + what + " has ended",
                                "Pay ₹" + SubscriptionService.PRICE_RUPEES + " in Clozest by " + payBy
                                        + ", or customers won't find your salon from the next day.", now,
                                Text.of(r.autoPayFailed(now) ? "SUBSCRIPTION_ENDED_AUTOPAY" : "SUBSCRIPTION_ENDED",
                                        Map.of("what", whatText, "price", SubscriptionService.PRICE_RUPEES,
                                                "payBy", SubscriptionService.dayValue(
                                                        r.bookableUntil().minusSeconds(1)))));
                        default -> subscriptions.tell(r.shopId(), "SUBSCRIPTION_HIDDEN",
                                "Customers can't find your salon",
                                "Your salon is hidden on Clozest and takes no new online bookings until you pay "
                                        + price + ". Bookings already made stay.", now,
                                Text.of("SUBSCRIPTION_HIDDEN", Map.of("price", priceText)));
                    }
                    return true;
                });
                if (Boolean.TRUE.equals(told)) {
                    sent++;
                }
            }
        } while (due.size() == BATCH && sent > before);   // a full batch none of which changed: stop, never loop
        return sent;
    }
}
