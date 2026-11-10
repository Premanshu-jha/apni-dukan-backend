-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- Every change below to an existing table is a change of its description only (nullable columns, a constant default,
-- checks and a key added NOT VALID): no row is read or rewritten. The checks and the key are validated in V36, the new
-- index on shops is built CONCURRENTLY in V35. A lock taken in a migration is held until it commits, so the new tables
-- (without keys to existing tables) and the backfill come first, and everything that locks a busy existing table
-- (shops, salon_services, revinfo, which every audited write inserts into) comes last, just before the commit: those
-- locks are then held for a moment, not through the backfill.

-- ============ 1. Ratings ============
--
-- A customer rates a completed online booking (not a walk-in: it has no customer account), once, and may change the
-- rating until 14 days after the visit (RatingService). Stars 1-5; tags is a set of what went well, one bit each
-- (RatingTag): 1 on time, 2 clean, 4 friendly, 8 skilled, 16 good value. The comment is for the salon only.
-- No foreign key to appointments (see V33: it would lock the busiest table); the appointment's rows are never deleted.
CREATE TABLE salon_ratings (
    appointment_id  UUID          PRIMARY KEY,
    shop_id         UUID          NOT NULL,
    customer_id     UUID          NOT NULL,
    stars           SMALLINT      NOT NULL CHECK (stars BETWEEN 1 AND 5),
    tags            SMALLINT      NOT NULL CHECK (tags BETWEEN 0 AND 31),
    comment         VARCHAR(300),
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL
);
-- The salon's list, newest first, a page at a time.
CREATE INDEX ix_salon_ratings_shop ON salon_ratings (shop_id, created_at DESC, appointment_id DESC);
-- A deleted account's comments.
CREATE INDEX ix_salon_ratings_customer ON salon_ratings (customer_id);

-- Running totals per salon, changed in the same transaction as each rating, so the salon list shows an average for 30
-- salons with one indexed read each instead of reading every rating.
CREATE TABLE salon_rating_totals (
    shop_id         UUID     PRIMARY KEY,
    ratings         INTEGER  NOT NULL CHECK (ratings >= 0),
    stars_sum       BIGINT   NOT NULL CHECK (stars_sum >= 0),
    on_time         INTEGER  NOT NULL CHECK (on_time >= 0),
    clean           INTEGER  NOT NULL CHECK (clean >= 0),
    friendly        INTEGER  NOT NULL CHECK (friendly >= 0),
    skilled         INTEGER  NOT NULL CHECK (skilled >= 0),
    good_value      INTEGER  NOT NULL CHECK (good_value >= 0)
);

-- ============ 2. After a visit: the rating request and the come-back reminder ============
--
-- One row per completed online booking, written in the same statement that completes it (the automatic end, or
-- Finish). VisitFollowUpJob sends:
--   prompt_at: "How was your visit?" (a few minutes after the end), unless the customer rated it already;
--   remind_at: "Time for your next haircut?" at 10:00 India time, return_after_days after the visit day (the
--              shortest interval of its services; none if all are 0), unless the customer switched the reminders
--              off, has an upcoming booking, visited a salon again since, or the salon or its services are gone.
-- Each is set to NULL once handled. No foreign keys: appointments (V33) and users are never deleted.
CREATE TABLE visit_follow_ups (
    appointment_id  UUID          PRIMARY KEY,
    customer_id     UUID          NOT NULL,
    shop_id         UUID          NOT NULL,
    completed_at    TIMESTAMPTZ   NOT NULL,
    prompt_at       TIMESTAMPTZ,
    remind_at       TIMESTAMPTZ
);
CREATE INDEX ix_visit_follow_ups_prompt ON visit_follow_ups (prompt_at) WHERE prompt_at IS NOT NULL;
CREATE INDEX ix_visit_follow_ups_remind ON visit_follow_ups (remind_at) WHERE remind_at IS NOT NULL;
-- "Visited again since?", and a deleted account's rows.
CREATE INDEX ix_visit_follow_ups_customer ON visit_follow_ups (customer_id, completed_at);

-- A customer's choice about the come-back reminders. No row: on (the default).
CREATE TABLE salon_customer_prefs (
    user_id              UUID         PRIMARY KEY,
    come_back_reminders  BOOLEAN      NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL
);

