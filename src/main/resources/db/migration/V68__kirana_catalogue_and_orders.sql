-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- Kirana, round 2: the shared product catalogue and online orders. Customers order from a kirana within 2 km and pay
-- Clozest (Razorpay: UPI, or Pay Later as their khata); the shop accepts within 3 minutes, packs and delivers against
-- the customer's OTP. Items the shop lacks come from another shop near it (one delivery) or as a separate order. Each
-- shop is paid its items to its UPI ID about 2 working days after delivery (RazorpayX); Clozest keeps the ₹10 charge.
--
-- As in V66: new tables first; keys to shops and users last, NOT VALID (V69 validates them).

-- ============ 1. The shared catalogue: one entry per real product, keyed by its barcodes ============
-- Grows from the shops: the first shop to add a barcode creates the entry; super admins correct it. A shop's product
-- links to an entry, which is how "the same item" is found in another shop.
CREATE TABLE kirana_catalogue_items (
    id               UUID          PRIMARY KEY,
    name             VARCHAR(100)  NOT NULL,
    brand            VARCHAR(60),
    size             VARCHAR(30),                      -- as printed: "500 g", "1 L", "Pack of 6"
    unit             VARCHAR(5)    NOT NULL CHECK (unit IN ('PIECE', 'KG')),
    mrp_paise        BIGINT        CHECK (mrp_paise BETWEEN 1 AND 10000000),
    category         VARCHAR(40),
    created_by_shop  UUID,                             -- shops (key added below)
    created_at       TIMESTAMPTZ   NOT NULL,
    updated_at       TIMESTAMPTZ   NOT NULL,
    version          BIGINT        NOT NULL
);
CREATE INDEX ix_kirana_catalogue_items_list ON kirana_catalogue_items ((lower(name) COLLATE "C"), id);
CREATE INDEX ix_kirana_catalogue_items_search ON kirana_catalogue_items USING gin (lower(name) gin_trgm_ops);

-- A barcode belongs to one catalogue entry for every shop; pack: how many of it one scan means.
CREATE TABLE kirana_catalogue_barcodes (
    code        VARCHAR(32)   COLLATE "C" PRIMARY KEY,
    item_id     UUID          NOT NULL REFERENCES kirana_catalogue_items (id),
    pack        NUMERIC(12,3) NOT NULL CHECK (pack > 0 AND pack <= 9999),
    created_at  TIMESTAMPTZ   NOT NULL
);
CREATE INDEX ix_kirana_catalogue_barcodes_item ON kirana_catalogue_barcodes (item_id);

ALTER TABLE kirana_products ADD COLUMN catalogue_item_id UUID;
ALTER TABLE kirana_products ADD CONSTRAINT fk_kirana_products_catalogue FOREIGN KEY (catalogue_item_id)
    REFERENCES kirana_catalogue_items (id) NOT VALID;
-- The same item in nearby shops (orders filled by another shop)
CREATE INDEX ix_kirana_products_catalogue ON kirana_products (catalogue_item_id, shop_id)
    WHERE catalogue_item_id IS NOT NULL AND active;

-- ============ 2. A shop's online orders: whether it takes them, its FSSAI licence, its UPI ID check ============
CREATE TABLE kirana_online (
    shop_id             UUID          PRIMARY KEY,     -- shops (key added below)
    taking_orders       BOOLEAN       NOT NULL,        -- the owner's switch (busy day, holiday)
    fssai_number        VARCHAR(14),
    fssai_file_id       UUID,                          -- files: the certificate (a photo or a PDF)
    fssai_status        VARCHAR(8)    NOT NULL CHECK (fssai_status IN ('NONE', 'CHECKING', 'VERIFIED', 'REJECTED')),
    fssai_reason        VARCHAR(300),                  -- why it was refused, in the owner's words
    fssai_valid_until   DATE,
    fssai_checked_at    TIMESTAMPTZ,
    fssai_tries         SMALLINT      NOT NULL,        -- AI reads tried for the current certificate
    upi_recheck         BOOLEAN       NOT NULL,        -- UPI ID changed after approval: payouts wait for the field team
    upi_checked_by      UUID,                          -- users (key added below)
    upi_checked_at      TIMESTAMPTZ,
    rating_sum          BIGINT        NOT NULL,
    rating_count        INTEGER       NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_kirana_online_fssai CHECK (fssai_status = 'NONE' OR (fssai_number IS NOT NULL AND fssai_file_id IS NOT NULL))
);
-- The FSSAI check job's queue, and the field team's list of UPI IDs to re-check
CREATE INDEX ix_kirana_online_fssai_checking ON kirana_online (fssai_checked_at) WHERE fssai_status = 'CHECKING';
CREATE INDEX ix_kirana_online_fssai_rejected ON kirana_online (fssai_checked_at DESC) WHERE fssai_status = 'REJECTED';
CREATE INDEX ix_kirana_online_upi_recheck ON kirana_online (updated_at) WHERE upi_recheck;

