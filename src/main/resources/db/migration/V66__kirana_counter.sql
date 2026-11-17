-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- Kirana, round 1: the shop's own counter. Its products (with their barcodes and packs) and stock, walk-in bills (paid
-- in cash, to the shop's own UPI QR, or on the shop's khata, with returns and payment changes), the khata book of its
-- regular customers, and the shop's UPI ID. No money passes
-- through Clozest here: walk-in customers pay the shop directly, and the app only records it.
--
-- As in V37: the new tables first, without keys to existing tables; the keys to shops and users last, NOT VALID, so
-- their short locks come just before the commit. V67 validates them. Nothing existing is changed.

-- ============ 1. The shop's settings ============
-- One row per kirana, made on first use. Its lock (FOR UPDATE) puts the shop's bills one after the other, so each gets
-- the next bill number and a double tap is seen.
CREATE TABLE kirana_settings (
    shop_id         UUID          PRIMARY KEY,         -- shops (key added below)
    next_bill_no    BIGINT        NOT NULL CHECK (next_bill_no >= 1),
    -- The shop's UPI ID (VPA): its walk-in QR now, Clozest's payouts later. Owner only; NULL until set.
    upi_id          VARCHAR(100),
    upi_updated_by  UUID,                              -- users (key added below)
    upi_updated_at  TIMESTAMPTZ,
    CONSTRAINT ck_kirana_settings_upi CHECK ((upi_id IS NULL) = (upi_updated_at IS NULL))
);

-- ============ 2. Products and stock ============
-- unit: PIECE (sold in whole numbers) or KG (loose items, to the gram). stock may go below zero: a sale at the counter
-- is never refused because the app's count is behind; the shop sees it and corrects the count.
CREATE TABLE kirana_products (
    id            UUID          PRIMARY KEY,
    shop_id       UUID          NOT NULL,              -- shops (key added below)
    name          VARCHAR(100)  NOT NULL,
    unit          VARCHAR(5)    NOT NULL CHECK (unit IN ('PIECE', 'KG')),
    price_paise   BIGINT        NOT NULL CHECK (price_paise BETWEEN 1 AND 10000000),   -- per piece or per kg
    stock         NUMERIC(12,3) NOT NULL,
    low_stock_at  NUMERIC(12,3) CHECK (low_stock_at >= 0),   -- "running out" at or below this; NULL: no alert
    active        BOOLEAN       NOT NULL,              -- false: removed from the catalogue (old bills keep it)
    version       BIGINT        NOT NULL,
    created_by    UUID          NOT NULL,              -- users (key added below)
    created_at    TIMESTAMPTZ   NOT NULL,
    updated_at    TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_kirana_products_pieces CHECK (unit = 'KG' OR (stock = trunc(stock)
        AND (low_stock_at IS NULL OR low_stock_at = trunc(low_stock_at))))
);
-- One active product per name (case aside)
CREATE UNIQUE INDEX ux_kirana_products_name ON kirana_products (shop_id, lower(name)) WHERE active;
-- The catalogue page, in name order, and the search by the start of a name (LIKE 'abc%'): in the "C" collation one
-- index serves both (ProductStore.page sorts and compares with the same collation)
CREATE INDEX ix_kirana_products_list ON kirana_products (shop_id, (lower(name) COLLATE "C"), id) WHERE active;
-- The search: a word anywhere in the name ("salt" finds "Tata Salt 1 kg"), through pg_trgm (V13)
CREATE INDEX ix_kirana_products_search ON kirana_products USING gin (lower(name) gin_trgm_ops) WHERE active;
-- No index mentions stock: a sale then updates the row in place (a HOT update) without touching any index.
-- "Running out" reads the shop's catalogue through ix_kirana_products_list (a shop has thousands of rows at most).

