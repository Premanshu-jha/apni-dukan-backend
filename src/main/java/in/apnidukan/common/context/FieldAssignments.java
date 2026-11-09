package in.apnidukan.common.context;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Which field agent visits each business waiting for a visit. Implemented by the shops module; used by the auth
 * module's Team page, so neither depends on the other.
 */
public interface FieldAssignments {

    /**
     * An admin stopped covering pincodes (removed from their list), or stopped being a field agent (made a super
     * admin, removed from the team, account deleted): each business waiting for their visit in those pincodes (every
     * one, when {@code removed} is null) goes to the least-loaded field agent who covers its pincode, or to
     * Unassigned. One a super admin sent them in another pincode stays. Call it after the change is saved, holding
     * {@link TeamLock} exclusively.
     * @param removed the pincodes taken away; null when they are no longer a field agent at all
     * @param by      the super admin who made the change; null when the person deleted their own account
     */
    void coverageChanged(UUID adminId, Collection<String> removed, UUID by);

    /**
     * A field agent now covers these pincodes (added to their list, or a new agent): the businesses there that wait
     * with nobody assigned go through the rule. Call it after the change is saved, holding {@link TeamLock}
     * exclusively.
     */
    void coverageAdded(UUID adminId, Collection<String> added, UUID by);

    /** How many businesses waiting for a visit each of these admins has (0 for none). */
    Map<UUID, Long> pendingVisits(Collection<UUID> adminIds);
}
