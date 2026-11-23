package in.apnidukan.shops.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * salon_subscriptions (V65): each salon's trial, what Razorpay was paid for, and until when customers can book it.
 * razorpay_events: the webhooks already handled.
 */
@Repository
public class SubscriptionStore {

    /** After access ends, this long before the salon is hidden from customers (the grace to pay). */
    public static final Duration GRACE = Duration.ofDays(3);
    /**
     * AutoPay counts as running until this long after access ended (or after it was set up, if that is later: paying
     * once the trial is over) without a charge arriving (Razorpay charges at once or at the period's end and tells us
     * within minutes): later, the salon is treated as unpaid again, so the owner sees it, gets the reminders and can
     * pay another way, instead of being hidden while the app says "AutoPay on".
     */
    public static final Duration AUTOPAY_LATE = Duration.ofDays(1);
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    /** trialsFrom: {@link #bookableAfter} in SQL, of the new trial end (?) or the paid end if later. */
    private static final String TRIALS_FROM = "UPDATE salon_subscriptions SET trial_ends_at = ?, bookable_until = "
            + "((date_trunc('day', (GREATEST(?::timestamptz, COALESCE(paid_until, ?::timestamptz)) + INTERVAL '3 days') "
            + "AT TIME ZONE 'Asia/Kolkata') + INTERVAL '1 day') AT TIME ZONE 'Asia/Kolkata'), "
            + "notice_level = 0, notice_for = NULL, updated_at = ? WHERE trial_ends_at < ?";
    /**
     * In SQL: AutoPay set up and not overdue as of the ? (now), or a Google Play subscription renewing by itself and
     * not more than a day past its end as of the second ? (now again).
     */
    private static final String AUTOPAY_RUNNING = "((COALESCE(razorpay_status, '') IN ('AUTHENTICATED', 'ACTIVE') "
            + "AND GREATEST(trial_ends_at, COALESCE(paid_until, trial_ends_at), "
            + "COALESCE(subscription_made_at, trial_ends_at)) + INTERVAL '1 day' > ?) "
            + "OR (play_auto_renewing AND play_expires_at + INTERVAL '1 day' > ?))";

    /**
     * When customers stop finding a salon whose access ends at {@code access}: the end of the third day after it,
     * India time (midnight starting the next day), so "pay by <day>" holds for that whole day.
     */
    public static Instant bookableAfter(Instant access) {
        return access.plus(GRACE).atZone(INDIA).toLocalDate().plusDays(1).atStartOfDay(INDIA).toInstant();
    }

    /**
     * One salon's row.
     * @param accessUntil   the later of the trial's end and the paid-up end
     * @param bookableUntil {@link #bookableAfter} accessUntil
     */
    public record Row(UUID shopId, Instant trialEndsAt, Instant paidUntil, Instant accessUntil, Instant bookableUntil,
                      String subscriptionId, Instant subscriptionMadeAt, String razorpayStatus,
                      Instant razorpayStatusAt, Instant creatingSince, int noticeLevel, Instant noticeFor,
                      boolean playAutoRenewing, Instant playExpiresAt, String playToken, Instant playPaidUntil,
                      String playState) {

        /** A row without a Google Play subscription (everything before the Play Store app). */
        public Row(UUID shopId, Instant trialEndsAt, Instant paidUntil, Instant accessUntil, Instant bookableUntil,
                   String subscriptionId, Instant subscriptionMadeAt, String razorpayStatus, Instant razorpayStatusAt,
                   Instant creatingSince, int noticeLevel, Instant noticeFor) {
            this(shopId, trialEndsAt, paidUntil, accessUntil, bookableUntil, subscriptionId, subscriptionMadeAt,
                    razorpayStatus, razorpayStatusAt, creatingSince, noticeLevel, noticeFor, false, null, null, null, null);
        }

        /**
         * A Google Play subscription renews by itself and isn't more than {@link #AUTOPAY_LATE} past its end (Google
         * tells us of each renewal within minutes): counts as AutoPay running.
         */
        public boolean playRenewing(Instant now) {
            return playAutoRenewing && playExpiresAt != null && now.isBefore(playExpiresAt.plus(AUTOPAY_LATE));
        }

        /** AutoPay was approved and Razorpay hasn't said it stopped (AUTHENTICATED or ACTIVE). */
        public boolean autoPaySetUp() {
            return "AUTHENTICATED".equals(razorpayStatus) || "ACTIVE".equals(razorpayStatus);
        }

        /**
         * AutoPay is running (Razorpay will charge by itself): no reminders, nothing to pay. Not once a charge is
         * more than {@link #AUTOPAY_LATE} overdue (a lost webhook, a mandate stopped without word), counted from the
         * period's end or, when set up after it, from then.
         */
        public boolean autoPayOn(Instant now) {
            Instant from = subscriptionMadeAt != null && subscriptionMadeAt.isAfter(accessUntil)
                    ? subscriptionMadeAt : accessUntil;
            return autoPaySetUp() && now.isBefore(from.plus(AUTOPAY_LATE)) || playRenewing(now);
        }

        /**
         * The last AutoPay charge failed (Razorpay retrying, or it gave up), or one is overdue; or Google Play
         * couldn't take a renewal (its grace period or account hold: the owner fixes the payment method in the Play
         * Store).
         */
        public boolean autoPayFailed(Instant now) {
            return "PENDING".equals(razorpayStatus) || "HALTED".equals(razorpayStatus)
                    || autoPaySetUp() && !autoPayOn(now)
                    || "SUBSCRIPTION_STATE_IN_GRACE_PERIOD".equals(playState)
                    || "SUBSCRIPTION_STATE_ON_HOLD".equals(playState);
        }
    }

