SET LOCAL lock_timeout = '3s';

-- Cities for the Leads page's "City or district" search: a city covers all areas of its districts (India Post files
-- Hyderabad under three: Hyderabad, Medchal Malkajgiri and Ranga Reddy). Read-only reference data, loaded from
-- db/data/india-cities.csv by R__India_places (with the pincodes, from db/data/india-pincodes.csv), which reloads both
-- whenever a CSV changes.
-- India Post's point for a pincode is often far off (Yapral 500087 is placed 54 km away): such a point is marked, and a
-- scan uses it only when Mapbox doesn't know the pincode, keeping only businesses that give this pincode
ALTER TABLE india_pincodes ADD COLUMN location_doubtful BOOLEAN NOT NULL DEFAULT false;

CREATE TABLE india_cities (
    name        VARCHAR(80)  NOT NULL,
    state       VARCHAR(80)  NOT NULL,
    -- other names people search it by, "; "-separated (Bangalore, Bombay, Gurgaon, ...)
    other_names VARCHAR(400) NOT NULL DEFAULT '',
    PRIMARY KEY (name, state)
);

CREATE TABLE india_city_districts (
    city           VARCHAR(80) NOT NULL,
    city_state     VARCHAR(80) NOT NULL,
    -- the order its areas are listed in: the city's own district first
    position       SMALLINT    NOT NULL,
    district       VARCHAR(80) NOT NULL,
    district_state VARCHAR(80) NOT NULL,
    PRIMARY KEY (city, city_state, district, district_state),
    FOREIGN KEY (city, city_state) REFERENCES india_cities (name, state) ON DELETE CASCADE
);
