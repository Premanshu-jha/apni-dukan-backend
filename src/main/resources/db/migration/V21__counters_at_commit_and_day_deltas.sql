-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- ============ The order of the statements ============
-- Every lock taken here is held until the migration commits, and each one waits up to 3 s (lock_timeout) while it
-- queues behind the app's transactions, holding up the tables already locked meanwhile. So the strongest lock on the
-- busiest table comes first, while nothing else is held: the new columns on appointments (with their stamping trigger,
-- under the same lock). Then the functions (no table lock), the commit-time triggers on the quiet tables, and the
-- busiest ones last (appointment_services, then appointments, whose lock is already held). Every statement is a
-- change of the table's description only (no row is read or rewritten), so the whole file takes milliseconds.

-- ============ 1. What changed on a salon's day board since the app last asked (GET /salon/day?since=) ============
--
-- Every appointment row carries change_seq, a number from one sequence for all salons taken each time the row is
-- written (the order changes happened), and change_xid, the id of the transaction that wrote it. A sequence number is
-- taken when the row is written, not when its transaction commits, so a row written early by a slow transaction can
-- become visible after a later number already was: "every row above the last number seen" would miss it. Transaction
-- ids answer that exactly: the app's cursor is the oldest transaction still running when its last answer was read
-- (pg_snapshot_xmin), and the next delta reads every row of the salon written by that transaction or a later one
-- (change_xid >= cursor). Anything the last answer could not see is in it; a few rows it already had may come again
-- (the app simply replaces them). Found through (shop_id, change_xid) (V24), one index range per salon.
-- Rows written before this migration have no stamp: they are only ever in a whole-day answer, which comes first.
CREATE SEQUENCE appointment_change_seq;

ALTER TABLE appointments ADD COLUMN change_seq BIGINT, ADD COLUMN change_xid BIGINT;

CREATE FUNCTION appointments_stamp_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.change_seq := nextval('appointment_change_seq');
    NEW.change_xid := pg_current_xact_id()::text::bigint;
    RETURN NEW;
END $$;

CREATE TRIGGER tr_appointments_stamp_change BEFORE INSERT OR UPDATE ON appointments
    FOR EACH ROW EXECUTE FUNCTION appointments_stamp_change();

-- Everything above is quick: the new columns are nullable without a default (only the table's description changes,
-- no row is rewritten), and each trigger is created without reading the table. The tables' locks are held only for
-- that moment. Existing rows keep change_seq and change_xid empty: nothing to fill in, because a delta only ever
-- follows a whole-day answer, and rows written before that answer are in it; any row written from now on is stamped.
-- The index the deltas read through is built in V24, CONCURRENTLY (no lock on reads or writes while it builds).


-- ============ 2. A salon's change counter moves at commit, not in the middle of the writer's transaction ============
--
-- Until now (V17) the statement triggers updated the salon's salon_versions row as soon as a write ran, and that row
-- stayed locked until the writer committed: every write of one salon (a booking, a walk-in, a cancel, the automatic
-- end) waited for the one before it to commit, however different the barbers. A salon with 50 barbers and 2,000
-- bookings a day queued on that one row.
--
-- Now the statement triggers only note which salons a transaction changed, in a setting that lives as long as the
-- transaction (set_config(..., true); through Neon's pooler a transaction stays on one connection, so it works there
-- too). A deferred trigger (DEFERRABLE INITIALLY DEFERRED) runs once at commit and moves each noted salon's counter
-- once, in shop id order. Its row is then locked only between that update and the commit, a moment, and two writes in
-- one salon no longer wait for each other. A writer takes a counter last of all its locks and then waits for nothing but
-- other counters (in shop id order), so it can't deadlock with anything.
--
-- The counter still moves in the same transaction as the change and becomes visible with it (the day board reads the
-- counter first and the rows after, so it can show newer rows under an older counter, never the other way round).
-- The numbers still come from salon_version_seq; a counter now moves once per transaction instead of once per
-- statement.
--
-- Copies of the backend still on the previous version keep working: the triggers are the database's, and their own
-- direct calls of salon_versions_bump (a renamed customer) still work as before.

