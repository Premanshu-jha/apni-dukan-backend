-- The field team's lists, page by page, with tens of thousands of shops: when a shop entered its current status and
-- whether it was ever approved become columns (kept by ShopReviewStore.addEvent, which writes every timeline entry),
-- instead of being worked out from the timeline and the history for every shop on every list.

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

-- A list page: one group of one type, longest in it first, the next page after the last (status_since, id)
CREATE INDEX ix_shops_review_list ON shops (type, status, ever_approved, status_since, id);

-- People whose deleted account still has message texts to empty: the request that deletes the account only queues
-- them, and MessageCleanupJob empties their messages a slice at a time (a busy salon owner can have lakhs)
CREATE TABLE account_wipes (
    user_id   UUID PRIMARY KEY REFERENCES users (id),
    queued_at TIMESTAMPTZ NOT NULL
);
