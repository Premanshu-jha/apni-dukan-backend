package in.apnidukan.shops.service;

import in.apnidukan.common.context.FieldAssignments;
import in.apnidukan.common.context.TeamLock;
import in.apnidukan.common.context.TeamRole;
import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.notify.Notifications;
import in.apnidukan.common.notify.Notifications.Notice;
import in.apnidukan.shops.repo.ShopAssignmentStore;
import in.apnidukan.shops.repo.ShopAssignmentStore.Locked;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Which field agent visits each business waiting for a visit (PENDING_VISIT, or a live business's re-check).
 *
 * <p>The rule: when a business starts waiting for a visit (submitted, an approved one changed what was checked,
 * reinstated), or its pincode changes while it waits and its agent doesn't cover the new one, it goes to the field
 * agent covering its pincode with the fewest businesses waiting (the longest on the team on a tie), never to someone
 * who works in it; nobody covers it: Unassigned. When an agent stops covering a pincode (Team page), their waiting
 * businesses there go through the same rule. A super admin can also assign a business to any field agent, or move
 * all of one agent's waiting businesses to another (leave).
 *
 * <p>Every assignment takes the team lock shared (TeamLock), before the business's row: a Team-page change and an
 * assignment never cross, and never deadlock. Callers that change a business first (an owner's edit) take it at the
 * start of their transaction with {@link #lockForAssignment()}. Every assignment is logged (shop_assignments) and
 * the new agent is told (an in-app message).
 */
@Service
public class ShopAssignments implements FieldAssignments {

    /**
     * Businesses locked and handled per statement when going through an agent's list (any length, in slices); 500
     * unless app.assignments.slice says otherwise (tests set it small so every list goes through several slices).
     */
    private final int sliceSize;

    private final ShopAssignmentStore store;
    private final UserDirectory users;
    private final Notifications notifications;
    private final DashboardCounts dashboardCounts;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ShopAssignments(ShopAssignmentStore store, UserDirectory users, Notifications notifications,
                           DashboardCounts dashboardCounts, JdbcTemplate jdbc, Clock clock,
                           @Value("${app.assignments.slice:500}") int slice) {
        this.sliceSize = slice;
        this.store = store;
        this.users = users;
        this.notifications = notifications;
        this.dashboardCounts = dashboardCounts;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Called first in a transaction that may assign a business after changing it (see the class comment). */
    public void lockForAssignment() {
        TeamLock.shared(jdbc);
    }

    /**
     * The business started waiting for a visit: assign it by the rule. The caller saved the change and holds the
     * shared team lock (lockForAssignment).
     * @param reason SUBMITTED, RECHECK or REINSTATED
     */
    public void startedWaiting(UUID shopId, String reason) {
        // Always assigned and told, even back to the same agent (a business sent back and submitted again is news to
        // whoever visits it)
        store.lock(shopId).filter(Locked::pending).ifPresent(shop -> assignByRule(shop, null, reason, null, true));
    }

    /**
     * The business's pincode changed while it waits: if its agent doesn't cover the new one (or it is unassigned), it
     * goes through the rule again. The caller saved the change and holds the shared team lock.
     */
    public void pincodeChanged(UUID shopId) {
        store.lock(shopId).filter(Locked::pending)
                .filter(shop -> shop.assignee() == null || !store.covers(shop.assignee(), shop.pincode()))
                .ifPresent(shop -> assignByRule(shop, null, "PINCODE_CHANGED", null, false));
    }

    /**
     * Called by an invitation's accept right after lockForAssignment, before it takes the business's row for share:
     * if the business waits for the joiner's own visit, its row is taken for update now. Upgrading the shared lock
     * later, in memberJoined, would deadlock with a helper accepting at the same moment (each holding what the other
     * waits for). Nearly always it isn't theirs, and nothing is locked.
     */
    public void lockBeforeJoining(UUID shopId, UUID userId) {
        if (store.assignee(shopId).map(userId::equals).orElse(false)) {
            store.lock(shopId);
        }
    }

    /**
     * Someone just joined this business (a helper or barber invitation, a hand-over): if it waits for their visit,
     * it goes to someone else by the rule (nobody decides on a business they work in). The caller took the shared
     * team lock first.
     */
    public void memberJoined(UUID shopId, UUID userId) {
        // Read without a lock first: nearly always it isn't theirs (when it is, joining took the row with
        // lockBeforeJoining, so this lock is already held)
        if (!store.assignee(shopId).map(userId::equals).orElse(false)) {
            return;
        }
        store.lock(shopId).filter(Locked::pending).filter(shop -> userId.equals(shop.assignee()))
                .ifPresent(shop -> assignByRule(shop, userId, "COVERAGE_CHANGED", null, false));
    }

    @Override
    public void coverageChanged(UUID adminId, Collection<String> removed, UUID by) {
        // Still on the team (a super admin, or now in support) is a coverage change, not a removal
        String reason = users.isAdmin(adminId) ? "COVERAGE_CHANGED" : "ADMIN_REMOVED";
        UUID after = null;
        Map<UUID, Integer> batch = new java.util.HashMap<>();
        for (List<Locked> slice = store.lockPendingOf(adminId, null, sliceSize); !slice.isEmpty();
                slice = store.lockPendingOf(adminId, after, sliceSize)) {
            for (Locked shop : slice) {
                // Only those in the pincodes taken away: one a super admin sent them elsewhere stays with them
                if ((removed == null || removed.contains(shop.pincode())) && !store.covers(adminId, shop.pincode())) {
                    assignByRule(shop, adminId, reason, by, false, batch);
                }
            }
            after = slice.get(slice.size() - 1).shopId();
        }
        tellBatch(batch);
    }

    @Override
    public void coverageAdded(UUID adminId, Collection<String> added, UUID by) {
        UUID after = null;
        Map<UUID, Integer> batch = new java.util.HashMap<>();
        for (List<Locked> slice = store.lockUnassignedIn(added, null, sliceSize); !slice.isEmpty();
                slice = store.lockUnassignedIn(added, after, sliceSize)) {
            for (Locked shop : slice) {
                assignByRule(shop, null, "COVERAGE_CHANGED", by, false, batch);
            }
            after = slice.get(slice.size() - 1).shopId();
        }
        tellBatch(batch);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Long> pendingVisits(Collection<UUID> adminIds) {
        return store.pendingCounts(adminIds);
    }

    /**
     * A super admin gives a waiting business to this field agent (any pincode: a super admin may send someone outside
     * their area). 404 if there's no such business; 409 WRONG_STATUS unless it is waiting for a visit; 400
     * NOT_A_FIELD_AGENT; 409 OWN_SHOP when that agent works in it. Assigning it to whoever has it already changes
     * nothing.
     */
    @Transactional
    public void assign(UUID superAdminId, UUID shopId, UUID toAdminId) {
        requireSuperAdmin(superAdminId);
        lockForAssignment();
        requireSuperAdmin(superAdminId);   // again, after waiting for a Team change: demoted meanwhile
        Locked shop = store.lock(shopId).orElseThrow(() -> ApiException.notFound("Shop"));
        if (!shop.pending()) {
            throw new ApiException(HttpStatus.CONFLICT, "WRONG_STATUS",
                    "Only a business waiting for a visit can be assigned; this one is " + shop.status() + ".",
                    Map.of("status", shop.status()));
        }
        requireFieldAgent(toAdminId);
        if (store.isMember(shopId, toAdminId)) {
            throw new ApiException(HttpStatus.CONFLICT, "OWN_SHOP",
                    "That field agent works in this business, so they can't visit it. Choose someone else.");
        }
        if (!toAdminId.equals(shop.assignee())) {
            move(shop, toAdminId, "MANUAL", superAdminId, true);
            dashboardCounts.clearAfterCommit();
        }
    }

    /**
     * A super admin moves every business waiting for one admin's visit to a field agent (for leave). One the new
     * agent works in stays where it was. Returns how many moved and how many stayed. 400 NOT_A_FIELD_AGENT; 400
     * VALIDATION_FAILED when both are the same person.
     */
    @Transactional
    public Moved moveAll(UUID superAdminId, UUID fromAdminId, UUID toAdminId) {
        requireSuperAdmin(superAdminId);
        if (fromAdminId.equals(toAdminId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid",
                    Map.of("toAdminId", "choose someone else"));
        }
        lockForAssignment();
        requireSuperAdmin(superAdminId);   // again, after waiting for a Team change: demoted meanwhile
        requireFieldAgent(toAdminId);
        int moved = 0;
        int stayed = 0;
        UUID after = null;
        for (List<Locked> slice = store.lockPendingOf(fromAdminId, null, sliceSize); !slice.isEmpty();
                slice = store.lockPendingOf(fromAdminId, after, sliceSize)) {
            for (Locked shop : slice) {
                if (store.isMember(shop.shopId(), toAdminId)) {
                    stayed++;
                } else {
                    move(shop, toAdminId, "MOVE_ALL", superAdminId, false);   // one message for all, below
                    moved++;
                }
            }
            after = slice.get(slice.size() - 1).shopId();
        }
        if (moved > 0) {
            dashboardCounts.clearAfterCommit();
            Instant now = now();
            notifications.queue(new Notice(toAdminId, "FIELD_VISIT_ASSIGNED", null, now,
                    moved == 1 ? "A visit moved to you" : moved + " visits moved to you",
                    "Open the admin website to see the businesses now waiting for your visit.", null, Map.of()));
        }
        return new Moved(moved, stayed);
    }

    /**
     * 404 (as for any business they may not see) unless this admin may see this business: a super admin sees every
     * one, a field agent only those assigned to them. Returns their role.
     */
    @Transactional(readOnly = true)
    public TeamRole requireCanSee(UUID adminId, UUID shopId) {
        TeamRole role = users.teamRole(adminId);
        if (!role.onTeam()) {
            throw ApiException.notFound("Page");
        }
        if (role == TeamRole.FIELD_AGENT && !store.assignee(shopId).map(adminId::equals).orElse(false)) {
            throw ApiException.notFound("Shop");
        }
        return role;
    }

    /**
     * Like requireCanSee, for a decision: the business's row is locked first, so a reassignment can't land between
     * this check and the decision (it waits, then finds the decision made).
     */
    public TeamRole requireCanDecide(UUID adminId, UUID shopId) {
        TeamRole role = users.teamRole(adminId);
        if (!role.onTeam()) {
            throw ApiException.notFound("Page");
        }
        Locked shop = store.lock(shopId).orElseThrow(() -> ApiException.notFound("Shop"));
        if (role == TeamRole.FIELD_AGENT && !adminId.equals(shop.assignee())) {
            throw ApiException.notFound("Shop");
        }
        return role;
    }

    /** 403 SUPER_ADMIN_ONLY unless a super admin (404 if not on the team at all). */
    public void requireSuperAdmin(UUID userId) {
        TeamRole role = users.teamRole(userId);
        if (!role.onTeam()) {
            throw ApiException.notFound("Page");
        }
        if (role != TeamRole.SUPER_ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SUPER_ADMIN_ONLY", "Only a super admin can do this.");
        }
    }

    /** Each business's assignee (only the assigned ones). */
    public Map<UUID, UUID> assignees(Collection<UUID> shopIds) {
        return store.assignees(shopIds);
    }

    private void requireFieldAgent(UUID adminId) {
        if (!store.isFieldAgent(adminId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NOT_A_FIELD_AGENT",
                    "That person isn't a field agent on the team.");
        }
    }

    /**
     * @param always log and tell even when it stays with the same agent (it started waiting again); otherwise only a
     *               change (or "still nobody") is written
     */
    private void assignByRule(Locked shop, UUID except, String reason, UUID by, boolean always) {
        assignByRule(shop, except, reason, by, always, null);
    }

    /** @param batch when not null, agents are counted here instead of told one by one (told once, see tellBatch) */
    private void assignByRule(Locked shop, UUID except, String reason, UUID by, boolean always,
                              Map<UUID, Integer> batch) {
        UUID to = store.pick(shop.shopId(), shop.pincode(), except).orElse(null);
        if (always || !java.util.Objects.equals(to, shop.assignee())) {   // nobody to nobody isn't a change
            if (batch != null) {
                if (to != null) {
                    batch.merge(to, 1, Integer::sum);
                }
                move(shop, to, reason, by, false, false);
            } else {
                move(shop, to, reason, by, always, true);
            }
        }
    }

    /** One message to each agent who got businesses in a bulk change (pincodes added or taken away). */
    private void tellBatch(Map<UUID, Integer> batch) {
        Instant now = now();
        batch.forEach((agent, n) -> notifications.queue(new Notice(agent, "FIELD_VISIT_ASSIGNED", null, now,
                n == 1 ? "New visit for you" : n + " new visits for you",
                "Open the admin website to see the businesses now waiting for your visit.", null, Map.of())));
    }

    private void move(Locked shop, UUID to, String reason, UUID by, boolean tellSameAgent) {
        move(shop, to, reason, by, tellSameAgent, !"MOVE_ALL".equals(reason));
    }

    /**
     * @param tellSameAgent tell the agent even when it was theirs already (it started waiting again)
     * @param tell          false in a bulk change: the caller sends one message for all
     */
    private void move(Locked shop, UUID to, String reason, UUID by, boolean tellSameAgent, boolean tell) {
        Instant now = now();
        store.set(shop.shopId(), to, now);
        store.log(shop.shopId(), now, shop.assignee(), to, by, reason);
        if (tell && to != null && (tellSameAgent || !to.equals(shop.assignee()))) {
            notifications.queue(new Notice(to, "FIELD_VISIT_ASSIGNED", shop.shopId(), now, "New visit for you",
                    shop.name() + ", " + shop.area() + " " + shop.pincode()
                            + " is yours to visit. Open the admin website to see it.", null, Map.of()));
        }
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    /** @param stayed businesses the new agent works in, left with the old one */
    public record Moved(int moved, int stayed) {
    }
}