-- The kirana AI reads (FSSAI certificates, distributor bills) per India day, against the daily cap
CREATE TABLE kirana_ai_calls (
    day     DATE     PRIMARY KEY,
    calls   INTEGER  NOT NULL
);

-- ============ 3. Online orders ============
-- A checkout: what the customer paid in one go (one Razorpay order). It holds one order (a delivery) or two
-- (separate orders: the items a shop lacks come from another shop, delivered by it).
CREATE TABLE kirana_checkouts (
    id                    UUID          PRIMARY KEY,
    customer_id           UUID          NOT NULL,      -- users (key added below)
    client_ref            UUID          NOT NULL,      -- the app's: a double tap finds the first checkout
    items_paise           BIGINT        NOT NULL CHECK (items_paise > 0),
    platform_paise        BIGINT        NOT NULL CHECK (platform_paise >= 0),
    total_paise           BIGINT        NOT NULL,
    method_wanted         VARCHAR(9)    NOT NULL CHECK (method_wanted IN ('UPI', 'PAY_LATER')),
    status                VARCHAR(8)    NOT NULL CHECK (status IN ('PENDING', 'PAID', 'EXPIRED')),
    razorpay_order_id     VARCHAR(40),
    razorpay_payment_id   VARCHAR(40),
    paid_method           VARCHAR(20),                 -- as Razorpay reports it: upi, paylater, ...
    refunded_paise        BIGINT        NOT NULL CHECK (refunded_paise >= 0),
    created_at            TIMESTAMPTZ   NOT NULL,
    paid_at               TIMESTAMPTZ,
    CONSTRAINT ck_kirana_checkouts_total CHECK (total_paise = items_paise + platform_paise
        AND refunded_paise <= total_paise),
    CONSTRAINT ck_kirana_checkouts_paid CHECK ((status = 'PAID') = (paid_at IS NOT NULL)
        AND (paid_at IS NULL OR razorpay_payment_id IS NOT NULL))
);
CREATE UNIQUE INDEX ux_kirana_checkouts_client_ref ON kirana_checkouts (customer_id, client_ref);
CREATE UNIQUE INDEX ux_kirana_checkouts_razorpay ON kirana_checkouts (razorpay_order_id)
    WHERE razorpay_order_id IS NOT NULL;
-- The expiry job's queue (checkouts never paid)
CREATE INDEX ix_kirana_checkouts_pending ON kirana_checkouts (created_at) WHERE status = 'PENDING';

CREATE SEQUENCE kirana_order_no_seq START 1001;

