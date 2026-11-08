-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- The salon video's foreign key, added NOT VALID in V26, now checked for the existing rows too (as V23 did for V22's
-- checks). VALIDATE reads every row but takes only a SHARE UPDATE EXCLUSIVE lock: reads and writes of shops go on
-- meanwhile. In its own migration, so V26's short lock was already released.
ALTER TABLE shops VALIDATE CONSTRAINT fk_shops_shop_video;
