-- On a live database: wait at most 3 s for the table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- "My requests": how many messages someone wrote in the last hour (20 at most) is counted on every message added
-- from the apps, under the helpdesk lock. Without this index that count read every message ever written.
CREATE INDEX ix_ticket_messages_author_in ON ticket_messages (author_id, created_at) WHERE direction = 'IN';
