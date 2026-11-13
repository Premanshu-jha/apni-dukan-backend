-- On a live database: wait at most 3 s for the table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- V52's check (every file but a ticket's has who uploaded it), now for the existing rows too (as V51 did for V49).
-- VALIDATE reads every row but takes only a SHARE UPDATE EXCLUSIVE lock: reads and writes of files go on meanwhile.
ALTER TABLE files VALIDATE CONSTRAINT files_uploader_unless_ticket;
