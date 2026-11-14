-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- V55's checks, added NOT VALID, now checked for the existing rows too (as V36 did for V34). VALIDATE takes only a
-- SHARE UPDATE EXCLUSIVE lock: reads and writes go on meanwhile.
ALTER TABLE shops VALIDATE CONSTRAINT ck_shops_licence_check;
ALTER TABLE shops VALIDATE CONSTRAINT ck_shops_licence_check_document;
ALTER TABLE shops VALIDATE CONSTRAINT ck_shops_licence_passed;
