-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- ============ A customer's saved places (Home, Work, others) ============
--
-- The customer app finds salons near the phone's GPS point or near one of these. Each is set like a salon's address
-- at registration: the four address fields (filled from GPS or typed), then a pin on the map the customer can drag.
-- Only the customer sees them; salons never do. At most 10 a person (PlaceService.MAX_PLACES, checked under a lock),
-- one Home and one Work (the index below), any number of others with a name of their own.
--
-- A new table: its key to users only takes a short lock on users (it has no rows to check).
-- Deleting the account deletes the person's places (PlacesAccountDeletion).
CREATE TABLE customer_places (
    id           UUID          PRIMARY KEY,
    user_id      UUID          NOT NULL REFERENCES users (id),
    kind         TEXT          NOT NULL CHECK (kind IN ('HOME', 'WORK', 'OTHER')),
    -- Only for OTHER ("Mom's place"): Home and Work are named by their kind
    name         TEXT          CHECK (char_length(name) BETWEEN 2 AND 30),
    address_line TEXT          NOT NULL CHECK (char_length(address_line) BETWEEN 2 AND 200),
    area         TEXT          NOT NULL CHECK (char_length(area) BETWEEN 2 AND 100),
    city         TEXT          NOT NULL CHECK (char_length(city) BETWEEN 2 AND 60),
    pincode      TEXT          NOT NULL CHECK (pincode ~ '^[1-9][0-9]{5}$'),
    -- Inside India, as for a shop's pin
    latitude     NUMERIC(9, 6) NOT NULL CHECK (latitude BETWEEN 6 AND 37.6),
    longitude    NUMERIC(9, 6) NOT NULL CHECK (longitude BETWEEN 68 AND 97.5),
    created_at   TIMESTAMPTZ   NOT NULL,
    updated_at   TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_customer_places_name_only_for_other CHECK ((kind = 'OTHER') = (name IS NOT NULL))
);

-- The person's list (and the count before adding one).
CREATE INDEX ix_customer_places_user ON customer_places (user_id, created_at);
-- One Home and one Work each.
CREATE UNIQUE INDEX ux_customer_places_home_work ON customer_places (user_id, kind) WHERE kind <> 'OTHER';
