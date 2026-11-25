SET LOCAL lock_timeout = '3s';

-- Analytics for the super admin (admin website › Analytics; see "Analytics" in the README).

-- Every subscription payment and refund, one row each, with where it came from. Until now a payment only moved the
-- salon's paid-up end (salon_subscriptions) and nothing kept the payment itself.
--   WEBSITE: Razorpay, from the website; APP_RAZORPAY: Razorpay picked in the Play Store app (Google's user choice
--   billing); GOOGLE_PLAY: Google Play's own billing.
--   external_id: Razorpay's payment id (pay_…) or refund id (rfnd_…); Google's order id (GPA.…), or "<order id>:refund"
--   for its refund. A webhook or notice that comes twice adds nothing (unique).
--   fee_paise: Razorpay's fee with its GST, as Razorpay gives it; null for Google Play (Google doesn't say: the page
--   estimates it at the rate in analytics_settings) and for refunds.
--   test: made with Razorpay's test keys or a Google Play test purchase: never counted in the money figures.
CREATE TABLE subscription_payments (
    id            UUID         PRIMARY KEY,
    shop_id       UUID         NOT NULL REFERENCES shops (id),
    source        VARCHAR(12)  NOT NULL CHECK (source IN ('WEBSITE', 'APP_RAZORPAY', 'GOOGLE_PLAY')),
    kind          VARCHAR(6)   NOT NULL CHECK (kind IN ('CHARGE', 'REFUND')),
    external_id   VARCHAR(100) NOT NULL,
    -- a refund: the charge it gives back (its external_id)
    charge_id     VARCHAR(100),
    amount_paise  BIGINT       NOT NULL,
    fee_paise     BIGINT       CHECK (fee_paise >= 0),
    paid_at       TIMESTAMPTZ  NOT NULL,
    -- a charge: the end of the time it paid for
    period_end    TIMESTAMPTZ,
    test          BOOLEAN      NOT NULL DEFAULT false,
    -- brought in from Razorpay by the one-time import (PaymentImport), not from a webhook
    imported      BOOLEAN      NOT NULL DEFAULT false,
    created_at    TIMESTAMPTZ  NOT NULL,
    UNIQUE (kind, external_id),
    CHECK ((kind = 'CHARGE') = (amount_paise >= 0)),
    CHECK ((kind = 'REFUND') = (charge_id IS NOT NULL))
);
CREATE INDEX ix_subscription_payments_paid ON subscription_payments (paid_at);
CREATE INDEX ix_subscription_payments_shop ON subscription_payments (shop_id, paid_at);

-- Who used Clozest on a day, once per person, side and channel: the daily, weekly and monthly active counts. Kept 35
-- days only (the hourly job deletes older days), never shown per person: only counts leave this table.
CREATE TABLE daily_activity (
    day       DATE        NOT NULL,
    audience  VARCHAR(8)  NOT NULL CHECK (audience IN ('CUSTOMER', 'PARTNER', 'ADMIN')),
    channel   VARCHAR(4)  NOT NULL CHECK (channel IN ('APP', 'WEB')),
    user_id   UUID        NOT NULL,
    PRIMARY KEY (day, audience, channel, user_id)
);
-- A salon's last day of use (its members' activity)
CREATE INDEX ix_daily_activity_user ON daily_activity (user_id, day);

-- Phones of each Play Store app version per day: each app says once a day that it is its first update check of the
-- day; only these totals are kept (no phone, no person).
CREATE TABLE app_version_daily (
    day           DATE        NOT NULL,
    app           VARCHAR(10) NOT NULL CHECK (app IN ('partner', 'customer')),
    version_code  INTEGER     NOT NULL CHECK (version_code > 0),
    -- a Play Store install (false: a test APK)
    store         BOOLEAN     NOT NULL,
    phones        INTEGER     NOT NULL CHECK (phones >= 0),
    PRIMARY KEY (day, app, version_code, store)
);

