-- On a live database: wait at most 3 s for each table's lock (a copy of the backend still running holds them for its
-- writes). Without it, a migration queues behind those writes and every write after it queues behind the migration;
-- with it, the deploy fails fast and is simply run again (see "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- Bytes in R2 that must still be deleted. When a file's row goes, its storage key is written here in the same
-- transaction; after the commit the backend deletes the object and removes this row. If R2 fails or doesn't answer
-- then, the row stays and FileDeletionJob tries again later (waiting longer after each failure), so an object whose
-- row is gone is never forgotten. The same for an upload whose row couldn't be saved after its bytes were stored.
CREATE TABLE file_deletions (
    storage_key  VARCHAR(500)  PRIMARY KEY,
    queued_at    TIMESTAMPTZ   NOT NULL,
    attempts     INTEGER       NOT NULL DEFAULT 0,
    next_try_at  TIMESTAMPTZ   NOT NULL                    -- when the job may try it next
);
CREATE INDEX ix_file_deletions_due ON file_deletions (next_try_at);
