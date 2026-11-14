-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- Until this version the licence reader forced a tool call (tool_choice "tool"), which Sonnet 5.5 refuses with a 400,
-- so every licence read since the key was set failed as NOT_READ ("we couldn't read it") after 3 tries. Those are
-- checked again now, from the start (their owners are told the real answer), and the tries a waiting check counted
-- for the refusals are forgotten. Uses ix_shops_licence_follow_up (FAILED and PENDING rows only).
UPDATE shops SET licence_check_status = 'PENDING', licence_check_reason = NULL, licence_number = NULL,
    licence_valid_until = NULL, licence_extract = NULL, licence_check_attempts = 0, licence_check_unusable = 0,
    licence_check_due_at = now(), licence_check_since = now(), licence_expiry_notice = 0,
    registration_doc_checked_at = NULL
WHERE licence_check_status = 'FAILED' AND licence_check_reason = 'NOT_READ'
  AND registration_doc_type = 'TRADE_LICENCE' AND registration_doc_file_id IS NOT NULL;

UPDATE shops SET licence_check_unusable = 0, licence_check_due_at = now()
WHERE licence_check_status = 'PENDING' AND licence_check_unusable > 0;
