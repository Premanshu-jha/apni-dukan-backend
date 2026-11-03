-- Salons: a shop of type SALON lists its services, its barbers, which services each barber does, their weekly
-- hours and its online booking settings (setup tables, kept in the *_aud history tables by Hibernate Envers, like
-- shops in V3). Then the daily work: appointments (bookings, walk-ins, breaks, leave, closed days) with their
-- services and an event log, customer strikes, pauses, walk-in access, and the notifications queue.

-- Lets a GiST index compare plain values (=) next to ranges (&&): needed by the "no overlapping shifts" rule.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- What kind of shop it is, chosen at registration and never changed. Existing shops are kiranas.
ALTER TABLE shops ADD COLUMN type VARCHAR(10) NOT NULL DEFAULT 'KIRANA' CHECK (type IN ('KIRANA', 'SALON'));
ALTER TABLE shops_aud ADD COLUMN type VARCHAR(10);

-- Registration step 2 (kirana and salon): the owner's photo and the shop's photo, both needed before Submit for
-- visit. Customers see the shop's photo.
ALTER TABLE shops ADD COLUMN owner_photo_file_id UUID REFERENCES files (id);
ALTER TABLE shops ADD COLUMN shop_photo_file_id UUID REFERENCES files (id);
ALTER TABLE shops_aud ADD COLUMN owner_photo_file_id UUID;
ALTER TABLE shops_aud ADD COLUMN shop_photo_file_id UUID;

-- A salon's barber can have his own sign-in: a BARBER member of the salon (linked from salon_staff.user_id).
ALTER TABLE shop_members DROP CONSTRAINT shop_members_role_check;
ALTER TABLE shop_members ADD CONSTRAINT shop_members_role_check CHECK (role IN ('OWNER', 'HELPER', 'BARBER'));

-- Online booking settings. No row yet = the defaults (30 minutes ahead, up to 7 days ahead).
CREATE TABLE salon_settings (
    shop_id         UUID         PRIMARY KEY REFERENCES shops (id),
    min_lead_min    INTEGER      NOT NULL CHECK (min_lead_min BETWEEN 0 AND 1440),   -- earliest booking, minutes from now
    max_days_ahead  INTEGER      NOT NULL CHECK (max_days_ahead BETWEEN 1 AND 30),  -- latest booking, days from today
    version         BIGINT       NOT NULL,                 -- optimistic locking: a stale edit is refused
    updated_at      TIMESTAMPTZ  NOT NULL
);

-- What the salon offers. Duration includes any clean-up time the salon wants to allow.
CREATE TABLE salon_services (
    id             UUID          PRIMARY KEY,
    shop_id        UUID          NOT NULL REFERENCES shops (id),
    name           VARCHAR(60)   NOT NULL,
    duration_min   INTEGER       NOT NULL CHECK (duration_min BETWEEN 5 AND 480 AND duration_min % 5 = 0),
    price_paise    BIGINT        NOT NULL CHECK (price_paise BETWEEN 0 AND 10000000),   -- ₹0 to ₹1,00,000
    price_is_from  BOOLEAN       NOT NULL,                 -- shown as "from ₹X" when the final price can vary
    active         BOOLEAN       NOT NULL,
    sort_order     INTEGER       NOT NULL CHECK (sort_order BETWEEN 0 AND 999),
    version        BIGINT        NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL,
    updated_at     TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ux_salon_services_id_shop UNIQUE (id, shop_id)   -- target of staff_services' same-salon key
);
-- Two active services can't share a name (Haircut and haircut count as the same).
CREATE UNIQUE INDEX ux_salon_services_name ON salon_services (shop_id, lower(name)) WHERE active;
CREATE INDEX ix_salon_services_shop ON salon_services (shop_id);

-- The salon's barbers. A barber is a name; user_id is his own sign-in when the owner linked his phone.
CREATE TABLE salon_staff (
    id          UUID          PRIMARY KEY,
    shop_id     UUID          NOT NULL REFERENCES shops (id),
    name        VARCHAR(60)   NOT NULL,
    user_id     UUID          UNIQUE REFERENCES users (id),
    photo_file_id UUID        REFERENCES files (id),          -- optional photo for the salon's screens
    active      BOOLEAN       NOT NULL,
    version     BIGINT        NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL,
    updated_at  TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ux_salon_staff_id_shop UNIQUE (id, shop_id)      -- target of staff_services' same-salon key
);
CREATE INDEX ix_salon_staff_shop ON salon_staff (shop_id);

