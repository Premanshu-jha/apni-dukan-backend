-- Each shop's timeline for the field team: sent for a visit, changed after approval, approved, sent back,
-- suspended, reinstated, and notes. `reason` is what the owner was told; `note` is for admins only.
CREATE TABLE shop_events (
    id         UUID          PRIMARY KEY,
    seq        BIGINT        GENERATED ALWAYS AS IDENTITY,   -- order of entries made in the same instant
    shop_id    UUID          NOT NULL REFERENCES shops (id),
    at         TIMESTAMPTZ   NOT NULL,
    by_user    UUID          REFERENCES users (id),      -- the owner or an admin; null for the system
    by_admin   BOOLEAN       NOT NULL,
    event      VARCHAR(20)   NOT NULL CHECK (event IN ('SUBMITTED', 'EDITED', 'APPROVED', 'SENT_BACK', 'SUSPENDED',
                                                       'REINSTATED', 'NOTE')),
    reason     VARCHAR(200),
    note       VARCHAR(500),
    checklist  VARCHAR(300)                              -- comma-separated items, e.g. PIN_WRONG,SHOP_SHUT
);
CREATE INDEX ix_shop_events_shop ON shop_events (shop_id, at DESC, seq DESC);

-- The field team's pages read a shop's approved versions from its history, and deleting a photo checks whether it is
-- one of them
CREATE INDEX ix_shops_aud_approved ON shops_aud (id, rev DESC) WHERE status = 'APPROVED';
CREATE INDEX ix_shops_aud_approved_owner_photo ON shops_aud (owner_photo_file_id) WHERE status = 'APPROVED';
CREATE INDEX ix_shops_aud_approved_shop_photo ON shops_aud (shop_photo_file_id) WHERE status = 'APPROVED';
