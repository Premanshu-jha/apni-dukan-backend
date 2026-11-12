-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- ============ Support people ============
-- A new designation for an ADMIN: SUPPORT answers the support tickets and nothing else (no pincodes, no businesses).
-- The new check only allows more than the old one, so every row already passes; VALIDATE reads the users table
-- (small) without blocking writes.
ALTER TABLE users DROP CONSTRAINT users_admin_designation_check;
ALTER TABLE users ADD CONSTRAINT users_admin_designation_check
    CHECK (admin_designation IN ('FIELD_AGENT', 'SUPPORT')) NOT VALID;
ALTER TABLE users VALIDATE CONSTRAINT users_admin_designation_check;

-- ============ Support tickets ============
-- One per question someone sent: by email to support@clozest.in (Cloudflare's Email Worker hands it to the backend)
-- or from "Need help" in the customer or partner app. Given to the support person with the fewest open tickets (or
-- nobody: Unassigned, which super admins see). New tables: nothing existing is rewritten.
CREATE TABLE tickets (
    id               UUID          PRIMARY KEY,
    number           BIGINT        GENERATED ALWAYS AS IDENTITY (START WITH 1001) UNIQUE,   -- shown as CLZ-1001
    source           VARCHAR(10)   NOT NULL CHECK (source IN ('EMAIL', 'CUSTOMER', 'PARTNER')),
    status           VARCHAR(10)   NOT NULL CHECK (status IN ('OPEN', 'WAITING', 'RESOLVED')),
    subject          VARCHAR(200)  NOT NULL,
    -- Who asked: their account when we know it (an app ticket, or an email from a verified address), else null
    requester_id     UUID          REFERENCES users (id),
    requester_email  VARCHAR(254),                            -- where our replies go; null once they deleted their account
    requester_name   VARCHAR(100),                            -- the email's display name for an unknown sender
    assignee_id      UUID          REFERENCES users (id),     -- null = Unassigned
    -- False when the first email's sender couldn't be proven (no DMARC pass from Cloudflare): the From may be forged
    sender_verified  BOOLEAN       NOT NULL DEFAULT true,
    created_at       TIMESTAMPTZ   NOT NULL,
    updated_at       TIMESTAMPTZ   NOT NULL,
    last_message_at  TIMESTAMPTZ   NOT NULL,                  -- the inbox's order
    resolved_at      TIMESTAMPTZ,
    version          BIGINT        NOT NULL DEFAULT 0         -- a stale reply or reassignment is refused
);
-- A support person's open tickets (counting them for the next assignment, the menu's count)
CREATE INDEX ix_tickets_assignee_open ON tickets (assignee_id, status) WHERE status <> 'RESOLVED';
-- One person's list by status, newest activity first (keyset paging on last_message_at, id)
CREATE INDEX ix_tickets_assignee_status ON tickets (assignee_id, status, last_message_at DESC, id);
-- The lists by status (super admins), newest activity first
CREATE INDEX ix_tickets_status ON tickets (status, last_message_at DESC, id);
-- A sender's tickets: the hourly limit, and the account's own list
CREATE INDEX ix_tickets_requester ON tickets (requester_id, created_at) WHERE requester_id IS NOT NULL;
CREATE INDEX ix_tickets_requester_email ON tickets (lower(requester_email), created_at);

-- The conversation: what the person wrote (IN) and our replies (OUT), in order
CREATE TABLE ticket_messages (
    id                UUID          PRIMARY KEY,
    seq               BIGINT        GENERATED ALWAYS AS IDENTITY,
    ticket_id         UUID          NOT NULL REFERENCES tickets (id) ON DELETE CASCADE,
    direction         VARCHAR(3)    NOT NULL CHECK (direction IN ('IN', 'OUT')),
    author_id         UUID          REFERENCES users (id),    -- the support person (OUT), or the person if known (IN)
    body              TEXT          NOT NULL CHECK (length(body) <= 20000),
    attachments       INTEGER       NOT NULL DEFAULT 0,       -- an email's attachments (kept in the Gmail copy only)
    -- sha-256 of the sender and the email's Message-ID: the same email twice is one (one sender can't block another's)
    email_message_id  VARCHAR(64)   UNIQUE,
    sender_verified   BOOLEAN       NOT NULL DEFAULT true,    -- see tickets.sender_verified
    created_at        TIMESTAMPTZ   NOT NULL
);
CREATE INDEX ix_ticket_messages_ticket ON ticket_messages (ticket_id, seq);

-- What happened to a ticket and who did it: created, assigned, reassigned, profile viewed, replied, resolved, reopened
CREATE TABLE ticket_events (
    id         BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id  UUID          NOT NULL REFERENCES tickets (id) ON DELETE CASCADE,
    actor_id   UUID          REFERENCES users (id),           -- null = the system (assignment, an email arriving)
    event      VARCHAR(30)   NOT NULL,
    detail     VARCHAR(300),
    at         TIMESTAMPTZ   NOT NULL
);
CREATE INDEX ix_ticket_events_ticket ON ticket_events (ticket_id, id);

-- Emails to send (the acknowledgement, a reply): written with the change in its transaction, sent by
-- TicketEmailJob afterwards, tried again after 1, 5 and 15 minutes, then FAILED (shown on the ticket)
CREATE TABLE ticket_emails (
    id             UUID          PRIMARY KEY,
    ticket_id      UUID          NOT NULL REFERENCES tickets (id) ON DELETE CASCADE,
    to_address     VARCHAR(254)  NOT NULL,
    subject        VARCHAR(250)  NOT NULL,
    body           TEXT          NOT NULL,
    status         VARCHAR(10)   NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'CANCELLED')),
    attempts       INTEGER       NOT NULL DEFAULT 0,
    next_try_at    TIMESTAMPTZ   NOT NULL,
    claimed_until  TIMESTAMPTZ,                               -- a job copy is sending it until then
    sent_at        TIMESTAMPTZ,
    created_at     TIMESTAMPTZ   NOT NULL
);
CREATE INDEX ix_ticket_emails_due ON ticket_emails (next_try_at) WHERE status = 'PENDING';
-- The acknowledgements sent in the last hour (a global cap against floods of forged senders)
CREATE INDEX ix_ticket_emails_created ON ticket_emails (created_at);
CREATE INDEX ix_ticket_emails_ticket ON ticket_emails (ticket_id);
