-- Built CONCURRENTLY, without a transaction and with no lock timeout, for the reasons given in V24: writes to shops go
-- on while they build. If a build fails, see V24 and "Database changes" in the README (each DROP lets it run again).
SET lock_timeout = 0;

-- Waiting for a visit now includes live shops waiting for a re-check (V41): the waiting-only indexes of V31 are built
-- again with that condition, under new names, and the old ones dropped once the new ones exist.

-- The Re-check list: shops approved before and waiting again (moved while live, or reinstated), longest waiting first.
DROP INDEX CONCURRENTLY IF EXISTS ix_shops_review_recheck;
CREATE INDEX CONCURRENTLY ix_shops_review_recheck ON shops (type, status_since, id)
    WHERE (status = 'PENDING_VISIT' AND ever_approved) OR recheck_since IS NOT NULL;

-- The super admins' Unassigned list.
DROP INDEX CONCURRENTLY IF EXISTS ix_shops_unassigned_waiting;
CREATE INDEX CONCURRENTLY ix_shops_unassigned_waiting ON shops (type, status_since, id)
    WHERE (status = 'PENDING_VISIT' OR recheck_since IS NOT NULL) AND assigned_admin_id IS NULL;
DROP INDEX CONCURRENTLY IF EXISTS ix_shops_unassigned;

-- How many businesses wait for each agent (the least-loaded pick, the Team page).
DROP INDEX CONCURRENTLY IF EXISTS ix_shops_assignee_waiting_any;
CREATE INDEX CONCURRENTLY ix_shops_assignee_waiting_any ON shops (assigned_admin_id)
    WHERE status = 'PENDING_VISIT' OR recheck_since IS NOT NULL;
DROP INDEX CONCURRENTLY IF EXISTS ix_shops_assignee_waiting;

-- Pincodes added to an agent pick up the businesses there waiting with nobody.
DROP INDEX CONCURRENTLY IF EXISTS ix_shops_unassigned_pincode_waiting;
CREATE INDEX CONCURRENTLY ix_shops_unassigned_pincode_waiting ON shops (pincode, id)
    WHERE (status = 'PENDING_VISIT' OR recheck_since IS NOT NULL) AND assigned_admin_id IS NULL;
DROP INDEX CONCURRENTLY IF EXISTS ix_shops_unassigned_pincode;
