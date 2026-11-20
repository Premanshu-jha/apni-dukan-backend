-- GS1 DataKart (switched on by its settings): products' official pictures for the shared catalogue, and barcodes
-- suggested for products added without one. Short locks only: if one can't be taken in 3 seconds the deploy fails
-- and is retried.
SET LOCAL lock_timeout = '3s';

-- The platform's own pictures (CATALOGUE_PHOTO) belong to no shop and no uploader, like support files. NOT VALID now
-- (no scan of files), validated by V80.
ALTER TABLE files DROP CONSTRAINT files_shop_unless_ticket;
ALTER TABLE files ADD CONSTRAINT files_shop_unless_ticket
    CHECK (shop_id IS NOT NULL OR purpose IN ('TICKET_FILE', 'CATALOGUE_PHOTO')) NOT VALID;
ALTER TABLE files DROP CONSTRAINT files_uploader_unless_ticket;
ALTER TABLE files ADD CONSTRAINT files_uploader_unless_ticket
    CHECK (uploaded_by IS NOT NULL OR purpose IN ('TICKET_FILE', 'CATALOGUE_PHOTO')) NOT VALID;

-- A barcode's link may come from GS1 (the official registry: trusted)
ALTER TABLE kirana_catalogue_barcodes DROP CONSTRAINT ck_kirana_catalogue_barcodes_source;
ALTER TABLE kirana_catalogue_barcodes ADD CONSTRAINT ck_kirana_catalogue_barcodes_source
    CHECK (source IN ('SHOP', 'MASTER', 'ADMIN', 'GS1')) NOT VALID;

-- What to look up on GS1, and what came of it:
--   CODE: a catalogue barcode whose item has no picture (its official picture, brand and size);
--   NAME: a shop's product added without a barcode (its barcode found by name, linked to its catalogue item).
-- WAITING until looked up (again at next_try_at); NOT_FOUND looked up again a month later (brands keep adding);
-- FOUND done; REFUSED: GS1's product or picture didn't match ours (note says why).
CREATE TABLE kirana_gs1_lookups (
    id           UUID          PRIMARY KEY,
    kind         VARCHAR(4)    NOT NULL CHECK (kind IN ('CODE', 'NAME')),
    code         VARCHAR(32)   COLLATE "C",       -- CODE: the barcode looked up; NAME: the barcode found
    item_id      UUID,                            -- CODE: the catalogue item (key below)
    shop_id      UUID,                            -- NAME: the shop and its product (keys below)
    product_id   UUID,
    status       VARCHAR(9)    NOT NULL CHECK (status IN ('WAITING', 'FOUND', 'NOT_FOUND', 'REFUSED')),
    note         VARCHAR(300),
    tries        SMALLINT      NOT NULL DEFAULT 0,
    next_try_at  TIMESTAMPTZ   NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL,
    updated_at   TIMESTAMPTZ   NOT NULL,
    CHECK ((kind = 'CODE') = (item_id IS NOT NULL AND code IS NOT NULL)),
    CHECK ((kind = 'NAME') = (shop_id IS NOT NULL AND product_id IS NOT NULL))
);
-- A lookup goes with what it is for: an item merged away by the team, a shop's data wiped
ALTER TABLE kirana_gs1_lookups ADD CONSTRAINT fk_kirana_gs1_item FOREIGN KEY (item_id)
    REFERENCES kirana_catalogue_items (id) ON DELETE CASCADE NOT VALID;
ALTER TABLE kirana_gs1_lookups ADD CONSTRAINT fk_kirana_gs1_shop FOREIGN KEY (shop_id) REFERENCES shops (id)
    ON DELETE CASCADE NOT VALID;
ALTER TABLE kirana_gs1_lookups ADD CONSTRAINT fk_kirana_gs1_product FOREIGN KEY (product_id)
    REFERENCES kirana_products (id) ON DELETE CASCADE NOT VALID;
-- One lookup per barcode and per product (queued again safely)
CREATE UNIQUE INDEX ux_kirana_gs1_code ON kirana_gs1_lookups (code) WHERE kind = 'CODE';
CREATE UNIQUE INDEX ux_kirana_gs1_product ON kirana_gs1_lookups (product_id) WHERE kind = 'NAME';
-- The job's queue; an item's lookup (a product waiting for its picture); the team's list by status
CREATE INDEX ix_kirana_gs1_due ON kirana_gs1_lookups (next_try_at) WHERE status IN ('WAITING', 'NOT_FOUND');
CREATE INDEX ix_kirana_gs1_item ON kirana_gs1_lookups (item_id) WHERE kind = 'CODE';
CREATE INDEX ix_kirana_gs1_status ON kirana_gs1_lookups (status, updated_at DESC, id DESC);

-- GS1's picture checks have their own daily AI allowance, so a backlog never holds up shops' own photos
CREATE TABLE kirana_gs1_ai_calls (
    day     DATE     PRIMARY KEY,
    calls   INTEGER  NOT NULL
);
