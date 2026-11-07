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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who works in a shop, for its owner: list everyone, invite a helper by phone (ShopInvitesService), remove a helper. Anyone but the owner
 * can also leave the shop themselves (so a phone added without asking is never stuck: that person signs in and
 * leaves). A barber's sign-in is linked from the salon's barber screen instead (SalonSetupService), because it
 * belongs to his barber profile; removing it there, or him leaving here, unlinks it.
 *
 * Whoever leaves or is removed loses their walk-in access too (ShopChangeListener.memberRemoved), and every
 * session of theirs ends after the commit, so a token naming the shop stops working at once.
 */
@Service
public class ShopMembersService {

    /** Helpers per shop: plenty for a kirana or a salon, and each new phone makes an account. */
    static final int MAX_HELPERS = 10;

    private final ShopMemberRepository members;
    private final ShopService shops;
    private final UserDirectory users;
    private final List<ShopChangeListener> listeners;
    private final SessionRevocations sessions;
    private final ShopInvitesService invites;

    public ShopMembersService(ShopMemberRepository members, ShopService shops, UserDirectory users,
                              List<ShopChangeListener> listeners, SessionRevocations sessions,
                              ShopInvitesService invites) {
        this.invites = invites;
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
     * Invites a helper by phone (owner only): see ShopInvitesService. The answer is the same whether or not the
     * number has an account; the person joins only when they accept.
     */
    public ShopInvitesService.InviteResult addHelper(UUID ownerId, String phone) {
        return invites.inviteHelper(ownerId, phone);
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
        // Tokens naming the shop stop working now (if Redis doesn't answer: within 15 minutes, never a 500 after the
        // removal was saved)
        sessions.revokeAllAfterCommit(userId, "Left or removed from the shop");
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
