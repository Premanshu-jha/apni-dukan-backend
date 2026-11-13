package in.apnidukan.helpdesk.service;

import in.apnidukan.common.context.SupportDesk;
import in.apnidukan.common.context.TeamLock;
import in.apnidukan.common.context.TeamRole;
import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.helpdesk.domain.Ticket;
import in.apnidukan.helpdesk.repo.TicketStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who handles a ticket: the support person with the fewest open (not resolved) tickets, chosen at random among
 * those tied; nobody (Unassigned, which super admins see) when there is no support person.
 *
 * <p>Locks, always in this order: the team lock shared ({@link TeamLock}: a team change, which takes it exclusively,
 * waits, so a ticket never goes to someone being removed from support), then the helpdesk lock (one assignment at a
 * time, so two tickets arriving together are counted one after the other and spread fairly), then ticket rows.
 * Team changes call {@link #leftSupport} and {@link #joinedSupport} holding the team lock exclusively, then take the
 * helpdesk lock, then rows: the same order, so the two never deadlock.
 */
@Service
public class TicketAssigner implements SupportDesk {

    /** The helpdesk's advisory lock (TeamLock is 7201). */
    static final int LOCK = 7301;

    private final TicketStore store;
    private final UserDirectory users;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public TicketAssigner(TicketStore store, UserDirectory users, JdbcTemplate jdbc, Clock clock) {
        this.store = store;
        this.users = users;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Takes the team lock (shared) and the helpdesk lock, in that order, until the transaction ends. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock() {
        TeamLock.shared(jdbc);
        helpdeskLock();
    }

    /**
     * Only the team lock (shared): enough to add to a ticket whose person still receives tickets (nobody is assigned,
     * so new tickets elsewhere never wait on it). Take {@link #lock} instead when a ticket may need assigning; inside,
     * {@link #tryLockAssigning} takes the helpdesk lock only if nobody holds it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockTeam() {
        TeamLock.shared(jdbc);
    }

    /**
     * The helpdesk lock without waiting (true at once if this transaction holds it already): for a ticket that turned
     * out to need assigning after only {@link #lockTeam} (its person went away a moment ago). Never waits, so it can't
     * deadlock with someone holding the lock and waiting for this ticket's row.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryLockAssigning() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, LOCK));
    }

    /**
     * Who should get the next ticket (null = Unassigned), never the person who asked (a support person's own
     * question goes to someone else). Call after {@link #lock}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID next(UUID requesterId) {
        List<UUID> staff = new ArrayList<>(available());
        staff.remove(requesterId);
        return pick(staff);
    }

    /** Whether new tickets (and ones coming back) may go to this person: may hold tickets and isn't away. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean mayReceive(UUID userId) {
        // Away is about support people: someone marked who has become a super admin meanwhile still receives
        return mayHold(userId) && !(store.isAway(userId) && users.teamRole(userId) == TeamRole.SUPPORT);
    }

    /** The support people who get new tickets: everyone in support who isn't away. */
    private List<UUID> available() {
        List<UUID> staff = new ArrayList<>(users.supportStaff());
        staff.removeAll(store.awaySince(staff).keySet());
        return staff;
    }

    /** Whether this person may still hold tickets (a support person or a super admin). */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean mayHold(UUID userId) {
        return userId != null && users.teamRole(userId).handlesTickets();
    }

    /**
     * A super admin moves every open ticket of one person (away, on leave): to {@code to}, or (null) to the other
     * support people by the usual rule. A ticket asked by {@code to} goes by the rule instead. Returns how many moved.
     * Call after {@link #lock}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int moveAll(UUID from, UUID to, UUID by) {
        List<Ticket> theirs = store.lockOpenOf(from);
        Set<UUID> staff = new LinkedHashSet<>(available());
        staff.remove(from);
        Instant now = clock.instant();
        Map<UUID, String> names = names(staff);
        if (to != null) {
            names.putAll(names(List.of(to)));
        }
        int moved = 0;
        for (Ticket t : theirs) {
            UUID target = to != null && !to.equals(t.requesterId()) ? to : pick(without(staff, t.requesterId()));
            if (target == null) {
                continue;   // nobody else may hold it: it stays
            }
            store.reassign(t.id(), target, now);
            store.addEvent(t.id(), by, "REASSIGNED", "Moved with all of them; given to "
                    + names.getOrDefault(target, "another support person"), now);
            moved++;
        }
        return moved;
    }

    /**
     * The safety net for many people at once: every open ticket held by someone who no longer receives tickets (away,
     * or out of support) goes to whoever is here with fewest, as {@link #moveAll} does. A ticket written on at the very
     * moment its person went away (that write locks only the ticket, not the whole helpdesk) is caught here within
     * minutes. Returns how many moved. Call after {@link #lock}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int reassignStranded() {
        int moved = 0;
        for (UUID holder : store.openAssignees()) {
            if (!mayReceive(holder)) {
                moved += moveAll(holder, null, null);
            }
        }
        return moved;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void leftSupport(UUID userId, UUID by) {
        helpdeskLock();
        store.clearAway(userId);   // away only means something while in support
        List<Ticket> theirs = store.lockOpenOf(userId);
        if (theirs.isEmpty()) {
            return;
        }
        Set<UUID> staff = new LinkedHashSet<>(available());
        staff.remove(userId);   // their change may not be visible to that query yet
        Instant now = clock.instant();
        Map<UUID, String> names = names(staff);
        for (Ticket t : theirs) {
            UUID to = pick(without(staff, t.requesterId()));
            store.reassign(t.id(), to, now);
            store.addEvent(t.id(), by, to == null ? "UNASSIGNED" : "REASSIGNED",
                    "The support person left support" + (to == null ? "; nobody else is in support"
                            : "; given to " + names.getOrDefault(to, "another support person")), now);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void joinedSupport(UUID userId, UUID by) {
        helpdeskLock();
        store.clearAway(userId);   // newly in support (again): not away, whether or not anything waits
        List<Ticket> waiting = store.lockUnassignedOpen();
        if (waiting.isEmpty()) {
            return;
        }
        Set<UUID> staff = new LinkedHashSet<>(available());
        staff.add(userId);   // their change may not be visible to that query yet
        Instant now = clock.instant();
        Map<UUID, String> names = names(staff);
        for (Ticket t : waiting) {
            UUID to = pick(without(staff, t.requesterId()));
            if (to == null) {
                continue;   // their own question, and nobody else is in support: it stays Unassigned
            }
            store.reassign(t.id(), to, now);
            store.addEvent(t.id(), by, "ASSIGNED", "Given to " + names.getOrDefault(to, "a support person")
                    + " (joined support)", now);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Long> openTickets(Collection<UUID> userIds) {
        return store.openCounts(userIds);
    }

    /**
     * The one with the fewest open tickets, at random among ties; null when there is nobody. Counted afresh each
     * time, so the tickets moved earlier in the same transaction count too.
     */
    private UUID pick(Collection<UUID> staff) {
        if (staff.isEmpty()) {
            return null;
        }
        Map<UUID, Long> open = store.openCounts(staff);
        long fewest = Long.MAX_VALUE;
        List<UUID> tied = new ArrayList<>();
        for (UUID id : staff) {
            long n = open.getOrDefault(id, 0L);
            if (n < fewest) {
                fewest = n;
                tied.clear();
            }
            if (n == fewest) {
                tied.add(id);
            }
        }
        return tied.get(random.nextInt(tied.size()));
    }

    private static List<UUID> without(Collection<UUID> staff, UUID person) {
        List<UUID> out = new ArrayList<>(staff);
        out.remove(person);
        return out;
    }

    private Map<UUID, String> names(Collection<UUID> ids) {
        Map<UUID, String> out = new HashMap<>();
        users.find(ids).forEach((id, p) -> out.put(id, p.name() == null ? p.phone() : p.name()));
        return out;
    }

    private void helpdeskLock() {
        jdbc.queryForObject("SELECT count(*) FROM (SELECT pg_advisory_xact_lock(?)) l", Long.class, LOCK);
    }
}
