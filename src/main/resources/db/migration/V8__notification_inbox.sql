-- The in-app message list ("inbox"): each message's PUSH row is also its entry in the list.
-- read_at = when the person opened it in the app (null = unread).
ALTER TABLE notification_jobs ADD COLUMN read_at TIMESTAMPTZ;

-- Whose details a message carries when it is sent to someone else (a salon told "Rahul booked"), so deleting
-- Rahul's account blanks those messages too.
ALTER TABLE notification_jobs ADD COLUMN about_user_id UUID;
CREATE INDEX ix_notification_jobs_about ON notification_jobs (about_user_id) WHERE about_user_id IS NOT NULL;

-- Newest first, per person, for the list.
CREATE INDEX ix_notification_jobs_inbox ON notification_jobs (user_id, send_at DESC, id DESC) WHERE channel = 'PUSH';

-- The bell's unread count reads only unread rows (a busy salon gets many messages it never opens in the app).
CREATE INDEX ix_notification_jobs_unread ON notification_jobs (user_id) INCLUDE (type, status, send_at, finished_at)
    WHERE channel = 'PUSH' AND read_at IS NULL AND title <> '';
