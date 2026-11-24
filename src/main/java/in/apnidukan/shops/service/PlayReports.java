package in.apnidukan.shops.service;

import in.apnidukan.common.jobs.JobLeases;
import in.apnidukan.shops.repo.PlayReportStore;
import in.apnidukan.shops.repo.SubscriptionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Razorpay payments made from the Play Store app, reported to Google (user choice billing: Google charges its reduced
 * fee on them, and must hear of each within 24 hours).
 * <ul>
 *   <li>{@link #recordFromApp}, from Razorpay's webhook (subscription.charged) in its transaction: for a
 *       subscription the app opened (it has Google's externalTransactionToken), each charge is queued once
 *       (play_external_transactions). The first charge (at once: the app offers paying only once the salon's free or
 *       paid time is over) is the first transaction, "s-&lt;subscription&gt;", with the token; each later one is
 *       "p-&lt;payment&gt;", naming the first.</li>
 *   <li>{@link #runOnce}, every minute (on any server: each round claims its own rows): sends what is due, the first
 *       before the charges that name it; Google's 409 (it has it already) counts as done; a refusal that won't change
 *       is kept as REFUSED and logged as an ERROR (to report by hand in Play Console), and so are the charges naming a
 *       refused first (Google can't take them either); anything else is tried again after 1, 2, 4 ... minutes (at
 *       most an hour apart). A round stops after {@link #ROUND} so it never runs into the next. Reports still waiting
 *       after 20 hours are logged as an ERROR. Google is asked outside any database transaction.</li>
 * </ul>
 * Payments made on the website are never reported: the token exists only for subscriptions the app opened.
 */
@Component
public class PlayReports {

    private static final Logger log = LoggerFactory.getLogger(PlayReports.class);
    static final int BATCH = 50;
    static final Duration MAX_WAIT = Duration.ofHours(1);
    static final Duration LATE = Duration.ofHours(20);
    /** A round's time; its claimed rows wait {@link #CLAIM} before another round may take them. */
    static final Duration ROUND = Duration.ofMinutes(3);
    static final Duration CLAIM = Duration.ofMinutes(10);

    private final PlayReportStore reports;
    private final SubscriptionStore subscriptions;
    private final GooglePlay googlePlay;
    private final JobLeases leases;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    private final Clock clock;
    private final boolean enabled;

    public PlayReports(PlayReportStore reports, SubscriptionStore subscriptions, GooglePlay googlePlay,
                       JobLeases leases, org.springframework.transaction.PlatformTransactionManager transactions,
                       Clock clock, @Value("${app.jobs.enabled:true}") boolean enabled) {
        this.reports = reports;
        this.subscriptions = subscriptions;
        this.googlePlay = googlePlay;
        this.leases = leases;
        this.transactions = new org.springframework.transaction.support.TransactionTemplate(transactions);
        this.clock = clock;
        this.enabled = enabled;
    }

    /**
     * Razorpay charged subscription {@code subscriptionId} (payment {@code paymentId}): if the app opened it, queue
     * its report. In the webhook's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordFromApp(UUID shopId, String subscriptionId, String paymentId, long amountPaise, Instant at,
                              Instant now) {
        Optional<String> token = subscriptions.appBillingToken(subscriptionId);
        if (token.isEmpty() || paymentId == null || paymentId.isBlank()) {
            return;   // the website's: not Google's business
        }
        String state = subscriptions.pincode(shopId).flatMap(PincodeStates::of).orElse(null);
        if (state == null) {
            log.error("Salon {} has a PIN code with no known state: report its in-app payment to Google by hand",
                    shopId);
            return;
        }
        int amount = (int) Math.max(0, Math.min(amountPaise, Integer.MAX_VALUE));
        Optional<String> initial = reports.initialOf(subscriptionId);
        if (initial.isEmpty()) {
            // Its first charge: the first transaction, with Google's token
            reports.queue("s-" + subscriptionId, paymentId, shopId, subscriptionId, null, token.get(), amount, at,
                    state, now);
        } else {
            // Its first charge again (a replayed webhook) adds nothing: one report per payment
            reports.queue("p-" + paymentId, paymentId, shopId, subscriptionId, initial.get(), null, amount, at, state,
                    now);
        }
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void scheduled() {
        if (enabled && googlePlay.configured()) {
            leases.runExclusively("play-reports", CLAIM, this::runOnce);
        }
    }

    /** One round; returns how many Google accepted. Called by tests too. */
    public int runOnce() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        long stopAt = System.nanoTime() + ROUND.toNanos();
        List<PlayReportStore.Pending> due = transactions.execute(s -> reports.claimDue(now, now.plus(CLAIM), BATCH));
        int done = 0;
        for (PlayReportStore.Pending p : due == null ? List.<PlayReportStore.Pending>of() : due) {
            if (System.nanoTime() - stopAt > 0) {
                // Out of time: the rest wait for their claim to run out (CLAIM), then go in a later round
                break;
            }
            if (p.initialId() != null) {
                String first = transactions.execute(s -> reports.initialStatus(p.initialId()).orElse("REFUSED"));
                if ("REFUSED".equals(first)) {
                    transactions.executeWithoutResult(s -> reports.refused(p.id(), clock.instant()));
                    log.error("In-app payment report {} names a first one Google refused: report both by hand in "
                            + "Play Console", p.id());
                    continue;
                }
                if (!"DONE".equals(first)) {
                    transactions.executeWithoutResult(s -> reports.retry(p.id(), now.plus(Duration.ofMinutes(1))));
                    continue;   // Google needs the first one first
                }
            }
            GooglePlay.Reported answer = googlePlay.reportExternal(new GooglePlay.ExternalTransaction(p.id(),
                    p.amountPaise(), p.transactionTime(), p.token(), p.initialId(), p.taxState()));
            Instant after = clock.instant();
            switch (answer) {
                case DONE -> {
                    transactions.executeWithoutResult(s -> reports.done(p.id(), after));
                    done++;
                }
                case REFUSED -> {
                    transactions.executeWithoutResult(s -> reports.refused(p.id(), after));
                    log.error("Google refused in-app payment report {}: report it by hand in Play Console", p.id());
                }
                default -> {
                    long minutes = Math.min(MAX_WAIT.toMinutes(), 1L << Math.min(p.attempts(), 6));
                    Instant next = after.plus(Duration.ofMinutes(minutes));
                    transactions.executeWithoutResult(s -> reports.retry(p.id(), next));
                }
            }
        }
        Integer late = transactions.execute(s -> reports.waitingOlderThan(now.minus(LATE)));
        if (late != null && late > 0) {
            log.error("{} in-app payment reports to Google are over 20 hours old (Google wants them within 24)", late);
        }
        return done;
    }
}
