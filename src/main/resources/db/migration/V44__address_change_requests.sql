-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- ============ The owner asks the field team to change a verified address ============
-- Once the field team verified a shop's address (V41: shops.ever_approved), the owner can't change the address or
-- the pin himself. He asks in the partner app: the new address and pin wait here for the field team, the shop keeps
-- its old address (and stays live and bookable) until they approve it (it is then applied, as their own change of
-- address), or decline it with a reason he is told.
--
-- At most one request waits per shop (the unique index below): a new one replaces the waiting one (CANCELLED).
-- New table: nothing existing is rewritten. Its foreign keys hold shops and users briefly (SHARE ROW EXCLUSIVE, which
-- waits for a write in progress on them); with the 3 s limit a very busy moment fails the deploy rather than queue
-- traffic behind it, and the deploy is retried.
CREATE TABLE address_change_requests (
    id           UUID          PRIMARY KEY,
    seq          BIGINT        GENERATED ALWAYS AS IDENTITY,      -- the order they were made in (times can be equal)
    shop_id      UUID          NOT NULL REFERENCES shops (id) ON DELETE CASCADE,
    requested_by UUID          NOT NULL REFERENCES users (id),   -- the owner who asked
    address_line VARCHAR(200)  NOT NULL,
    area         VARCHAR(100)  NOT NULL,
    city         VARCHAR(60)   NOT NULL,
    pincode      CHAR(6)       NOT NULL,
    latitude     NUMERIC(9, 6) NOT NULL,
    longitude    NUMERIC(9, 6) NOT NULL,
    status       VARCHAR(10)   NOT NULL DEFAULT 'PENDING'
                 CHECK (status IN ('PENDING', 'APPROVED', 'DECLINED', 'CANCELLED')),
    reason       VARCHAR(200),                                   -- why it was declined (told to the owner)
    decided_by   UUID          REFERENCES users (id),            -- who approved, declined or cancelled it
    created_at   TIMESTAMPTZ   NOT NULL,
    decided_at   TIMESTAMPTZ,
    CONSTRAINT ck_address_request_decided CHECK ((status = 'PENDING') = (decided_at IS NULL))
);

-- One waiting request per shop; also how the field team's pages find it
CREATE UNIQUE INDEX ux_address_request_pending ON address_change_requests (shop_id) WHERE status = 'PENDING';
-- A shop's latest request (the owner sees the last decision: declined, with the reason)
CREATE INDEX ix_address_request_shop ON address_change_requests (shop_id, seq DESC);
