package in.apnidukan.shops.service;

import in.apnidukan.common.context.RazorpayEvents;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.notify.Notifications;
import in.apnidukan.common.notify.Notifications.Notice;
import in.apnidukan.shops.domain.Shop;
import in.apnidukan.shops.domain.ShopMember;
import in.apnidukan.shops.domain.ShopRole;
import in.apnidukan.shops.domain.ShopType;
import in.apnidukan.shops.repo.ShopMemberRepository;
import in.apnidukan.shops.repo.SubscriptionStore;
import in.apnidukan.shops.repo.SubscriptionStore.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The salons' subscription: ₹{@value #PRICE_RUPEES} a month, through Razorpay Subscriptions (UPI AutoPay, card or
 * netbanking). Customers pay Clozest nothing; helpers and barbers pay nothing.
 * <ul>
 *   <li>The salon's first approval starts a {@value #TRIAL_DAYS}-day free trial ({@link #startTrial}).</li>
 *   <li>The owner opens the payment screen ({@link #checkout}): a Razorpay subscription is made for the salon, its
 *       first charge on the trial's last day (or at once when the trial is over), and the app opens Razorpay's checkout
 *       with it; approving AutoPay once with the UPI PIN is all. The checkout's signed answer ({@link #confirm}) shows
 *       "AutoPay on" at once.</li>
 *   <li>Razorpay's webhooks ({@link #webhook}) are the truth: each charge moves paid_until to the month's end (even
 *       one on a subscription the salon has since replaced: money taken always counts), and a failed, stopped or
 *       cancelled AutoPay is told to the owner.</li>
 *   <li>Without a payment, until midnight (India time) ending the third day after the trial or the paid month ends, the
 *       salon is then hidden from customers' search and takes no new online bookings (bookings already made stay and
 *       may move), until it pays: only while SUBSCRIPTIONS_ENFORCED is on, so nothing is hidden before live payments
 *       work. The day it is switched on, every salon's trial is counted from then ({@link #beginEnforcement}).
 *       SubscriptionNoticeJob tells the owner before and after.</li>
 *   <li>A salon never has two live subscriptions: a new checkout cancels the old one at Razorpay first, and closing
 *       the salon or handing it over cancels the owner's AutoPay ({@link #stopAutoPay}).</li>
 * </ul>
 */
@Service
public class SubscriptionService implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);
    public static final int PRICE_RUPEES = 500;
    public static final int TRIAL_DAYS = 30;
    static final Duration TRIAL = Duration.ofDays(TRIAL_DAYS);
    /** Another tap while a server asks Razorpay for the subscription waits this long before it may try itself. */
    static final Duration CREATING_FOR = Duration.ofSeconds(30);
    /** A subscription the owner didn't finish approving is offered again for this long, then a new one is made. */
    static final Duration REUSE_FOR = Duration.ofHours(1);
    /** A first charge closer than this is made at once instead (Razorpay wants start_at well in the future). */
    static final Duration MIN_START_AHEAD = Duration.ofMinutes(15);
    /** The app's theme for Razorpay's checkout (the partner app's navy). */
    public static final String THEME_COLOR = "#0B0F26";
    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final String DAY_PATTERN = "d MMM yyyy";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern(DAY_PATTERN, Locale.ENGLISH);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> STATUSES = Set.of("CREATED", "AUTHENTICATED", "ACTIVE", "PENDING", "HALTED",
            "CANCELLED", "COMPLETED", "EXPIRED", "PAUSED");
    /** Statuses after which Razorpay charges nothing more: nothing to cancel. */
    private static final Set<String> OVER = Set.of("CANCELLED", "COMPLETED", "EXPIRED");
    static final String PAY_URL = "/subscription";

    /**
     * What the partner app shows.
     * @param state         NONE (not approved yet), AUTOPAY (AutoPay on: Razorpay charges by itself), TRIAL, PAID
     *                      (paid up, AutoPay off), GRACE (ended: still visible, pay by the day before hiddenFrom),
     *                      HIDDEN (customers can't find it)
     * @param accessUntil   the trial's or the paid month's end
     * @param hiddenFrom    when customers stop finding the salon without a payment: midnight (India time) ending the
     *                      third day after accessUntil
     * @param autoPayFailed the last AutoPay charge failed or is overdue (the app says so, and the owner may pay)
     * @param testSalon     admin view: its owner is an OTP test account (its trial may be ended for Razorpay's review)
     * @param enforced      whether the salon is really hidden then (SUBSCRIPTIONS_ENFORCED)
     * @param canPay        the caller is the owner and payments are set up
     * @param paymentsReady Razorpay's keys and plan are set (else the Pay button says "not available yet")
     * @param testMode      Razorpay test keys: nothing real is charged
     */
    public record View(String state, Instant trialEndsAt, Instant paidUntil, Instant accessUntil, Instant hiddenFrom,
                       String razorpayStatus, int pricePerMonth, boolean enforced, boolean canPay,
                       boolean paymentsReady, boolean testMode, boolean autoPayFailed, boolean testSalon) {
    }

    /** The owner's details, filled in on Razorpay's checkout. */
    public record Prefill(String name, String email, String contact) {
    }

    /** What the app opens Razorpay's checkout with (all of it public: it is in the checkout page itself). */
    public record Checkout(String keyId, String subscriptionId, String name, String description, String image,
                           String themeColor, Prefill prefill, Instant firstChargeAt, boolean testMode) {
    }

    private final SubscriptionStore store;
    /** Handlers of Razorpay events that aren't about subscriptions (kirana online orders). */
    private final java.util.List<RazorpayEvents> otherEvents;
    private final ShopMemberRepository members;
    private final RazorpayGateway razorpay;
    private final Notifications notifications;
    private final TransactionTemplate transactions;
    /** Its own transaction always: also usable right after another committed (afterCommit), where REQUIRED isn't. */
    private final TransactionTemplate ownTransaction;
    private final Clock clock;
    private final boolean enforced;
    private final String logoUrl;

    public SubscriptionService(SubscriptionStore store, ShopMemberRepository members, RazorpayGateway razorpay,
                               Notifications notifications, PlatformTransactionManager transactions, Clock clock,
                               @Value("${app.subscriptions.enforce:false}") boolean enforced,
                               @Value("${app.subscriptions.logo-url:https://partner.clozest.in/icon-512.png}")
                               String logoUrl, java.util.List<RazorpayEvents> otherEvents) {
        this.otherEvents = java.util.List.copyOf(otherEvents);
        this.store = store;
        this.members = members;
        this.razorpay = razorpay;
        this.notifications = notifications;
        this.transactions = new TransactionTemplate(transactions);
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.enforced = enforced;
        this.logoUrl = logoUrl;
    }

    /** Whether lapsed salons are really hidden (SUBSCRIPTIONS_ENFORCED). */
    public boolean enforced() {
        return enforced;
    }

    /**
     * The salon's first approval: its free trial starts now (a salon approved again later keeps the row it has). In
     * the approval's transaction. Kiranas have no subscription.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Instant> startTrial(Shop shop) {
        if (shop.getType() != ShopType.SALON) {
            return Optional.empty();
        }
        Instant now = now();
        Instant ends = now.plus(TRIAL);
        return store.startTrial(shop.getId(), ends, now) ? Optional.of(ends) : Optional.empty();
    }

    /**
     * Hiding unpaid salons starts (SUBSCRIPTIONS_ENFORCED on), on the first start of a server with it: every salon's
     * free trial is counted from now if that is later than its own, so none is hidden without its reminders first.
     * Once only (subscription_enforcement), whichever server starts first.
     */
    @Override
    public void afterSingletonsInstantiated() {
        beginEnforcement();   // before the web server takes requests: no salon is hidden for a moment by mistake
    }

    /** See {@link #afterSingletonsInstantiated}; also called by the tests. */
    public void beginEnforcement() {
        if (!enforced) {
            return;
        }
        Instant now = now();
        Integer changed = transactions.execute(s -> store.enforcementStarts(now) ? store.trialsFrom(now, TRIAL) : null);
        if (changed != null) {
            log.info("Hiding unpaid salons starts now: {} salons' free trials counted from today", changed);
        }
    }

    /** GET /shops/me/subscription: anyone in the salon sees it; only the owner can pay. */
    @Transactional(readOnly = true)
    public View mine(UUID userId) {
        ShopMember member = membership(userId);
        return view(store.find(member.getShopId()).orElse(null), member.getRole() == ShopRole.OWNER, now());
    }

    /**
     * POST /shops/me/subscription/checkout (owner): the Razorpay subscription the app's checkout opens. A double tap,
     * or the owner coming back within the hour, gets the same one. Any other one still live at Razorpay (not approved
     * for an hour, a failed AutoPay being retried, an overdue one) is cancelled there first, so a salon is never
     * charged twice. Razorpay is asked outside any database transaction (the row only marks "asking" meanwhile).
     */
    public Checkout checkout(UUID userId) {
        ShopMember owner = owner(userId);
        UUID shopId = owner.getShopId();
        String keyId = razorpay.keyId();   // 503 SUBSCRIPTIONS_OFF before anything else
        Instant now = now();
        Row row = transactions.execute(s -> {
            Row r = store.lock(shopId).orElseThrow(SubscriptionService::notStarted);
            if (r.autoPayOn(now)) {
                throw new ApiException(HttpStatus.CONFLICT, "ALREADY_SUBSCRIBED",
                        "AutoPay is already on for your salon. Nothing more to do.");
            }
            if (reusable(r, now)) {
                return r;
            }
            if (r.creatingSince() != null && r.creatingSince().isAfter(now.minus(CREATING_FOR))) {
                throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_STARTING",
                        "The payment screen is opening. Please wait a moment.").retryAfter(5);
            }
            if (live(r)) {
                store.wantCancelled(r.subscriptionId(), shopId, now);
            }
            store.markCreating(shopId, now);
            return r;
        });
        Instant firstCharge = firstCharge(row, now);
        String subscriptionId;
        if (reusable(row, now)) {
            subscriptionId = row.subscriptionId();
        } else {
            RazorpayGateway.Created created;
            try {
                if (live(row) && !cancelNow(row.subscriptionId())) {
                    throw unavailable();   // never a second live subscription: the old one goes first
                }
                created = razorpay.createSubscription(shopId, firstCharge);
            } catch (RuntimeException e) {
                transactions.executeWithoutResult(s -> store.clearCreating(shopId, now, now()));
                throw e;
            }
            String status = STATUSES.contains(created.status()) ? created.status() : "CREATED";
            Boolean saved = transactions.execute(s -> {
                store.lock(shopId);
                if (store.saveCreated(shopId, created.id(), status, now, row.paidUntil(), now())) {
                    return true;
                }
                // Something changed meanwhile (a payment arrived, a later tap, the salon closed or changed owner): the
                // new one is never offered, and is cancelled so it can't be approved later
                store.wantCancelled(created.id(), shopId, now());
                store.clearCreating(shopId, now, now());   // its own mark only (a later tap's stays)
                return false;
            });
            if (!Boolean.TRUE.equals(saved)) {
                cancelNow(created.id());
                throw new ApiException(HttpStatus.CONFLICT, "SUBSCRIPTION_CHANGED",
                        "Your salon's subscription changed just now. Check the page again.");
            }
            subscriptionId = created.id();
        }
        Prefill prefill = store.contact(userId)
                .map(c -> new Prefill(nz(c.name()), nz(c.email()), nz(c.phone())))
                .orElse(new Prefill("", "", ""));
        String when = firstCharge == null ? "today" : "on " + day(firstCharge);
        return new Checkout(keyId, subscriptionId, "Clozest",
                "Salon subscription: ₹" + PRICE_RUPEES + " a month, first payment " + when,
                logoUrl, THEME_COLOR, prefill, firstCharge, razorpay.testMode());
    }

    /**
     * POST /shops/me/subscription/confirm (owner): the checkout's answer. Its signature proves Razorpay authorised
     * AutoPay for this subscription, so the app shows "AutoPay on" at once; the webhooks bring the payments.
     */
    @Transactional
    public View confirm(UUID userId, String paymentId, String subscriptionId, String signature) {
        ShopMember owner = owner(userId);
        Instant now = now();
        Row row = store.lock(owner.getShopId()).orElseThrow(SubscriptionService::notStarted);
        if (row.subscriptionId() == null || !row.subscriptionId().equals(subscriptionId)
                || !razorpay.checkoutSigned(paymentId, subscriptionId, signature)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PAYMENT_NOT_CONFIRMED",
                    "We couldn't confirm that payment. If money was taken, it will show here within a few minutes.");
        }
        // No status time: Razorpay's own events about it (a failed first charge, say) still count after this
        store.confirmAuthenticated(row.shopId(), now);
        return view(store.find(row.shopId()).orElseThrow(), true, now);
    }

    /**
     * POST /payments/razorpay/webhook: Razorpay tells us about a subscription. Checked by its signature (403
     * BAD_SIGNATURE), handled once per event id. Returns what happened (for the log and the tests).
     */
    public String webhook(byte[] body, String signature, String eventId) {
        if (!razorpay.webhookSigned(body, signature)) {
            log.warn("A Razorpay webhook with a wrong or missing signature was refused");
            throw new ApiException(HttpStatus.FORBIDDEN, "BAD_SIGNATURE", "Not signed by Razorpay.");
        }
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (JacksonException e) {
            throw ApiException.badRequest("NOT_JSON", "That isn't a webhook.");
        }
        String event = root.path("event").asString("");
        JsonNode sub = root.path("payload").path("subscription").path("entity");
        String subscriptionId = sub.path("id").asString("");
        if (!event.startsWith("subscription.") || subscriptionId.isEmpty()) {
            // Payments, orders and refunds of other modules (kirana online orders): theirs to handle
            for (RazorpayEvents handler : otherEvents) {
                String result = handler.handle(event, body, eventId);
                if (result != null) {
                    return result;
                }
            }
            return "IGNORED";
        }
        String status = sub.path("status").asString("").toUpperCase(Locale.ROOT);
        Instant at = Instant.ofEpochSecond(root.path("created_at").asLong(clock.instant().getEpochSecond()));
        long currentEnd = sub.path("current_end").asLong(0);
        String noteShop = sub.path("notes").path("shop_id").asString("");
        Instant now = now();
        return transactions.execute(s -> {
            if (eventId != null && !eventId.isBlank() && !store.firstTime(eventId.strip(), now)) {
                return "DUPLICATE";
            }
            Optional<Row> found = store.lockBySubscription(subscriptionId);
            if (found.isEmpty()) {
                // A subscription the salon no longer has (replaced, or dropped for a closing or a new owner): money it
                // took still counts for the salon named in its notes, and if it is still live it is cancelled, so it
                // can't keep charging
                Optional<UUID> shop = shopOf(noteShop);
                if (shop.isPresent() && !OVER.contains(status) && STATUSES.contains(status)) {
                    cancelAfterCommit(subscriptionId, shop.get(), now);
                }
                if ("subscription.charged".equals(event) && currentEnd > 0) {
                    Optional<Row> salon = shop.flatMap(store::lock);
                    if (salon.isPresent()) {
                        paid(salon.get(), Instant.ofEpochSecond(currentEnd), now);
                        return "CREDITED";
                    }
                    log.error("Razorpay charged subscription {} that no salon has: check it in the dashboard",
                            subscriptionId);
                }
                return "UNKNOWN";
            }
            Row row = found.get();
            boolean kept = STATUSES.contains(status) && store.saveStatus(row.shopId(), status, at, now);
            switch (event) {
                case "subscription.charged" -> {
                    if (currentEnd > 0) {
                        paid(row, Instant.ofEpochSecond(currentEnd), now);
                    }
                }
                case "subscription.authenticated" -> {
                    if (kept) {
                        tell(row.shopId(), "SUBSCRIPTION_AUTOPAY", "AutoPay is on",
                                "₹" + PRICE_RUPEES + " will be paid each month by itself. You can stop it any time "
                                        + "in your UPI app.", now,
                                Notifications.Text.of("SUBSCRIPTION_AUTOPAY", Map.of("price", PRICE_RUPEES)));
                    }
                }
                case "subscription.pending" -> {
                    if (kept) {
                        tell(row.shopId(), "SUBSCRIPTION_FAILED", "Payment didn't go through",
                                "This month's ₹" + PRICE_RUPEES + " AutoPay failed. It will be tried again; check "
                                        + "your UPI app or bank has money and the AutoPay is on.", now,
                                Notifications.Text.of("SUBSCRIPTION_FAILED", Map.of("price", PRICE_RUPEES)));
                    }
                }
                case "subscription.halted", "subscription.cancelled" -> {
                    if (kept) {
                        Row after = store.find(row.shopId()).orElseThrow();
                        tell(row.shopId(), "SUBSCRIPTION_STOPPED", "AutoPay stopped",
                                "AutoPay for your salon has stopped. It stays on until " + day(after.accessUntil())
                                        + "; open Clozest to pay and keep taking online bookings.", now,
                                Notifications.Text.of("SUBSCRIPTION_STOPPED",
                                        Map.of("day", dayValue(after.accessUntil()))));
                    }
                }
                default -> {
                    // activated, paused, resumed, completed...: the status above is all
                }
            }
            return kept ? "UPDATED" : "OLD";
        });
    }

    /** A charge moved paid-until (never back): the owner is told, once (a replayed webhook changes nothing). */
    private void paid(Row row, Instant paidUntil, Instant now) {
        if (store.paid(row, paidUntil, now)) {
            tell(row.shopId(), "SUBSCRIPTION_PAID", "Payment received",
                    "₹" + PRICE_RUPEES + " received for your salon. Paid up to " + day(paidUntil)
                            + ". Customers can find and book it.", now,
                    Notifications.Text.of("SUBSCRIPTION_PAID", Map.of("price", PRICE_RUPEES,
                            "day", dayValue(paidUntil))));
        }
    }

    private static Optional<UUID> shopOf(String id) {
        try {
            return id.isEmpty() ? Optional.empty() : Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * The salon closes or gets a new owner: the owner's AutoPay is cancelled at Razorpay, so he is charged no more
     * (what was paid stays with the salon; a new owner sets up his own). In the caller's transaction, the salon's row
     * locked; Razorpay is asked right after the commit, and SubscriptionNoticeJob tries again until it is done.
     * Returns whether there was a subscription to stop.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean stopAutoPay(UUID shopId) {
        Instant now = now();
        Optional<Row> row = store.lock(shopId);
        if (row.isEmpty() || row.get().subscriptionId() == null) {
            return false;
        }
        String subscriptionId = row.get().subscriptionId();
        boolean live = live(row.get());
        if (live) {
            cancelAfterCommit(subscriptionId, shopId, now);
        }
        store.forgetSubscription(shopId, now);
        return live && row.get().autoPaySetUp();
    }

    /**
     * Queues a Razorpay subscription for cancelling (razorpay_cancellations, in the current transaction) and tries it
     * right after the commit; SubscriptionNoticeJob tries again until Razorpay confirms.
     */
    private void cancelAfterCommit(String subscriptionId, UUID shopId, Instant now) {
        store.wantCancelled(subscriptionId, shopId, now);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cancelNow(subscriptionId);
            }
        });
    }

    /**
     * Cancels at Razorpay a subscription waiting in razorpay_cancellations; true once done (then it is forgotten).
     * Never throws: false when Razorpay can't be reached (tried again by SubscriptionNoticeJob).
     */
    boolean cancelNow(String subscriptionId) {
        try {
            if (razorpay.cancelSubscription(subscriptionId)) {
                ownTransaction.executeWithoutResult(s -> store.cancelled(subscriptionId));
                return true;
            }
        } catch (RuntimeException e) {
            log.warn("Couldn't cancel Razorpay subscription {} now; will try again", subscriptionId);
        }
        return false;
    }

    /** SubscriptionNoticeJob: the cancellations still to make; returns how many were done. */
    public int retryCancellations(int limit) {
        if (!razorpay.configured()) {
            return 0;
        }
        List<String> due = store.cancellationsDue(limit);
        return (int) due.stream().filter(this::cancelNow).count();
    }

    /** The salon's subscription for the admin website (anyone on the team who can see the salon). */
    @Transactional(readOnly = true)
    public View forAdmin(UUID shopId) {
        View v = view(store.find(shopId).orElse(null), false, now());
        return v.state().equals("NONE") ? v : withTestSalon(v, store.ownedByTestAccount(shopId));
    }

    private static View withTestSalon(View v, boolean test) {
        return new View(v.state(), v.trialEndsAt(), v.paidUntil(), v.accessUntil(), v.hiddenFrom(), v.razorpayStatus(),
                v.pricePerMonth(), v.enforced(), v.canPay(), v.paymentsReady(), v.testMode(), v.autoPayFailed(), test);
    }

    /**
     * A test salon (its owner a test account) set up for Razorpay's reviewer: its trial ends now, so the partner app
     * shows the payment screen. 409 NOT_A_TEST_SALON for a real salon, 409 SUBSCRIPTION_NOT_STARTED before approval.
     */
    @Transactional
    public View endTrialNow(UUID shopId) {
        Instant now = now();
        Row row = store.lock(shopId).orElseThrow(SubscriptionService::notStarted);
        if (!store.ownedByTestAccount(shopId)) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_A_TEST_SALON",
                    "Only a test salon (its owner signed in with an OTP test number) can have its trial ended.");
        }
        // A checkout opened before (first charge: the old trial's end) is dropped, and cancelled so it can't be approved
        if (row.subscriptionId() != null && "CREATED".equals(row.razorpayStatus())) {
            cancelAfterCommit(row.subscriptionId(), shopId, now);
        }
        store.setTrialEnd(row, now, now);
        return withTestSalon(view(store.find(shopId).orElseThrow(), false, now), true);
    }

    View view(Row row, boolean owner, Instant now) {
        boolean ready = razorpay.configured();
        boolean test = ready && razorpay.testMode();
        if (row == null) {
            return new View("NONE", null, null, null, null, null, PRICE_RUPEES, enforced, false, ready, test, false,
                    false);
        }
        String state;
        boolean autoPay = row.autoPayOn(now);
        if (autoPay) {
            state = "AUTOPAY";
        } else if (now.isBefore(row.accessUntil())) {
            state = row.paidUntil() == null || !row.paidUntil().isAfter(row.trialEndsAt()) ? "TRIAL" : "PAID";
        } else if (now.isBefore(row.bookableUntil())) {
            state = "GRACE";
        } else {
            state = "HIDDEN";
        }
        return new View(state, row.trialEndsAt(), row.paidUntil(), row.accessUntil(), row.bookableUntil(),
                row.razorpayStatus(), PRICE_RUPEES, enforced, owner && ready && !autoPay, ready, test,
                row.autoPayFailed(now), false);
    }

    /** The first charge: the trial's or paid month's end, or null (at once) when that is (nearly) here. */
    static Instant firstCharge(Row row, Instant now) {
        return row.accessUntil().isAfter(now.plus(MIN_START_AHEAD)) ? row.accessUntil() : null;
    }

    /** The row's subscription may still charge at Razorpay (so it must be cancelled before another is made). */
    private static boolean live(Row r) {
        return r.subscriptionId() != null && !OVER.contains(r.razorpayStatus());
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENTS_UNAVAILABLE",
                "Couldn't reach the payment service. Please try again in a minute.").retryAfter(30);
    }

    private static boolean reusable(Row r, Instant now) {
        return r.subscriptionId() != null && "CREATED".equals(r.razorpayStatus()) && r.subscriptionMadeAt() != null
                && r.subscriptionMadeAt().isAfter(now.minus(REUSE_FOR));
    }

    /** To the salon's owner, in English here and as {@code text} for an owner who reads another language. */
    void tell(UUID shopId, String type, String title, String body, Instant now, Notifications.Text text) {
        for (UUID owner : store.owners(shopId)) {
            notifications.queue(new Notice(owner, type, shopId, now, title, body, PAY_URL, Map.of())
                    .in(text.key(), text.values()));
        }
    }

    static String day(Instant at) {
        return LocalDate.ofInstant(at, INDIA).format(DAY);
    }

    /** day() as a text's value: "20 Nov 2026" with the month's name in the reader's language. */
    static Notifications.Day dayValue(Instant at) {
        return new Notifications.Day(LocalDate.ofInstant(at, INDIA), DAY_PATTERN);
    }

    private ShopMember membership(UUID userId) {
        return members.findByUserId(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NO_SHOP",
                "You haven't registered a shop yet."));
    }

    private ShopMember owner(UUID userId) {
        ShopMember member = membership(userId);
        if (member.getRole() != ShopRole.OWNER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER_ONLY", "Only the salon owner can pay.");
        }
        return member;
    }

    private static ApiException notStarted() {
        return new ApiException(HttpStatus.CONFLICT, "SUBSCRIPTION_NOT_STARTED",
                "Your salon's free trial starts when our field team approves it. Nothing to pay until then.");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
