-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- Three new, empty tables: their indexes are built at once. The foreign keys take a short lock on shops and users
-- (no row is read: the new tables are empty).

-- ============ Helpers and barbers join by invitation ============
-- An owner asks the person with a phone number to join as a helper, or to sign in as one of the salon's barbers. The
-- answer to the owner is the same whether or not that number has an account (no account is made, no name shown); the
-- person sees the invitation after signing in and accepts or declines. It runs out after 7 days (expires_at; one still
-- PENDING after that counts as expired wherever it is read). At most one waiting per shop and phone, and one per barber
-- profile. staff_id has no foreign key: the shops module doesn't own the salon's tables (the salon checks the profile
-- under its own lock when the invitation is accepted).
CREATE TABLE shop_invites (
    id          UUID          PRIMARY KEY,
    shop_id     UUID          NOT NULL REFERENCES shops (id),
    role        VARCHAR(10)   NOT NULL CHECK (role IN ('HELPER', 'BARBER')),
    staff_id    UUID,                                             -- the barber profile (BARBER only)
    to_phone    VARCHAR(10)   NOT NULL,                           -- as the owner typed it, 10 digits like users.phone
    invited_by  UUID          NOT NULL REFERENCES users (id),
    status      VARCHAR(10)   NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'CANCELLED', 'EXPIRED')),
    created_at  TIMESTAMPTZ   NOT NULL,
    expires_at  TIMESTAMPTZ   NOT NULL,
    decided_at  TIMESTAMPTZ,
    decided_by  UUID          REFERENCES users (id),
    CHECK ((role = 'BARBER') = (staff_id IS NOT NULL))
);
CREATE UNIQUE INDEX ux_shop_invites_one_per_phone ON shop_invites (shop_id, to_phone) WHERE status = 'PENDING';
CREATE UNIQUE INDEX ux_shop_invites_one_per_barber ON shop_invites (staff_id) WHERE status = 'PENDING';
-- "Is anything waiting for my phone?" each time the app shows the invitations card
CREATE INDEX ix_shop_invites_to_phone ON shop_invites (to_phone) WHERE status = 'PENDING';
CREATE INDEX ix_shop_invites_invited_by ON shop_invites (invited_by);
CREATE INDEX ix_shop_invites_decided_by ON shop_invites (decided_by) WHERE decided_by IS NOT NULL;

-- ============ A closed salon's bookings are cancelled in the background ============
-- Closing a salon marks the shop CLOSED at once (no booking can be made from then on) and queues it here; a job
-- (SalonClosingJob) cancels its upcoming online bookings a slice at a time and tells each customer, so closing a salon
-- with thousands of bookings ahead stays one short request. The row goes once nothing is left to cancel.
CREATE TABLE salon_closings (
    shop_id      UUID          PRIMARY KEY REFERENCES shops (id),
    reason       VARCHAR(200)  NOT NULL,      -- what customers are told
    detail       VARCHAR(100)  NOT NULL,      -- the appointment history's note
    cancel_from  TIMESTAMPTZ   NOT NULL,      -- bookings starting after this are cancelled
    queued_at    TIMESTAMPTZ   NOT NULL
);

-- ============ Someone leaving a salon: their salon messages go in the background ============
-- The person's salon messages (SALON_*) sent up to up_to are hidden from their list at once (the inbox reads this
-- row) and deleted a slice at a time by a job (SalonMessagePurgeJob), walking their rows in id order from after_id.
-- A salon owner can have lakhs of them; one DELETE inside the request would run past the 5 s limit.
CREATE TABLE salon_message_purges (
    user_id    UUID          PRIMARY KEY REFERENCES users (id),
    up_to      TIMESTAMPTZ   NOT NULL,
    after_id   UUID          NOT NULL,
    queued_at  TIMESTAMPTZ   NOT NULL
);
