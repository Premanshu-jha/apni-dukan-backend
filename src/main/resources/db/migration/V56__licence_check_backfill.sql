-- On a live database: wait at most 3 s for each row's lock (see V17 and "Database changes" in the README). Only rows
-- with a document are touched (a few salons), each with a row lock; reads of shops go on meanwhile.
SET LOCAL lock_timeout = '3s';

-- Documents already there. A closed salon's document goes (it is never opened again: its licence number must not
-- block a new registration of the same business).
UPDATE shops SET registration_doc_type = NULL, registration_doc_file_id = NULL, registration_doc_checked_at = NULL
    WHERE registration_doc_file_id IS NOT NULL AND status = 'CLOSED';
-- Only a trade licence counts now: a Udyam or Shops and Establishments certificate stays on the salon (the owner sees
-- why it no longer counts and can replace it) but gives no badge and is never sent to the AI.
UPDATE shops SET licence_check_status = 'FAILED', licence_check_reason = 'NOT_TRADE_LICENCE',
                 registration_doc_checked_at = NULL
    WHERE registration_doc_file_id IS NOT NULL AND registration_doc_type <> 'TRADE_LICENCE';
-- Every trade licence is read again by the job (the field team's check gave no number or last day).
UPDATE shops SET licence_check_status = 'PENDING', licence_check_due_at = now(), licence_check_since = now(),
                 registration_doc_checked_at = NULL
    WHERE registration_doc_file_id IS NOT NULL AND registration_doc_type = 'TRADE_LICENCE';
