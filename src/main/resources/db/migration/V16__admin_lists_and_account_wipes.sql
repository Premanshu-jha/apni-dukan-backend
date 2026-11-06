-- The field team's lists, page by page, with tens of thousands of shops: when a shop entered its current status and
-- whether it was ever approved become columns, instead of being worked out from the timeline and the history for every
-- shop on every list. A trigger on the timeline (shop_events) keeps them, so every writer does, including a copy of
-- the backend still running the previous version while this one starts.

-- When the shop entered its current status: its latest timeline entry other than a note (as before), or when it
-- registered for shops from before the timeline (V11)
ALTER TABLE shops ADD COLUMN status_since TIMESTAMPTZ;
UPDATE shops s SET status_since = COALESCE(
    (SELECT max(e.at) FROM shop_events e WHERE e.shop_id = s.id AND e.event <> 'NOTE'), s.created_at);

-- A new shop starts its status when it is created (the app doesn't map this column)
CREATE FUNCTION shops_status_since_default() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.status_since := COALESCE(NEW.status_since, NEW.created_at);
    RETURN NEW;
END $$;
CREATE TRIGGER tr_shops_status_since_default BEFORE INSERT ON shops
    FOR EACH ROW EXECUTE FUNCTION shops_status_since_default();
ALTER TABLE shops ALTER COLUMN status_since SET NOT NULL;

-- Was it ever approved (first visit or re-check), from the history
ALTER TABLE shops ADD COLUMN ever_approved BOOLEAN NOT NULL DEFAULT false;
UPDATE shops s SET ever_approved = true
    WHERE EXISTS (SELECT 1 FROM shops_aud a WHERE a.id = s.id AND a.status = 'APPROVED');

-- Every status change writes a timeline entry other than a note: that moment is when the shop entered its status,
-- and an APPROVED entry makes it approved for good (reinstating sends it back for a visit, so it doesn't)
CREATE FUNCTION shops_follow_timeline() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE shops SET status_since = GREATEST(status_since, NEW.at),
                     ever_approved = ever_approved OR NEW.event = 'APPROVED'
        WHERE id = NEW.shop_id;
    RETURN NULL;
END $$;
CREATE TRIGGER tr_shops_follow_timeline AFTER INSERT ON shop_events
    FOR EACH ROW WHEN (NEW.event <> 'NOTE') EXECUTE FUNCTION shops_follow_timeline();

-- A list page is one range of an index, however deep: one group of one type, longest in it first, the next page
-- after the last (status_since, id). Waiting shops split by first visit / re-check, so they have their own index.
CREATE INDEX ix_shops_review_list ON shops (type, status, status_since, id);
CREATE INDEX ix_shops_review_waiting ON shops (type, ever_approved, status_since, id) WHERE status = 'PENDING_VISIT';
-- Sent back: drafts with a reason only (registrations never submitted are drafts too, and can be many)
CREATE INDEX ix_shops_review_sent_back ON shops (type, status_since, id)
    WHERE status = 'DRAFT' AND status_reason IS NOT NULL;

-- People whose deleted account still has message texts to empty: the request that deletes the account only queues
-- them, and MessageCleanupJob empties their messages a slice at a time (a busy salon owner can have lakhs)
CREATE TABLE account_wipes (
    user_id   UUID PRIMARY KEY REFERENCES users (id),
    queued_at TIMESTAMPTZ NOT NULL,
    -- How far it got (the last message id emptied, in id order): the next run carries on from here
    after_id  UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000'
);
