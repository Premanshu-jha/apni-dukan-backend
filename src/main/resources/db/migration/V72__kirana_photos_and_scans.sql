-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- Kirana, round 3: stock from photos and scans. A distributor's bill photo is read by AI into a draft that the shop
-- checks and adds to stock; scanning while shelving adds stock (or ticks off a bill's lines) and links barcodes, an
-- unknown barcode's label read by AI to suggest the product; a paper bill written while the phone was off becomes a
-- counter bill from its photo; the counter suggests what sells. The shared catalogue learns which barcode is which
-- product from the shops (trusted once two agree, or from a distributor's product master), with a review list for the
-- team, and keeps one shared photo per item.
--
-- As before: new tables first; columns, keys and checks on existing tables NOT VALID (V73 validates them).

-- ============ 1. Photos read by AI ============
-- One upload (1 to 3 pictures: a bill's pages, or one pack label), read in the background within a minute.
CREATE TABLE kirana_reads (
    id            UUID          PRIMARY KEY,
    shop_id       UUID          NOT NULL,              -- shops (key added below)
    kind          VARCHAR(10)   NOT NULL CHECK (kind IN ('PURCHASE', 'PAPER_BILL', 'LABEL')),
    status        VARCHAR(9)    NOT NULL CHECK (status IN ('READING', 'READ', 'FAILED', 'DONE', 'DISCARDED')),
    file_ids      UUID[]        NOT NULL CHECK (cardinality(file_ids) BETWEEN 1 AND 3),
    client_ref    UUID          NOT NULL,              -- the app's id for the upload: a retry is kept once
    tries         SMALLINT      NOT NULL CHECK (tries >= 0),
    next_try_at   TIMESTAMPTZ   NOT NULL,
    failure       VARCHAR(300),                        -- FAILED: why, in the shop's words
    result        JSONB,                               -- READ: what the AI read
    bill_id       UUID,                                -- PAPER_BILL, DONE: the counter bill made from it
    product_id    UUID          REFERENCES kirana_products (id),   -- LABEL of a product's photo: whose
    created_by    UUID          NOT NULL,              -- users (key added below)
    created_at    TIMESTAMPTZ   NOT NULL,
    updated_at    TIMESTAMPTZ   NOT NULL
);
CREATE UNIQUE INDEX ux_kirana_reads_client_ref ON kirana_reads (shop_id, client_ref);
-- The reading job's queue, and the shop's lists of uploads (newest first)
CREATE INDEX ix_kirana_reads_queue ON kirana_reads (next_try_at) WHERE status = 'READING';
CREATE INDEX ix_kirana_reads_shop ON kirana_reads (shop_id, kind, created_at DESC, id DESC);
-- whether a file is still being read or used (before it may be deleted)
CREATE INDEX ix_kirana_reads_files ON kirana_reads USING gin (file_ids);

-- AI reads per shop and day (at most 30; kirana_ai_calls keeps the whole platform's cap)
CREATE TABLE kirana_shop_ai_calls (
    shop_id  UUID     NOT NULL,                        -- shops (key added below)
    day      DATE     NOT NULL,
    calls    INTEGER  NOT NULL CHECK (calls >= 0),
    PRIMARY KEY (shop_id, day)
);

-- ============ 2. Distributor bills: a draft from the photo, added to stock once checked ============
CREATE TABLE kirana_purchases (
    id               UUID          PRIMARY KEY,
    shop_id          UUID          NOT NULL,           -- shops (key added below)
    read_id          UUID          NOT NULL UNIQUE REFERENCES kirana_reads (id),
    status           VARCHAR(9)    NOT NULL CHECK (status IN ('DRAFT', 'ADDED', 'DISCARDED')),
    distributor      VARCHAR(100),                     -- as printed
    distributor_key  VARCHAR(100)  NOT NULL,           -- the name as compared ('' when none was read)
    bill_no          VARCHAR(40),
    bill_date        DATE,
    total_paise      BIGINT        CHECK (total_paise >= 0),
    version          BIGINT        NOT NULL,
    created_by       UUID          NOT NULL,           -- users (key added below)
    created_at       TIMESTAMPTZ   NOT NULL,
    updated_at       TIMESTAMPTZ   NOT NULL,
    added_by         UUID,                             -- users (key added below)
    added_at         TIMESTAMPTZ,
    CONSTRAINT ck_kirana_purchases_added CHECK ((status = 'ADDED') = (added_at IS NOT NULL)
        AND (added_at IS NULL) = (added_by IS NULL))
);
CREATE INDEX ix_kirana_purchases_shop ON kirana_purchases (shop_id, created_at DESC, id DESC);
-- "This bill was already added": the same distributor, number and date
CREATE INDEX ix_kirana_purchases_bill ON kirana_purchases (shop_id, distributor_key, bill_no) WHERE status = 'ADDED';

-- A bill's lines as read, and what the shop decided for each
CREATE TABLE kirana_purchase_lines (
    purchase_id   UUID          NOT NULL REFERENCES kirana_purchases (id),
    line_no       SMALLINT      NOT NULL CHECK (line_no BETWEEN 1 AND 200),
    wording       VARCHAR(150)  NOT NULL,              -- as printed on the bill
    brand         VARCHAR(60),                         -- the AI's tidy reading of it
    name          VARCHAR(100),
    size          VARCHAR(30),
    quantity      NUMERIC(12,3) NOT NULL CHECK (quantity >= 0),   -- pieces (or kg) that came in
    rate_paise    BIGINT        CHECK (rate_paise >= 0),          -- paid per piece (or kg)
    mrp_paise     BIGINT        CHECK (mrp_paise >= 0),
    amount_paise  BIGINT        CHECK (amount_paise >= 0),
    product_id    UUID          REFERENCES kirana_products (id),
    -- How its product was found: NONE (not yet), SURE (one clear match), LIKELY (the best of a few), REMEMBERED (this
    -- distributor's wording, matched before), PICKED (the shop chose it), SKIPPED (not added to stock)
    pick          VARCHAR(10)   NOT NULL CHECK (pick IN ('NONE', 'SURE', 'LIKELY', 'REMEMBERED', 'PICKED', 'SKIPPED')),
    PRIMARY KEY (purchase_id, line_no)
);
CREATE INDEX ix_kirana_purchase_lines_product ON kirana_purchase_lines (product_id) WHERE product_id IS NOT NULL;

-- Products scanned while shelving a bill (its draft open): held with the bill, never added to stock by the scan. Adding
-- the bill adds its lines' quantities, plus what was scanned of products on no line; throwing it away adds what was
-- scanned (it was shelved)
CREATE TABLE kirana_purchase_scans (
    purchase_id  UUID          NOT NULL REFERENCES kirana_purchases (id),
    product_id   UUID          NOT NULL REFERENCES kirana_products (id),
    quantity     NUMERIC(12,3) NOT NULL CHECK (quantity > 0 AND quantity <= 999999),
    PRIMARY KEY (purchase_id, product_id)
);
CREATE INDEX ix_kirana_purchase_scans_product ON kirana_purchase_scans (product_id);

-- A distributor's wordings the shop matched before: the next bill with the same wording matches by itself
CREATE TABLE kirana_wordings (
    shop_id          UUID          NOT NULL,           -- shops (key added below)
    distributor_key  VARCHAR(100)  NOT NULL,
    wording_key      VARCHAR(150)  NOT NULL,
    product_id       UUID          NOT NULL REFERENCES kirana_products (id),
    updated_at       TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (shop_id, distributor_key, wording_key)
);
CREATE INDEX ix_kirana_wordings_product ON kirana_wordings (product_id);

-- ============ 3. Scans while shelving ============
-- A barcode no product of the shop has, waiting for one tap ("Needs a tap"): its label read, its suggestions
CREATE TABLE kirana_unknown_scans (
    id           UUID          PRIMARY KEY,
    shop_id      UUID          NOT NULL,               -- shops (key added below)
    code         VARCHAR(32)   COLLATE "C" NOT NULL,
    scans        NUMERIC(12,3) NOT NULL CHECK (scans > 0 AND scans <= 999999),   -- times scanned while waiting (linking adds them, times the pack)
    purchase_id  UUID          REFERENCES kirana_purchases (id),   -- scanned against this open bill
    read_id      UUID          REFERENCES kirana_reads (id),       -- its label, being read or read
    -- OPEN (waiting), LINKED, DISMISSED (put away; may be brought back), MERGED (brought back into another entry)
    status       VARCHAR(9)    NOT NULL CHECK (status IN ('OPEN', 'LINKED', 'DISMISSED', 'MERGED')),
    product_id   UUID          REFERENCES kirana_products (id),    -- LINKED: to this product
    created_by   UUID          NOT NULL,               -- users (key added below)
    created_at   TIMESTAMPTZ   NOT NULL,
    updated_at   TIMESTAMPTZ   NOT NULL
);
-- one open entry per barcode and bill (scans without a bill are one entry too): each is settled with its own bill
CREATE UNIQUE INDEX ux_kirana_unknown_scans_open ON kirana_unknown_scans
    (shop_id, code, coalesce(purchase_id, '00000000-0000-0000-0000-000000000000'::uuid)) WHERE status = 'OPEN';
-- a pack's label is read once across all shops: another shop's read of the same barcode is reused
CREATE INDEX ix_kirana_unknown_scans_code ON kirana_unknown_scans (code) WHERE read_id IS NOT NULL;
CREATE INDEX ix_kirana_unknown_scans_shop ON kirana_unknown_scans (shop_id, status, created_at DESC, id DESC);

-- A batch of scans the app sent (its id): a retry after a lost answer is counted once
CREATE TABLE kirana_scan_batches (
    shop_id     UUID         NOT NULL,                 -- shops (key added below)
    client_ref  UUID         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (shop_id, client_ref)
);
CREATE INDEX ix_kirana_scan_batches_at ON kirana_scan_batches (created_at);

-- How scanning goes, per shop and day: how many scans, how many were unknown, how they were settled (one tap on a sure
-- suggestion, a pick from a few, a new product). The admin website's "unknown scan rate".
CREATE TABLE kirana_scan_stats (
    shop_id   UUID     NOT NULL,                       -- shops (key added below)
    day       DATE     NOT NULL,
    scans     INTEGER  NOT NULL CHECK (scans >= 0),
    unknown   INTEGER  NOT NULL CHECK (unknown >= 0),
    sure      INTEGER  NOT NULL CHECK (sure >= 0),
    picked    INTEGER  NOT NULL CHECK (picked >= 0),
    created   INTEGER  NOT NULL CHECK (created >= 0),
    PRIMARY KEY (shop_id, day)
);
CREATE INDEX ix_kirana_scan_stats_day ON kirana_scan_stats (day);

-- ============ 4. The shared catalogue learns from the shops ============
-- Where a barcode's link came from: SHOP (an owner's pick), MASTER (a distributor's product master) or ADMIN; a
-- shop's link is trusted (confirmed) once a second shop links the same barcode to the same item
ALTER TABLE kirana_catalogue_barcodes ADD COLUMN source VARCHAR(6) NOT NULL DEFAULT 'SHOP';
ALTER TABLE kirana_catalogue_barcodes ADD CONSTRAINT ck_kirana_catalogue_barcodes_source
    CHECK (source IN ('SHOP', 'MASTER', 'ADMIN')) NOT VALID;
ALTER TABLE kirana_catalogue_barcodes ADD COLUMN confirmed BOOLEAN NOT NULL DEFAULT false;
-- One shared photo per item (the first shop's, checked)
ALTER TABLE kirana_catalogue_items ADD COLUMN photo_file_id UUID;
-- a photo a shop stops using is kept while the catalogue shares it (new column: every row NULL, quick to build)
CREATE INDEX ix_kirana_catalogue_items_photo ON kirana_catalogue_items (photo_file_id) WHERE photo_file_id IS NOT NULL;

-- Which item each shop linked a barcode to (two shops agreeing confirm it; disagreeing, the team decides)
CREATE TABLE kirana_barcode_votes (
    code        VARCHAR(32)  COLLATE "C" NOT NULL,
    shop_id     UUID         NOT NULL,                 -- shops (key added below)
    item_id     UUID         NOT NULL REFERENCES kirana_catalogue_items (id),
    created_at  TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (code, shop_id)
);
CREATE INDEX ix_kirana_barcode_votes_item ON kirana_barcode_votes (code, item_id);
-- Shops' links made before this: each shop's say counted, and barcodes two shops already agree on are trusted
INSERT INTO kirana_barcode_votes (code, shop_id, item_id, created_at)
SELECT b.code, b.shop_id, p.catalogue_item_id, now()
FROM kirana_barcodes b
JOIN kirana_products p ON p.id = b.product_id
JOIN kirana_catalogue_barcodes c ON c.code = b.code AND c.item_id = p.catalogue_item_id
WHERE b.active
ON CONFLICT DO NOTHING;
UPDATE kirana_catalogue_barcodes c SET confirmed = true
WHERE (SELECT count(*) FROM kirana_barcode_votes v WHERE v.code = c.code AND v.item_id = c.item_id) >= 2;

-- The team's review list: shops disagreeing on a barcode, a correction of a trusted link, an imported row that clashes
CREATE TABLE kirana_catalogue_reviews (
    id             UUID          PRIMARY KEY,
    code           VARCHAR(32)   COLLATE "C" NOT NULL,
    kind           VARCHAR(10)   NOT NULL CHECK (kind IN ('CONFLICT', 'CORRECTION', 'IMPORT')),
    item_id        UUID          REFERENCES kirana_catalogue_items (id) ON DELETE SET NULL,   -- what it says now
    other_item_id  UUID          REFERENCES kirana_catalogue_items (id) ON DELETE SET NULL,   -- a shop's (or import's)
    shop_id        UUID,                               -- the shop that raised it (shops, key added below)
    note           VARCHAR(300),
    status         VARCHAR(4)    NOT NULL CHECK (status IN ('OPEN', 'DONE')),
    decided_by     UUID,                               -- users (key added below)
    created_at     TIMESTAMPTZ   NOT NULL,
    decided_at     TIMESTAMPTZ
);
CREATE UNIQUE INDEX ux_kirana_catalogue_reviews_open ON kirana_catalogue_reviews (code) WHERE status = 'OPEN';
CREATE INDEX ix_kirana_catalogue_reviews_list ON kirana_catalogue_reviews (status, created_at);

-- ============ 5. Products: cost and photo ============
ALTER TABLE kirana_products ADD COLUMN cost_paise BIGINT;   -- paid per piece (or kg) on the last bill (owner only)
ALTER TABLE kirana_products ADD CONSTRAINT ck_kirana_products_cost CHECK (cost_paise > 0) NOT VALID;
ALTER TABLE kirana_products ADD COLUMN photo_file_id UUID;  -- the shop's own photo (else the catalogue item's)
CREATE INDEX ix_kirana_products_photo ON kirana_products (photo_file_id) WHERE photo_file_id IS NOT NULL;

-- ============ 6. Stock moves: a bill's restock, a scan's barcode, a move between products ============
ALTER TABLE kirana_stock_moves ADD COLUMN purchase_id UUID;
ALTER TABLE kirana_stock_moves ADD CONSTRAINT fk_kirana_stock_moves_purchase FOREIGN KEY (purchase_id)
    REFERENCES kirana_purchases (id) NOT VALID;
ALTER TABLE kirana_stock_moves ADD COLUMN barcode VARCHAR(32);   -- RESTOCK by a scan: the barcode scanned
ALTER TABLE kirana_stock_moves DROP CONSTRAINT kirana_stock_moves_reason_check;
-- MOVE: stock moved to (or from) another product the shop had mixed up (one move on each)
ALTER TABLE kirana_stock_moves ADD CONSTRAINT kirana_stock_moves_reason_check CHECK (reason IN ('SALE', 'VOID',
    'RETURN', 'RESTOCK', 'DAMAGED', 'COUNT', 'ORDER', 'ORDER_BACK', 'MOVE')) NOT VALID;
ALTER TABLE kirana_stock_moves ADD CONSTRAINT ck_kirana_stock_moves_purchase
    CHECK (purchase_id IS NULL OR reason = 'RESTOCK') NOT VALID;

-- ============ 7. Paper bills: a counter bill made from a photo of one written while the phone was off ============
ALTER TABLE kirana_bills ADD COLUMN written_on DATE;     -- the day on the paper (up to 7 days before it was entered)
ALTER TABLE kirana_bills ADD COLUMN photo_file_id UUID;  -- the paper bill's photo, kept as its record
CREATE INDEX ix_kirana_bills_photo ON kirana_bills (photo_file_id) WHERE photo_file_id IS NOT NULL;

-- ============ Keys to shops and users (NOT VALID; V73 checks them) ============
ALTER TABLE kirana_reads ADD CONSTRAINT fk_kirana_reads_shop FOREIGN KEY (shop_id) REFERENCES shops (id) NOT VALID;
ALTER TABLE kirana_reads ADD CONSTRAINT fk_kirana_reads_by FOREIGN KEY (created_by) REFERENCES users (id) NOT VALID;
ALTER TABLE kirana_shop_ai_calls ADD CONSTRAINT fk_kirana_shop_ai_calls_shop FOREIGN KEY (shop_id)
    REFERENCES shops (id) NOT VALID;
ALTER TABLE kirana_purchases ADD CONSTRAINT fk_kirana_purchases_shop FOREIGN KEY (shop_id)
    REFERENCES shops (id) NOT VALID;
ALTER TABLE kirana_purchases ADD CONSTRAINT fk_kirana_purchases_by FOREIGN KEY (created_by)
    REFERENCES users (id) NOT VALID;
ALTER TABLE kirana_purchases ADD CONSTRAINT fk_kirana_purchases_added_by FOREIGN KEY (added_by)
    REFERENCES users (id) NOT VALID;
ALTER TABLE kirana_wordings ADD CONSTRAINT fk_kirana_wordings_shop FOREIGN KEY (shop_id)
    REFERENCES shops (id) NOT VALID;
ALTER TABLE kirana_unknown_scans ADD CONSTRAINT fk_kirana_unknown_scans_shop FOREIGN KEY (shop_id)
    REFERENCES shops (id) NOT VALID;
ALTER TABLE kirana_unknown_scans ADD CONSTRAINT fk_kirana_unknown_scans_by FOREIGN KEY (created_by)
    REFERENCES users (id) NOT VALID;
ALTER TABLE kirana_scan_batches ADD CONSTRAINT fk_kirana_scan_batches_shop FOREIGN KEY (shop_id)
    REFERENCES shops (id) NOT VALID;
ALTER TABLE kirana_scan_stats ADD CONSTRAINT fk_kirana_scan_stats_shop FOREIGN KEY (shop_id)
    REFERENCES shops (id) NOT VALID;
ALTER TABLE kirana_barcode_votes ADD CONSTRAINT fk_kirana_barcode_votes_shop FOREIGN KEY (shop_id)
    REFERENCES shops (id) NOT VALID;
ALTER TABLE kirana_catalogue_reviews ADD CONSTRAINT fk_kirana_catalogue_reviews_shop FOREIGN KEY (shop_id)
    REFERENCES shops (id) NOT VALID;
ALTER TABLE kirana_catalogue_reviews ADD CONSTRAINT fk_kirana_catalogue_reviews_by FOREIGN KEY (decided_by)
    REFERENCES users (id) NOT VALID;
