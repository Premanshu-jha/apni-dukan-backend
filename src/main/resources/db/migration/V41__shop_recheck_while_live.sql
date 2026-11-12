-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- ============ A live shop's changes are re-checked while it stays live ============
-- Before: an approved shop whose owner changed what the field team checked (the address or pin, a photo, the salon
-- video) went back to PENDING_VISIT, hidden from customers, and its staff couldn't open the partner app until the
-- re-check. Now a verified address can't be changed by the owner at all (the field team changes it), and a new photo,
-- video, GSTIN or Udyam number keeps the shop APPROVED (bookable, its team works as usual) while it waits:
-- recheck_since is when it started waiting (the first change after the last check; later changes while it waits
-- don't move it). Cleared when the field team checks it (approve), sends it back (DRAFT), suspends it, or the owner
-- closes it. So it is set only on an APPROVED shop (the check below).
--
-- "Waiting for a visit" is now: status = 'PENDING_VISIT' OR recheck_since IS NOT NULL (the field team's lists, the
-- assignments, the counts); V42 rebuilds their indexes for it.
--
-- A new nullable column with no default: no table rewrite. The check is added NOT VALID (no lock held while existing
-- rows are read; all are NULL, so none can be wrong) and validated in V43.
--
-- During a rolling deploy the old copy doesn't know these columns: an owner edit or a decision it saves on a shop
-- the new copy put in a re-check breaks the check and answers 500 until the deploy ends. Deploy with one replica (or
-- at a quiet moment); see "API changes for re-checks while live" in the README.
ALTER TABLE shops ADD COLUMN recheck_since TIMESTAMPTZ;
ALTER TABLE shops ADD CONSTRAINT ck_shops_recheck_only_live CHECK (recheck_since IS NULL OR status = 'APPROVED') NOT VALID;
ALTER TABLE shops_aud ADD COLUMN recheck_since TIMESTAMPTZ;

-- How many times the field team has checked the shop (approved it, at a visit or a re-check). The history row where
-- it first has its newest value is the version they checked ("What changed" compares with it): a re-check can also
-- end without a check (the owner's address request, its only change, was taken back), and that row mustn't count as
-- checked. 0 for shops approved before this column (their check is found as before). A constant default: no rewrite.
ALTER TABLE shops ADD COLUMN checks INTEGER NOT NULL DEFAULT 0;
ALTER TABLE shops_aud ADD COLUMN checks INTEGER;

-- "Ask the owner to fix" (the field team, on a re-check): the shop stays as it is and keeps its place in the queue,
-- the owner is told the reason. A timeline entry of its own that, like a note, doesn't move status_since.
ALTER TABLE shop_events DROP CONSTRAINT shop_events_event_check;
ALTER TABLE shop_events ADD CONSTRAINT shop_events_event_check
    CHECK (event IN ('SUBMITTED', 'EDITED', 'APPROVED', 'SENT_BACK', 'SUSPENDED', 'REINSTATED', 'NOTE', 'CLOSED',
                     'HANDED_OVER', 'FIX_ASKED')) NOT VALID;
DROP TRIGGER tr_shops_follow_timeline ON shop_events;
CREATE TRIGGER tr_shops_follow_timeline AFTER INSERT ON shop_events
    FOR EACH ROW WHEN (NEW.event NOT IN ('NOTE', 'HANDED_OVER', 'FIX_ASKED')) EXECUTE FUNCTION shops_follow_timeline();
