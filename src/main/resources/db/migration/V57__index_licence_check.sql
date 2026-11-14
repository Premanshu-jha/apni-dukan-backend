-- Built CONCURRENTLY, without a transaction and with no lock timeout, for the reasons given in V24: writes to shops go
-- on while it builds. If the build fails, see V24 and "Database changes" in the README (the DROP lets it run again).
SET lock_timeout = 0;

-- The licences the job reads next (LicenceCheckStore.claim), soonest first.
DROP INDEX CONCURRENTLY IF EXISTS ix_shops_licence_due;
CREATE INDEX CONCURRENTLY ix_shops_licence_due ON shops (licence_check_due_at, id)
    WHERE licence_check_status = 'PENDING';

-- One salon per licence number: a second salon sending the same licence fails with DUPLICATE (and two checks
-- finishing at the same moment can't both pass).
DROP INDEX CONCURRENTLY IF EXISTS ux_shops_licence_number;
CREATE UNIQUE INDEX CONCURRENTLY ux_shops_licence_number ON shops (licence_number)
    WHERE licence_check_status = 'PASSED';

-- Licences coming to their last day, for LicenceExpiryJob's messages (a month before, and when expired).
DROP INDEX CONCURRENTLY IF EXISTS ix_shops_licence_expiry;
CREATE INDEX CONCURRENTLY ix_shops_licence_expiry ON shops (licence_valid_until, id)
    WHERE licence_check_status = 'PASSED' AND licence_expiry_notice < 2;

-- The super admins' list of licences to follow up (not accepted, or waiting long), longest first.
DROP INDEX CONCURRENTLY IF EXISTS ix_shops_licence_follow_up;
CREATE INDEX CONCURRENTLY ix_shops_licence_follow_up ON shops (licence_check_since, id)
    WHERE licence_check_status IN ('PENDING', 'FAILED');

-- The field team's list of documents to check is gone (the job checks them).
DROP INDEX CONCURRENTLY IF EXISTS ix_shops_registration_doc_to_check;