-- Daily totals the hourly job works out (AnalyticsJob): signups, active people, bookings… per day (India's day), with
-- an optional breakdown (dim: a pincode, an hour, a side and channel). The page reads only these.
CREATE TABLE analytics_daily (
    metric  VARCHAR(40) NOT NULL,
    day     DATE        NOT NULL,
    dim     VARCHAR(40) NOT NULL DEFAULT '',
    value   BIGINT      NOT NULL,
    PRIMARY KEY (metric, day, dim)
);

-- Per salon, refreshed by the hourly job: its use over the last 30 days and how far its setup is.
CREATE TABLE salon_usage (
    shop_id           UUID        PRIMARY KEY REFERENCES shops (id),
    -- the last day the owner or any staff used Clozest (never goes back: activity older than 35 days is gone)
    last_active_day   DATE,
    bookings_30d      INTEGER     NOT NULL DEFAULT 0,
    completed_30d     INTEGER     NOT NULL DEFAULT 0,
    walk_ins_30d      INTEGER     NOT NULL DEFAULT 0,
    no_shows_30d      INTEGER     NOT NULL DEFAULT 0,
    services          INTEGER     NOT NULL DEFAULT 0,
    staff             INTEGER     NOT NULL DEFAULT 0,
    -- owner photo, salon photo, salon video: how many of the three are there
    pictures          INTEGER     NOT NULL DEFAULT 0,
    updated_at        TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_salon_usage_last_active ON salon_usage (last_active_day NULLS FIRST, shop_id);
CREATE INDEX ix_salon_usage_bookings ON salon_usage (bookings_30d DESC, shop_id);

-- Google's cut, which Google doesn't tell us per payment: the rates the page estimates it with (basis points: 1500 =
-- 15%). Set by the super admin on the page.
CREATE TABLE analytics_settings (
    id                    SMALLINT    PRIMARY KEY CHECK (id = 1),
    play_fee_bps          INTEGER     NOT NULL DEFAULT 1500 CHECK (play_fee_bps BETWEEN 0 AND 10000),
    user_choice_fee_bps   INTEGER     NOT NULL DEFAULT 1100 CHECK (user_choice_fee_bps BETWEEN 0 AND 10000),
    version               INTEGER     NOT NULL DEFAULT 0,
    changed_at            TIMESTAMPTZ,
    changed_by            UUID        REFERENCES users (id)
);
INSERT INTO analytics_settings (id) VALUES (1);

-- One-time work done once for good (the Razorpay import, the first backfill of the daily totals)
CREATE TABLE analytics_jobs (
    name     VARCHAR(80) PRIMARY KEY,
    done_at  TIMESTAMPTZ NOT NULL
);

-- The super admin's fee rate changes are logged with the team's other changes
-- A Razorpay refund whose payment isn't in the ledger yet (its charge webhook still being retried, say): kept here
-- and recorded as soon as the charge is (PaymentLedger.charge). A refund of something else (a kirana order) never
-- matches; the hourly job deletes them after 30 days.
CREATE TABLE subscription_refunds_waiting (
    refund_id    VARCHAR(100) PRIMARY KEY,
    payment_id   VARCHAR(100) NOT NULL,
    amount_paise BIGINT       NOT NULL CHECK (amount_paise >= 0),
    refunded_at  TIMESTAMPTZ  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_subscription_refunds_waiting_payment ON subscription_refunds_waiting (payment_id);
CREATE INDEX ix_subscription_refunds_waiting_created ON subscription_refunds_waiting (created_at);

ALTER TABLE admin_events DROP CONSTRAINT admin_events_event_check;
ALTER TABLE admin_events ADD CONSTRAINT admin_events_event_check CHECK (event IN ('ADMIN_ADDED', 'ADMIN_REMOVED',
    'ADMIN_EDITED', 'TEST_PHONE_ADDED', 'TEST_PHONE_REMOVED', 'TEST_EMAIL_ADDED', 'TEST_EMAIL_REMOVED',
    'TEST_MODE_ON', 'TEST_MODE_OFF', 'APP_RELEASE_PULLED', 'APP_RELEASE_RESTORED', 'APP_SETTINGS_CHANGED',
    'ANALYTICS_SETTINGS_CHANGED', 'APP_RELEASE_URGENT', 'APP_RELEASE_NOT_URGENT'));