    private static final String COLUMNS = "shop_id, trial_ends_at, paid_until, bookable_until, "
            + "razorpay_subscription_id, subscription_made_at, razorpay_status, razorpay_status_at, creating_since, "
            + "notice_level, notice_for, play_auto_renewing, play_expires_at, play_purchase_token, play_paid_until, "
            + "play_state";

    private static final RowMapper<Row> ROW = (rs, i) -> {
        Instant trial = rs.getTimestamp("trial_ends_at").toInstant();
        Instant paid = instant(rs.getTimestamp("paid_until"));
        Instant access = paid != null && paid.isAfter(trial) ? paid : trial;
        return new Row(rs.getObject("shop_id", UUID.class), trial, paid, access,
                rs.getTimestamp("bookable_until").toInstant(), rs.getString("razorpay_subscription_id"),
                instant(rs.getTimestamp("subscription_made_at")), rs.getString("razorpay_status"),
                instant(rs.getTimestamp("razorpay_status_at")), instant(rs.getTimestamp("creating_since")),
                rs.getInt("notice_level"), instant(rs.getTimestamp("notice_for")), rs.getBoolean("play_auto_renewing"),
                instant(rs.getTimestamp("play_expires_at")), rs.getString("play_purchase_token"),
                instant(rs.getTimestamp("play_paid_until")), rs.getString("play_state"));
    };

    private final JdbcTemplate jdbc;

    public SubscriptionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Row> find(UUID shopId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM salon_subscriptions WHERE shop_id = ?", ROW, shopId)
                .stream().findFirst();
    }

    /** The row, locked until the transaction ends (one change at a time per salon). */
    public Optional<Row> lock(UUID shopId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM salon_subscriptions WHERE shop_id = ? FOR UPDATE", ROW, shopId)
                .stream().findFirst();
    }