-- A product's barcodes: several per product (MRP batches, a new pack design), each with how many pieces (or kg) one
-- scan means: 1 for a single pack, 12 for a carton of 12. One active product per barcode in a shop; a removed
-- product's barcodes are kept inactive, so putting it back brings them back (unless another product has one now).
-- code in the "C" collation: the unique key also serves the search by the first digits (LIKE '8901%').
CREATE TABLE kirana_barcodes (
    product_id   UUID          NOT NULL REFERENCES kirana_products (id),
    code         VARCHAR(32)   COLLATE "C" NOT NULL,   -- as printed (EAN-13, UPC, the shop's own), upper case
    shop_id      UUID          NOT NULL,               -- shops (key added below)
    pack         NUMERIC(12,3) NOT NULL CHECK (pack > 0 AND pack <= 9999),
    active       BOOLEAN       NOT NULL,               -- as its product
    created_by   UUID          NOT NULL,               -- users (key added below)
    created_at   TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (product_id, code)
);
CREATE UNIQUE INDEX ux_kirana_barcodes_code ON kirana_barcodes (shop_id, code) WHERE active;

-- Every change to a product's stock, with why: a sale (its cancellation or a return), stock that came in, a damaged piece taken
-- off the shelf, or a count by hand that sets it.
CREATE TABLE kirana_stock_moves (
    id           UUID          PRIMARY KEY,
    shop_id      UUID          NOT NULL,               -- shops (key added below)
    product_id   UUID          NOT NULL REFERENCES kirana_products (id),
    change       NUMERIC(12,3) NOT NULL,               -- + in, - out
    stock_after  NUMERIC(12,3) NOT NULL,
    reason       VARCHAR(10)   NOT NULL CHECK (reason IN ('SALE', 'VOID', 'RETURN', 'RESTOCK', 'DAMAGED', 'COUNT')),
    bill_id      UUID,                                 -- SALE, VOID and RETURN: the bill (key added below)
    note         VARCHAR(200),
    client_ref   UUID,                                 -- RESTOCK/DAMAGED/COUNT from the app: a retry is kept once
    created_by   UUID          NOT NULL,               -- users (key added below)
    created_at   TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_kirana_stock_moves_bill CHECK ((reason IN ('SALE', 'VOID', 'RETURN')) = (bill_id IS NOT NULL))
);
CREATE UNIQUE INDEX ux_kirana_stock_moves_client_ref ON kirana_stock_moves (shop_id, client_ref)
    WHERE client_ref IS NOT NULL;
-- A cancelled or repeated bill's stock changes (the bill's "stock before → after" is read back from them)
CREATE INDEX ix_kirana_stock_moves_bill ON kirana_stock_moves (bill_id) WHERE bill_id IS NOT NULL;
CREATE INDEX ix_kirana_stock_moves_product ON kirana_stock_moves (product_id, created_at DESC, id DESC);

-- ============ 3. The khata book: the shop's regular customers who buy on credit ============
-- balance_paise: what the customer owes the shop now; below zero the shop holds an advance of theirs (paid more than
-- they owed, or a paid sale was cancelled or returned), used up by their next khata sale.
-- credit_limit_paise: ₹2,000 for a new customer unless the owner sets another or none.
CREATE TABLE kirana_khata_customers (
    id                  UUID          PRIMARY KEY,
    shop_id             UUID          NOT NULL,        -- shops (key added below)
    name                VARCHAR(80)   NOT NULL,
    phone               VARCHAR(10),                   -- optional, for the WhatsApp reminder
    note                VARCHAR(100),                  -- e.g. "Tower B 1204"
    credit_limit_paise  BIGINT        CHECK (credit_limit_paise BETWEEN 0 AND 100000000),   -- NULL: no limit
    balance_paise       BIGINT        NOT NULL,
    active              BOOLEAN       NOT NULL,
    version             BIGINT        NOT NULL,
    created_by          UUID          NOT NULL,        -- users (key added below)
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_kirana_khata_customers_phone CHECK (phone ~ '^[6-9][0-9]{9}$')
);
CREATE UNIQUE INDEX ux_kirana_khata_customers_phone ON kirana_khata_customers (shop_id, phone)
    WHERE phone IS NOT NULL AND active;
