-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- V34's checks and key, added NOT VALID, now checked for the existing rows too (as V28 did for V26). VALIDATE reads
-- every row but takes only a SHARE UPDATE EXCLUSIVE lock: reads and writes go on meanwhile. In its own migration, so
-- V34's short locks were already released.
ALTER TABLE shops VALIDATE CONSTRAINT ck_shops_registration_doc;
ALTER TABLE shops VALIDATE CONSTRAINT fk_shops_registration_doc;
ALTER TABLE shops VALIDATE CONSTRAINT ck_shops_registration_doc_checked;
ALTER TABLE salon_staff VALIDATE CONSTRAINT ck_salon_staff_removed;
ALTER TABLE salon_services VALIDATE CONSTRAINT ck_salon_services_return_after_days;
