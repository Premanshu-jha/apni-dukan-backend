-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- The salons' subscription (₹500 a month, through Razorpay Subscriptions with UPI AutoPay): one row per salon, made at
-- its first approval with a 30-day free trial (see SubscriptionService).
--   access_until  = the later of trial_ends_at and paid_until: the salon has paid (or is in its trial) until then
--   bookable_until = midnight (India time) ending the third day after access_until: from then on the salon is
--                    hidden from customers' search and takes no new online bookings (bookings already made stay and
--                    may still move), while SUBSCRIPTIONS_ENFORCED is on
-- Customers' search and booking read bookable_until (ShopDirectoryService, SalonVersionStore): a salon without a row
-- (not approved yet, a kiranas) is never hidden by it.
CREATE TABLE salon_subscriptions (
    shop_id                  UUID PRIMARY KEY REFERENCES shops (id),
    trial_ends_at            TIMESTAMPTZ NOT NULL,
    -- the end of the last month Razorpay charged (its subscription's current_end); null before the first payment
    paid_until               TIMESTAMPTZ,
    bookable_until           TIMESTAMPTZ NOT NULL,
    -- the Razorpay subscription (sub_...), made when the owner first opens the payment screen; a new one replaces a
    -- cancelled or halted one
    razorpay_subscription_id VARCHAR(40) UNIQUE,
    -- when it was made: a CREATED one (the owner left the checkout) is offered again only for an hour
    subscription_made_at     TIMESTAMPTZ,
    -- Razorpay's status for it: CREATED, AUTHENTICATED, ACTIVE, PENDING, HALTED, CANCELLED, COMPLETED, EXPIRED, PAUSED
    razorpay_status          VARCHAR(20),
    -- when Razorpay's event with that status happened (its created_at): an older event arriving late changes nothing
    razorpay_status_at       TIMESTAMPTZ,
    -- set while a server is asking Razorpay for a new subscription (two taps at once make one, not two)
    creating_since           TIMESTAMPTZ,
    -- which messages about access_until's end were sent (SubscriptionNoticeJob): 0 none, 1 "ends in 7 days",
    -- 2 "ended, 3 days to pay", 3 "hidden"; notice_for is the access_until they were about (a payment moves it on,
    -- so the next period's messages start again from 0)
    notice_level             SMALLINT    NOT NULL DEFAULT 0,
    notice_for               TIMESTAMPTZ,
    created_at               TIMESTAMPTZ NOT NULL,
    updated_at               TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_salon_subscriptions_status CHECK (razorpay_status IN ('CREATED', 'AUTHENTICATED', 'ACTIVE',
        'PENDING', 'HALTED', 'CANCELLED', 'COMPLETED', 'EXPIRED', 'PAUSED')),
    CONSTRAINT ck_salon_subscriptions_level CHECK (notice_level BETWEEN 0 AND 3)
);

-- The notice job's batches: salons whose paid or trial time ends within a week, without AutoPay on
CREATE INDEX ix_salon_subscriptions_bookable ON salon_subscriptions (bookable_until);

-- Razorpay webhooks already handled (its x-razorpay-event-id): a retried delivery is answered 200 and skipped.
-- Kept 30 days (SubscriptionNoticeJob deletes older ones).
CREATE TABLE razorpay_events (
    event_id    VARCHAR(60) PRIMARY KEY,
    received_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_razorpay_events_received ON razorpay_events (received_at);

-- Razorpay subscriptions to cancel (a salon closed or handed over with AutoPay on, or replaced by a new checkout):
-- tried at once, then by SubscriptionNoticeJob until Razorpay confirms
CREATE TABLE razorpay_cancellations (
    subscription_id VARCHAR(40) PRIMARY KEY,
    shop_id         UUID        NOT NULL,
    wanted_at       TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_razorpay_cancellations_wanted ON razorpay_cancellations (wanted_at);

-- When hiding unpaid salons was switched on (SUBSCRIPTIONS_ENFORCED): one row, written once. That moment every
-- salon's free trial is counted from then (if later than its own), so nobody is hidden without warning.
CREATE TABLE subscription_enforcement (
    id         SMALLINT    PRIMARY KEY CHECK (id = 1),
    started_at TIMESTAMPTZ NOT NULL
);

-- Salons approved before this change: their 30-day trial starts now (none is hidden for a past approval). Hidden
-- only from midnight, India time, ending the third day after the trial (SubscriptionStore.bookableAfter)
INSERT INTO salon_subscriptions (shop_id, trial_ends_at, bookable_until, created_at, updated_at)
SELECT id, now() + INTERVAL '30 days',
       (date_trunc('day', (now() + INTERVAL '33 days') AT TIME ZONE 'Asia/Kolkata') + INTERVAL '1 day')
           AT TIME ZONE 'Asia/Kolkata',
       now(), now()
FROM shops
WHERE type = 'SALON' AND (status = 'APPROVED' OR ever_approved);
