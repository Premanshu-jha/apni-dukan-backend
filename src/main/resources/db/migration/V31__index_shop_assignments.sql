-- Built CONCURRENTLY, without a transaction and with no lock timeout, for the reasons given in V24: writes to shops go
-- on while they build. If a build fails, see V24 and "Database changes" in the README (each DROP lets it run again).
SET lock_timeout = 0;

-- A field agent's list (any status, longest in it first) and their waiting count (the least-loaded pick) are each one
-- range of this index.
DROP INDEX CONCURRENTLY IF EXISTS ix_shops_assignee_list;
CREATE INDEX CONCURRENTLY ix_shops_assignee_list ON shops (assigned_admin_id, type, status, status_since, id)
    WHERE assigned_admin_id IS NOT NULL;

-- The super admins' Unassigned list: businesses waiting with no agent, longest waiting first.
DROP INDEX CONCURRENTLY IF EXISTS ix_shops_unassigned;
CREATE INDEX CONCURRENTLY ix_shops_unassigned ON shops (type, status_since, id)
    WHERE status = 'PENDING_VISIT' AND assigned_admin_id IS NULL;

-- How many businesses wait for each agent (the least-loaded pick, the Team page): only the waiting ones, however many
-- an agent visited before.
DROP INDEX CONCURRENTLY IF EXISTS ix_shops_assignee_waiting;
CREATE INDEX CONCURRENTLY ix_shops_assignee_waiting ON shops (assigned_admin_id) WHERE status = 'PENDING_VISIT';

-- Pincodes added to an agent pick up the businesses there waiting with nobody (ShopAssignmentStore.lockUnassignedIn).
DROP INDEX CONCURRENTLY IF EXISTS ix_shops_unassigned_pincode;
CREATE INDEX CONCURRENTLY ix_shops_unassigned_pincode ON shops (pincode, id)
    WHERE status = 'PENDING_VISIT' AND assigned_admin_id IS NULL;