-- An order: one delivery, by its shop (the one the customer chose, or the other shop of a separate order), against
-- the customer's OTP. Its items come in parts: the shop's own, and any another shop sends it (a combined order).
CREATE TABLE kirana_orders (
    id                 UUID          PRIMARY KEY,
    order_no           BIGINT        NOT NULL DEFAULT nextval('kirana_order_no_seq'),
    checkout_id        UUID          NOT NULL REFERENCES kirana_checkouts (id),
    shop_id            UUID          NOT NULL,         -- the delivering shop (key added below)
    customer_id        UUID          NOT NULL,         -- users (key added below)
    address_line       VARCHAR(200)  NOT NULL,         -- flat, tower, landmark
    latitude           NUMERIC(9,6)  NOT NULL,
    longitude          NUMERIC(9,6)  NOT NULL,
    status             VARCHAR(16)   NOT NULL CHECK (status IN ('WAITING_PAYMENT', 'PLACED', 'ACCEPTED', 'PACKED',
                                                              'OUT_FOR_DELIVERY', 'DELIVERED', 'CANCELLED')),
    cancel_reason      VARCHAR(200),
    platform_paise     BIGINT        NOT NULL CHECK (platform_paise >= 0),   -- the ₹10, on one order of a checkout
    delivery_otp       VARCHAR(4)    NOT NULL,         -- shown to the customer only
    otp_wrong          SMALLINT      NOT NULL,
    otp_locked_until   TIMESTAMPTZ,
    placed_at          TIMESTAMPTZ,
    accepted_at        TIMESTAMPTZ,
    packed_at          TIMESTAMPTZ,
    out_at             TIMESTAMPTZ,
    delivered_at       TIMESTAMPTZ,
    cancelled_at       TIMESTAMPTZ,
    rating             SMALLINT      CHECK (rating BETWEEN 1 AND 5),
    rating_comment     VARCHAR(300),
    rated_at           TIMESTAMPTZ,
    version            BIGINT        NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_kirana_orders_otp CHECK (delivery_otp ~ '^[0-9]{4}$'),
    CONSTRAINT ck_kirana_orders_cancel CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL))
);
CREATE UNIQUE INDEX ux_kirana_orders_no ON kirana_orders (order_no);
CREATE INDEX ix_kirana_orders_checkout ON kirana_orders (checkout_id);
-- The customer's orders, newest first; the shop's orders, newest first
CREATE INDEX ix_kirana_orders_customer ON kirana_orders (customer_id, created_at DESC, id DESC);
CREATE INDEX ix_kirana_orders_shop ON kirana_orders (shop_id, created_at DESC, id DESC);
-- Paid orders still open, oldest first (the admin website's "stuck orders")
CREATE INDEX ix_kirana_orders_open ON kirana_orders (placed_at)
    WHERE status IN ('PLACED', 'ACCEPTED', 'PACKED', 'OUT_FOR_DELIVERY');

-- A part of an order: the items one shop gives. OWN: the delivering shop's; SUPPLIER: another shop's, handed to the
-- delivering shop (which never shows the customer's details to it). Each part is accepted within 3 minutes.
CREATE TABLE kirana_order_parts (
    id            UUID          PRIMARY KEY,
    order_id      UUID          NOT NULL REFERENCES kirana_orders (id),
    shop_id       UUID          NOT NULL,              -- shops (key added below)
    role          VARCHAR(8)    NOT NULL CHECK (role IN ('OWN', 'SUPPLIER')),
    status        VARCHAR(16)   NOT NULL CHECK (status IN ('WAITING_PAYMENT', 'WAITING', 'ACCEPTED', 'PACKED',
                                                        'HANDED', 'REJECTED', 'TIMED_OUT', 'CANCELLED')),
    accept_by     TIMESTAMPTZ,
    accepted_at   TIMESTAMPTZ,
    accepted_by   UUID,                                -- users (key added below)
    reason        VARCHAR(200),                        -- why it was rejected or cancelled
    items_paise   BIGINT        NOT NULL CHECK (items_paise >= 0),   -- its lines not marked missing
    -- The shop's order screens: OPEN while the shop has something to do, DONE after; NULL until paid
    board         VARCHAR(4)    CHECK (board IN ('OPEN', 'DONE')),
    version       BIGINT        NOT NULL,
    updated_at    TIMESTAMPTZ   NOT NULL
);
CREATE UNIQUE INDEX ux_kirana_order_parts_shop ON kirana_order_parts (order_id, shop_id);
-- A shop's open and done order screens, newest first, and the 3-minute job's queue
CREATE INDEX ix_kirana_order_parts_shop ON kirana_order_parts (shop_id, board, updated_at DESC, id DESC)
    WHERE board IS NOT NULL;
CREATE INDEX ix_kirana_order_parts_waiting ON kirana_order_parts (accept_by) WHERE status = 'WAITING';

CREATE TABLE kirana_order_lines (
    part_id            UUID          NOT NULL REFERENCES kirana_order_parts (id),
    line_no            SMALLINT      NOT NULL CHECK (line_no BETWEEN 1 AND 100),
    product_id         UUID          NOT NULL REFERENCES kirana_products (id),
    catalogue_item_id  UUID          REFERENCES kirana_catalogue_items (id),
    name               VARCHAR(100)  NOT NULL,
    unit               VARCHAR(5)    NOT NULL CHECK (unit IN ('PIECE', 'KG')),
    quantity           NUMERIC(12,3) NOT NULL CHECK (quantity > 0),
    price_paise        BIGINT        NOT NULL CHECK (price_paise > 0),
    amount_paise       BIGINT        NOT NULL CHECK (amount_paise >= 0),
    missing            BOOLEAN       NOT NULL,         -- the shop found it doesn't have it: refunded
    PRIMARY KEY (part_id, line_no)
);
-- Whether a product was ever sold online (its unit is fixed from then on, as for counter sales)
CREATE INDEX ix_kirana_order_lines_product ON kirana_order_lines (product_id);

-- Stock taken by an accepted order part, and given back (an item marked missing, an order cancelled after it)
-- (The keys and checks on these existing tables are added NOT VALID, so no scan holds their lock; V69 checks them.)
ALTER TABLE kirana_stock_moves ADD COLUMN order_part_id UUID;
ALTER TABLE kirana_stock_moves ADD CONSTRAINT fk_kirana_stock_moves_part FOREIGN KEY (order_part_id)
    REFERENCES kirana_order_parts (id) NOT VALID;
ALTER TABLE kirana_stock_moves DROP CONSTRAINT kirana_stock_moves_reason_check;
ALTER TABLE kirana_stock_moves ADD CONSTRAINT kirana_stock_moves_reason_check CHECK (reason IN ('SALE', 'VOID',
    'RETURN', 'RESTOCK', 'DAMAGED', 'COUNT', 'ORDER', 'ORDER_BACK')) NOT VALID;
ALTER TABLE kirana_stock_moves ADD CONSTRAINT ck_kirana_stock_moves_order CHECK (
    (reason IN ('ORDER', 'ORDER_BACK')) = (order_part_id IS NOT NULL)) NOT VALID;
-- (Its index is built CONCURRENTLY in V70: counter bills go on writing stock moves meanwhile.)
-- An order's stock may move with no shop person behind it (a part timed out, the customer cancelled)
ALTER TABLE kirana_stock_moves ALTER COLUMN created_by DROP NOT NULL;
ALTER TABLE kirana_stock_moves ADD CONSTRAINT ck_kirana_stock_moves_by CHECK (
    created_by IS NOT NULL OR reason IN ('ORDER', 'ORDER_BACK')) NOT VALID;

-- ============ 4. Money: refunds, payouts ============
-- A refund to the customer's payment (Razorpay): rejected or timed-out parts, items marked missing, a cancelled
-- order, a late payment for an expired checkout. Sent by a job, never inside the request.
CREATE TABLE kirana_refunds (
    id                   UUID          PRIMARY KEY,
    checkout_id          UUID          NOT NULL REFERENCES kirana_checkouts (id),
    order_id             UUID          REFERENCES kirana_orders (id),
    -- Which payment it goes back to: the checkout's, or a second payment of the same checkout (refunded in full)
    payment_id           VARCHAR(40),
    amount_paise         BIGINT        NOT NULL CHECK (amount_paise > 0),
    reason               VARCHAR(20)   NOT NULL,
    status               VARCHAR(10)   NOT NULL CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'PROCESSED', 'FAILED')),
    razorpay_refund_id   VARCHAR(40),
    tries                SMALLINT      NOT NULL,
    next_try_at          TIMESTAMPTZ   NOT NULL,
    failure              VARCHAR(300),
    created_at           TIMESTAMPTZ   NOT NULL,
    updated_at           TIMESTAMPTZ   NOT NULL
);
CREATE INDEX ix_kirana_refunds_checkout ON kirana_refunds (checkout_id);
CREATE INDEX ix_kirana_refunds_queue ON kirana_refunds (next_try_at) WHERE status IN ('PENDING', 'SENDING');
CREATE INDEX ix_kirana_refunds_failed ON kirana_refunds (updated_at DESC) WHERE status = 'FAILED';
CREATE UNIQUE INDEX ux_kirana_refunds_razorpay ON kirana_refunds (razorpay_refund_id)
    WHERE razorpay_refund_id IS NOT NULL;

