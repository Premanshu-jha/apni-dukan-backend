-- Sign-in cleanup at scale. Every refresh ends one token (ROTATED) and adds a new one, so ROTATED rows are most of
-- refresh_tokens. They are kept only while they can still raise the "copied token" alarm: while the token that
-- replaced them is still in use. replaced_by links a rotated token to that successor (NULL on rows rotated before
-- this migration, which keep the old 30-day rule).
ALTER TABLE refresh_tokens ADD COLUMN replaced_by UUID;

-- Message cleanup: finished SMS rows are kept a few days only (they are never shown in the app's list), so they are
-- found by their own small index instead of scanning every finished Web Push row of the last 90 days.
-- (Plain CREATE INDEX: the table is still small; on a large table this would be CREATE INDEX CONCURRENTLY.)
CREATE INDEX ix_notification_jobs_sms_finished ON notification_jobs (finished_at)
    WHERE status <> 'PENDING' AND channel = 'SMS';
