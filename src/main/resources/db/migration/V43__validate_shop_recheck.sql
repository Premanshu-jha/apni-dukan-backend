-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- V41's checks, now checked for the existing rows too (as V28 did for V26). VALIDATE reads every row but takes only a
-- SHARE UPDATE EXCLUSIVE lock: reads and writes go on meanwhile.
ALTER TABLE shops VALIDATE CONSTRAINT ck_shops_recheck_only_live;
ALTER TABLE shop_events VALIDATE CONSTRAINT shop_events_event_check;
