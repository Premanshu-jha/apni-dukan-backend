-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- A salon's trade licence is checked automatically now (LicenceCheckJob reads it with AI, LicenceRules decide), no
-- longer by the field team. Customers see "Licensed" only while the check PASSED and the licence is still valid.
--   licence_check_status   PENDING (waiting for the job), PASSED, FAILED; NULL when the salon has no document
--   licence_check_reason   why it FAILED (LicenceCheckReason); NULL otherwise
--   licence_number         the licence number read from it (letters and digits only), once PASSED: one salon each
--   licence_valid_until    the last day it is valid, once PASSED (the badge goes after that day)
--   licence_extract        what was read from the document (for the owner's and the admin page)
--   licence_check_attempts the job's tries at this check; also what the job's answer must still find (a new check
--                          starts again from 0, so an answer for an older one is dropped)
--   licence_check_unusable answers the job couldn't use (3 and it FAILS: NOT_READ)
--   licence_check_due_at   when the job reads it next (PENDING only)
--   licence_check_since    when this check started (owner and admin see how long it has been waiting)
--   licence_expiry_notice  messages sent about its last day: 1 (a month before), 2 (expired)
-- The Shop entity starts a check (a new document: PENDING) or ends it (removed: all NULL), and ShopService repeats
-- the start in plain SQL; LicenceCheckStore writes the job's answer with plain SQL. Shop is saved with @DynamicUpdate,
-- so an owner saving something else never writes these back. Not in shops_aud: the timeline keeps each result.
-- Only the columns here: the rows are filled in V56, after this lock is gone.
ALTER TABLE shops ADD COLUMN licence_check_status VARCHAR(10);
ALTER TABLE shops ADD COLUMN licence_check_reason VARCHAR(30);
ALTER TABLE shops ADD COLUMN licence_number VARCHAR(40);
ALTER TABLE shops ADD COLUMN licence_valid_until DATE;
ALTER TABLE shops ADD COLUMN licence_extract JSONB;
ALTER TABLE shops ADD COLUMN licence_check_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE shops ADD COLUMN licence_check_unusable INT NOT NULL DEFAULT 0;
ALTER TABLE shops ADD COLUMN licence_check_due_at TIMESTAMPTZ;
ALTER TABLE shops ADD COLUMN licence_check_since TIMESTAMPTZ;
ALTER TABLE shops ADD COLUMN licence_expiry_notice SMALLINT NOT NULL DEFAULT 0;

-- Added NOT VALID (no row read now) and validated in V58.
ALTER TABLE shops ADD CONSTRAINT ck_shops_licence_check
    CHECK (licence_check_status IS NULL OR licence_check_status IN ('PENDING', 'PASSED', 'FAILED')) NOT VALID;
ALTER TABLE shops ADD CONSTRAINT ck_shops_licence_check_document
    CHECK ((licence_check_status IS NULL) = (registration_doc_file_id IS NULL)) NOT VALID;
ALTER TABLE shops ADD CONSTRAINT ck_shops_licence_passed
    CHECK (licence_check_status IS DISTINCT FROM 'PASSED'
           OR (licence_number IS NOT NULL AND licence_valid_until IS NOT NULL)) NOT VALID;