-- ============ 3. The salon's opening hours ============
--
-- One row per weekday the salon is open (ISO: 1 = Monday); a weekday without a row is its weekly off. One block a
-- day, on 15-minute steps, stored like staff_hours as minutes after midnight (1440 = midnight). Every barber's hours
-- must lie inside them (SalonSetupService); festival holidays are closed days (closures), as before.
-- No row at all: the salon hasn't set its hours yet (registered before this version without any barber hours).
CREATE TABLE salon_hours (
    id          UUID         PRIMARY KEY,
    shop_id     UUID         NOT NULL,   -- its key to shops is added at the end (section 6)
    weekday     INTEGER      NOT NULL CHECK (weekday BETWEEN 1 AND 7),
    open_min    INTEGER      NOT NULL CHECK (open_min BETWEEN 0 AND 1425 AND open_min % 15 = 0),
    close_min   INTEGER      NOT NULL CHECK (close_min BETWEEN 15 AND 1440 AND close_min % 15 = 0),
    updated_at  TIMESTAMPTZ  NOT NULL,
    CHECK (close_min > open_min),
    CONSTRAINT ux_salon_hours_day UNIQUE (shop_id, weekday)
);

CREATE TABLE salon_hours_aud (
    id          UUID         NOT NULL,
    rev         BIGINT       NOT NULL,   -- its key to revinfo is added at the end (section 6)
    revtype     SMALLINT,
    shop_id     UUID,
    weekday     INTEGER,
    open_min    INTEGER,
    close_min   INTEGER,
    updated_at  TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

-- Salons registered before this version: each weekday from the earliest start to the latest end of its working
-- barbers' hours, so customers see the days and hours they can actually book. Every working barber's hours are then
-- inside the salon's, as the new rule wants; a switched-off barber's are cut to fit when he is switched on again
-- (SalonSetupService.updateStaff). A salon whose working barbers have no hours yet gets none (it sets them).
INSERT INTO salon_hours (id, shop_id, weekday, open_min, close_min, updated_at)
SELECT gen_random_uuid(), st.shop_id, h.weekday, min(h.start_min), max(h.end_min), now()
FROM staff_hours h JOIN salon_staff st ON st.id = h.staff_id
WHERE st.active
GROUP BY st.shop_id, h.weekday;

-- ============ 3b. The salon's closed days (festival holidays) ============
--
-- A holiday belongs to the salon, not to its barbers: one row per closed day (its id is the closure id the barbers'
-- CLOSED blocks carry, so the booking rules stay as they are). It can be set before any barber exists (during
-- registration); every barber working that day, and any added or switched on later, gets a CLOSED block for it
-- (SalonDayService.addClosure, SalonSetupService.joinClosedDays). Removing the day removes the row and the blocks.
-- The weekly off is the salon's too (a weekday without salon_hours); leave and breaks stay per barber.
CREATE TABLE salon_closed_days (
    id          UUID          PRIMARY KEY,
    shop_id     UUID          NOT NULL,   -- its key to shops is added at the end (section 6)
    day         DATE          NOT NULL,
    starts_at   TIMESTAMPTZ   NOT NULL,   -- the day's first moment, India time
    ends_at     TIMESTAMPTZ   NOT NULL,
    created_by  UUID          NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL,
    CHECK (ends_at > starts_at),
    CONSTRAINT ux_salon_closed_days_day UNIQUE (shop_id, day)
);
-- Upcoming closed days of a salon (the list, the cards, a barber joining)
CREATE INDEX ix_salon_closed_days_shop ON salon_closed_days (shop_id, ends_at);

-- The closed days made before this version, from their barbers' CLOSED blocks (one row per closure id)
INSERT INTO salon_closed_days (id, shop_id, day, starts_at, ends_at, created_by, created_at)
SELECT a.closure_id, a.shop_id, date(min(a.starts_at) AT TIME ZONE 'Asia/Kolkata'), min(a.starts_at), min(a.ends_at),
       (array_agg(a.created_by ORDER BY a.created_at))[1], min(a.created_at)
FROM appointments a
WHERE a.closure_id IS NOT NULL AND a.status = 'BLOCKED'
GROUP BY a.closure_id, a.shop_id
ON CONFLICT DO NOTHING;
-- Two closure ids for one salon and day (not possible under the old rules, but cheap to rule out): the second one's
-- blocks join the day that was kept, so opening that day again removes them all
UPDATE appointments a SET closure_id = d.id
FROM salon_closed_days d
WHERE a.closure_id IS NOT NULL AND a.status = 'BLOCKED' AND a.shop_id = d.shop_id
  AND date(a.starts_at AT TIME ZONE 'Asia/Kolkata') = d.day AND a.closure_id <> d.id;

-- ============ 4. The salon's registration document, and removing a barber ============
--
-- One document a salon may add (optional: many salons aren't registered): its Shops & Establishments registration
-- certificate, its trade licence or its Udyam certificate, as a photo or a PDF (a REG_DOC upload). The owner can add,
-- replace or remove it at any time; the salon stays live meanwhile. registration_doc_checked_at: when the field team
-- checked this document (set when it approves the salon after a visit, or with "Mark as checked"); a new document
-- clears it. Customers see "Registered" only while a document is there and checked, else "Unregistered". Nullable:
-- kiranas and salons without one.
ALTER TABLE shops ADD COLUMN registration_doc_type VARCHAR(20);
ALTER TABLE shops ADD COLUMN registration_doc_file_id UUID;
ALTER TABLE shops ADD COLUMN registration_doc_checked_at TIMESTAMPTZ;
ALTER TABLE shops ADD CONSTRAINT ck_shops_registration_doc_checked
    CHECK (registration_doc_checked_at IS NULL OR registration_doc_file_id IS NOT NULL) NOT VALID;
ALTER TABLE shops ADD CONSTRAINT ck_shops_registration_doc
    CHECK ((registration_doc_type IS NULL) = (registration_doc_file_id IS NULL)
           AND (registration_doc_type IS NULL
                OR registration_doc_type IN ('SHOPS_ESTABLISHMENT', 'TRADE_LICENCE', 'UDYAM'))) NOT VALID;
ALTER TABLE shops ADD CONSTRAINT fk_shops_registration_doc FOREIGN KEY (registration_doc_file_id)
    REFERENCES files (id) NOT VALID;
ALTER TABLE shops_aud ADD COLUMN registration_doc_type VARCHAR(20);
ALTER TABLE shops_aud ADD COLUMN registration_doc_file_id UUID;
ALTER TABLE shops_aud ADD COLUMN registration_doc_checked_at TIMESTAMPTZ;

-- A barber the owner removed for good (a contract barber who won't come back): switched off, gone from the salon's
-- lists and can't be switched on or edited again; his past bookings keep his name. Null: not removed. Switching off
-- (active = false) stays the way to pause a barber who comes and goes.
ALTER TABLE salon_staff ADD COLUMN removed_at TIMESTAMPTZ;
ALTER TABLE salon_staff ADD CONSTRAINT ck_salon_staff_removed CHECK (removed_at IS NULL OR NOT active) NOT VALID;
ALTER TABLE salon_staff_aud ADD COLUMN removed_at TIMESTAMPTZ;

-- ============ 5. Come-back reminder interval per service ============
--
-- How many days after a visit the customer is asked whether they'd like the same service again (VisitFollowUpJob):
-- 1 to 180, or 0 for no reminder for this service (e.g. a hair wash). A visit with several services uses the shortest
-- interval among them that isn't 0. Every existing service gets 14 days (a constant default: no rewrite).
ALTER TABLE salon_services ADD COLUMN return_after_days INTEGER NOT NULL DEFAULT 14;
ALTER TABLE salon_services ADD CONSTRAINT ck_salon_services_return_after_days
    CHECK (return_after_days BETWEEN 0 AND 180) NOT VALID;
ALTER TABLE salon_services_aud ADD COLUMN return_after_days INTEGER;

-- ============ 6. The new tables' keys to existing tables ============
--
-- Last, for the reason at the top. salon_hours is new and small (the backfill), so checking its rows is quick.
ALTER TABLE salon_hours ADD CONSTRAINT fk_salon_hours_shop FOREIGN KEY (shop_id) REFERENCES shops (id);
ALTER TABLE salon_closed_days ADD CONSTRAINT fk_salon_closed_days_shop FOREIGN KEY (shop_id) REFERENCES shops (id);
ALTER TABLE salon_hours_aud ADD CONSTRAINT fk_salon_hours_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev);