-- What each shop earned from a delivered order part, due about 2 working days after delivery (once Razorpay has
-- settled the money). Gathered per shop into one payout to its UPI ID.
CREATE TABLE kirana_payouts (
    id                   UUID          PRIMARY KEY,
    shop_id              UUID          NOT NULL,       -- shops (key added below)
    amount_paise         BIGINT        NOT NULL CHECK (amount_paise > 0),
    upi_id               VARCHAR(100)  NOT NULL,
    status               VARCHAR(10)   NOT NULL CHECK (status IN ('SENDING', 'SENT', 'PROCESSED', 'FAILED',
                                                                'REVERSED')),
    razorpay_payout_id   VARCHAR(40),
    failure              VARCHAR(300),
    created_at           TIMESTAMPTZ   NOT NULL,
    updated_at           TIMESTAMPTZ   NOT NULL
);
CREATE INDEX ix_kirana_payouts_shop ON kirana_payouts (shop_id, created_at DESC, id DESC);
CREATE INDEX ix_kirana_payouts_sending ON kirana_payouts (updated_at) WHERE status = 'SENDING';
CREATE INDEX ix_kirana_payouts_status ON kirana_payouts (status, updated_at DESC);
CREATE UNIQUE INDEX ux_kirana_payouts_razorpay ON kirana_payouts (razorpay_payout_id)
    WHERE razorpay_payout_id IS NOT NULL;

