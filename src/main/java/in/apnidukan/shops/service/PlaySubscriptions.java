package in.apnidukan.shops.service;

import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.jobs.JobLeases;
import in.apnidukan.shops.domain.ShopMember;
import in.apnidukan.shops.repo.SubscriptionStore;
import in.apnidukan.shops.repo.SubscriptionStore.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The salon's subscription bought with Google Play in the Play Store app (the owner picked Google Play on Google's
 * choice screen). Google charges and renews it; we follow what Google says:
 * <ul>
 *   <li>{@link #confirm}: the app's purchase, checked with Google (the product, the salon it was bought for, paid
 *       until when), and the salon paid up to its end; then acknowledged (else Google refunds it after 3 days; if
 *       Google doesn't answer now, {@link #recheck} tries again every few minutes). Paying twice is never right: the
 *       salon's Razorpay subscription, if any is still live, is cancelled, and so is an older Google Play purchase of
 *       the salon (bought with another Google account, say).</li>
 *   <li>{@link #notification}: Google's Real-time developer notifications (Pub/Sub push) say a subscription renewed,
 *       failed, was cancelled, refunded or expired; Google is asked again and the salon follows. A refund or revoke
 *       takes back the paid time Google Play gave. Pub/Sub's push address carries a secret
 *       (GOOGLE_PLAY_NOTIFICATION_SECRET), checked here.</li>
 *   <li>{@link #recheck}, every hour on one server: purchases not yet acknowledged, and those near or past their end,
 *       are asked about again (a notification lost on the way changes nothing for long); purchases a salon dropped
 *       are cancelled at Google until that is done.</li>
 * </ul>
 * Only time Google was paid for counts as paid: not a free trial (the app offers none), not Google's grace period
 * while it retries a failed payment. Test purchases (licence testers) count only with
 * app.google-play.allow-test-purchases (GOOGLE_PLAY_ALLOW_TEST_PURCHASES, off in production). Google is asked outside
 * database transactions; the row is locked only to write what Google said, and an older answer never overwrites a
 * newer one.
 */
@Service
public class PlaySubscriptions {

    private static final Logger log = LoggerFactory.getLogger(PlaySubscriptions.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final int RECHECK_BATCH = 50;
    /** A round's time (its lease is longer), and a dropped purchase's wait between cancel tries. */
    static final Duration ROUND = Duration.ofMinutes(5);
    static final Duration CANCEL_RETRY = Duration.ofMinutes(30);

    private final SubscriptionService subscriptions;
    private final SubscriptionStore store;
    private final GooglePlay googlePlay;
    private final JobLeases leases;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final byte[] notificationSecret;
    private final boolean allowTests;
    private final boolean enabled;

    public PlaySubscriptions(SubscriptionService subscriptions, SubscriptionStore store, GooglePlay googlePlay,
                             JobLeases leases, PlatformTransactionManager transactions, Clock clock,
                             @Value("${app.google-play.notification-secret:}") String notificationSecret,
                             @Value("${app.google-play.allow-test-purchases:false}") boolean allowTests,
                             @Value("${app.jobs.enabled:true}") boolean enabled) {
        this.subscriptions = subscriptions;
        this.store = store;
        this.googlePlay = googlePlay;
        this.leases = leases;
        this.transactions = new TransactionTemplate(transactions);
        this.clock = clock;
        this.notificationSecret = notificationSecret.strip().getBytes(StandardCharsets.UTF_8);
        this.allowTests = allowTests;
        this.enabled = enabled;
    }

    /**
     * POST /shops/me/subscription/google-play (owner): the purchase the app just made (or one it found not yet
     * confirmed). 400 PLAY_PURCHASE_INVALID when Google doesn't know it, it is another product, a test purchase in
     * production, or it was bought for another salon; 409 PLAY_PURCHASE_TAKEN when another salon has it (a purchase
     * the salon dropped earlier, say); 503 IN_APP_PAYMENTS_OFF / PAYMENTS_UNAVAILABLE. Sending it again is fine.
     */
    public SubscriptionService.View confirm(UUID userId, String purchaseToken) {
        ShopMember owner = subscriptions.owner(userId);
        UUID shopId = owner.getShopId();
        Instant asked = now();
        GooglePlay.PlaySubscription play = googlePlay.subscription(purchaseToken)
                .filter(p -> p.productId().equals(googlePlay.productId()))
                .filter(p -> p.accountId().equals(shopId.toString()))
                .filter(p -> allowTests || !p.test())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "PLAY_PURCHASE_INVALID",
                        "Google Play doesn't show this payment for your salon."));
        Boolean attached = transactions.execute(s -> {
            Row row = store.lock(shopId).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                    "SUBSCRIPTION_NOT_STARTED", "Your salon's free trial starts once our field team approves it."));
            Optional<Row> holder = store.lockByPlayToken(purchaseToken);
            if (holder.isPresent() && !holder.get().shopId().equals(shopId) || store.dropped(purchaseToken)) {
                throw new ApiException(HttpStatus.CONFLICT, "PLAY_PURCHASE_TAKEN",
                        "This Google Play payment no longer belongs to your salon.");
            }
            return apply(row, purchaseToken, play, asked, now());
        });
        // Only a purchase the salon has now (one it doesn't keep is left for Google to refund)
        if (Boolean.TRUE.equals(attached)) {
            acknowledge(purchaseToken, play);
        }
        return subscriptions.mine(userId);
    }

    /**
     * POST /payments/google-play/notifications?secret=...: one Pub/Sub push. 403 BAD_SECRET without the right secret.
     * Returns what happened; an answer other than 2xx makes Pub/Sub send it again (Google not answering: 503).
     */
    public String notification(String secret, byte[] body) {
        if (notificationSecret.length == 0 || secret == null
                || !MessageDigest.isEqual(notificationSecret, secret.strip().getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "BAD_SECRET", "Not from Google Play.");
        }
        String token;
        try {
            JsonNode push = JSON.readTree(body);
            byte[] data = Base64.getDecoder().decode(push.path("message").path("data").asString(""));
            JsonNode note = JSON.readTree(data);
            if (note.has("testNotification")) {
                return "TEST";
            }
            // A subscription's news, or a voided (refunded) purchase: either way, Google is asked about it again
            token = note.has("voidedPurchaseNotification")
                    ? note.path("voidedPurchaseNotification").path("purchaseToken").asString("")
                    : note.path("subscriptionNotification").path("purchaseToken").asString("");
        } catch (JacksonException | IllegalArgumentException e) {
            return "IGNORED";   // not ours to retry: a 2xx stops Pub/Sub sending it again
        }
        if (!GooglePlay.token(token)) {
            return "IGNORED";
        }
        return refresh(token);
    }

    /** Asks Google about one purchase and writes what it says. */
    String refresh(String purchaseToken) {
        Instant asked = now();
        Optional<GooglePlay.PlaySubscription> found = googlePlay.subscription(purchaseToken)
                .filter(p -> p.productId().equals(googlePlay.productId()))
                .filter(p -> allowTests || !p.test());
        if (found.isEmpty()) {
            transactions.executeWithoutResult(s -> store.playChecked(purchaseToken, now()));
            return "UNKNOWN";
        }
        GooglePlay.PlaySubscription play = found.get();
        Boolean known = transactions.execute(s -> {
            Optional<Row> row = store.lockByPlayToken(purchaseToken);
            if (row.isEmpty()) {
                if (store.dropped(purchaseToken)) {
                    return false;   // a purchase the salon dropped: never again
                }
                // Bought, but the app never confirmed it (closed at the wrong moment): the salon it was bought for
                row = shopOf(play.accountId()).flatMap(store::lock);
            }
            return row.isPresent() && apply(row.get(), purchaseToken, play, asked, now());
        });
        if (Boolean.TRUE.equals(known)) {
            acknowledge(purchaseToken, play);
            return "UPDATED";
        }
        return "UNKNOWN";
    }

    /**
     * Google's word (asked at {@code asked}) on a purchase of the salon, the row locked. A purchase other than the
     * salon's own is taken only when it is paid for and the salon's isn't renewing any more (a new purchase after the
     * old one ended or was cancelled), or it renews too and runs later (a second one bought meanwhile, with another
     * Google account): the older one is then dropped and cancelled. An old, cancelled purchase found later never
     * replaces a renewing one. Paid time moves the
     * salon's paid-up end; a refund or revoke takes back what Google Play gave; a renewing purchase stops the salon's
     * Razorpay subscription. Returns whether the salon has this purchase now.
     */
    private boolean apply(Row row, String purchaseToken, GooglePlay.PlaySubscription play, Instant asked,
                          Instant now) {
        Row salon = row;
        String own = row.playToken();
        if (own != null && !own.equals(purchaseToken)) {
            boolean newer = play.autoRenewing() && play.expiresAt() != null
                    && (row.playExpiresAt() == null || play.expiresAt().isAfter(row.playExpiresAt()));
            if (!play.paid() || row.playRenewing(now) && !newer) {
                return false;   // the salon keeps the purchase it has
            }
            store.dropPlay(row.shopId(), own, now);
            salon = store.lock(row.shopId()).orElseThrow();
        }
        if (!store.savePlay(salon.shopId(), purchaseToken, play.state(), play.expiresAt(),
                play.autoRenewing() && play.entitled(), play.acknowledged(), asked, now)) {
            return true;   // a newer answer is there already
        }
        if (play.paid()) {
            subscriptions.paidByPlay(salon, play.expiresAt(), now);
        } else if (play.over() && play.expiresAt() != null) {
            if (store.takeBackPlay(salon, play.expiresAt(), now)) {
                log.info("Google Play took back salon {}'s payment: its paid time goes back", salon.shopId());
            }
        }
        // Paying twice is never right: Google Play renews by itself, so the salon's Razorpay subscription stops
        if (play.paid() && play.autoRenewing()) {
            subscriptions.stopRazorpayForPlay(salon, now);
        }
        return true;
    }

    /** Acknowledged at Google if not yet; a failure is only logged (recheck tries again in a few minutes). */
    private void acknowledge(String purchaseToken, GooglePlay.PlaySubscription play) {
        if (play.acknowledged() || !play.entitled()) {
            return;
        }
        try {
            googlePlay.acknowledge(purchaseToken);
            transactions.executeWithoutResult(s -> store.playAcknowledged(purchaseToken));
        } catch (ApiException e) {
            log.warn("Google Play didn't take the acknowledgement now; will try again");
        }
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void scheduled() {
        if (enabled && googlePlay.configured()) {
            leases.runExclusively("play-recheck", ROUND.multipliedBy(2), this::recheck);
        }
    }

    /** One round; returns how many purchases were asked about. Called by tests too. */
    public int recheck() {
        Instant now = now();
        long stopAt = System.nanoTime() + ROUND.toNanos();
        List<String> tokens = transactions.execute(s -> store.playTokensToCheck(now, RECHECK_BATCH));
        int asked = 0;
        for (String token : tokens == null ? List.<String>of() : tokens) {
            if (System.nanoTime() - stopAt > 0) {
                break;
            }
            try {
                refresh(token);
                asked++;
            } catch (ApiException e) {
                log.warn("Google Play didn't answer about a subscription; will ask again");
                break;   // Google is down: the rest wait for the next round
            }
        }
        List<String> drops = transactions.execute(s -> store.dropsDue(now, now.plus(CANCEL_RETRY), RECHECK_BATCH));
        for (String token : drops == null ? List.<String>of() : drops) {
            if (System.nanoTime() - stopAt > 0) {
                break;
            }
            if (googlePlay.cancel(token)) {
                transactions.executeWithoutResult(s -> store.dropCancelled(token));
            } else {
                log.warn("Couldn't stop a dropped Google Play purchase renewing; will try again");
            }
        }
        return asked;
    }

    private static Optional<UUID> shopOf(String id) {
        try {
            return id == null || id.isEmpty() ? Optional.empty() : Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
