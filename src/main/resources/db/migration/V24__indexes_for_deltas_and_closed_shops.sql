-- Built CONCURRENTLY: reads and writes of these tables go on while each index builds (a plain CREATE INDEX would
-- block writes for the whole build, and with appointments at lakhs of rows that is every booking for minutes).
-- CONCURRENTLY can't run inside a transaction, so Flyway runs this file without one (spring.flyway.mixed=true lets the
-- SET below share the file). Hence SET, not SET LOCAL: it stays on Flyway's own connection, which later migrations
-- set again for themselves.
--
-- No lock timeout here (0 = wait as long as it takes), unlike every other migration: a concurrent build never blocks
-- the app's reads or writes, but it waits for every transaction older than itself to end (a job's 60 s slice, a
-- session left open in Neon's SQL editor). With a timeout it would fail there, and a failed migration outside a
-- transaction is recorded as failed and stops every later start until someone repairs it. Waiting costs nothing but
-- the deploy's time. (Flyway's connection has no statement limit either: spring.flyway.init-sqls.)
SET lock_timeout = 0;

-- If a build still fails (the deploy stopped mid-build), Postgres keeps a half-built INVALID index and Flyway records
-- this migration as failed. Then: run `flyway repair` (or delete its failed row from flyway_schema_history) and deploy
-- again; each DROP below removes a half-built index first, so the file can always run again from the start. See
-- "Database changes" in the README.

-- The day board and upcoming deltas (V21): the salon's rows written since a transaction id, one index range. Rows
-- from before V21 have no stamp and are left out of it (a delta never needs them).
DROP INDEX CONCURRENTLY IF EXISTS ix_appointments_shop_change;
CREATE INDEX CONCURRENTLY ix_appointments_shop_change ON appointments (shop_id, change_xid)
    WHERE change_xid IS NOT NULL;

-- (The field team's list of closed shops needs no index of its own: ix_shops_review_list (V16) is
-- (type, status, status_since, id), the same pages for every status.)

-- An owner asking to close again (a lost reply) is answered from their latest closing
DROP INDEX CONCURRENTLY IF EXISTS ix_shop_events_closed_by;
CREATE INDEX CONCURRENTLY ix_shop_events_closed_by ON shop_events (by_user, at) WHERE event = 'CLOSED';