CREATE INDEX ix_kirana_khata_customers_list ON kirana_khata_customers (shop_id, (lower(name) COLLATE "C"), id)
    WHERE active;
-- The search: a word anywhere in the name or the note ("1204" finds "Tower B 1204")
CREATE INDEX ix_kirana_khata_customers_search ON kirana_khata_customers
    USING gin ((lower(name) || ' ' || coalesce(lower(note), '')) gin_trgm_ops) WHERE active;

-- ============ 4. Walk-in bills ============
-- bill_no: the shop's own running number. client_ref: made by the app for each bill, so a double tap or a retry after
-- a lost answer finds the first bill instead of making a second. total = subtotal (the lines) - discount. A khata bill
-- may be part paid at once (paid_now, in cash or to the UPI QR): only the rest goes on the khata. payment and the
-- khata customer may be changed later (kirana_bill_changes); refunded_paise grows with each return.
CREATE TABLE kirana_bills (
    id                 UUID          PRIMARY KEY,
    shop_id            UUID          NOT NULL,         -- shops (key added below)
    bill_no            BIGINT        NOT NULL,
    client_ref         UUID          NOT NULL,
    subtotal_paise     BIGINT        NOT NULL CHECK (subtotal_paise >= 0),
    discount_paise     BIGINT        NOT NULL CHECK (discount_paise >= 0),
    total_paise        BIGINT        NOT NULL CHECK (total_paise >= 0),
    payment            VARCHAR(5)    NOT NULL CHECK (payment IN ('CASH', 'UPI', 'KHATA')),
    khata_customer_id  UUID          REFERENCES kirana_khata_customers (id),
    paid_now_paise     BIGINT,                         -- KHATA only: paid at the counter, the rest on the khata
    paid_now_by        VARCHAR(4)    CHECK (paid_now_by IN ('CASH', 'UPI')),
    refunded_paise     BIGINT        NOT NULL CHECK (refunded_paise >= 0),
    version            BIGINT        NOT NULL,
    created_by         UUID          NOT NULL,         -- users (key added below)
    created_at         TIMESTAMPTZ   NOT NULL,
    voided_at          TIMESTAMPTZ,                    -- cancelled: stock back, khata reversed
    voided_by          UUID,                           -- users (key added below)
    void_reason        VARCHAR(200),
    CONSTRAINT ck_kirana_bills_total CHECK (total_paise = subtotal_paise - discount_paise
        AND refunded_paise <= total_paise),
    CONSTRAINT ck_kirana_bills_khata CHECK ((payment = 'KHATA') = (khata_customer_id IS NOT NULL)),
    CONSTRAINT ck_kirana_bills_paid_now CHECK ((paid_now_paise IS NULL) = (paid_now_by IS NULL)
        AND (paid_now_paise IS NULL OR (payment = 'KHATA' AND paid_now_paise > 0 AND paid_now_paise < total_paise))),
    CONSTRAINT ck_kirana_bills_void CHECK ((voided_at IS NULL) = (voided_by IS NULL)
        AND (voided_at IS NULL) = (void_reason IS NULL))
);
CREATE UNIQUE INDEX ux_kirana_bills_no ON kirana_bills (shop_id, bill_no);
CREATE UNIQUE INDEX ux_kirana_bills_client_ref ON kirana_bills (shop_id, client_ref);
-- The bills list (newest first) and the day's totals
CREATE INDEX ix_kirana_bills_shop ON kirana_bills (shop_id, created_at DESC, id DESC);
CREATE INDEX ix_kirana_bills_khata ON kirana_bills (khata_customer_id) WHERE khata_customer_id IS NOT NULL;
-- The day's cancelled bills (counted on the day they were cancelled)
CREATE INDEX ix_kirana_bills_voided ON kirana_bills (shop_id, voided_at) WHERE voided_at IS NOT NULL;

