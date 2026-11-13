-- On a live database: wait at most 3 s for a lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- Photos, videos and PDFs sent with support tickets belong to a person (a customer may have no shop): their files
-- row has no shop. Every other purpose still has one.
ALTER TABLE files ALTER COLUMN shop_id DROP NOT NULL;
-- Checked for new rows now; for the existing rows in V51 (as V26 and V28 did), so this short lock isn't held while
-- every row is read. Its index for the daily limit is built in V50 (writes to files go on meanwhile).
ALTER TABLE files ADD CONSTRAINT files_shop_unless_ticket CHECK (shop_id IS NOT NULL OR purpose = 'TICKET_FILE')
    NOT VALID;

-- A ticket video's upload link belongs to a person too (video_uploads is small: one row a salon video or ticket
-- video, so its index is built here).
ALTER TABLE video_uploads ALTER COLUMN shop_id DROP NOT NULL;
CREATE INDEX ix_video_uploads_person_created ON video_uploads (created_by, created_at) WHERE shop_id IS NULL;

-- Which files a ticket message carries (each file on one message only), in the order they were added. The type,
-- size and name are kept here too, so a ticket is shown without asking the files module about each file.
CREATE TABLE ticket_message_files (
    file_id       uuid          PRIMARY KEY,
    message_id    uuid          NOT NULL REFERENCES ticket_messages (id) ON DELETE CASCADE,
    ticket_id     uuid          NOT NULL REFERENCES tickets (id) ON DELETE CASCADE,
    position      smallint      NOT NULL,
    content_type  varchar(100)  NOT NULL,
    size_bytes    bigint        NOT NULL,
    name          varchar(255),
    removed_at    timestamptz                                  -- deleted (6 months after resolved, account deleted)
);
-- A ticket's files, by message, in order (every ticket shown); the message's cascade and the retention job's join.
CREATE INDEX ix_ticket_message_files_ticket ON ticket_message_files (ticket_id, message_id, position);
CREATE INDEX ix_ticket_message_files_message ON ticket_message_files (message_id);
