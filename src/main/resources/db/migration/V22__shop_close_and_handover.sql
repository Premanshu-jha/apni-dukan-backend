-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- An owner can close their shop for good (CLOSED: off the app, every booking still to come cancelled, everyone's
-- membership ended) or hand it over to someone else. A closed shop is never opened again: its owner registers a new
-- one. Its rows stay (bookings, history, the timeline) for the field team and the customers' past bookings.
-- NOT VALID: the new checks hold for every row written from now on, and adding them reads no existing row (the
-- table's lock is held only for that moment). The existing rows are checked in V23, in its own transaction, under a
-- lock that lets reads and writes go on.
ALTER TABLE shops DROP CONSTRAINT shops_status_check;
ALTER TABLE shops ADD CONSTRAINT shops_status_check
    CHECK (status IN ('DRAFT', 'PENDING_VISIT', 'APPROVED', 'SUSPENDED', 'CLOSED')) NOT VALID;

-- Timeline entries for both
ALTER TABLE shop_events DROP CONSTRAINT shop_events_event_check;
ALTER TABLE shop_events ADD CONSTRAINT shop_events_event_check
    CHECK (event IN ('SUBMITTED', 'EDITED', 'APPROVED', 'SENT_BACK', 'SUSPENDED', 'REINSTATED', 'NOTE', 'CLOSED',
                     'HANDED_OVER')) NOT VALID;

-- A hand-over the owner asked for: to a phone number, so the answer to the owner never says whether that number has
-- an account (the person with that phone sees it after signing in). At most one waiting per shop; it runs out after
-- 7 days (expires_at; a request still marked PENDING after that counts as expired everywhere it is read).
-- A new, empty table: its indexes are built at once.
CREATE TABLE shop_handovers (
    id          UUID          PRIMARY KEY,
    shop_id     UUID          NOT NULL REFERENCES shops (id),
    from_user   UUID          NOT NULL REFERENCES users (id),     -- the owner who asked
    to_phone    VARCHAR(10)   NOT NULL,                           -- 10 digits, as users.phone
    status      VARCHAR(10)   NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'CANCELLED', 'EXPIRED')),
    created_at  TIMESTAMPTZ   NOT NULL,
    expires_at  TIMESTAMPTZ   NOT NULL,
    decided_at  TIMESTAMPTZ,
    decided_by  UUID          REFERENCES users (id)               -- who accepted, declined or cancelled it
);
CREATE UNIQUE INDEX ux_shop_handovers_one_pending ON shop_handovers (shop_id) WHERE status = 'PENDING';
-- "Is anything waiting for my phone?" on every check of the app's hand-over screen
CREATE INDEX ix_shop_handovers_to_phone ON shop_handovers (to_phone) WHERE status = 'PENDING';
CREATE INDEX ix_shop_handovers_from_user ON shop_handovers (from_user);
CREATE INDEX ix_shop_handovers_decided_by ON shop_handovers (decided_by) WHERE decided_by IS NOT NULL;

-- A hand-over is not a change of status: like a note, it doesn't move the shop's status_since (V16), so it keeps its
-- place in the field team's lists
DROP TRIGGER tr_shops_follow_timeline ON shop_events;
CREATE TRIGGER tr_shops_follow_timeline AFTER INSERT ON shop_events
    FOR EACH ROW WHEN (NEW.event NOT IN ('NOTE', 'HANDED_OVER')) EXECUTE FUNCTION shops_follow_timeline();

-- The index on shop_events for a repeated close is built in V24, CONCURRENTLY (the closed list reads V16's
-- ix_shops_review_list).