-- The bill's lines as sold: name, unit and price copied, so a later change to the product never changes an old bill.
-- An item not in the list has no product: 1 piece at the amount typed. A loose item sold by amount ("₹20 of jeera")
-- keeps the amount typed and the weight it buys.
CREATE TABLE kirana_bill_lines (
    bill_id            UUID          NOT NULL REFERENCES kirana_bills (id),
    line_no            SMALLINT      NOT NULL CHECK (line_no BETWEEN 1 AND 100),
    product_id         UUID          REFERENCES kirana_products (id),   -- NULL: an item not in the list
    name               VARCHAR(100)  NOT NULL,
    unit               VARCHAR(5)    NOT NULL CHECK (unit IN ('PIECE', 'KG')),
    quantity           NUMERIC(12,3) NOT NULL CHECK (quantity > 0),
    price_paise        BIGINT        NOT NULL CHECK (price_paise > 0),
    amount_paise       BIGINT        NOT NULL CHECK (amount_paise >= 0),
    returned_quantity  NUMERIC(12,3) NOT NULL CHECK (returned_quantity >= 0 AND returned_quantity <= quantity),
    PRIMARY KEY (bill_id, line_no)
);
-- Whether a product was ever sold (its unit can be changed only before)
CREATE INDEX ix_kirana_bill_lines_product ON kirana_bill_lines (product_id) WHERE product_id IS NOT NULL;

-- Returns: some items of a bill brought back. The money goes back the way the bill was paid: off the khata first for a
-- khata bill (up to what the bill still has on it), the rest handed back in cash or to the customer's UPI.
-- client_ref as on bills: a return sent twice is made once.
CREATE TABLE kirana_bill_returns (
    id              UUID          PRIMARY KEY,
    shop_id         UUID          NOT NULL,            -- shops (key added below)
    bill_id         UUID          NOT NULL REFERENCES kirana_bills (id),
    client_ref      UUID          NOT NULL,
    refund_paise    BIGINT        NOT NULL CHECK (refund_paise >= 0),
    khata_paise     BIGINT        NOT NULL CHECK (khata_paise >= 0),    -- taken off the khata
    handed_paise    BIGINT        NOT NULL CHECK (handed_paise >= 0),   -- handed back
    handed_by       VARCHAR(4)    CHECK (handed_by IN ('CASH', 'UPI')),
    reason          VARCHAR(200),
    created_by      UUID          NOT NULL,            -- users (key added below)
    created_at      TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_kirana_bill_returns_split CHECK (refund_paise = khata_paise + handed_paise
        AND (handed_by IS NULL) = (handed_paise = 0))
);
CREATE UNIQUE INDEX ux_kirana_bill_returns_client_ref ON kirana_bill_returns (shop_id, client_ref);
CREATE INDEX ix_kirana_bill_returns_bill ON kirana_bill_returns (bill_id);
-- The day's returns, for the day's totals
CREATE INDEX ix_kirana_bill_returns_shop ON kirana_bill_returns (shop_id, created_at);

CREATE TABLE kirana_bill_return_lines (
    return_id     UUID          NOT NULL REFERENCES kirana_bill_returns (id),
    line_no       SMALLINT      NOT NULL,
    quantity      NUMERIC(12,3) NOT NULL CHECK (quantity > 0),
    value_paise   BIGINT        NOT NULL CHECK (value_paise >= 0),   -- the line's amount for this quantity
    PRIMARY KEY (return_id, line_no)
);

