package in.apnidukan.common.context;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The support tickets, as the Team page needs them. Implemented by the helpdesk module; used by the auth module, so
 * neither depends on the other. Both calls run inside the team change's transaction, holding {@link TeamLock}
 * exclusively.
 */
public interface SupportDesk {

    /**
     * This person stopped being a support person (another designation, a super admin, removed from the team,
     * account deleted): each ticket still open with them goes to the support person with the fewest open tickets,
     * or to Unassigned when there is nobody else.
     * @param by the super admin who made the change; null when the person deleted their own account
     */
    void leftSupport(UUID userId, UUID by);

    /** A new support person: the tickets waiting in Unassigned go through the rule (fewest open tickets first). */
    void joinedSupport(UUID userId, UUID by);

    /** How many tickets each of these people has open (not resolved); 0 for none. */
    Map<UUID, Long> openTickets(Collection<UUID> userIds);
}
