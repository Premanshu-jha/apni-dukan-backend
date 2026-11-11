-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- Salon offers: a discount the owner sets for some days (and, if he likes, some hours of each day), sent to the
-- salon's past customers when he publishes it. Bookings made inside it keep the offer price.
--
-- As in V34: the new tables come first, without keys to existing tables; everything that locks a busy existing table
-- (the keys to shops, users and salon_services, then salon_customer_prefs and appointment_services) comes last, just
-- before the commit, so those locks are held for a moment. Every change to an existing table is a change of its
-- description only (nullable columns, a constant default, checks and keys added NOT VALID): no row is read or
-- rewritten. V38 builds two indexes CONCURRENTLY; V39 validates the checks and keys.

-- ============ 1. The offers ============
CREATE TABLE salon_offers (
    id            UUID         PRIMARY KEY,
    shop_id       UUID         NOT NULL,              -- shops (key added below)
    percent_off   SMALLINT     NOT NULL CHECK (percent_off BETWEEN 5 AND 50),
    all_services  BOOLEAN      NOT NULL,              -- false: only the rows in salon_offer_services
    starts_on     DATE         NOT NULL,              -- India dates, both included
    ends_on       DATE         NOT NULL,
    from_min      INTEGER,                            -- each day from (minutes after midnight); NULL: all day
    to_min        INTEGER,                            -- each day until (exclusive)
    ended_at      TIMESTAMPTZ,                        -- the owner ended it early
    notified      INTEGER      NOT NULL CHECK (notified >= 0),   -- how many customers the message went to
    created_by    UUID         NOT NULL,              -- users (key added below)
    created_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_salon_offers_dates CHECK (ends_on >= starts_on),
    CONSTRAINT ck_salon_offers_hours CHECK ((from_min IS NULL) = (to_min IS NULL)
        AND (from_min IS NULL OR (from_min >= 0 AND to_min <= 1440 AND from_min < to_min
            AND from_min % 15 = 0 AND to_min % 15 = 0)))
);
-- The salon's offers still to come or running (the overlap check, cards, the setup in memory) and its list
CREATE INDEX ix_salon_offers_shop ON salon_offers (shop_id, ends_on);

CREATE TABLE salon_offer_services (
    offer_id    UUID  NOT NULL REFERENCES salon_offers (id),
    service_id  UUID  NOT NULL,                       -- salon_services (key added below)
    PRIMARY KEY (offer_id, service_id)
);

-- An offer published or ended moves the salon's setup_version (V17/V21), like a change to its services: the setup
-- kept in memory (SalonSetups) carries the offers, so a free-time check prices its starts without another read, and
-- every server sees a new or ended offer at its next read
CREATE TRIGGER tr_salon_offers_versions_ins AFTER INSERT ON salon_offers REFERENCING NEW TABLE AS new_rows
    FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_new('true');
CREATE TRIGGER tr_salon_offers_versions_upd AFTER UPDATE ON salon_offers REFERENCING NEW TABLE AS new_rows
    FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_new('true');
CREATE TRIGGER tr_salon_offers_versions_del AFTER DELETE ON salon_offers REFERENCING OLD TABLE AS old_rows
    FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_old('true');
CREATE CONSTRAINT TRIGGER tr_salon_offers_versions_flush AFTER INSERT OR UPDATE OR DELETE ON salon_offers
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION salon_versions_flush();

-- ============ 2. Customers who don't want offer messages ============
-- From one salon: a row here. From every salon: salon_customer_prefs.salon_offers = false (below).
CREATE TABLE salon_offer_mutes (
    user_id     UUID         NOT NULL,                -- users (key added below)
    shop_id     UUID         NOT NULL,                -- shops (key added below)
    created_at  TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (user_id, shop_id)
);

-- ============ 3. Existing tables, last ============
-- The new tables' keys first: each takes a short lock on shops, users or salon_services and waits (at most 3 s) for
-- writes in progress there, while nothing here holds up readers yet. Then the two changes that lock their table
-- against everything for a moment (no read can queue behind them for long: nothing is waited for after them).
-- NOT VALID: the new tables are empty; V39 validates them anyway
ALTER TABLE salon_offers ADD CONSTRAINT fk_salon_offers_shop FOREIGN KEY (shop_id) REFERENCES shops (id) NOT VALID;
ALTER TABLE salon_offers ADD CONSTRAINT fk_salon_offers_by FOREIGN KEY (created_by) REFERENCES users (id) NOT VALID;
ALTER TABLE salon_offer_services ADD CONSTRAINT fk_salon_offer_services_service
    FOREIGN KEY (service_id) REFERENCES salon_services (id) NOT VALID;
ALTER TABLE salon_offer_mutes ADD CONSTRAINT fk_salon_offer_mutes_user
    FOREIGN KEY (user_id) REFERENCES users (id) NOT VALID;
ALTER TABLE salon_offer_mutes ADD CONSTRAINT fk_salon_offer_mutes_shop
    FOREIGN KEY (shop_id) REFERENCES shops (id) NOT VALID;

ALTER TABLE salon_customer_prefs ADD COLUMN salon_offers BOOLEAN NOT NULL DEFAULT true;

-- The offer a booked service was priced with. list_price_paise: the price before the offer (price_paise is what the
-- customer pays); both NULL without an offer
ALTER TABLE appointment_services ADD COLUMN offer_id UUID;
ALTER TABLE appointment_services ADD COLUMN list_price_paise BIGINT;
ALTER TABLE appointment_services ADD CONSTRAINT ck_appointment_services_offer
    CHECK ((offer_id IS NULL) = (list_price_paise IS NULL)) NOT VALID;
ALTER TABLE appointment_services ADD CONSTRAINT fk_appointment_services_offer
    FOREIGN KEY (offer_id) REFERENCES salon_offers (id) NOT VALID;
