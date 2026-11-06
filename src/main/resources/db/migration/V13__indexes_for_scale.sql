-- Indexes for the queries that run all the time or touch large tables, so they stay fast with tens of thousands of
-- shops, millions of appointments and messages. (Plain CREATE INDEX: the tables are still small; on large tables
-- this would be CREATE INDEX CONCURRENTLY in a migration of its own.)

-- Bookings still waiting to start, by start time: the every-minute no-show job reads only these
CREATE INDEX ix_appointments_confirmed_start ON appointments (starts_at)
    WHERE status = 'CONFIRMED';

-- A salon's day or week: appointments of one salon overlapping a time range, without reading its whole history
CREATE INDEX ix_appointments_shop_range ON appointments USING gist (shop_id, time_range)
    WHERE status <> 'REMOVED';

-- A salon's upcoming online bookings, in start order
CREATE INDEX ix_appointments_shop_confirmed ON appointments (shop_id, starts_at)
    WHERE status = 'CONFIRMED';

-- Message cleanup (finished messages older than the retention) and account deletion (all of a person's messages)
CREATE INDEX ix_notification_jobs_finished ON notification_jobs (finished_at) WHERE status <> 'PENDING';
CREATE INDEX ix_notification_jobs_user ON notification_jobs (user_id, id);   -- also walks a deleted account's rows

-- Sign-in cleanup: expired and revoked refresh tokens
CREATE INDEX ix_refresh_tokens_expires ON refresh_tokens (expires_at);
CREATE INDEX ix_refresh_tokens_revoked ON refresh_tokens (revoked_at) WHERE revoked_at IS NOT NULL;

-- Field team's search by name, owner's name or owner's phone in part (LIKE '%…%'), with trigram indexes
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX ix_shops_name_trgm ON shops USING gin (lower(name) gin_trgm_ops);
CREATE INDEX ix_shops_owner_name_trgm ON shops USING gin (lower(owner_name) gin_trgm_ops);
CREATE INDEX ix_users_phone_trgm ON users USING gin (phone gin_trgm_ops);   -- owner's phone in part

-- Lookups and foreign-key checks that would otherwise read whole tables
CREATE INDEX ix_walk_in_access_user ON walk_in_access (user_id);
CREATE INDEX ix_shops_owner_photo ON shops (owner_photo_file_id) WHERE owner_photo_file_id IS NOT NULL;
CREATE INDEX ix_shops_shop_photo ON shops (shop_photo_file_id) WHERE shop_photo_file_id IS NOT NULL;
