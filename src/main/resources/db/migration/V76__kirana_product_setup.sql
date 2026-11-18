-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- A kirana's products set up after Submit (backend PR "kirana product setup", 2026-11-18): every product with a
-- count of what is on the shelf, from a photographed list or entered by hand. The field visit is booked only when the
-- owner says it's done, and the shop can't be approved before.

-- When the owner said all the products are added (NULL: not yet; a salon never needs it). The field visit is booked
-- then. Nullable with no default: added instantly, no row rewritten.
ALTER TABLE shops ADD COLUMN products_ready_at TIMESTAMPTZ;
-- Kiranas waiting for their first visit now set up their products first; their visit is booked again when they finish
-- (an agent assigned now would hold a business none of their lists shows).
UPDATE shops SET assigned_admin_id = NULL, assigned_at = NULL
WHERE type = 'KIRANA' AND status = 'PENDING_VISIT' AND NOT ever_approved AND assigned_admin_id IS NOT NULL;

-- When the product's stock was last counted by hand (added with a count, or Count): NULL until then, and again when a
-- sale took it below zero or it was missing for an online order. Only counted products are offered online.
ALTER TABLE kirana_products ADD COLUMN counted_at TIMESTAMPTZ;
-- A new photo of a product is checked by AI before it shows (the old one stays until then): the photo (and a second one
-- of the side with the size, when the front doesn't show it), how its check stands (CHECKING; REFUSED or NEED_SIZE
-- with the reason, to retake) and whether the accepted photo may be shared in the catalogue (a pack matching its
-- catalogue item, shared once the shop is approved). NOT NULL DEFAULT false: no rewrite (Postgres 11+).
-- Keys and rules added NOT VALID (no scan now), validated by V77.
ALTER TABLE kirana_products ADD COLUMN photo_new_file_id UUID;
ALTER TABLE kirana_products ADD COLUMN photo_new_size_file_id UUID;
ALTER TABLE kirana_products ADD COLUMN photo_check VARCHAR(9);
ALTER TABLE kirana_products ADD COLUMN photo_check_reason VARCHAR(300);
-- Why, as a code the app words in the shop's language (WRONG_SIZE, BLURRY …), and what the photo showed
ALTER TABLE kirana_products ADD COLUMN photo_check_code VARCHAR(16);
ALTER TABLE kirana_products ADD COLUMN photo_check_seen VARCHAR(160);
ALTER TABLE kirana_products ADD COLUMN photo_shareable BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE kirana_products ADD CONSTRAINT fk_kirana_products_photo_new FOREIGN KEY (photo_new_file_id)
    REFERENCES files (id) NOT VALID;
ALTER TABLE kirana_products ADD CONSTRAINT fk_kirana_products_photo_new_size FOREIGN KEY (photo_new_size_file_id)
    REFERENCES files (id) NOT VALID;
ALTER TABLE kirana_products ADD CONSTRAINT ck_kirana_products_photo_check
    CHECK (photo_check IN ('CHECKING', 'REFUSED', 'NEED_SIZE')
        AND (photo_check IS NULL) = (photo_new_file_id IS NULL)) NOT VALID;

-- Photo checks have their own daily AI allowance for the whole platform, so shops photographing their products never
-- hold up other shops' bills (kirana_ai_calls)
CREATE TABLE kirana_photo_ai_calls (
    day     DATE     PRIMARY KEY,
    calls   INTEGER  NOT NULL
);

-- Products the shop already has stock of stay offered online: counted as they stand. Kiranas aren't live yet (the
-- kirana branch), so this touches few rows.
UPDATE kirana_products SET counted_at = updated_at WHERE stock > 0;

-- A page of the shop's own list of products (a stock register, a notebook) is read like a bill: kind STOCK_LIST.
-- The kind rule dropped and added again NOT VALID (no row read now), validated in V77; it is wider.
ALTER TABLE kirana_reads DROP CONSTRAINT kirana_reads_kind_check;
ALTER TABLE kirana_reads ADD CONSTRAINT kirana_reads_kind_check
    CHECK (kind IN ('PURCHASE', 'PAPER_BILL', 'LABEL', 'STOCK_LIST')) NOT VALID;
-- A list page with more products than one read keeps (150): how many were left out, so the shop is told to photograph
-- the rest again. Nullable, no default.
ALTER TABLE kirana_reads ADD COLUMN lines_left_out SMALLINT CHECK (lines_left_out > 0);

-- Each line read from a list page, for the shop to check: added as a product (with its count), or skipped.
CREATE TABLE kirana_list_lines (
    id          UUID          PRIMARY KEY,
    shop_id     UUID          NOT NULL,              -- shops (key added below)
    read_id     UUID          NOT NULL,              -- kirana_reads: the page it was read from
    line_no     SMALLINT      NOT NULL CHECK (line_no BETWEEN 1 AND 300),
    wording     VARCHAR(150)  NOT NULL,              -- as written
    brand       VARCHAR(60),
    name        VARCHAR(100),                        -- tidied: the product's name with its variant
    size        VARCHAR(30),                         -- "500 g", "1 L"
    by_weight   BOOLEAN       NOT NULL,              -- loose goods counted in kg
    mrp_paise   BIGINT        CHECK (mrp_paise >= 0),
    quantity    NUMERIC(12,3) CHECK (quantity >= 0),  -- as written: a hint, the shop counts it again
    status      VARCHAR(7)    NOT NULL CHECK (status IN ('NEW', 'ADDED', 'SKIPPED')),
    product_id  UUID          REFERENCES kirana_products (id),   -- ADDED: the product it became (or was counted on)
    counted     NUMERIC(12,3) CHECK (counted >= 0),  -- ADDED: what the shop counted (beside what the list says)
    updated_at  TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ux_kirana_list_lines_line UNIQUE (read_id, line_no),
    CONSTRAINT ck_kirana_list_lines_added CHECK ((status = 'ADDED') = (product_id IS NOT NULL))
);
-- The lines still to check, page by page in the order the pages were sent, and the counts per status
CREATE INDEX ix_kirana_list_lines_shop ON kirana_list_lines (shop_id, status, read_id, line_no);
ALTER TABLE kirana_list_lines ADD CONSTRAINT fk_kirana_list_lines_shop FOREIGN KEY (shop_id) REFERENCES shops (id)
    NOT VALID;
ALTER TABLE kirana_list_lines ADD CONSTRAINT fk_kirana_list_lines_read FOREIGN KEY (read_id)
    REFERENCES kirana_reads (id) NOT VALID;
-- The products not counted yet: their index is built CONCURRENTLY by V78.