-- Notes the salons of a change; setup = true also moves setup_version. Each id once, in the order first seen.
CREATE FUNCTION salon_versions_touch(shops UUID[], setup BOOLEAN) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    setting TEXT := CASE WHEN setup THEN 'apnidukan.salons_setup' ELSE 'apnidukan.salons' END;
    noted TEXT := COALESCE(current_setting(setting, true), '');
    added TEXT;
BEGIN
    SELECT string_agg(DISTINCT u.s::text, ',') INTO added
    FROM unnest(shops) AS u(s)
    WHERE u.s IS NOT NULL AND strpos(noted, u.s::text) = 0;
    IF added IS NOT NULL THEN
        PERFORM set_config(setting, CASE WHEN noted = '' THEN added ELSE noted || ',' || added END, true);
    END IF;
END $$;

-- At commit: every noted salon's counter once, in shop id order, then forgets them (the deferred trigger fires once
-- per changed row; all but the first find nothing to do).
CREATE FUNCTION salon_versions_flush() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    plain TEXT := COALESCE(current_setting('apnidukan.salons', true), '');
    setup TEXT := COALESCE(current_setting('apnidukan.salons_setup', true), '');
    r RECORD;
    n BIGINT;
BEGIN
    IF plain = '' AND setup = '' THEN
        RETURN NULL;
    END IF;
    PERFORM set_config('apnidukan.salons', '', true);
    PERFORM set_config('apnidukan.salons_setup', '', true);
    FOR r IN
        SELECT u.s::uuid AS shop_id, bool_or(u.is_setup) AS is_setup
        FROM (SELECT unnest(string_to_array(NULLIF(plain, ''), ',')) AS s, false AS is_setup
              UNION ALL
              SELECT unnest(string_to_array(NULLIF(setup, ''), ',')), true) u
        GROUP BY u.s
        ORDER BY 1
    LOOP
        n := nextval('salon_version_seq');
        INSERT INTO salon_versions AS v (shop_id, version, setup_version) VALUES (r.shop_id, n, n)
        ON CONFLICT (shop_id) DO UPDATE SET version = n,
            setup_version = CASE WHEN r.is_setup THEN n ELSE v.setup_version END;
    END LOOP;
    RETURN NULL;
END $$;

-- The statement triggers of V17 now only take note (same functions, same triggers)
CREATE OR REPLACE FUNCTION salon_versions_from_new() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM salon_versions_touch(ARRAY(SELECT shop_id FROM new_rows), TG_ARGV[0]::boolean);
    RETURN NULL;
END $$;

CREATE OR REPLACE FUNCTION salon_versions_from_old() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM salon_versions_touch(ARRAY(SELECT shop_id FROM old_rows), TG_ARGV[0]::boolean);
    RETURN NULL;
END $$;

CREATE OR REPLACE FUNCTION salon_versions_from_new_staff() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM salon_versions_touch(ARRAY(SELECT st.shop_id FROM new_rows r JOIN salon_staff st ON st.id = r.staff_id),
                                 TG_ARGV[0]::boolean);
    RETURN NULL;
END $$;

CREATE OR REPLACE FUNCTION salon_versions_from_old_staff() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM salon_versions_touch(ARRAY(SELECT st.shop_id FROM old_rows r JOIN salon_staff st ON st.id = r.staff_id),
                                 TG_ARGV[0]::boolean);
    RETURN NULL;
END $$;

CREATE OR REPLACE FUNCTION salon_versions_from_new_appointment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM salon_versions_touch(ARRAY(SELECT a.shop_id FROM new_rows r JOIN appointments a ON a.id = r.appointment_id),
                                 TG_ARGV[0]::boolean);
    RETURN NULL;
END $$;

