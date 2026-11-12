package in.apnidukan.shops.service;

import in.apnidukan.common.context.ShopChangeListener;
import in.apnidukan.common.context.TestAccountShops;
import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.context.UserDirectory.Person;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.notify.Notifications;
import in.apnidukan.common.notify.Notifications.Notice;
import in.apnidukan.common.security.RateLimiter;
import in.apnidukan.common.security.SessionRevocations;
import in.apnidukan.common.text.TextCleaner;
import in.apnidukan.common.text.TextCleaner.TextField;
import in.apnidukan.shops.api.ShopResponse;
import in.apnidukan.shops.domain.Shop;
import in.apnidukan.shops.domain.ShopEventType;
import in.apnidukan.shops.domain.ShopMember;
import in.apnidukan.shops.domain.ShopRole;
import in.apnidukan.shops.domain.ShopStatus;
import in.apnidukan.shops.repo.AddressRequestStore;
import in.apnidukan.shops.repo.ShopHandoverStore;
import in.apnidukan.shops.repo.ShopHandoverStore.Handover;
import in.apnidukan.shops.repo.ShopMemberRepository;
import in.apnidukan.shops.repo.ShopRepository;
import in.apnidukan.shops.repo.ShopReviewStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * An owner leaving their shop: closing it for good, or handing it over to someone else. Until one of these, an owner
 * can't delete their account (AuthService: 409 SHOP_OWNER) or leave (409 OWNER_CANT_LEAVE).
 *
 * <p><b>Closing</b> (POST /shops/me/close): the shop becomes CLOSED at once (off the app, never opened again: the
 * owner registers a new shop if they come back; no booking can be made from that moment), a salon's upcoming online
 * bookings are cancelled and each customer is told, in the background a slice at a time (ShopChangeListener.closed:
 * a salon with thousands ahead still closes in one short request), and everyone's membership ends, the owner's too:
 * helpers and barbers as if removed (walk-in access and a barber's sign-in go, their sessions end at once), the owner
 * keeps their sessions and simply has no shop any more. A waiting hand-over and waiting invitations are cancelled.
 * Its rows stay for the field team and the customers' past bookings.
 *
 * <p><b>Handing over</b> (POST /shops/me/handover): the owner names a phone number. The answer is the same whether
 * or not that number has an account, so it can't be used to find out who uses Clozest; a shop may ask at most
 * {@link #HANDOVERS_PER_DAY} times a day. The person with that phone sees the request after signing in
 * (GET /me/handover) and accepts or declines; it runs out after 7 days, and the owner can cancel it. On accept, in one
 * transaction: the old owner leaves the shop (as if removed: their sessions end) and the new one becomes its OWNER
 * (a helper or barber of the shop is promoted; anyone else must have no shop). Both are told.
 *
 * <p><b>Races.</b> Every step takes the shop's row first (FOR UPDATE: closing, asking, cancelling, accepting,
 * declining), then the person's, so they run one after the other: a hand-over accepted first leaves the old owner
 * nothing to close; a shop closed first has cancelled the request. A person deleting their account while accepting:
 * the accept holds their row (FOR SHARE) and the deletion waits, or the deletion went first and the accept is 401; a
 * helper's membership is only deleted while it isn't OWNER (ShopService.removeMember checks under its row lock).
 */
@Service
public class ShopOwnershipService implements TestAccountShops {

    private static final Logger log = LoggerFactory.getLogger(ShopOwnershipService.class);

    /** A hand-over request waits this long for an answer. */
    static final Duration HANDOVER_LIFETIME = Duration.ofDays(7);
    /** Hand-over requests one shop may make per day (each names a phone number). */
    static final int HANDOVERS_PER_DAY = 5;
    /** A closing that this person made this recently answers a retry with the closed shop (a lost reply). */
    static final Duration CLOSE_RETRY_WITHIN = Duration.ofDays(1);
    /** The owner's reason for closing: for the field team, optional. */
    static final TextField CLOSE_REASON = new TextField("reason", 2, 200);
    /** The field team's note on a shop closed because its test account was claimed by the number's holder. */
    static final String TEST_ACCOUNT_CLAIMED = "Closed automatically: a test account's shop, and the number's real "
            + "holder signed in";
    /** The answer to every hand-over request, whether or not the number has an account. */
    public static final String SAME_ANSWER = "If that number has a Clozest account, they'll see your request.";

    /** The owner's view of their waiting request. */
    public record HandoverView(UUID id, String phone, Instant createdAt, Instant expiresAt) {
    }

    /**
     * The answer to a hand-over request.
     * @param message always SAME_ANSWER
     */
    public record HandoverRequested(String message, HandoverView handover) {
    }

    /** A request waiting for the person signed in (to their phone). */
    public record IncomingHandover(UUID id, UUID shopId, long shopCode, String shopName, String type, String area,
                                   String city, String ownerName, Instant createdAt, Instant expiresAt) {
    }

    private final ShopRepository shops;
    private final ShopMemberRepository members;
    private final ShopHandoverStore handovers;
    private final ShopReviewStore review;
    private final UserDirectory users;
    private final Notifications notifications;
    private final List<ShopChangeListener> listeners;
    private final SessionRevocations sessions;
    private final RateLimiter limits;
    private final DashboardCounts dashboardCounts;
    private final ShopInvitesService invites;
    private final Clock clock;

    private final ShopAssignments assignments;
    private final AddressRequestStore addressRequests;
    private final ShopService shopService;

    public ShopOwnershipService(ShopRepository shops, ShopMemberRepository members, ShopHandoverStore handovers,
                                ShopReviewStore review, UserDirectory users, Notifications notifications,
                                List<ShopChangeListener> listeners, SessionRevocations sessions, RateLimiter limits,
                                DashboardCounts dashboardCounts, ShopInvitesService invites,
                                ShopAssignments assignments, AddressRequestStore addressRequests,
                                ShopService shopService, Clock clock) {
        this.addressRequests = addressRequests;
        this.shopService = shopService;
        this.assignments = assignments;
        this.invites = invites;
        this.shops = shops;
        this.members = members;
        this.handovers = handovers;
        this.review = review;
        this.users = users;
        this.notifications = notifications;
        this.listeners = listeners;
        this.sessions = sessions;
        this.limits = limits;
        this.dashboardCounts = dashboardCounts;
        this.clock = clock;
    }

    // ---------- closing ----------

    /**
     * Closes my shop for good (owner only). {@code version} is the one from GET /shops/me (409 SHOP_CHANGED if
     * stale). Asking again after it worked (a lost reply) answers the closed shop again.
     */
    @Transactional
    public ShopResponse close(UUID userId, long version, String reasonInput) {
        Optional<ShopMember> member = members.findByUserId(userId);
        if (member.isEmpty()) {
            return review.closedBy(userId, now().minus(CLOSE_RETRY_WITHIN)).flatMap(shops::findById)
                    .map(s -> ShopResponse.from(s, ShopRole.OWNER)).orElseThrow(ShopOwnershipService::noShop);
        }
        UUID shopId = requireOwner(member.get()).getShopId();
        Shop shop = shops.lockById(shopId).orElseThrow();
        ShopMember owner = ownerUnderLock(shopId, userId);   // a hand-over accepted a moment ago left them nothing
        if (shop.getVersion() != version) {
            throw shopChanged();
        }
        String reason = reasonInput == null || reasonInput.isBlank() ? null
                : TextCleaner.clean(CLOSE_REASON, reasonInput);
        closeLocked(shop, owner, userId, reason, now());
        return ShopResponse.from(shop, ShopRole.OWNER);
    }

    /**
     * A test account was claimed by its number's real holder (TestAccountShops): a shop the testers registered is
     * closed, a helper's or barber's membership ends as if removed. Inside the sign-in's transaction; the shop's row
     * first, as every step here.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void endFor(UUID userId) {
        Optional<ShopMember> member = members.findByUserId(userId);
        if (member.isEmpty()) {
            return;
        }
        UUID shopId = member.get().getShopId();
        Shop shop = shops.lockById(shopId).orElseThrow();
        Optional<String> role = members.lockRole(shopId, userId);   // read fresh under the shop's lock
        if (role.isEmpty()) {
            return;   // left a moment ago
        }
        ShopMember fresh = members.findByShopIdAndUserId(shopId, userId).orElseThrow();
        if (ShopRole.OWNER.name().equals(role.get())) {
            closeLocked(shop, fresh, userId, TEST_ACCOUNT_CLAIMED, now());
        } else {
            leave(fresh, true);
        }
    }

    /**
     * The closing itself, the shop's row locked: CLOSED at once (no booking can be made from now on), its waiting
     * hand-over and invitations cancelled, the salon told (it queues the cancel of upcoming bookings, done in the
     * background: SalonClosingJob), and everyone leaves.
     */
    private void closeLocked(Shop shop, ShopMember owner, UUID by, String reason, Instant now) {
        UUID shopId = shop.getId();
        shop.close(reason, now);
        shops.saveAndFlush(shop);   // its row is ours: a booking in progress (FOR SHARE) has finished
        review.addEvent(shopId, now, by, false, ShopEventType.CLOSED, reason, null, List.of());
        handovers.cancelFor(shopId, by, now);
        invites.cancelAll(shopId, by, now);
        addressRequests.decide(shopId, "CANCELLED", null, by, now);   // nobody can decide it any more
        listeners.forEach(l -> l.closed(shopId));
        // Everyone leaves: helpers and barbers as if removed (sessions end), then the owner (keeps their sessions).
        // At most a few dozen people; what each module does for them is done for all together.
        List<UUID> others = new ArrayList<>();
        for (ShopMember m : members.findByShopIdOrderByCreatedAtAscIdAsc(shopId)) {
            if (m.getRole() != ShopRole.OWNER) {
                delete(m);
                others.add(m.getUserId());
            }
        }
        if (!others.isEmpty()) {
            listeners.forEach(l -> l.membersRemoved(shopId, others));
            others.forEach(this::endSessionsAfterCommit);
        }
        leave(owner, false);
        dashboardCounts.clearAfterCommit();
    }

    // ---------- handing over: the owner ----------

    /**
     * Asks the person with this phone to take my shop over (owner only). The same answer whether or not the number
     * has an account (they see it after signing in). 409 HANDOVER_PENDING while another request waits (cancel it
     * first); 429 TOO_MANY_REQUESTS after HANDOVERS_PER_DAY requests in a day.
     */
    @Transactional
    public HandoverRequested requestHandover(UUID userId, String phoneInput, long version) {
        UUID shopId = requireOwner(membership(userId)).getShopId();
        String phone = users.normalizePhone(phoneInput);
        requireRequestsLeft(shopId);   // counted before anything else is checked: a refused try counts too
        Shop shop = shops.lockById(shopId).orElseThrow();
        ownerUnderLock(shopId, userId);
        if (shop.getVersion() != version) {
            throw shopChanged();
        }
        Person me = users.find(Set.of(userId)).get(userId);
        if (me != null && phone.equals(me.phone())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid",
                    Map.of("phone", "is your own number"));
        }
        Instant now = now();
        handovers.expire(shopId, now);
        if (handovers.waitingFor(shopId, now).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "HANDOVER_PENDING",
                    "You already asked someone to take over this shop. Cancel that request first.");
        }
        Handover h = new Handover(UUID.randomUUID(), shopId, userId, phone, "PENDING", now,
                now.plus(HANDOVER_LIFETIME));
        handovers.insert(h);
        // Told in the app if the number has an account; nothing in the answer depends on it
        users.findByPhone(phone).ifPresent(to -> notifications.queue(new Notice(to, "SHOP_HANDOVER_REQUEST", h.id(),
                now, "A shop for you", "The owner of " + shop.details().name() + " wants to hand the shop over to "
                + "you. Open Clozest to accept or decline.", "/handover", Map.of())));
        return new HandoverRequested(SAME_ANSWER, view(h));
    }

    /** My shop's request still waiting, if any (owner only). */
    @Transactional(readOnly = true)
    public Optional<HandoverView> myHandover(UUID userId) {
        UUID shopId = requireOwner(membership(userId)).getShopId();
        return handovers.waitingFor(shopId, now()).map(ShopOwnershipService::view);
    }

    /** Cancels my shop's waiting request (owner only); nothing waiting is fine too. */
    @Transactional
    public void cancelHandover(UUID userId) {
        UUID shopId = requireOwner(membership(userId)).getShopId();
        if (shops.lockById(shopId).isEmpty()) {   // the shop's row first, as every step here
            throw noShop();
        }
        ownerUnderLock(shopId, userId);
        handovers.cancelFor(shopId, userId, now());
    }

    // ---------- handing over: the person asked ----------

    /** Requests waiting for my phone (usually none or one). */
    @Transactional(readOnly = true)
    public List<IncomingHandover> incoming(UUID userId) {
        Person me = users.find(Set.of(userId)).get(userId);
        if (me == null || me.phone() == null) {
            return List.of();
        }
        List<Handover> waiting = handovers.waitingForPhone(me.phone(), now());
        Map<UUID, Shop> byId = shops.findAllById(waiting.stream().map(Handover::shopId).toList()).stream()
                .collect(Collectors.toMap(Shop::getId, Function.identity()));
        return waiting.stream().filter(h -> byId.containsKey(h.shopId())).map(h -> {
            Shop s = byId.get(h.shopId());
            return new IncomingHandover(h.id(), s.getId(), s.getShopCode(), s.details().name(), s.getType().name(),
                    s.details().area(), s.details().city(), s.details().ownerName(), h.createdAt(), h.expiresAt());
        }).toList();
    }

    /**
     * Takes the shop over. 404 HANDOVER_NOT_FOUND unless the request is for my phone; 409 HANDOVER_GONE once it was
     * cancelled, declined, ran out, or the shop closed or changed owner (details.status); 409 ALREADY_IN_A_SHOP if I
     * work in another shop (leave it first); 403 PROFILE_INCOMPLETE without a verified email and a name. Then call
     * POST /auth/refresh: the new token carries the shop as OWNER.
     */
    @Transactional
    public ShopResponse accept(UUID userId, UUID handoverId) {
        Handover asked = addressedTo(userId, handoverId);
        assignments.lockForAssignment();   // before any row: taking the shop over may take it from them (memberJoined)
        Shop shop = shops.lockById(asked.shopId()).orElseThrow();   // the shop's row first, as every step here
        // Then their membership (if any), then their own row: the order leaving and account deletion take them in
        members.lockByUserId(userId);
        if (!users.lockIfActive(userId)) {   // a deletion of this account waits for us, or went first
            throw new ApiException(HttpStatus.UNAUTHORIZED, "NOT_SIGNED_IN", "Sign in first.");
        }
        users.requireCompleteProfile(userId);   // an owner, like anyone registering a shop
        Instant now = now();
        Handover h = handovers.lock(handoverId).orElseThrow();
        requireWaiting(h, now);
        if (shop.getStatus() == ShopStatus.CLOSED) {
            throw gone("CLOSED");
        }
        ShopMember oldOwner = members.findByShopIdAndUserId(shop.getId(), h.fromUser())
                .filter(m -> m.getRole() == ShopRole.OWNER).orElseThrow(() -> gone("OWNER_CHANGED"));
        Optional<ShopMember> mine = members.findByUserId(userId);
        if (mine.isPresent() && !mine.get().getShopId().equals(shop.getId())) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_IN_A_SHOP",
                    "You work in another shop. Leave it first, then accept.");
        }
        if (!handovers.decide(handoverId, "ACCEPTED", userId, now)) {
            throw gone("CHANGED");   // can't happen under the row lock
        }
        // The old owner's waiting address change was his: the new owner asks again if he wants it
        if (addressRequests.decide(shop.getId(), "CANCELLED", null, h.fromUser(), now)) {
            shop.touch(now);
            shopService.endRecheckIfNothingLeft(shop);
            shops.saveAndFlush(shop);
            dashboardCounts.clearAfterCommit();
        }
        // The old owner first: there is exactly one owner (ux_shop_members_one_owner)
        members.delete(oldOwner);
        members.flush();
        if (mine.isPresent()) {
            ShopMember promoted = mine.get();
            promoted.becomeOwner();
            members.saveAndFlush(promoted);
            listeners.forEach(l -> l.becameOwner(shop.getId(), userId));
        } else {
            try {
                members.saveAndFlush(new ShopMember(shop.getId(), userId, ShopRole.OWNER, now));
            } catch (DataIntegrityViolationException e) {
                // Added to a shop at this very moment (one shop per person)
                throw new ApiException(HttpStatus.CONFLICT, "ALREADY_IN_A_SHOP",
                        "You work in another shop. Leave it first, then accept.");
            }
        }
        listeners.forEach(l -> l.memberRemoved(shop.getId(), oldOwner.getUserId()));
        endSessionsAfterCommit(oldOwner.getUserId());   // their token names the shop as its owner
        invites.cancelAll(shop.getId(), userId, now);   // the old owner's invitations: the new owner invites again
        review.addEvent(shop.getId(), now, userId, false, ShopEventType.HANDED_OVER, null, null, List.of());
        String name = shop.details().name();
        notifications.queue(new Notice(oldOwner.getUserId(), "SHOP_HANDOVER_ACCEPTED", handoverId, now,
                "Shop handed over", name + " now belongs to the person you chose. You are no longer in it.",
                "/shops/me", Map.of()));
        notifications.queue(new Notice(userId, "SHOP_HANDOVER_DONE", handoverId, now, "The shop is yours",
                "You now own " + name + " on Clozest. Check its details and photos.", "/shops/me", Map.of()));
        assignments.memberJoined(shop.getId(), userId);   // a field agent can't visit a business they work in
        return ShopResponse.from(shop, ShopRole.OWNER);
    }

    /** Says no. 404 / 409 as for accept. The owner is told. */
    @Transactional
    public void decline(UUID userId, UUID handoverId) {
        Handover asked = addressedTo(userId, handoverId);
        Shop shop = shops.lockById(asked.shopId()).orElseThrow();
        Instant now = now();
        Handover h = handovers.lock(handoverId).orElseThrow();
        requireWaiting(h, now);
        if (!handovers.decide(handoverId, "DECLINED", userId, now)) {
            throw gone("CHANGED");
        }
        notifications.queue(new Notice(h.fromUser(), "SHOP_HANDOVER_DECLINED", handoverId, now,
                "Hand-over declined", "The person you asked won't take over " + shop.details().name() + ".",
                "/shops/me", Map.of()));
    }

    // ---------- helpers ----------

    /** The request, if it was made for the phone of this person; 404 otherwise (another person's ids reveal nothing). */
    private Handover addressedTo(UUID userId, UUID handoverId) {
        Person me = users.find(Set.of(userId)).get(userId);
        return handovers.find(handoverId)
                .filter(h -> me != null && me.phone() != null && h.toPhone().equals(me.phone()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "HANDOVER_NOT_FOUND",
                        "No such hand-over request."));
    }

    private static void requireWaiting(Handover h, Instant now) {
        if (!h.waitingAt(now)) {
            throw gone("PENDING".equals(h.status()) ? "EXPIRED" : h.status());
        }
    }

    private static ApiException gone(String status) {
        return new ApiException(HttpStatus.CONFLICT, "HANDOVER_GONE",
                "This hand-over request isn't open any more.", Map.of("status", status));
    }

    /** Ends a membership: as if removed, with the salon told; a helper's or barber's sessions end. */
    private void leave(ShopMember m, boolean endSessions) {
        delete(m);
        listeners.forEach(l -> l.memberRemoved(m.getShopId(), m.getUserId()));
        if (endSessions) {
            endSessionsAfterCommit(m.getUserId());
        }
    }

    private void delete(ShopMember m) {
        try {
            members.delete(m);
            members.flush();
        } catch (ObjectOptimisticLockingFailureException goneMeanwhile) {
            // They left (or deleted their account) at this very moment: start again
            throw new ApiException(HttpStatus.CONFLICT, "TRY_AGAIN",
                    "Someone else changed the same thing at the same moment. Try again.");
        }
    }

    private void endSessionsAfterCommit(UUID userId) {
        // Tokens naming the shop stop working now; if Redis doesn't answer, within 15 minutes (the membership is gone)
        sessions.revokeAllAfterCommit(userId, "Shop membership ended");
    }

    /**
     * 429 TOO_MANY_REQUESTS after HANDOVERS_PER_DAY requests by one shop in 24 hours: each names a phone, so many
     * would be a way to message strangers. If Redis doesn't answer, the request goes ahead.
     */
    private void requireRequestsLeft(UUID shopId) {
        String key = "handover-requests:" + shopId;
        try {
            if (!limits.tryHit(key, HANDOVERS_PER_DAY, Duration.ofHours(24))) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS",
                        "Too many hand-over requests today. Try again tomorrow.").retryAfter(limits.secondsLeft(key));
            }
        } catch (DataAccessException redisDown) {
            log.warn("Redis didn't answer the hand-over count ({}); letting it through",
                    redisDown.getClass().getSimpleName());
        }
    }

    private ShopMember membership(UUID userId) {
        return members.findByUserId(userId).orElseThrow(ShopOwnershipService::noShop);
    }

    private static ShopMember requireOwner(ShopMember m) {
        if (m.getRole() != ShopRole.OWNER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER_ONLY", "Only the shop owner can do this.");
        }
        return m;
    }

    /** Still this shop's owner, read again under the shop's lock (a hand-over accepted meanwhile ended it). */
    private ShopMember ownerUnderLock(UUID shopId, UUID userId) {
        return members.findByShopIdAndUserId(shopId, userId).filter(m -> m.getRole() == ShopRole.OWNER)
                .orElseThrow(ShopOwnershipService::noShop);
    }

    private static HandoverView view(Handover h) {
        return new HandoverView(h.id(), h.toPhone(), h.createdAt(), h.expiresAt());
    }

    private static ApiException noShop() {
        return new ApiException(HttpStatus.NOT_FOUND, "NO_SHOP", "You haven't registered or joined a shop yet.");
    }

    private static ApiException shopChanged() {
        return new ApiException(HttpStatus.CONFLICT, "SHOP_CHANGED",
                "Someone else saved changes to this shop. Reload and try again.");
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
