-- Each shop's timeline for the field team: sent for a visit, changed after approval, approved, sent back,
-- suspended, reinstated, and notes. `reason` is what the owner was told; `note` is for admins only.
CREATE TABLE shop_events (
    id         UUID          PRIMARY KEY,
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
CREATE INDEX ix_shop_events_shop ON shop_events (shop_id, at DESC);

-- The field team's lists read the latest approved version of a shop from its history
CREATE INDEX ix_shops_aud_approved ON shops_aud (id, rev DESC) WHERE status = 'APPROVED';