-- A bill's payment changed after saving (UPI failed and they paid cash; put on khata after all): from what to what.
-- The day's totals count the change on the day it was made (the money moved then), not the bill's day.
CREATE TABLE kirana_bill_changes (
    id                  UUID          PRIMARY KEY,
    shop_id             UUID          NOT NULL,        -- shops (key added below)
    bill_id             UUID          NOT NULL REFERENCES kirana_bills (id),
    from_payment        VARCHAR(5)    NOT NULL,
    from_customer_id    UUID          REFERENCES kirana_khata_customers (id),
    from_paid_now       BIGINT,
    from_paid_now_by    VARCHAR(4),
    to_payment          VARCHAR(5)    NOT NULL,
    to_customer_id      UUID          REFERENCES kirana_khata_customers (id),
    to_paid_now         BIGINT,
    to_paid_now_by      VARCHAR(4),
    created_by          UUID          NOT NULL,        -- users (key added below)
    created_at          TIMESTAMPTZ   NOT NULL
);
CREATE INDEX ix_kirana_bill_changes_bill ON kirana_bill_changes (bill_id, created_at);
-- The day's payment changes, for the day's totals
CREATE INDEX ix_kirana_bill_changes_shop ON kirana_bill_changes (shop_id, created_at);

ALTER TABLE kirana_stock_moves ADD CONSTRAINT fk_kirana_stock_moves_bill FOREIGN KEY (bill_id)
    REFERENCES kirana_bills (id);

-- Each khata entry: a sale on credit (+, the part not paid at once), a payment received (-), a cancelled sale (-), a
-- return (-), a bill moved on or off the khata (CHANGE, + or -), the owner undoing a payment recorded by mistake
-- (REVERSAL, +, naming the payment), or an advance given back to the customer (REFUND, +). client_ref as on bills: a payment recorded twice by a double tap is kept once.
CREATE TABLE kirana_khata_entries (
    id              UUID          PRIMARY KEY,
    shop_id         UUID          NOT NULL,            -- shops (key added below)
    customer_id     UUID          NOT NULL REFERENCES kirana_khata_customers (id),
    kind            VARCHAR(8)    NOT NULL CHECK (kind IN ('SALE', 'PAYMENT', 'VOID', 'RETURN', 'CHANGE',
                                                         'REVERSAL', 'REFUND')),
    amount_paise    BIGINT        NOT NULL,            -- + owed more, - owed less
    balance_after   BIGINT        NOT NULL,
    bill_id         UUID          REFERENCES kirana_bills (id),
    method          VARCHAR(4)    CHECK (method IN ('CASH', 'UPI')),   -- PAYMENT, REVERSAL and REFUND
    client_ref      UUID,                              -- PAYMENT and REFUND
    reverses_id     UUID          REFERENCES kirana_khata_entries (id),   -- REVERSAL: the payment it undoes
    note            VARCHAR(200),
    created_by      UUID          NOT NULL,            -- users (key added below)
    created_at      TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_kirana_khata_entries_kind CHECK (
        (kind = 'PAYMENT' AND amount_paise < 0 AND method IS NOT NULL AND client_ref IS NOT NULL AND bill_id IS NULL
            AND reverses_id IS NULL)
        OR (kind = 'REFUND' AND amount_paise > 0 AND method IS NOT NULL AND client_ref IS NOT NULL AND bill_id IS NULL
            AND reverses_id IS NULL)
        OR (kind = 'REVERSAL' AND amount_paise > 0 AND method IS NOT NULL AND client_ref IS NULL AND bill_id IS NULL
            AND reverses_id IS NOT NULL)
        OR (kind IN ('SALE', 'VOID', 'RETURN', 'CHANGE') AND method IS NULL AND client_ref IS NULL
            AND bill_id IS NOT NULL AND reverses_id IS NULL
            AND (kind <> 'SALE' OR amount_paise >= 0) AND (kind NOT IN ('VOID', 'RETURN') OR amount_paise <= 0)))
);
CREATE UNIQUE INDEX ux_kirana_khata_entries_client_ref ON kirana_khata_entries (shop_id, client_ref)
    WHERE client_ref IS NOT NULL;
-- A payment is undone once
CREATE UNIQUE INDEX ux_kirana_khata_entries_reverses ON kirana_khata_entries (reverses_id)
    WHERE reverses_id IS NOT NULL;
CREATE INDEX ix_kirana_khata_entries_customer ON kirana_khata_entries (customer_id, created_at DESC, id DESC);
-- The day's khata payments (less those undone that day and advances given back), for the day's totals
CREATE INDEX ix_kirana_khata_entries_payments ON kirana_khata_entries (shop_id, created_at)
    WHERE kind IN ('PAYMENT', 'REVERSAL', 'REFUND');

