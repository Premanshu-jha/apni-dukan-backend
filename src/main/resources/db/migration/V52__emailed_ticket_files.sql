-- On a live database: wait at most 3 s for a lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- A photo, PDF or video that came with a support email belongs to the account it is from, or to nobody (an address
-- with no account). Every other file still has who uploaded it. The check is for new rows now and for the existing
-- rows in V53 (as V49 and V51 did), so this short lock isn't held while every row is read.
ALTER TABLE files ALTER COLUMN uploaded_by DROP NOT NULL;
ALTER TABLE files ADD CONSTRAINT files_uploader_unless_ticket CHECK (uploaded_by IS NOT NULL OR purpose = 'TICKET_FILE')
    NOT VALID;

-- An emailed file's fingerprint (sha-256 of it as it came, before a photo is cleaned): a reply quoting our email sends
-- its pasted photos again, and they are kept once per ticket. Null for the app's files. No default: no table rewrite.
ALTER TABLE ticket_message_files ADD COLUMN sha256 varchar(64);
