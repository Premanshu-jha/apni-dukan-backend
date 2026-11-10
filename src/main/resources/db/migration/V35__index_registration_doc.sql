-- Built CONCURRENTLY, without a transaction and with no lock timeout, for the reasons given in V24: writes to shops go
-- on while it builds. If the build fails, see V24 and "Database changes" in the README (the DROP lets it run again).
SET lock_timeout = 0;

-- "Is this file some shop's registration document?" (a file in use can't be deleted), like ix_shops_shop_video (V27).
DROP INDEX CONCURRENTLY IF EXISTS ix_shops_registration_doc;
CREATE INDEX CONCURRENTLY ix_shops_registration_doc ON shops (registration_doc_file_id)
    WHERE registration_doc_file_id IS NOT NULL;

-- The field team's list of documents to check (added or replaced since the last check), oldest change first.
DROP INDEX CONCURRENTLY IF EXISTS ix_shops_registration_doc_to_check;
CREATE INDEX CONCURRENTLY ix_shops_registration_doc_to_check ON shops (updated_at, id)
    WHERE registration_doc_file_id IS NOT NULL AND registration_doc_checked_at IS NULL;

-- Closed days are read from salon_closed_days now (V34), so this index of the barbers' CLOSED blocks by salon is no
-- longer used; it only slowed every write to appointments. (Removing a day's blocks uses ix_appointments_closure.)
DROP INDEX CONCURRENTLY IF EXISTS ix_appointments_shop_closures;
