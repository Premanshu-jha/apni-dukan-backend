package in.apnidukan.shops.service;

import in.apnidukan.common.context.ShopChangeListener;
import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.context.UserDirectory.Person;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.security.SessionRevocations;
import in.apnidukan.shops.domain.ShopMember;
import in.apnidukan.shops.domain.ShopRole;
import in.apnidukan.shops.repo.ShopMemberRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who works in a shop, for its owner: list everyone, add a helper by phone, remove a helper. Anyone but the owner
 * can also leave the shop themselves (so a phone added without asking is never stuck: that person signs in and
 * leaves). A barber's sign-in is linked from the salon's barber screen instead (SalonSetupService), because it
 * belongs to his barber profile; removing it there, or him leaving here, unlinks it.
 *
 * Whoever leaves or is removed loses their walk-in access too (ShopChangeListener.memberRemoved), and every
 * session of theirs ends after the commit, so a token naming the shop stops working at once.
 */
@Service
public class ShopMembersService {

    private final ShopMemberRepository members;
    private final ShopService shops;
    private final UserDirectory users;
    private final List<ShopChangeListener> listeners;
    private final SessionRevocations sessions;

    public ShopMembersService(ShopMemberRepository members, ShopService shops, UserDirectory users,
                              List<ShopChangeListener> listeners, SessionRevocations sessions) {
        this.members = members;
        this.shops = shops;
        this.users = users;
        this.listeners = listeners;
        this.sessions = sessions;
    }

    /** @param phone shown to the owner only (this list is owner only) */
    public record MemberView(UUID userId, String name, String phone, ShopRole role, Instant joinedAt) {
    }

    /** Everyone in my shop, owner first, then by when they joined. Owner only. */
    @Transactional(readOnly = true)
    public List<MemberView> members(UUID ownerId) {
        ShopMember owner = owner(ownerId);
        List<ShopMember> all = members.findByShopIdOrderByCreatedAtAscIdAsc(owner.getShopId()).stream()
                .sorted(java.util.Comparator.comparing((ShopMember m) -> m.getRole() != ShopRole.OWNER)).toList();
        Map<UUID, Person> people = users.find(all.stream().map(ShopMember::getUserId).toList());
        return all.stream().map(m -> {
            Person p = people.get(m.getUserId());
            return new MemberView(m.getUserId(), p == null ? null : p.name(), p == null ? null : p.phone(),
                    m.getRole(), m.getCreatedAt());
        }).toList();
    }

    /**
     * Adds a helper by phone (owner only). A phone nobody has signed in with yet gets an account; the helper signs
     * in with the usual OTP and finds the shop. 409 ALREADY_IN_A_SHOP if that person works in a shop already.
     */
    @Transactional
    public List<MemberView> addHelper(UUID ownerId, String phone) {
        ShopMember owner = owner(ownerId);
        UUID helper = users.findOrCreateByPhone(phone);
        shops.addMember(owner.getShopId(), helper, ShopRole.HELPER.name());
        return members(ownerId);
    }

    /** Removes a helper (owner only). 404 for anyone who isn't a helper of my shop. */
    @Transactional
    public List<MemberView> removeHelper(UUID ownerId, UUID helperId) {
        ShopMember owner = owner(ownerId);
        if (members.findByShopIdAndUserId(owner.getShopId(), helperId).filter(m -> m.getRole() == ShopRole.HELPER)
                .isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "HELPER_NOT_FOUND", "This person isn't a helper in your shop.");
        }
        remove(owner.getShopId(), helperId);
        return members(ownerId);
    }

    /** Leaves my shop (a helper or barber; the owner can't: 409 OWNER_CANT_LEAVE). */
    @Transactional
    public void leave(UUID userId) {
        ShopMember me = members.findByUserId(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "NO_SHOP", "You haven't registered or joined a shop yet."));
        if (me.getRole() == ShopRole.OWNER) {
            throw new ApiException(HttpStatus.CONFLICT, "OWNER_CANT_LEAVE",
                    "You own this shop. Close it or hand it over instead.");
        }
        remove(me.getShopId(), userId);
    }

    private void remove(UUID shopId, UUID userId) {
        shops.removeMember(shopId, userId);
        listeners.forEach(l -> l.memberRemoved(shopId, userId));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sessions.revokeAll(userId);   // tokens naming the shop stop working now
            }
        });
    }

    private ShopMember owner(UUID userId) {
        ShopMember m = members.findByUserId(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "NO_SHOP", "You haven't registered or joined a shop yet."));
        if (m.getRole() != ShopRole.OWNER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER_ONLY", "Only the shop owner can do this.");
        }
        return m;
    }
}
