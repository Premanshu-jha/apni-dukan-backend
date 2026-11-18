-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- Validates V74's rule (reads the shops table once, without blocking writes).
ALTER TABLE shops VALIDATE CONSTRAINT ck_shops_licence_passed;