CREATE OR REPLACE FUNCTION salon_versions_from_old_appointment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM salon_versions_touch(ARRAY(SELECT a.shop_id FROM old_rows r JOIN appointments a ON a.id = r.appointment_id),
                                 TG_ARGV[0]::boolean);
    RETURN NULL;
END $$;

-- Who works in a salon and who may add walk-ins decide what each person's day board shows (a barber without walk-in
-- access sees only his own column; addedByMe depends on it): they now count as setup, so a day board delta (section 1)
-- answers with the whole day when they change. Customers' setup kept in memory is read again then too (rare).

DROP TRIGGER tr_shop_members_versions_ins ON shop_members;
CREATE TRIGGER tr_shop_members_versions_ins AFTER INSERT ON shop_members REFERENCING NEW TABLE AS new_rows
    FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_new('true');

DROP TRIGGER tr_shop_members_versions_upd ON shop_members;
CREATE TRIGGER tr_shop_members_versions_upd AFTER UPDATE ON shop_members REFERENCING NEW TABLE AS new_rows
    FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_new('true');

DROP TRIGGER tr_shop_members_versions_del ON shop_members;
CREATE TRIGGER tr_shop_members_versions_del AFTER DELETE ON shop_members REFERENCING OLD TABLE AS old_rows
    FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_old('true');

DROP TRIGGER tr_walk_in_access_versions_ins ON walk_in_access;
CREATE TRIGGER tr_walk_in_access_versions_ins AFTER INSERT ON walk_in_access REFERENCING NEW TABLE AS new_rows
    FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_new('true');

DROP TRIGGER tr_walk_in_access_versions_upd ON walk_in_access;
CREATE TRIGGER tr_walk_in_access_versions_upd AFTER UPDATE ON walk_in_access REFERENCING NEW TABLE AS new_rows
    FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_new('true');

DROP TRIGGER tr_walk_in_access_versions_del ON walk_in_access;
CREATE TRIGGER tr_walk_in_access_versions_del AFTER DELETE ON walk_in_access REFERENCING OLD TABLE AS old_rows
    FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_old('true');


-- One deferred trigger per table the counters follow: it queues the commit-time flush. (Postgres allows only row
-- triggers to be deferred; each fires at commit and returns at once after the first.)

CREATE CONSTRAINT TRIGGER tr_salon_settings_versions_flush AFTER INSERT OR UPDATE OR DELETE ON salon_settings
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION salon_versions_flush();

CREATE CONSTRAINT TRIGGER tr_salon_services_versions_flush AFTER INSERT OR UPDATE OR DELETE ON salon_services
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION salon_versions_flush();

CREATE CONSTRAINT TRIGGER tr_staff_services_versions_flush AFTER INSERT OR UPDATE OR DELETE ON staff_services
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION salon_versions_flush();

CREATE CONSTRAINT TRIGGER tr_staff_hours_versions_flush AFTER INSERT OR UPDATE OR DELETE ON staff_hours
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION salon_versions_flush();

CREATE CONSTRAINT TRIGGER tr_salon_staff_versions_flush AFTER INSERT OR UPDATE OR DELETE ON salon_staff
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION salon_versions_flush();

CREATE CONSTRAINT TRIGGER tr_walk_in_access_versions_flush AFTER INSERT OR UPDATE OR DELETE ON walk_in_access
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION salon_versions_flush();

CREATE CONSTRAINT TRIGGER tr_shop_members_versions_flush AFTER INSERT OR UPDATE OR DELETE ON shop_members
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION salon_versions_flush();

CREATE CONSTRAINT TRIGGER tr_salon_pauses_versions_flush AFTER INSERT OR UPDATE OR DELETE ON salon_pauses
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION salon_versions_flush();

CREATE CONSTRAINT TRIGGER tr_appointment_services_versions_flush AFTER INSERT OR UPDATE OR DELETE ON appointment_services
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION salon_versions_flush();

CREATE CONSTRAINT TRIGGER tr_appointments_versions_flush AFTER INSERT OR UPDATE OR DELETE ON appointments
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION salon_versions_flush();
