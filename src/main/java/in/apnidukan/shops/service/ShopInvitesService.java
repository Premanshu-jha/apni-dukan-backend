package in.apnidukan.shops.service;

import in.apnidukan.common.context.ShopChangeListener;
import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.context.UserDirectory.Person;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.notify.Notifications;
import in.apnidukan.common.notify.Notifications.Notice;
import in.apnidukan.common.security.RateLimiter;
import in.apnidukan.shops.api.ShopResponse;
import in.apnidukan.shops.domain.Shop;
import in.apnidukan.shops.domain.ShopMember;
import in.apnidukan.shops.domain.ShopRole;
import in.apnidukan.shops.domain.ShopStatus;
import in.apnidukan.shops.repo.ShopInviteStore;
import in.apnidukan.shops.repo.ShopInviteStore.Invite;
import in.apnidukan.shops.repo.ShopMemberRepository;
import in.apnidukan.shops.repo.ShopRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * People join a shop only by accepting an invitation: as a helper (the owner invites a phone), or as one of a salon's
 * barbers (the owner gives a barber profile a sign-in, SalonSetupService). Nobody is joined without being asked.
 *
 * <p><b>The owner</b> names a phone. The answer is the same whether or not that number has an account: no account is
 * made, no name is shown, and nothing says whether the person already works in a shop (they find that out when they
 * accept, about themselves). A shop may invite {@link #INVITES_PER_DAY} times a day (each names a phone, so many would
 * be a way to message strangers); asking the same phone again answers the invitation already waiting. The owner sees
 * the waiting ones by the phone they typed (GET /shops/me/invites) and can cancel them.
 *
 * <p><b>The person invited</b> sees it after signing in with that phone (GET /me/invites) and accepts or declines;
 * it runs out after {@link #INVITE_LIFETIME}. Accepting joins them (one shop per person); then POST /auth/refresh,
 * so the token names the shop.
 *
 * <p><b>Races.</b> Accepting takes the shop's row in shared mode first (closing and handing over hold it for update,
 * so they run one after the other, and a closed shop's invitations are cancelled), then the person's membership row,
 * then their own row (a deletion waits), then, for a barber, his profile (the salon's lock: the owner's unlink and
 * switch-off take it first too), and last the invitation (its UPDATE). Everyone takes them in this order, so steps can
 * only wait for each other.
 */
@Service
public class ShopInvitesService {

    private static final Logger log = LoggerFactory.getLogger(ShopInvitesService.class);

    /** An invitation waits this long for an answer. */
    static final Duration INVITE_LIFETIME = Duration.ofDays(7);
    /** Invitations one shop may make per day (helpers and barbers together). */
    static final int INVITES_PER_DAY = 20;
    /** The answer to every invitation, whether or not the number has an account. */
    public static final String SAME_ANSWER = "Invitation sent. They'll see it in Clozest when they sign in with "
            + "this number.";

    /** A waiting invitation, as the owner sees it: the phone they typed, never a name. */
    public record InviteView(UUID id, String phone, ShopRole role, UUID staffId, Instant createdAt,
                             Instant expiresAt) {
    }

    /** @param message always SAME_ANSWER */
    public record InviteResult(String message, InviteView invite) {
    }

    /** An invitation waiting for the person signed in (to their phone). */
    public record IncomingInvite(UUID id, UUID shopId, long shopCode, String shopName, String type, String area,
                                 String city, ShopRole role, Instant createdAt, Instant expiresAt) {
    }

    private final ShopRepository shops;
    private final ShopMemberRepository members;
    private final ShopInviteStore invites;
    private final UserDirectory users;
    private final Notifications notifications;
    private final List<ShopChangeListener> listeners;
    private final RateLimiter limits;
    private final Clock clock;

    public ShopInvitesService(ShopRepository shops, ShopMemberRepository members, ShopInviteStore invites,
                              UserDirectory users, Notifications notifications, List<ShopChangeListener> listeners,
                              RateLimiter limits, Clock clock) {
        this.shops = shops;
        this.members = members;
        this.invites = invites;
        this.users = users;
        this.notifications = notifications;
        this.listeners = listeners;
        this.limits = limits;
        this.clock = clock;
    }

    // ---------- the owner ----------

    /**
     * Invites the person with this phone to join my shop as a helper (owner only). 409 TOO_MANY_HELPERS when helpers
     * and helper invitations waiting reach ShopMembersService.MAX_HELPERS; 409 ALREADY_A_MEMBER if that phone already
     * works in this shop (the owner sees them in the members list anyway); 429 after INVITES_PER_DAY.
     */
    @Transactional
    public InviteResult inviteHelper(UUID ownerId, String phoneInput) {
        ShopMember owner = owner(ownerId);
        UUID shopId = owner.getShopId();
        String phone = users.normalizePhone(phoneInput);
        requireInvitesLeft(shopId);   // counted before anything else is checked: a refused try counts too
        // Two invitations (or accepts) at the same moment are counted one after the other
        members.lockMembers(shopId);
        Instant now = now();
        invites.expire(shopId, now);
        notOwnNumber(ownerId, phone);
        List<Invite> waiting = invites.waitingFor(shopId, now);
        Optional<Invite> same = waiting.stream().filter(v -> v.toPhone().equals(phone)).findFirst();
        if (same.isPresent()) {
            if (!ShopRole.HELPER.name().equals(same.get().role())) {
                // Invited to sign in as a barber here: answering with that one would look like a helper invitation
                throw invitePending();
            }
            return new InviteResult(SAME_ANSWER, view(same.get()));   // asked again: the one already waiting
        }
        if (inThisShop(shopId, phone)) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_A_MEMBER", "This number already works in your shop.");
        }
        long helpers = members.findByShopIdOrderByCreatedAtAscIdAsc(shopId).stream()
                .filter(m -> m.getRole() == ShopRole.HELPER).count()
                + waiting.stream().filter(v -> ShopRole.HELPER.name().equals(v.role())).count();
        if (helpers >= ShopMembersService.MAX_HELPERS) {
            throw new ApiException(HttpStatus.CONFLICT, "TOO_MANY_HELPERS", "A shop can have up to "
                    + ShopMembersService.MAX_HELPERS + " helpers, counting invitations still waiting. Remove or "
                    + "cancel one first.");
        }
        Shop shop = shops.findById(shopId).orElseThrow();
        Invite v = new Invite(UUID.randomUUID(), shopId, ShopRole.HELPER.name(), null, phone, ownerId, "PENDING", now,
                now.plus(INVITE_LIFETIME));
        if (!invites.insert(v)) {
            throw tryAgain();   // the same phone invited at this very moment (the lock above makes it rare)
        }
        tellInvited(v, shop, "a helper");
        return new InviteResult(SAME_ANSWER, view(v));
    }

    /**
     * Invites the person with this phone to sign in as this barber profile (the salon module checked the profile
     * under its lock: active, no sign-in yet). A waiting invitation for the profile is replaced. Same answer for any
     * number. 429 after INVITES_PER_DAY.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public InviteView inviteBarber(UUID shopId, UUID ownerId, UUID staffId, String phoneInput) {
        String phone = users.normalizePhone(phoneInput);
        requireInvitesLeft(shopId);
        members.lockMembers(shopId);
        Instant now = now();
        invites.expire(shopId, now);
        notOwnNumber(ownerId, phone);
        if (inThisShop(shopId, phone)) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_A_MEMBER", "This number already works in your shop.");
        }
        Optional<Invite> sameProfile = invites.waitingFor(shopId, now).stream()
                .filter(v -> staffId.equals(v.staffId())).findFirst();
        if (sameProfile.isPresent() && sameProfile.get().toPhone().equals(phone)) {
            return view(sameProfile.get());
        }
        invites.cancelForBarber(shopId, staffId, ownerId, now);   // another phone for him: the old one goes
        Invite v = new Invite(UUID.randomUUID(), shopId, ShopRole.BARBER.name(), staffId, phone, ownerId, "PENDING",
                now, now.plus(INVITE_LIFETIME));
        if (!invites.insert(v)) {
            throw invitePending();   // this phone already waits as a helper, or for another barber
        }
        tellInvited(v, shops.findById(shopId).orElseThrow(), "a barber");
        return view(v);
    }

    /** My shop's invitations still waiting, oldest first (owner only). */
    @Transactional(readOnly = true)
    public List<InviteView> waiting(UUID ownerId) {
        return invites.waitingFor(owner(ownerId).getShopId(), now()).stream().map(ShopInvitesService::view).toList();
    }

    /** Barber profile → the phone invited to sign in as him, for the salon's barber list (waiting ones only). */
    @Transactional(readOnly = true)
    public Map<UUID, String> waitingBarbers(UUID shopId) {
        return invites.waitingBarbers(shopId, now());
    }

    /**
     * Cancels one of my shop's waiting invitations (owner only). 404 INVITE_NOT_FOUND for another shop's. A barber's
     * is cancelled from his profile too (DELETE /salon/staff/{id}/login), which takes his lock first.
     */
    @Transactional
    public void cancel(UUID ownerId, UUID inviteId) {
        UUID shopId = owner(ownerId).getShopId();
        Invite v = invites.find(inviteId).filter(x -> x.shopId().equals(shopId)).orElseThrow(ShopInvitesService::notFound);
        if (v.staffId() != null) {
            // The barber's lock first, the order accepting takes them in
            listeners.forEach(l -> l.barberInviteChanging(shopId, v.staffId()));
        }
        invites.decide(inviteId, "CANCELLED", ownerId, now());   // false: answered or ran out meanwhile, fine too
    }

    /** The barber profile's waiting invitation goes (unlinked, switched off); the caller holds the profile's lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelBarber(UUID shopId, UUID staffId, UUID by) {
        invites.cancelForBarber(shopId, staffId, by, now());
    }

    /** Every waiting invitation of the shop goes (it closed, or changed owner); the caller holds the shop's lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelAll(UUID shopId, UUID by, Instant now) {
        invites.cancelAllFor(shopId, by, now);
    }

    // ---------- the person invited ----------

    /** Invitations waiting for my phone. */
    @Transactional(readOnly = true)
    public List<IncomingInvite> incoming(UUID userId) {
        Person me = users.find(Set.of(userId)).get(userId);
        if (me == null || me.phone() == null) {
            return List.of();
        }
        List<Invite> waiting = invites.waitingForPhone(me.phone(), now());
        Map<UUID, Shop> byId = shops.findAllById(waiting.stream().map(Invite::shopId).toList()).stream()
                .collect(Collectors.toMap(Shop::getId, Function.identity()));
        return waiting.stream().filter(v -> byId.containsKey(v.shopId())).map(v -> {
            Shop s = byId.get(v.shopId());
            return new IncomingInvite(v.id(), s.getId(), s.getShopCode(), s.details().name(), s.getType().name(),
                    s.details().area(), s.details().city(), ShopRole.valueOf(v.role()), v.createdAt(), v.expiresAt());
        }).toList();
    }

    /**
     * Joins the shop. 404 INVITE_NOT_FOUND unless it was made for my phone; 409 INVITE_GONE once it was cancelled,
     * declined, ran out, the shop closed, or (a barber) the profile was switched off or got another sign-in
     * (details.status); 409 ALREADY_IN_A_SHOP if I work in a shop (leave it first); 409 TOO_MANY_HELPERS when the shop
     * is full. Then POST /auth/refresh: the new token carries the shop.
     */
    @Transactional
    public ShopResponse accept(UUID userId, UUID inviteId) {
        Invite asked = addressedTo(userId, inviteId);
        Shop shop = shops.findForShare(asked.shopId()).orElseThrow();   // the shop's row first (see the class notes)
        Optional<UUID> mine = members.lockByUserId(userId);
        if (!users.lockIfActive(userId)) {   // a deletion of this account waits for us, or went first
            throw new ApiException(HttpStatus.UNAUTHORIZED, "NOT_SIGNED_IN", "Sign in first.");
        }
        Instant now = now();
        if (!asked.waitingAt(now)) {
            throw gone("PENDING".equals(asked.status()) ? "EXPIRED" : asked.status());
        }
        if (shop.getStatus() == ShopStatus.CLOSED) {
            throw gone("CLOSED");
        }
        if (mine.isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_IN_A_SHOP",
                    "You already work in a shop. Leave it first, then accept.");
        }
        ShopRole role = ShopRole.valueOf(asked.role());
        if (role == ShopRole.HELPER) {
            members.lockMembers(shop.getId());
            long helpers = members.findByShopIdOrderByCreatedAtAscIdAsc(shop.getId()).stream()
                    .filter(m -> m.getRole() == ShopRole.HELPER).count();
            if (helpers >= ShopMembersService.MAX_HELPERS) {
                throw new ApiException(HttpStatus.CONFLICT, "TOO_MANY_HELPERS",
                        "This shop has all the helpers it can have. Ask the owner.");
            }
        } else {
            // The barber profile, under its lock: still switched on and without a sign-in, else INVITE_GONE
            listeners.forEach(l -> l.barberJoining(shop.getId(), asked.staffId()));
        }
        if (!invites.decide(inviteId, "ACCEPTED", userId, now)) {
            throw gone("CHANGED");   // cancelled or answered a moment ago
        }
        try {
            members.saveAndFlush(new ShopMember(shop.getId(), userId, role, now));
        } catch (DataIntegrityViolationException e) {
            // Joined another shop (or took one over) at this very moment: one shop per person
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_IN_A_SHOP",
                    "You already work in a shop. Leave it first, then accept.");
        }
        if (role == ShopRole.BARBER) {
            listeners.forEach(l -> l.barberJoined(shop.getId(), asked.staffId(), userId));
        }
        Person me = users.find(Set.of(userId)).get(userId);
        String who = me == null || me.name() == null ? asked.toPhone() : me.name();
        notifications.queue(new Notice(asked.invitedBy(), "SHOP_INVITE_ACCEPTED", inviteId, now, "Invitation accepted",
                who + " joined " + shop.details().name() + " as " + (role == ShopRole.HELPER ? "a helper" : "a barber")
                        + ".", "/shops/me", Map.of()));
        return ShopResponse.from(shop, role);
    }

    /** Says no (the owner is told). 404 / 409 as for accept. */
    @Transactional
    public void decline(UUID userId, UUID inviteId) {
        Invite asked = addressedTo(userId, inviteId);
        Instant now = now();
        if (!invites.decide(inviteId, "DECLINED", userId, now)) {
            Invite v = invites.find(inviteId).orElseThrow();
            throw gone("PENDING".equals(v.status()) ? "EXPIRED" : v.status());
        }
        String shopName = shops.findById(asked.shopId()).map(s -> s.details().name()).orElse("your shop");
        notifications.queue(new Notice(asked.invitedBy(), "SHOP_INVITE_DECLINED", inviteId, now,
                "Invitation declined", "The person with " + asked.toPhone() + " won't join " + shopName + ".",
                "/shops/me", Map.of()));
    }

    // ---------- helpers ----------

    private void tellInvited(Invite v, Shop shop, String as) {
        // Told in the app if the number has an account; nothing in the owner's answer depends on it
        users.findByPhone(v.toPhone()).ifPresent(to -> notifications.queue(new Notice(to, "SHOP_INVITE", v.id(),
                v.createdAt(), "An invitation to join " + shop.details().name(), shop.details().name() + " ("
                + shop.details().area() + ") invited you to join as " + as + ". Open Clozest to accept or decline.",
                "/invites", Map.of())));
    }

    /** The invitation, if it was made for the phone of this person; 404 otherwise (other people's ids reveal nothing). */
    private Invite addressedTo(UUID userId, UUID inviteId) {
        Person me = users.find(Set.of(userId)).get(userId);
        return invites.find(inviteId)
                .filter(v -> me != null && me.phone() != null && v.toPhone().equals(me.phone()))
                .orElseThrow(ShopInvitesService::notFound);
    }

    private void notOwnNumber(UUID ownerId, String phone) {
        Person me = users.find(Set.of(ownerId)).get(ownerId);
        if (me != null && phone.equals(me.phone())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid",
                    Map.of("phone", "is your own number"));
        }
    }

    /** Whether the person with this phone already works in this shop (only ever about the owner's own shop). */
    private boolean inThisShop(UUID shopId, String phone) {
        return users.findByPhone(phone).flatMap(members::findByUserId).filter(m -> m.getShopId().equals(shopId))
                .isPresent();
    }

    /**
     * 429 TOO_MANY_REQUESTS after INVITES_PER_DAY invitations by one shop in 24 hours. If Redis doesn't answer, the
     * invitation goes ahead.
     */
    private void requireInvitesLeft(UUID shopId) {
        String key = "shop-invites:" + shopId;
        try {
            if (!limits.tryHit(key, INVITES_PER_DAY, Duration.ofHours(24))) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS",
                        "Too many invitations today. Try again tomorrow.").retryAfter(limits.secondsLeft(key));
            }
        } catch (DataAccessException redisDown) {
            log.warn("Redis didn't answer the invitation count ({}); letting it through",
                    redisDown.getClass().getSimpleName());
        }
    }

    private ShopMember owner(UUID userId) {
        ShopMember m = members.findByUserId(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "NO_SHOP", "You haven't registered or joined a shop yet."));
        if (m.getRole() != ShopRole.OWNER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER_ONLY", "Only the shop owner can do this.");
        }
        return m;
    }

    private static InviteView view(Invite v) {
        return new InviteView(v.id(), v.toPhone(), ShopRole.valueOf(v.role()), v.staffId(), v.createdAt(),
                v.expiresAt());
    }

    /** 409 INVITE_GONE, with what happened to it in details.status. */
    public static ApiException gone(String status) {
        return new ApiException(HttpStatus.CONFLICT, "INVITE_GONE", "This invitation isn't open any more.",
                Map.of("status", status));
    }

    /** 409 INVITE_PENDING: this phone already has another kind of invitation to this shop waiting. */
    private static ApiException invitePending() {
        return new ApiException(HttpStatus.CONFLICT, "INVITE_PENDING",
                "This number already has an invitation to your shop waiting. Cancel it first.");
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "INVITE_NOT_FOUND", "No such invitation.");
    }

    private static ApiException tryAgain() {
        return new ApiException(HttpStatus.CONFLICT, "TRY_AGAIN",
                "Someone else changed the same thing at the same moment. Try again.");
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