    /** The row of a Razorpay subscription, locked. */
    public Optional<Row> lockBySubscription(String subscriptionId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM salon_subscriptions WHERE razorpay_subscription_id = ? "
                + "FOR UPDATE", ROW, subscriptionId).stream().findFirst();
    }

    /**
     * The trial starts (the salon's first approval): a row with trialEnds, unless the salon has one already (approved
     * before, suspended and approved again: its trial isn't given again). Returns whether it was made.
     */
    public boolean startTrial(UUID shopId, Instant trialEnds, Instant now) {
        return jdbc.update("INSERT INTO salon_subscriptions (shop_id, trial_ends_at, bookable_until, created_at, "
                        + "updated_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT (shop_id) DO NOTHING",
                shopId, ts(trialEnds), ts(bookableAfter(trialEnds)), ts(now), ts(now)) == 1;
    }

    /** Marks that this server is asking Razorpay for a subscription now (see SubscriptionService.checkout). */
    public void markCreating(UUID shopId, Instant now) {
        jdbc.update("UPDATE salon_subscriptions SET creating_since = ?, updated_at = ? WHERE shop_id = ?",
                ts(now), ts(now), shopId);
    }

    /**
     * Razorpay made it: kept as the salon's subscription (CREATED until the owner approves AutoPay), with no status
     * time, so any event Razorpay sends about it counts. Only if nothing changed since this checkout marked the row
     * ({@code markedAt}): still its mark (no later tap, no closing or hand-over cleared it) and no payment arrived
     * ({@code paidUntilSeen}). False then, and nothing is saved: the caller cancels it at Razorpay.
     */
    public boolean saveCreated(UUID shopId, String subscriptionId, String status, Instant markedAt,
                               Instant paidUntilSeen, Instant now) {
        return jdbc.update("UPDATE salon_subscriptions SET razorpay_subscription_id = ?, subscription_made_at = ?, "
                        + "razorpay_status = ?, razorpay_status_at = NULL, creating_since = NULL, updated_at = ? "
                        + "WHERE shop_id = ? AND creating_since = ? AND paid_until IS NOT DISTINCT FROM ?",
                subscriptionId, ts(now), status, ts(now), shopId, ts(markedAt), ts(paidUntilSeen)) == 1;
    }

    /**
     * The checkout's signed answer: AutoPay approved. Only over CREATED (or nothing), and without a status time, so
     * Razorpay's own events, whatever their time, still count (a failed first charge after it, say).
     */
    public void confirmAuthenticated(UUID shopId, Instant now) {
        jdbc.update("UPDATE salon_subscriptions SET razorpay_status = 'AUTHENTICATED', updated_at = ? "
                + "WHERE shop_id = ? AND (razorpay_status IS NULL OR razorpay_status = 'CREATED')", ts(now), shopId);
    }

    /**
     * The salon no longer has a Razorpay subscription (cancelled for a closing or a new owner): what was paid stays,
     * the reminders start again.
     */
    public void forgetSubscription(UUID shopId, Instant now) {
        jdbc.update("UPDATE salon_subscriptions SET razorpay_subscription_id = NULL, subscription_made_at = NULL, "
                + "razorpay_status = NULL, razorpay_status_at = NULL, creating_since = NULL, notice_level = 0, "
                + "notice_for = NULL, updated_at = ? WHERE shop_id = ?", ts(now), shopId);
    }

    /** A Razorpay subscription to cancel (razorpay_cancellations): tried at once, then by the job until it is done. */
    public void wantCancelled(String subscriptionId, UUID shopId, Instant now) {
        jdbc.update("INSERT INTO razorpay_cancellations (subscription_id, shop_id, wanted_at) VALUES (?, ?, ?) "
                + "ON CONFLICT (subscription_id) DO NOTHING", subscriptionId, shopId, ts(now));
    }

    /** Razorpay cancelled it (or it was over already). */
    public void cancelled(String subscriptionId) {
        jdbc.update("DELETE FROM razorpay_cancellations WHERE subscription_id = ?", subscriptionId);
    }

    /** Cancellations still to make, oldest first. */
    public List<String> cancellationsDue(int limit) {
        return jdbc.queryForList("SELECT subscription_id FROM razorpay_cancellations ORDER BY wanted_at LIMIT ?",
                String.class, limit);
    }

    /** This checkout ({@code markedAt}) is over without a subscription: another try may start at once. */
    public void clearCreating(UUID shopId, Instant markedAt, Instant now) {
        jdbc.update("UPDATE salon_subscriptions SET creating_since = NULL, updated_at = ? "
                + "WHERE shop_id = ? AND creating_since = ?", ts(now), shopId, ts(markedAt));
    }

    /**
     * Razorpay's status for the salon's subscription as of {@code at} (a webhook's event time); an older one than what
     * is kept changes nothing. Returns whether it was kept.
     */
    public boolean saveStatus(UUID shopId, String status, Instant at, Instant now) {
        return jdbc.update("UPDATE salon_subscriptions SET razorpay_status = ?, razorpay_status_at = ?, updated_at = ? "
                        + "WHERE shop_id = ? AND (razorpay_status_at IS NULL OR razorpay_status_at <= ?)",
                status, ts(at), ts(now), shopId, ts(at)) == 1;
    }

    /** The owner's name, email and phone, to fill in the checkout. */
    public record Contact(String name, String email, String phone) {
    }

    public Optional<Contact> contact(UUID userId) {
        return jdbc.query("SELECT name, email, phone FROM users WHERE id = ? AND deleted_at IS NULL",
                (rs, i) -> new Contact(rs.getString(1), rs.getString(2), rs.getString(3)), userId).stream().findFirst();
    }

    /**
     * A month was paid up to {@code paidUntil}, the row locked ({@code row}). Never moves back: an old or replayed
     * webhook changes nothing, and false is returned (nobody is told twice). The salon is bookable again at once.
     */
    public boolean paid(Row row, Instant paidUntil, Instant now) {
        if (row.paidUntil() != null && !paidUntil.isAfter(row.paidUntil())) {
            return false;
        }
        Instant access = paidUntil.isAfter(row.trialEndsAt()) ? paidUntil : row.trialEndsAt();
        return jdbc.update("UPDATE salon_subscriptions SET paid_until = ?, bookable_until = ?, updated_at = ? "
                        + "WHERE shop_id = ? AND (paid_until IS NULL OR paid_until < ?)",
                ts(paidUntil), ts(bookableAfter(access)), ts(now), row.shopId(), ts(paidUntil)) == 1;
    }

    /**
     * The trial ends at {@code trialEnds}, the row locked (a test salon set up for Razorpay's reviewer). A checkout
     * opened before and not approved (CREATED) is dropped: its first charge was the old trial's end.
     */
    public void setTrialEnd(Row row, Instant trialEnds, Instant now) {
        Instant access = row.paidUntil() != null && row.paidUntil().isAfter(trialEnds) ? row.paidUntil() : trialEnds;
        jdbc.update("UPDATE salon_subscriptions SET trial_ends_at = ?, bookable_until = ?, notice_level = 0, "
                        + "notice_for = NULL, "
                        + "razorpay_subscription_id = CASE WHEN razorpay_status = 'CREATED' THEN NULL "
                        + "ELSE razorpay_subscription_id END, "
                        + "subscription_made_at = CASE WHEN razorpay_status = 'CREATED' THEN NULL "
                        + "ELSE subscription_made_at END, "
                        + "razorpay_status = CASE WHEN razorpay_status = 'CREATED' THEN NULL ELSE razorpay_status END, "
                        + "updated_at = ? WHERE shop_id = ?",
                ts(trialEnds), ts(bookableAfter(access)), ts(now), row.shopId());
    }

    /**
     * Unpaid salons are hidden from now on (SUBSCRIPTIONS_ENFORCED switched on): remembered once
     * (subscription_enforcement). Returns whether this call did it; on every server at once, one does.
     */
    public boolean enforcementStarts(Instant now) {
        return jdbc.update("INSERT INTO subscription_enforcement (id, started_at) VALUES (1, ?) "
                + "ON CONFLICT (id) DO NOTHING", ts(now)) == 1;
    }

    /**
     * When enforcement starts: every salon gets its free trial counted from now, if that is later than its own (it
     * keeps what it paid), and the reminders start from the beginning; live salons without a row (approved by an
     * older version) get one. Returns how many rows changed.
     */
    public int trialsFrom(Instant now, Duration trial) {
        Instant ends = now.plus(trial);
        int added = jdbc.update("INSERT INTO salon_subscriptions (shop_id, trial_ends_at, bookable_until, created_at, "
                        + "updated_at) SELECT id, ?, ?, ?, ? FROM shops WHERE type = 'SALON' "
                        + "AND (status = 'APPROVED' OR ever_approved) ON CONFLICT (shop_id) DO NOTHING",
                ts(ends), ts(bookableAfter(ends)), ts(now), ts(now));
        int moved = jdbc.update(TRIALS_FROM, ts(ends), ts(ends), ts(ends), ts(now), ts(ends));
        return added + moved;
    }

    /** dueForNotice: the access end, and the level due now (3 once hidden, 2 once ended, else 1). */
    private static final String ACCESS = "GREATEST(trial_ends_at, COALESCE(paid_until, trial_ends_at))";
    private static final String DUE = "SELECT " + COLUMNS + " FROM salon_subscriptions "
            + "WHERE bookable_until <= ?::timestamptz + ?::interval "
            + "AND bookable_until > ?::timestamptz - INTERVAL '30 days' "
            + "AND " + ACCESS + " <= ?::timestamptz + ?::interval "
            + "AND NOT " + AUTOPAY_RUNNING + " "
            + "AND (notice_for IS DISTINCT FROM " + ACCESS + " OR notice_level < CASE WHEN bookable_until <= ? THEN 3 "
            + "WHEN " + ACCESS + " <= ? THEN 2 ELSE 1 END) "
            + "AND EXISTS (SELECT 1 FROM shops s WHERE s.id = shop_id AND s.status = 'APPROVED') "
            + "ORDER BY bookable_until, shop_id LIMIT ?";

    /**
     * Salons that may need a message now: live, AutoPay not running, and their access ending within {@code ahead} (or
     * already over, up to 30 days back), the level due not sent yet for this end (so a batch never fills up with
     * salons already told). Oldest end first.
     */
    public List<Row> dueForNotice(Instant now, Duration ahead, int limit) {
        // bookable_until (indexed) is at most GRACE + 1 day after the access end; the access end itself decides
        return jdbc.query(DUE, ROW, ts(now), (ahead.plus(GRACE).plusDays(1)).toSeconds() + " seconds", ts(now),
                ts(now), ahead.toSeconds() + " seconds", ts(now), ts(now), ts(now), ts(now), limit);
    }

    /**
     * Records that the message of {@code level} about access ending at {@code accessUntil} was sent, only if nothing
     * changed meanwhile (another server sent it, a payment moved the end, AutoPay came on): returns false then.
     */
    public boolean noticeSent(UUID shopId, Instant accessUntil, int level, Instant now) {
        return jdbc.update("UPDATE salon_subscriptions SET notice_level = ?, notice_for = ?, updated_at = ? "
                        + "WHERE shop_id = ? AND " + ACCESS + " = ? "
                        + "AND (notice_for IS DISTINCT FROM ? OR notice_level < ?) AND NOT " + AUTOPAY_RUNNING,
                level, ts(accessUntil), ts(now), shopId, ts(accessUntil), ts(accessUntil), level, ts(now), ts(now)) == 1;
    }

    /**
     * The app opened the checkout after the owner picked Razorpay on Google's choice screen: Google's
     * externalTransactionToken, kept with the Razorpay subscription it opened ({@code subscriptionId}), so that
     * subscription's payments are reported to Google. A later checkout replaces both. The row locked.
     */
    public void saveAppBillingToken(UUID shopId, String subscriptionId, String token, Instant now) {
        jdbc.update("UPDATE salon_subscriptions SET app_billing_token = ?, app_billing_subscription_id = ?, "
                + "updated_at = ? WHERE shop_id = ?", token, subscriptionId, ts(now), shopId);
    }

    /** Whether the app opened this subscription (it has Google's token). */
    public boolean openedInApp(UUID shopId, String subscriptionId) {
        Boolean yes = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM salon_subscriptions WHERE shop_id = ? "
                + "AND app_billing_subscription_id = ? AND app_billing_token IS NOT NULL)", Boolean.class, shopId,
                subscriptionId);
        return Boolean.TRUE.equals(yes);
    }

    /** The token of a subscription opened from the app, if this one was. */
    public Optional<String> appBillingToken(String subscriptionId) {
        return jdbc.queryForList("SELECT app_billing_token FROM salon_subscriptions WHERE app_billing_subscription_id = ? "
                + "AND app_billing_token IS NOT NULL", String.class, subscriptionId).stream().findFirst();
    }

    /** The salon's Google Play purchase token, if it bought one. */
    public Optional<String> playToken(UUID shopId) {
        return jdbc.queryForList("SELECT play_purchase_token FROM salon_subscriptions WHERE shop_id = ? "
                + "AND play_purchase_token IS NOT NULL", String.class, shopId).stream().findFirst();
    }

    /** The salon a Google Play purchase belongs to, its row locked. */
    public Optional<Row> lockByPlayToken(String purchaseToken) {
        return jdbc.query("SELECT " + COLUMNS + " FROM salon_subscriptions WHERE play_purchase_token = ? FOR UPDATE",
                ROW, purchaseToken).stream().findFirst();
    }

    /**
     * Google's word on the salon's Google Play subscription (the row locked), as Google gave it at {@code askedAt}:
     * its token, state, end, whether it renews and whether it is acknowledged. Not written when the row already holds
     * a newer answer about the same purchase (asked after {@code askedAt}: two answers crossing, the older one never
     * wins). Returns whether it was written. The paid-up end moves with {@link #paid} separately.
     */
    public boolean savePlay(UUID shopId, String purchaseToken, String state, Instant expiresAt, boolean autoRenewing,
                            boolean acknowledged, Instant askedAt, Instant now) {
        return jdbc.update("UPDATE salon_subscriptions SET play_purchase_token = ?, play_state = ?, play_expires_at = ?, "
                        + "play_auto_renewing = ?, "
                        + "play_acknowledged = COALESCE(play_acknowledged AND play_purchase_token = ?, false) OR ?, "
                        + "play_checked_at = ?, updated_at = ? "
                        + "WHERE shop_id = ? AND (play_purchase_token IS DISTINCT FROM ? OR play_checked_at IS NULL "
                        + "OR play_checked_at <= ?)",
                purchaseToken, state, ts(expiresAt), autoRenewing, purchaseToken, acknowledged, ts(askedAt), ts(now),
                shopId, purchaseToken, ts(askedAt)) == 1;
    }

    /** We acknowledged the salon's purchase at Google. */
    public void playAcknowledged(String purchaseToken) {
        jdbc.update("UPDATE salon_subscriptions SET play_acknowledged = true WHERE play_purchase_token = ?",
                purchaseToken);
    }

    /** Google was asked about this purchase and doesn't know it (or not for this app): asked again much later. */
    public void playChecked(String purchaseToken, Instant now) {
        jdbc.update("UPDATE salon_subscriptions SET play_checked_at = ? WHERE play_purchase_token = ?", ts(now),
                purchaseToken);
    }

    /**
     * Google Play paid the salon up to {@code paidUntil} (the row locked): moved like {@link #paid} (never back), and
     * noted as Google Play's, so it can be taken back if Google refunds or revokes the purchase. Returns whether it
     * moved.
     */
    public boolean paidByPlay(Row row, Instant paidUntil, Instant now) {
        boolean moved = paid(row, paidUntil, now);
        if (moved) {
            jdbc.update("UPDATE salon_subscriptions SET play_paid_until = ? WHERE shop_id = ?", ts(paidUntil),
                    row.shopId());
        }
        return moved;
    }

    /**
     * Google took the salon's Google Play payment back (refunded, revoked, or never acknowledged): the paid-up end it
     * gave goes back to {@code until}, if nothing paid for later since (paid_until still the one Google Play gave).
     * The row locked. Returns whether it went back.
     */
    public boolean takeBackPlay(Row row, Instant until, Instant now) {
        if (row.playPaidUntil() == null || !row.playPaidUntil().equals(row.paidUntil())
                || !until.isBefore(row.paidUntil())) {
            return false;
        }
        Instant access = until.isAfter(row.trialEndsAt()) ? until : row.trialEndsAt();
        return jdbc.update("UPDATE salon_subscriptions SET paid_until = ?, play_paid_until = NULL, bookable_until = ?, "
                        + "updated_at = ? WHERE shop_id = ? AND paid_until = ?",
                ts(until), ts(bookableAfter(access)), ts(now), row.shopId(), ts(row.paidUntil())) == 1;
    }

    /**
     * The salon no longer has its Google Play purchase (it closed or changed hands, or bought another): forgotten here,
     * and kept in play_dropped_purchases, so it is cancelled at Google ({@link #dropsDue}) and Google's later
     * notices about it never attach it to a salon again.
     */
    public void dropPlay(UUID shopId, String purchaseToken, Instant now) {
        jdbc.update("INSERT INTO play_dropped_purchases (purchase_token, shop_id, next_try_at, created_at) "
                + "VALUES (?, ?, ?, ?) ON CONFLICT (purchase_token) DO NOTHING", purchaseToken, shopId, ts(now), ts(now));
        jdbc.update("UPDATE salon_subscriptions SET play_purchase_token = NULL, play_state = NULL, "
                        + "play_expires_at = NULL, play_auto_renewing = false, play_acknowledged = false, "
                        + "play_checked_at = NULL, play_paid_until = NULL, updated_at = ? "
                        + "WHERE shop_id = ? AND play_purchase_token = ?", ts(now), shopId, purchaseToken);
    }

    /** Whether this purchase was dropped by a salon (never attached again). */
    public boolean dropped(String purchaseToken) {
        Boolean yes = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM play_dropped_purchases WHERE purchase_token = ?)",
                Boolean.class, purchaseToken);
        return Boolean.TRUE.equals(yes);
    }

    /** Dropped purchases still to cancel at Google, claimed until {@code claimedUntil} (SKIP LOCKED). */
    public List<String> dropsDue(Instant now, Instant claimedUntil, int limit) {
        return jdbc.queryForList("UPDATE play_dropped_purchases SET next_try_at = ?, attempts = attempts + 1 "
                + "WHERE purchase_token IN (SELECT purchase_token FROM play_dropped_purchases WHERE NOT cancelled "
                + "AND next_try_at <= ? ORDER BY next_try_at LIMIT ? FOR UPDATE SKIP LOCKED) RETURNING purchase_token",
                String.class, ts(claimedUntil), ts(now), limit);
    }

    /** Google stopped the dropped purchase renewing (or it was over already). */
    public void dropCancelled(String purchaseToken) {
        jdbc.update("UPDATE play_dropped_purchases SET cancelled = true WHERE purchase_token = ?", purchaseToken);
    }

    /**
     * Google Play purchases to ask Google about again, the least recently asked first: any not yet acknowledged
     * (asked again after 5 minutes: Google refunds one not acknowledged within 3 days), and the renewing ones near or
     * just past their end (after 6 hours).
     */
    public List<String> playTokensToCheck(Instant now, int limit) {
        return jdbc.queryForList("SELECT play_purchase_token FROM salon_subscriptions WHERE play_purchase_token IS NOT "
                        + "NULL AND ((NOT play_acknowledged AND play_state IN ('SUBSCRIPTION_STATE_ACTIVE', "
                        + "'SUBSCRIPTION_STATE_IN_GRACE_PERIOD', 'SUBSCRIPTION_STATE_CANCELED', "
                        + "'SUBSCRIPTION_STATE_PENDING') AND (play_checked_at IS NULL "
                        + "OR play_checked_at < ?::timestamptz - INTERVAL '5 minutes')) "
                        + "OR (play_expires_at < ?::timestamptz + INTERVAL '1 day' "
                        + "AND play_expires_at > ?::timestamptz - INTERVAL '7 days' "
                        + "AND (play_checked_at IS NULL OR play_checked_at < ?::timestamptz - INTERVAL '6 hours'))) "
                        + "ORDER BY play_checked_at NULLS FIRST LIMIT ?", String.class, ts(now), ts(now), ts(now),
                ts(now), limit);
    }

    /** The salon's PIN code (its state, for the tax address Google needs). */
    public Optional<String> pincode(UUID shopId) {
        return jdbc.queryForList("SELECT pincode FROM shops WHERE id = ?", String.class, shopId).stream().findFirst();
    }

    /** The salon's owners (normally one), for its messages. */
    public List<UUID> owners(UUID shopId) {
        return jdbc.queryForList("SELECT user_id FROM shop_members WHERE shop_id = ? AND role = 'OWNER'", UUID.class,
                shopId);
    }

    /** Whether the salon's owner is a test account (users.test_account: made by an OTP test-mode sign-in). */
    public boolean ownedByTestAccount(UUID shopId) {
        Boolean yes = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM shop_members m JOIN users u ON u.id = m.user_id "
                + "WHERE m.shop_id = ? AND m.role = 'OWNER' AND u.test_account)", Boolean.class, shopId);
        return Boolean.TRUE.equals(yes);
    }

    /** Remembers a webhook; false if it was handled before (Razorpay retries until it gets a 2xx). */
    public boolean firstTime(String eventId, Instant now) {
        return jdbc.update("INSERT INTO razorpay_events (event_id, received_at) VALUES (?, ?) "
                + "ON CONFLICT (event_id) DO NOTHING", eventId, ts(now)) == 1;
    }

    /** Forgets webhooks older than {@code before} (Razorpay stops retrying long before), a batch at a time. */
    public int forgetEvents(Instant before, int limit) {
        return jdbc.update("DELETE FROM razorpay_events WHERE event_id IN (SELECT event_id FROM razorpay_events "
                + "WHERE received_at < ? LIMIT ?)", ts(before), limit);
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