CREATE TABLE kirana_earnings (
    part_id       UUID          PRIMARY KEY REFERENCES kirana_order_parts (id),
    shop_id       UUID          NOT NULL,              -- shops (key added below)
    amount_paise  BIGINT        NOT NULL CHECK (amount_paise > 0),
    due_on        DATE          NOT NULL,
    payout_id     UUID          REFERENCES kirana_payouts (id),
    created_at    TIMESTAMPTZ   NOT NULL
);
-- The payout job's queue: what is due and not yet paid, by shop
CREATE INDEX ix_kirana_earnings_due ON kirana_earnings (due_on, shop_id) WHERE payout_id IS NULL;
CREATE INDEX ix_kirana_earnings_shop ON kirana_earnings (shop_id, created_at DESC);
CREATE INDEX ix_kirana_earnings_payout ON kirana_earnings (payout_id) WHERE payout_id IS NOT NULL;

-- RazorpayX's contact and fund account (the UPI ID) made for a shop, reused for its next payouts to the same UPI ID
CREATE TABLE kirana_payout_accounts (
    shop_id           UUID          NOT NULL,          -- shops (key added below)
    upi_id            VARCHAR(100)  NOT NULL,
    contact_id        VARCHAR(40)   NOT NULL,
    fund_account_id   VARCHAR(40)   NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (shop_id, upi_id)
);

-- Razorpay and RazorpayX events about orders, refunds and payouts, each handled once (a retried webhook)
CREATE TABLE kirana_razorpay_events (
    event_id   VARCHAR(80)   PRIMARY KEY,
    at         TIMESTAMPTZ   NOT NULL
);

-- ============ 5. Keys to existing tables, last ============
ALTER TABLE kirana_catalogue_items ADD CONSTRAINT fk_kirana_catalogue_items_shop
    FOREIGN KEY (created_by_shop) REFERENCES shops (id) NOT VALID;
ALTER TABLE kirana_online ADD CONSTRAINT fk_kirana_online_shop
    FOREIGN KEY (shop_id) REFERENCES shops (id) NOT VALID;
ALTER TABLE kirana_online ADD CONSTRAINT fk_kirana_online_upi_by
    FOREIGN KEY (upi_checked_by) REFERENCES users (id) NOT VALID;
ALTER TABLE kirana_checkouts ADD CONSTRAINT fk_kirana_checkouts_customer
    FOREIGN KEY (customer_id) REFERENCES users (id) NOT VALID;
ALTER TABLE kirana_orders ADD CONSTRAINT fk_kirana_orders_shop
    FOREIGN KEY (shop_id) REFERENCES shops (id) NOT VALID;
ALTER TABLE kirana_orders ADD CONSTRAINT fk_kirana_orders_customer
    FOREIGN KEY (customer_id) REFERENCES users (id) NOT VALID;
ALTER TABLE kirana_order_parts ADD CONSTRAINT fk_kirana_order_parts_shop
    FOREIGN KEY (shop_id) REFERENCES shops (id) NOT VALID;
ALTER TABLE kirana_order_parts ADD CONSTRAINT fk_kirana_order_parts_by
    FOREIGN KEY (accepted_by) REFERENCES users (id) NOT VALID;
ALTER TABLE kirana_payouts ADD CONSTRAINT fk_kirana_payouts_shop
    FOREIGN KEY (shop_id) REFERENCES shops (id) NOT VALID;
ALTER TABLE kirana_earnings ADD CONSTRAINT fk_kirana_earnings_shop
    FOREIGN KEY (shop_id) REFERENCES shops (id) NOT VALID;
ALTER TABLE kirana_payout_accounts ADD CONSTRAINT fk_kirana_payout_accounts_shop
    FOREIGN KEY (shop_id) REFERENCES shops (id) NOT VALID;