-- Which barber does which service. Both foreign keys include shop_id, so the database itself refuses linking a
-- barber to another salon's service.
CREATE TABLE staff_services (
    id          UUID  PRIMARY KEY,
    shop_id     UUID  NOT NULL,
    staff_id    UUID  NOT NULL,
    service_id  UUID  NOT NULL,
    CONSTRAINT fk_staff_services_staff FOREIGN KEY (staff_id, shop_id) REFERENCES salon_staff (id, shop_id),
    CONSTRAINT fk_staff_services_service FOREIGN KEY (service_id, shop_id) REFERENCES salon_services (id, shop_id),
    CONSTRAINT ux_staff_services_pair UNIQUE (staff_id, service_id)
);

-- Weekly working hours in salon time (India). Two rows on one day = a split shift (10:00-14:00 and 16:00-21:00).
CREATE TABLE staff_hours (
    id         UUID     PRIMARY KEY,
    staff_id   UUID     NOT NULL REFERENCES salon_staff (id),
    weekday    INTEGER  NOT NULL CHECK (weekday BETWEEN 1 AND 7),                       -- ISO: 1 = Monday
    start_min  INTEGER  NOT NULL CHECK (start_min BETWEEN 0 AND 1425 AND start_min % 15 = 0), -- minutes after midnight
    end_min    INTEGER  NOT NULL CHECK (end_min BETWEEN 15 AND 1440 AND end_min % 15 = 0),    -- 1440 = midnight
    CHECK (end_min > start_min),
    -- One barber's shifts on the same day may touch (14:00 end, 14:00 start) but never overlap
    CONSTRAINT ex_staff_hours_no_overlap
        EXCLUDE USING gist (staff_id WITH =, weekday WITH =, int4range(start_min, end_min) WITH &&)
);

-- History tables: every version of every row. revtype: 0 created, 1 changed, 2 deleted.
CREATE TABLE salon_settings_aud (
    shop_id         UUID         NOT NULL,
    rev             BIGINT       NOT NULL REFERENCES revinfo (rev),
    revtype         SMALLINT,
    min_lead_min    INTEGER,
    max_days_ahead  INTEGER,
    updated_at      TIMESTAMPTZ,
    PRIMARY KEY (shop_id, rev)
);

