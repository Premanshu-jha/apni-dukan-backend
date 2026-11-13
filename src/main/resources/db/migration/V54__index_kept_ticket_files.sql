-- Built CONCURRENTLY, without a transaction and with no lock timeout, for the reasons given in V24 and V50: writes go
-- on while it builds. If the build fails, see V24 and "Database changes" in the README (the DROP lets it run again).
SET lock_timeout = 0;

-- The files still kept, by ticket: the hourly retention job reads only these (removed rows pile up for ever), and
-- tickets resolved, by when (the job's other half).
DROP INDEX CONCURRENTLY IF EXISTS ix_ticket_message_files_kept;
CREATE INDEX CONCURRENTLY ix_ticket_message_files_kept ON ticket_message_files (ticket_id) WHERE removed_at IS NULL;
DROP INDEX CONCURRENTLY IF EXISTS ix_tickets_resolved_at;
CREATE INDEX CONCURRENTLY ix_tickets_resolved_at ON tickets (resolved_at) WHERE status = 'RESOLVED';

-- A sender's emails in the last hour (the flood limit, read for every email): by ticket and when, emails only.
DROP INDEX CONCURRENTLY IF EXISTS ix_ticket_messages_emails;
CREATE INDEX CONCURRENTLY ix_ticket_messages_emails ON ticket_messages (ticket_id, created_at)
    WHERE direction = 'IN' AND email_message_id IS NOT NULL;
