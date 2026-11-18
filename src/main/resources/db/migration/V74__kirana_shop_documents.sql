-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- A kirana's documents and whether it sells food (backend PR "change kirana <-> salon while registering", 2026-11-18): a kirana may add its
-- municipal trade licence or its Shops and Establishments certificate, both checked automatically like a salon's
-- trade licence. Many Shops and Establishments certificates print no last day, so one of those can pass with no
-- licence_valid_until (no end date); a trade licence still needs one.
--
-- Only the PASSED rule changes: dropped and added again NOT VALID (no row is read now, so no long lock), validated in
-- V75. Every row passing the old rule passes the new one (it is wider).
ALTER TABLE shops DROP CONSTRAINT ck_shops_licence_passed;
ALTER TABLE shops ADD CONSTRAINT ck_shops_licence_passed
    CHECK (licence_check_status IS DISTINCT FROM 'PASSED'
           OR (licence_number IS NOT NULL
               AND (licence_valid_until IS NOT NULL OR registration_doc_type = 'SHOPS_ESTABLISHMENT'))) NOT VALID;

-- Whether the kirana sells any food (packed or loose), answered at registration: NULL until answered. Yes: its FSSAI
-- licence is needed before Submit for visit (and, from round 2's food filter on, before online orders); No: none, and
-- it lists no food online. Nullable with no default: added instantly, no row rewritten.
ALTER TABLE kirana_settings ADD COLUMN sells_food BOOLEAN;
