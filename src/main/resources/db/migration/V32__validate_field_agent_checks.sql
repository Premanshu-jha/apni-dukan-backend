-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- V30's checks and foreign key, added NOT VALID, now checked for the existing rows too (as V28 did for V26's key).
-- VALIDATE reads every row but takes only a SHARE UPDATE EXCLUSIVE lock: reads and writes go on meanwhile. In its own
-- migration, so V30's short locks were already released.
ALTER TABLE users VALIDATE CONSTRAINT users_admin_designation_check;
ALTER TABLE users VALIDATE CONSTRAINT users_designation_only_for_admins;
ALTER TABLE shops VALIDATE CONSTRAINT fk_shops_assigned_admin;
