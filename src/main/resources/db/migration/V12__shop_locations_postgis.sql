-- Finding salons (and later kirana shops) near a customer with PostGIS, the standard Postgres add-on for maps.
-- Each shop's pin becomes a geography point (kept in step by the database from latitude/longitude), and a map
-- index on it finds the shops within a distance and sorts them nearest first, without reading every shop.
-- PostGIS is available on Neon. The app never writes `location`.
CREATE EXTENSION IF NOT EXISTS postgis;

ALTER TABLE shops ADD COLUMN location geography(Point, 4326)
    GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(longitude::float8, latitude::float8), 4326)::geography) STORED;

-- Only approved shops are ever listed to customers
CREATE INDEX ix_shops_location_approved ON shops USING gist (location) WHERE status = 'APPROVED';
