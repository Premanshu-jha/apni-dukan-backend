-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- The status and timeline checks added NOT VALID in V22, now checked for the existing rows too. VALIDATE reads every
-- row but takes only a SHARE UPDATE EXCLUSIVE lock: reads and writes of shops and shop_events go on meanwhile (only
-- other schema changes wait). In its own migration, so V22's short locks were already released.
ALTER TABLE shops VALIDATE CONSTRAINT shops_status_check;
ALTER TABLE shop_events VALIDATE CONSTRAINT shop_events_event_check;
