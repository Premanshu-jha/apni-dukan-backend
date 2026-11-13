-- Built CONCURRENTLY, without a transaction and with no lock timeout, for the reasons given in V24: writes to files
-- (every shop photo, bill and receipt) go on while it builds. If the build fails, see V24 and "Database changes" in
-- the README (the DROP lets it run again).
SET lock_timeout = 0;

-- One person's ticket files today (the daily limit of 30, TicketFileService).
DROP INDEX CONCURRENTLY IF EXISTS ix_files_ticket_uploader;
CREATE INDEX CONCURRENTLY ix_files_ticket_uploader ON files (uploaded_by, created_at) WHERE purpose = 'TICKET_FILE';