CREATE TABLE salon_services_aud (
    id             UUID         NOT NULL,
    rev            BIGINT       NOT NULL REFERENCES revinfo (rev),
    revtype        SMALLINT,
    shop_id        UUID,
    name           VARCHAR(60),
    duration_min   INTEGER,
    price_paise    BIGINT,
    price_is_from  BOOLEAN,
    active         BOOLEAN,
    sort_order     INTEGER,
    created_at     TIMESTAMPTZ,
    updated_at     TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

CREATE TABLE salon_staff_aud (
    id          UUID         NOT NULL,
    rev         BIGINT       NOT NULL REFERENCES revinfo (rev),
    revtype     SMALLINT,
    shop_id     UUID,
    name        VARCHAR(60),
    user_id     UUID,
    photo_file_id UUID,
    active      BOOLEAN,
    created_at  TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

CREATE TABLE staff_services_aud (
    id          UUID      NOT NULL,
    rev         BIGINT    NOT NULL REFERENCES revinfo (rev),
    revtype     SMALLINT,
    shop_id     UUID,
    staff_id    UUID,
    service_id  UUID,
    PRIMARY KEY (id, rev)
);

CREATE TABLE staff_hours_aud (
    id         UUID      NOT NULL,
    rev        BIGINT    NOT NULL REFERENCES revinfo (rev),
    revtype    SMALLINT,
    staff_id   UUID,
    weekday    INTEGER,
    start_min  INTEGER,
    end_min    INTEGER,
    PRIMARY KEY (id, rev)
);

-- ---------------------------------------------------------------------------------------------------------------
-- Daily work. Written with plain SQL (not Envers): every change is logged in appointment_events instead.
-- ---------------------------------------------------------------------------------------------------------------

-- Bookings, walk-ins and blocks (break, leave, closed day) in one table, so one rule stops any two of them
-- overlapping for the same barber. Times are exact moments (UTC); the app shows them in India time.
CREATE TABLE appointments (
    id                 UUID          PRIMARY KEY,
    shop_id            UUID          NOT NULL REFERENCES shops (id),
    staff_id           UUID          NOT NULL,
    kind               VARCHAR(10)   NOT NULL CHECK (kind IN ('BOOKING', 'WALK_IN', 'BLOCK')),
    block_type         VARCHAR(10)   CHECK (block_type IN ('BREAK', 'LEAVE', 'CLOSED')),
    closure_id         UUID,                                   -- groups the CLOSED rows of one closed day
    customer_id        UUID          REFERENCES users (id),   -- bookings: who booked
    booking_no         VARCHAR(10)   UNIQUE,                   -- bookings: e.g. SB7K3Q9X
    booking_for        VARCHAR(60),                            -- bookings: optional "for" name
    guest_name         VARCHAR(60),                            -- walk-ins: optional name
    start_code         VARCHAR(4)    CHECK (start_code ~ '^[0-9]{4}$'),   -- bookings: shown only to the customer
    starts_at          TIMESTAMPTZ   NOT NULL,
    ends_at            TIMESTAMPTZ   NOT NULL,                 -- planned end; holds the barber until then
    -- Computed by the database for the overlap rule; the app never writes it
    time_range         TSTZRANGE     GENERATED ALWAYS AS (tstzrange(starts_at, ends_at, '[)')) STORED,
    status             VARCHAR(25)   NOT NULL CHECK (status IN ('CONFIRMED', 'IN_SERVICE', 'COMPLETED',
                           'CANCELLED_BY_CUSTOMER', 'CANCELLED_BY_SALON', 'NO_SHOW', 'BLOCKED', 'REMOVED')),
    started_at         TIMESTAMPTZ,
    started_with_code  BOOLEAN,                                -- bookings: false = "Start without code"
    cancel_reason      VARCHAR(200),
    created_by         UUID          NOT NULL REFERENCES users (id),
    client_request_id  UUID,                                   -- the Idempotency-Key it was made with
    created_at         TIMESTAMPTZ   NOT NULL,
    updated_at         TIMESTAMPTZ   NOT NULL,
    -- The barber must belong to the same salon
    CONSTRAINT fk_appointments_staff FOREIGN KEY (staff_id, shop_id) REFERENCES salon_staff (id, shop_id),
    CHECK (ends_at > starts_at),
    CHECK ((kind = 'BOOKING') = (customer_id IS NOT NULL)),
    CHECK ((kind = 'BOOKING') = (booking_no IS NOT NULL)),
    CHECK ((kind = 'BOOKING') = (start_code IS NOT NULL)),
    CHECK ((kind = 'BLOCK') = (block_type IS NOT NULL)),
    CHECK ((block_type IS NOT DISTINCT FROM 'CLOSED') = (closure_id IS NOT NULL)),
    CHECK ((kind = 'BLOCK') = (status IN ('BLOCKED', 'REMOVED'))),
    -- THE rule: while it holds his time (CONFIRMED, IN_SERVICE, BLOCKED), nothing else of the same barber may
    -- overlap it. Two bookings racing for the last free barber: the database lets exactly one through.
    CONSTRAINT ex_appointments_no_overlap EXCLUDE USING gist (staff_id WITH =, time_range WITH &&)
        WHERE (status IN ('CONFIRMED', 'IN_SERVICE', 'BLOCKED'))
);
-- One appointment per Idempotency-Key per person: a retried request finds the first one instead of making another
CREATE UNIQUE INDEX ux_appointments_request ON appointments (created_by, client_request_id)
    WHERE client_request_id IS NOT NULL;
CREATE INDEX ix_appointments_shop_time ON appointments (shop_id, starts_at);
CREATE INDEX ix_appointments_customer ON appointments (customer_id, starts_at) WHERE customer_id IS NOT NULL;
CREATE INDEX ix_appointments_in_service ON appointments (ends_at) WHERE status = 'IN_SERVICE';
CREATE INDEX ix_appointments_closure ON appointments (closure_id) WHERE closure_id IS NOT NULL;

-- The services of a booking or walk-in, copied when it was made (later price or length changes don't touch it)
CREATE TABLE appointment_services (
    appointment_id  UUID          NOT NULL REFERENCES appointments (id),
    position        INTEGER       NOT NULL,                    -- the order they were chosen in
    service_id      UUID          NOT NULL REFERENCES salon_services (id),
    name            VARCHAR(60)   NOT NULL,
    duration_min    INTEGER       NOT NULL,
    price_paise     BIGINT        NOT NULL,
    price_is_from   BOOLEAN       NOT NULL,
    PRIMARY KEY (appointment_id, position)
);

-- Everything that happened to an appointment, and who did it (by_user empty = the automatic end)
CREATE TABLE appointment_events (
    id              BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    appointment_id  UUID          NOT NULL REFERENCES appointments (id),
    happened_at     TIMESTAMPTZ   NOT NULL,
    by_user         UUID          REFERENCES users (id),
    event           VARCHAR(30)   NOT NULL,                    -- BOOKED, STARTED, NO_SHOW, COMPLETED, ...
    detail          VARCHAR(200)
);
CREATE INDEX ix_appointment_events_appointment ON appointment_events (appointment_id);

-- No-shows and late cancels. 3 within 60 days stop online booking for 30 days.
CREATE TABLE customer_strikes (
    id              UUID          PRIMARY KEY,
    customer_id     UUID          NOT NULL REFERENCES users (id),
    appointment_id  UUID          NOT NULL UNIQUE REFERENCES appointments (id),   -- one strike per booking
    kind            VARCHAR(15)   NOT NULL CHECK (kind IN ('NO_SHOW', 'LATE_CANCEL')),
    struck_at       TIMESTAMPTZ   NOT NULL
);
CREATE INDEX ix_customer_strikes_customer ON customer_strikes (customer_id, struck_at);

-- Online booking paused until a moment (no row, or a past moment = not paused)
CREATE TABLE salon_pauses (
    shop_id       UUID          PRIMARY KEY REFERENCES shops (id),
    paused_until  TIMESTAMPTZ   NOT NULL,
    updated_by    UUID          NOT NULL REFERENCES users (id),
    updated_at    TIMESTAMPTZ   NOT NULL
);

-- Who besides the owner may add walk-ins (a helper or barber the owner chose)
CREATE TABLE walk_in_access (
    shop_id     UUID          NOT NULL REFERENCES shops (id),
    user_id     UUID          NOT NULL REFERENCES users (id),
    granted_by  UUID          NOT NULL REFERENCES users (id),
    granted_at  TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (shop_id, user_id)
);

-- ---------------------------------------------------------------------------------------------------------------
-- Notifications (Web Push, and SMS for types with a template). A message is a row written in the same transaction
-- as the change it reports; a job sends due rows afterwards and retries failures.
-- ---------------------------------------------------------------------------------------------------------------
CREATE TABLE notification_jobs (
    id           UUID          PRIMARY KEY,
    user_id      UUID          NOT NULL REFERENCES users (id),
    channel      VARCHAR(5)    NOT NULL CHECK (channel IN ('PUSH', 'SMS')),   -- each channel its own row
    type         VARCHAR(30)   NOT NULL,                       -- BOOKING_CONFIRMED, BOOKING_REMINDER, ...
    ref_id       UUID,                                         -- what it is about (a booking), to cancel it
    title        VARCHAR(100)  NOT NULL,
    body         VARCHAR(500)  NOT NULL,
    url          VARCHAR(300),
    sms_variables VARCHAR(1000),                               -- SMS only: the template's variables
    send_at      TIMESTAMPTZ   NOT NULL,
    status       VARCHAR(10)   NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'CANCELLED', 'NO_DEVICE')),
    attempts     INTEGER       NOT NULL,
    next_try_at  TIMESTAMPTZ   NOT NULL,                       -- when the sender may take it next
    created_at   TIMESTAMPTZ   NOT NULL,
    finished_at  TIMESTAMPTZ
);
CREATE INDEX ix_notification_jobs_due ON notification_jobs (next_try_at) WHERE status = 'PENDING';
CREATE INDEX ix_notification_jobs_ref ON notification_jobs (ref_id) WHERE status = 'PENDING';

-- Devices that allowed notifications. The endpoint is a secret address at the browser's push service.
CREATE TABLE push_subscriptions (
    id          UUID           PRIMARY KEY,
    user_id     UUID           NOT NULL REFERENCES users (id),
    endpoint    VARCHAR(1000)  NOT NULL UNIQUE,
    p256dh      VARCHAR(100)   NOT NULL,                       -- the device's public key (base64url)
    auth        VARCHAR(50)    NOT NULL,                       -- the device's secret (base64url)
    created_at  TIMESTAMPTZ    NOT NULL
);
CREATE INDEX ix_push_subscriptions_user ON push_subscriptions (user_id);
