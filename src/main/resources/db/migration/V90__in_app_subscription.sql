SET LOCAL lock_timeout = '3s';

-- Paying the salon's subscription in the Play Store app (user choice billing, India): Google shows its own choice
-- screen, and the owner pays either with Razorpay (as on the website) or with Google Play. See "Paying in the Play
-- Store app" in the README.

-- Razorpay picked on Google's choice screen: the externalTransactionToken Google gave the app, kept with the Razorpay
-- subscription it was for (the one the checkout opened). Its payments are reported to Google (play_external_transactions).
ALTER TABLE salon_subscriptions ADD COLUMN app_billing_token VARCHAR(1000);
ALTER TABLE salon_subscriptions ADD COLUMN app_billing_subscription_id VARCHAR(40);
-- Each Razorpay webhook asks whether its subscription was the app's
CREATE INDEX ix_salon_subscriptions_app_billing ON salon_subscriptions (app_billing_subscription_id)
    WHERE app_billing_subscription_id IS NOT NULL;

-- Google Play picked: the purchase (its purchaseToken, a secret), Google's state for it, until when it is paid and
-- whether it renews by itself; whether we acknowledged it (Google refunds a purchase not acknowledged within 3 days);
-- the time Google was last asked; and the paid-until it gave the salon (taken back if Google refunds or revokes it).
ALTER TABLE salon_subscriptions ADD COLUMN play_purchase_token VARCHAR(1000);
ALTER TABLE salon_subscriptions ADD COLUMN play_state VARCHAR(40);
ALTER TABLE salon_subscriptions ADD COLUMN play_expires_at TIMESTAMPTZ;
ALTER TABLE salon_subscriptions ADD COLUMN play_auto_renewing BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE salon_subscriptions ADD COLUMN play_acknowledged BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE salon_subscriptions ADD COLUMN play_checked_at TIMESTAMPTZ;
ALTER TABLE salon_subscriptions ADD COLUMN play_paid_until TIMESTAMPTZ;
-- One salon per Google purchase (a token sent twice, or for another salon, finds this one)
CREATE UNIQUE INDEX ux_salon_subscriptions_play_token ON salon_subscriptions (play_purchase_token)
    WHERE play_purchase_token IS NOT NULL;

-- Google Play purchases a salon no longer has: replaced by a newer purchase, or dropped when the salon closed or changed
-- hands. Their renewal is cancelled at Google (tried until done), and Google's later notices about them never attach
-- them to a salon again.
CREATE TABLE play_dropped_purchases (
    purchase_token VARCHAR(1000) PRIMARY KEY,
    shop_id        UUID          NOT NULL REFERENCES shops (id),
    cancelled      BOOLEAN       NOT NULL DEFAULT false,
    attempts       INTEGER       NOT NULL DEFAULT 0,
    next_try_at    TIMESTAMPTZ   NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL
);
CREATE INDEX ix_play_dropped_due ON play_dropped_purchases (next_try_at) WHERE NOT cancelled;

-- Razorpay payments made from the app, to report to Google within 24 hours (PlayReports): the first payment with the
-- token (the subscription's first charge, at once: the app offers paying only once the free or paid time is over),
-- then each monthly charge naming the first.
CREATE TABLE play_external_transactions (
    -- ours, sent to Google as externalTransactionId: "s-<subscription id>" for the first, "p-<payment id>" after
    id                       VARCHAR(80)  PRIMARY KEY,
    -- the Razorpay payment it reports (the first's too: a replayed webhook for it adds nothing)
    payment_id               VARCHAR(40)  NOT NULL,
    shop_id                  UUID         NOT NULL REFERENCES shops (id),
    razorpay_subscription_id VARCHAR(40)  NOT NULL,
    -- the first transaction's id; null for the first itself
    initial_id               VARCHAR(80),
    -- the first only: the externalTransactionToken
    token                    VARCHAR(1000),
    amount_paise             INTEGER      NOT NULL CHECK (amount_paise >= 0),
    transaction_time         TIMESTAMPTZ  NOT NULL,
    -- the salon's state, as Google names it (from its PIN code)
    tax_state                VARCHAR(60)  NOT NULL,
    status                   VARCHAR(10)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DONE', 'REFUSED')),
    attempts                 INTEGER      NOT NULL DEFAULT 0,
    next_try_at              TIMESTAMPTZ  NOT NULL,
    reported_at              TIMESTAMPTZ,
    created_at               TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_play_external_first CHECK ((initial_id IS NULL) = (token IS NOT NULL))
);
-- The job's read: the waiting ones, due first
CREATE INDEX ix_play_external_due ON play_external_transactions (next_try_at) WHERE status = 'PENDING';
-- The first of a subscription, looked up for each later charge
CREATE INDEX ix_play_external_subscription ON play_external_transactions (razorpay_subscription_id);
-- One report per Razorpay payment
CREATE UNIQUE INDEX ux_play_external_payment ON play_external_transactions (payment_id);
-- The hourly recheck of Play subscriptions (PlaySubscriptions.recheck): not yet acknowledged, or near their end; the
-- least recently asked first
CREATE INDEX ix_salon_subscriptions_play_check ON salon_subscriptions (play_checked_at NULLS FIRST)
    WHERE play_purchase_token IS NOT NULL;
