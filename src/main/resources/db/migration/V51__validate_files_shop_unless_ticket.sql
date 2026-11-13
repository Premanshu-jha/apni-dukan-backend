-- On a live database: wait at most 3 s for the table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- V49's check (every file but a ticket's has a shop), now for the existing rows too (as V28 did for V26). VALIDATE
-- reads every row but takes only a SHARE UPDATE EXCLUSIVE lock: reads and writes of files go on meanwhile. In its own
-- migration, so V49's short lock was already released.
ALTER TABLE files VALIDATE CONSTRAINT files_shop_unless_ticket;
