-- Message cleanup: finished SMS rows are kept a few days only (they are never shown in the app's list), so they are
-- found by their own small index instead of scanning every finished Web Push row of the last 90 days.
-- (Plain CREATE INDEX: the table is still small; on a large table this would be CREATE INDEX CONCURRENTLY.)
CREATE INDEX ix_notification_jobs_sms_finished ON notification_jobs (finished_at)
    WHERE status <> 'PENDING' AND channel = 'SMS';
