-- Deleting an account keeps the row (bookings, strikes and history still point at it, and a salon's or kirana's
-- records must stay whole) but wipes who it was: phone, name and email go, deleted_at is set. The same phone can
-- sign up again later as a brand-new account.
ALTER TABLE users ALTER COLUMN phone DROP NOT NULL;
ALTER TABLE users ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE users ADD CONSTRAINT ck_users_phone_unless_deleted CHECK ((deleted_at IS NULL) = (phone IS NOT NULL));

-- Cancelling everything still waiting for one person (when their account is deleted)
CREATE INDEX ix_notification_jobs_user_pending ON notification_jobs (user_id) WHERE status = 'PENDING';
