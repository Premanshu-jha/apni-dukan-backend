-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- Field agents: every ADMIN now has a designation (a job title; FIELD_AGENT is the only one so far) and the pincodes
-- they cover. A SUPER_ADMIN has neither. Businesses waiting for a visit are assigned to one field agent. Indexes on
-- shops are built CONCURRENTLY in V31; the checks and the foreign key added NOT VALID here are validated in V32.

-- 1. Designation: required for ADMIN, empty for everyone else. Existing admins become field agents (no pincodes yet:
--    they get businesses once a super admin adds their pincodes on the Team page). New nullable columns: no table
--    rewrite; the checks are NOT VALID so no lock is held while every user row is read (V32 checks the old rows).
ALTER TABLE users ADD COLUMN admin_designation VARCHAR(20);
UPDATE users SET admin_designation = 'FIELD_AGENT' WHERE admin_role = 'ADMIN';
ALTER TABLE users ADD CONSTRAINT users_admin_designation_check
    CHECK (admin_designation IN ('FIELD_AGENT')) NOT VALID;
ALTER TABLE users ADD CONSTRAINT users_designation_only_for_admins
    CHECK ((admin_role = 'ADMIN') = (admin_designation IS NOT NULL)) NOT VALID;

-- Bumped by every Team-page change to a member, so two super admins editing the same person at once can't overwrite
-- each other's change (the edit carries the version it was made on: 409 MEMBER_CHANGED otherwise). A constant
-- default: no table rewrite.
ALTER TABLE users ADD COLUMN admin_version BIGINT NOT NULL DEFAULT 0;

-- 2. More than one super admin is allowed now
DROP INDEX ux_users_one_super_admin;

-- 3. The pincodes each field agent covers. A pincode may be covered by several agents. Looked up by pincode when a
--    business is assigned (ix_admin_pincodes_pincode; the primary key serves "this agent's pincodes").
CREATE TABLE admin_pincodes (
    user_id    UUID          NOT NULL REFERENCES users (id),
    pincode    VARCHAR(6)    NOT NULL CHECK (pincode ~ '^[1-9][0-9]{5}$'),
    added_at   TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (user_id, pincode)
);
CREATE INDEX ix_admin_pincodes_pincode ON admin_pincodes (pincode, user_id);

-- 4. Which field agent visits each business. Not mapped by the app's Shop entity (so a field-team decision or an
--    owner's edit never writes an old assignment back); kept by ShopAssignmentStore. Null: unassigned. It stays after
--    the decision, so an agent still sees the businesses they visited. Nullable columns, the key NOT VALID (all NULL).
ALTER TABLE shops ADD COLUMN assigned_admin_id UUID;
ALTER TABLE shops ADD COLUMN assigned_at TIMESTAMPTZ;
ALTER TABLE shops ADD CONSTRAINT fk_shops_assigned_admin FOREIGN KEY (assigned_admin_id) REFERENCES users (id)
    NOT VALID;

-- 5. Every assignment, automatic or by a super admin. Never deleted. by_user null: done automatically; to_admin null:
--    left unassigned (nobody covers its pincode).
CREATE TABLE shop_assignments (
    id          UUID          PRIMARY KEY,
    shop_id     UUID          NOT NULL REFERENCES shops (id),
    at          TIMESTAMPTZ   NOT NULL,
    from_admin  UUID          REFERENCES users (id),
    to_admin    UUID          REFERENCES users (id),
    by_user     UUID          REFERENCES users (id),
    reason      VARCHAR(30)   NOT NULL CHECK (reason IN ('SUBMITTED', 'RECHECK', 'REINSTATED', 'PINCODE_CHANGED',
                                                         'MANUAL', 'MOVE_ALL', 'COVERAGE_CHANGED', 'ADMIN_REMOVED'))
);
CREATE INDEX ix_shop_assignments_shop ON shop_assignments (shop_id, at DESC);
-- (No index on from_admin, to_admin or by_user: the log is read per shop only, and users are never deleted.)

-- 6. Team-page edits are recorded like additions and removals (admin_events is small)
ALTER TABLE admin_events DROP CONSTRAINT admin_events_event_check;
ALTER TABLE admin_events ADD CONSTRAINT admin_events_event_check CHECK (event IN ('ADMIN_ADDED', 'ADMIN_REMOVED',
    'ADMIN_EDITED', 'TEST_PHONE_ADDED', 'TEST_PHONE_REMOVED', 'TEST_EMAIL_ADDED', 'TEST_EMAIL_REMOVED',
    'TEST_MODE_ON', 'TEST_MODE_OFF'));
