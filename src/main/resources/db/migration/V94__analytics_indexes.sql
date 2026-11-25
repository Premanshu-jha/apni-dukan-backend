-- Built CONCURRENTLY, so reads and writes go on while each builds (see V24 for why, and why there is no lock timeout
-- here). For the hourly analytics job (AnalyticsJob): each day's new people, salons and bookings, and each day's
-- visits, are read as one index range of that day instead of the whole table.
SET lock_timeout = 0;

DROP INDEX CONCURRENTLY IF EXISTS ix_users_created;
CREATE INDEX CONCURRENTLY ix_users_created ON users (created_at);

DROP INDEX CONCURRENTLY IF EXISTS ix_shops_created;
CREATE INDEX CONCURRENTLY ix_shops_created ON shops (created_at);

-- Bookings by the day they were made
DROP INDEX CONCURRENTLY IF EXISTS ix_appointments_booked;
CREATE INDEX CONCURRENTLY ix_appointments_booked ON appointments (created_at) WHERE kind = 'BOOKING';

-- Visits (bookings and walk-ins) by the day they were for, whatever became of them
DROP INDEX CONCURRENTLY IF EXISTS ix_appointments_visits;
CREATE INDEX CONCURRENTLY ix_appointments_visits ON appointments (starts_at) WHERE kind <> 'BLOCK';

-- First approvals per day (DailyTotals: salons_approved, AnalyticsStore: the time to approval)
DROP INDEX CONCURRENTLY IF EXISTS ix_shop_events_approved;
CREATE INDEX CONCURRENTLY ix_shop_events_approved ON shop_events (at) WHERE event = 'APPROVED';
