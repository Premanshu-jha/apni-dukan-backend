SET LOCAL lock_timeout = '3s';

-- The admin website's Leads page: salons and beauty businesses found on Mapbox, and the team's work on each.
-- Mapbox's search results may only be used while showing them, never kept, so a lead keeps only our own work:
-- Mapbox's id for the place, its pincode, and what the team did. The name, phone and point are kept only once someone
-- on the team has tried to reach the business (any status but NEW): from then on it is the team's own contact record.
--   status: NEW (not contacted yet; kept only for a note or an assignment), NO_ANSWER (tried, nobody answered: not
--           counted as contacted), CALLED, VISITED, INTERESTED, NOT_INTERESTED. "Signed up" is not a status: it is
--           shop_id, set when the business registers.
--   contacted_at: when someone first tried to reach it (the first status past NEW); a salon counts as signed up
--           from this lead only if it registered after this.
--   shop_id/signed_up_at/credited_to: the Clozest salon this lead became, when it registered, and who gets the credit
--           (the person assigned, else the last person who worked on it). One lead per salon.
--   whatsapp_ok: the owner agreed to get WhatsApp messages from Clozest; when and who recorded it.
--   version: for saving: a change made on old data answers 409 LEAD_CHANGED.
CREATE TABLE leads (
    mapbox_id       VARCHAR(200) PRIMARY KEY,
    pincode         VARCHAR(6)   NOT NULL CHECK (pincode ~ '^[1-9][0-9]{5}$'),
    status          VARCHAR(16)  NOT NULL DEFAULT 'NEW'
                        CHECK (status IN ('NEW', 'NO_ANSWER', 'CALLED', 'VISITED', 'INTERESTED', 'NOT_INTERESTED')),
    name            VARCHAR(150),
    phone           VARCHAR(10)  CHECK (phone ~ '^[6-9][0-9]{9}$'),
    latitude        NUMERIC(9,6) CHECK (latitude BETWEEN 6 AND 37.6),
    longitude       NUMERIC(9,6) CHECK (longitude BETWEEN 68 AND 97.5),
    note            VARCHAR(500) NOT NULL DEFAULT '',
    assigned_to     UUID         REFERENCES users (id) ON DELETE SET NULL,
    follow_up_on    DATE,
    contacted_at    TIMESTAMPTZ,
    whatsapp_ok     BOOLEAN      NOT NULL DEFAULT false,
    whatsapp_ok_at  TIMESTAMPTZ,
    whatsapp_ok_by  UUID         REFERENCES users (id) ON DELETE SET NULL,
    shop_id         UUID         REFERENCES shops (id) ON DELETE SET NULL,
    signed_up_at    TIMESTAMPTZ,
    credited_to     UUID         REFERENCES users (id) ON DELETE SET NULL,
    version         INTEGER      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by      UUID         REFERENCES users (id) ON DELETE SET NULL,
    -- Contact details only once contacted
    CHECK (status <> 'NEW' OR (name IS NULL AND phone IS NULL AND latitude IS NULL)),
    CHECK (status = 'NEW' OR contacted_at IS NOT NULL),
    CHECK (shop_id IS NULL OR (signed_up_at IS NOT NULL AND contacted_at IS NOT NULL)),
    CHECK ((latitude IS NULL) = (longitude IS NULL)),
    CHECK (whatsapp_ok = (whatsapp_ok_at IS NOT NULL))
);

-- An area's leads (the scan joins them in); a person's follow-ups; one lead per salon; the matching job's work list.
CREATE INDEX ix_leads_pincode ON leads (pincode);
CREATE INDEX ix_leads_follow_up ON leads (assigned_to, follow_up_on) WHERE follow_up_on IS NOT NULL;
CREATE UNIQUE INDEX ux_leads_shop ON leads (shop_id) WHERE shop_id IS NOT NULL;
CREATE INDEX ix_leads_unmatched ON leads (mapbox_id) WHERE shop_id IS NULL AND status <> 'NEW';
-- A person's own leads (My leads), newest first
CREATE INDEX ix_leads_assigned ON leads (assigned_to, updated_at DESC) WHERE assigned_to IS NOT NULL;

-- What changed on a lead, by whom: the lead's history on the page.
--   field: STATUS, NOTE, ASSIGNED, FOLLOW_UP, WHATSAPP, PHONE, SIGNED_UP.
CREATE TABLE lead_events (
    id          BIGSERIAL    PRIMARY KEY,
    mapbox_id   VARCHAR(200) NOT NULL REFERENCES leads (mapbox_id) ON DELETE CASCADE,
    at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    by_user     UUID         REFERENCES users (id) ON DELETE SET NULL,
    field       VARCHAR(10)  NOT NULL
                    CHECK (field IN ('STATUS', 'NOTE', 'ASSIGNED', 'FOLLOW_UP', 'WHATSAPP', 'PHONE', 'SIGNED_UP')),
    old_value   VARCHAR(500),
    new_value   VARCHAR(500)
);
CREATE INDEX ix_lead_events_lead ON lead_events (mapbox_id, at DESC);

-- Mapbox requests per month (India's calendar, yyyy-MM), counted before each request so the monthly cap
-- (MAPBOX_MONTHLY_CAP) holds when many scans run at once on several servers.
CREATE TABLE mapbox_usage (
    month     VARCHAR(7) PRIMARY KEY CHECK (month ~ '^[0-9]{4}-[0-9]{2}$'),
    requests  BIGINT     NOT NULL DEFAULT 0 CHECK (requests >= 0)
);