-- ============ 5. Keys to existing tables, last ============
-- Each takes a short lock on shops or users and waits (at most 3 s) for writes in progress there. NOT VALID: the new
-- tables are empty; V67 validates them anyway.
ALTER TABLE kirana_settings ADD CONSTRAINT fk_kirana_settings_shop
    FOREIGN KEY (shop_id) REFERENCES shops (id) NOT VALID;
ALTER TABLE kirana_settings ADD CONSTRAINT fk_kirana_settings_upi_by
    FOREIGN KEY (upi_updated_by) REFERENCES users (id) NOT VALID;
ALTER TABLE kirana_products ADD CONSTRAINT fk_kirana_products_shop
    FOREIGN KEY (shop_id) REFERENCES shops (id) NOT VALID;
ALTER TABLE kirana_products ADD CONSTRAINT fk_kirana_products_by
    FOREIGN KEY (created_by) REFERENCES users (id) NOT VALID;
ALTER TABLE kirana_barcodes ADD CONSTRAINT fk_kirana_barcodes_shop
    FOREIGN KEY (shop_id) REFERENCES shops (id) NOT VALID;
ALTER TABLE kirana_barcodes ADD CONSTRAINT fk_kirana_barcodes_by
    FOREIGN KEY (created_by) REFERENCES users (id) NOT VALID;
ALTER TABLE kirana_stock_moves ADD CONSTRAINT fk_kirana_stock_moves_shop
    FOREIGN KEY (shop_id) REFERENCES shops (id) NOT VALID;
ALTER TABLE kirana_stock_moves ADD CONSTRAINT fk_kirana_stock_moves_by
    FOREIGN KEY (created_by) REFERENCES users (id) NOT VALID;
ALTER TABLE kirana_khata_customers ADD CONSTRAINT fk_kirana_khata_customers_shop
    FOREIGN KEY (shop_id) REFERENCES shops (id) NOT VALID;
ALTER TABLE kirana_khata_customers ADD CONSTRAINT fk_kirana_khata_customers_by
    FOREIGN KEY (created_by) REFERENCES users (id) NOT VALID;
ALTER TABLE kirana_bills ADD CONSTRAINT fk_kirana_bills_shop
    FOREIGN KEY (shop_id) REFERENCES shops (id) NOT VALID;
ALTER TABLE kirana_bills ADD CONSTRAINT fk_kirana_bills_by
    FOREIGN KEY (created_by) REFERENCES users (id) NOT VALID;
ALTER TABLE kirana_bills ADD CONSTRAINT fk_kirana_bills_voided_by
    FOREIGN KEY (voided_by) REFERENCES users (id) NOT VALID;
ALTER TABLE kirana_bill_returns ADD CONSTRAINT fk_kirana_bill_returns_shop
    FOREIGN KEY (shop_id) REFERENCES shops (id) NOT VALID;
ALTER TABLE kirana_bill_returns ADD CONSTRAINT fk_kirana_bill_returns_by
    FOREIGN KEY (created_by) REFERENCES users (id) NOT VALID;
ALTER TABLE kirana_bill_changes ADD CONSTRAINT fk_kirana_bill_changes_shop
    FOREIGN KEY (shop_id) REFERENCES shops (id) NOT VALID;
ALTER TABLE kirana_bill_changes ADD CONSTRAINT fk_kirana_bill_changes_by
    FOREIGN KEY (created_by) REFERENCES users (id) NOT VALID;
ALTER TABLE kirana_khata_entries ADD CONSTRAINT fk_kirana_khata_entries_shop
    FOREIGN KEY (shop_id) REFERENCES shops (id) NOT VALID;
ALTER TABLE kirana_khata_entries ADD CONSTRAINT fk_kirana_khata_entries_by
    FOREIGN KEY (created_by) REFERENCES users (id) NOT VALID;
