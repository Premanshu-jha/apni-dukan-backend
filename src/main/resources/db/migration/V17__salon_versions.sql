-- On a live database: wait at most 3 s for each table's lock (a copy of the backend still running holds them for its
-- writes). Without it, a migration queues behind those writes and every write after it queues behind the migration;
-- with it, the deploy fails fast and is simply run again (see "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- A change counter per salon, so the partner app's day board (every 30 s) and upcoming list (every 60 s) can be
-- answered "nothing changed" (304) after one primary-key read, instead of reading the day's appointments, barbers,
-- hours, services and customers again. Triggers on every table those answers read move the counter, so every writer
-- does it (the app, a copy of the backend still on the previous version, a fix typed into Neon by hand).
--
-- setup_version moves only when the salon's setup changes (barbers, their hours and services, the services, booking
-- settings): customers' free-time checks keep the setup in memory under it, and bookings coming in don't throw that
-- away.
--
-- Values come from one sequence shared by all salons, so a number is never given out twice, even if a row is deleted
-- and made again: a copy of an answer kept under an old number can never be mistaken for a newer one.
-- No foreign key to shops: it is only a counter (no row yet = 0, nothing changed since this migration), and it may be
-- written while a shop's rows are being deleted (the tests' cleanup).

CREATE SEQUENCE salon_version_seq;

CREATE TABLE salon_versions (
    shop_id        UUID    PRIMARY KEY,
    version        BIGINT  NOT NULL,     -- moves on every change the day board or upcoming list could show
    setup_version  BIGINT  NOT NULL      -- moves only on setup changes
);

-- Moves the counters of these salons, once each, in shop id order. One small row per salon is updated once per
-- statement (the triggers below are FOR EACH STATEMENT), not once per row: a round of the automatic end that closes
-- 500 appointments in 100 salons updates 100 rows once each. The row stays locked until the writer commits, so two
-- writes in one salon wait for each other for those few milliseconds; most of them already queue on the same
-- barber or shop row. Taking the rows in shop id order means two statements that touch several salons can't
-- deadlock on them.
CREATE FUNCTION salon_versions_bump(shops UUID[], setup BOOLEAN) RETURNS void LANGUAGE sql AS $$
    INSERT INTO salon_versions AS v (shop_id, version, setup_version)
    SELECT s, n, n FROM (
        SELECT s, nextval('salon_version_seq') AS n
        FROM (SELECT DISTINCT s FROM unnest(shops) AS u(s) WHERE s IS NOT NULL) d
        ORDER BY s) x
    ORDER BY s
    ON CONFLICT (shop_id) DO UPDATE SET version = EXCLUDED.version,
        setup_version = CASE WHEN setup THEN EXCLUDED.version ELSE v.setup_version END;
$$;

-- The changed rows come in as transition tables (new_rows, old_rows). TG_ARGV[0] says whether the table is setup.
-- Tables with a shop_id of their own:
CREATE FUNCTION salon_versions_from_new() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM salon_versions_bump(ARRAY(SELECT shop_id FROM new_rows), TG_ARGV[0]::boolean);
    RETURN NULL;
END $$;

CREATE FUNCTION salon_versions_from_old() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM salon_versions_bump(ARRAY(SELECT shop_id FROM old_rows), TG_ARGV[0]::boolean);
    RETURN NULL;
END $$;

-- staff_hours: through the barber
CREATE FUNCTION salon_versions_from_new_staff() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM salon_versions_bump(ARRAY(SELECT st.shop_id FROM new_rows r JOIN salon_staff st ON st.id = r.staff_id),
                                TG_ARGV[0]::boolean);
    RETURN NULL;
END $$;

CREATE FUNCTION salon_versions_from_old_staff() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM salon_versions_bump(ARRAY(SELECT st.shop_id FROM old_rows r JOIN salon_staff st ON st.id = r.staff_id),
                                TG_ARGV[0]::boolean);
    RETURN NULL;
END $$;

-- appointment_services: through the appointment
CREATE FUNCTION salon_versions_from_new_appointment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM salon_versions_bump(ARRAY(SELECT a.shop_id FROM new_rows r JOIN appointments a ON a.id = r.appointment_id),
                                TG_ARGV[0]::boolean);
    RETURN NULL;
END $$;

CREATE FUNCTION salon_versions_from_old_appointment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM salon_versions_bump(ARRAY(SELECT a.shop_id FROM old_rows r JOIN appointments a ON a.id = r.appointment_id),
                                TG_ARGV[0]::boolean);
    RETURN NULL;
END $$;

-- Postgres allows transition tables only on a trigger for one event, hence three triggers per table.
-- The daily work (not setup):
CREATE TRIGGER tr_appointments_versions_ins AFTER INSERT ON appointments REFERENCING NEW TABLE AS new_rows
    FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_new('false');
CREATE TRIGGER tr_appointments_versions_upd AFTER UPDATE ON appointments REFERENCING NEW TABLE AS new_rows
    FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_new('false');
CREATE TRIGGER tr_appointments_versions_del AFTER DELETE ON appointments REFERENCING OLD TABLE AS old_rows
    FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_old('false');

CREATE TRIGGER tr_appointment_services_versions_ins AFTER INSERT ON appointment_services
    REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_new_appointment('false');
CREATE TRIGGER tr_appointment_services_versions_upd AFTER UPDATE ON appointment_services
    REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_new_appointment('false');
CREATE TRIGGER tr_appointment_services_versions_del AFTER DELETE ON appointment_services
    REFERENCING OLD TABLE AS old_rows FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_old_appointment('false');

CREATE TRIGGER tr_salon_pauses_versions_ins AFTER INSERT ON salon_pauses REFERENCING NEW TABLE AS new_rows
    FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_new('false');
CREATE TRIGGER tr_salon_pauses_versions_upd AFTER UPDATE ON salon_pauses REFERENCING NEW TABLE AS new_rows
    FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_new('false');
CREATE TRIGGER tr_salon_pauses_versions_del AFTER DELETE ON salon_pauses REFERENCING OLD TABLE AS old_rows
    FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_old('false');

-- Who works there and who may add walk-ins decide what each person sees
CREATE TRIGGER tr_shop_members_versions_ins AFTER INSERT ON shop_members REFERENCING NEW TABLE AS new_rows
    FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_new('false');
CREATE TRIGGER tr_shop_members_versions_upd AFTER UPDATE ON shop_members REFERENCING NEW TABLE AS new_rows
    FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_new('false');
CREATE TRIGGER tr_shop_members_versions_del AFTER DELETE ON shop_members REFERENCING OLD TABLE AS old_rows
    FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_old('false');

CREATE TRIGGER tr_walk_in_access_versions_ins AFTER INSERT ON walk_in_access REFERENCING NEW TABLE AS new_rows
    FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_new('false');
CREATE TRIGGER tr_walk_in_access_versions_upd AFTER UPDATE ON walk_in_access REFERENCING NEW TABLE AS new_rows
    FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_new('false');
CREATE TRIGGER tr_walk_in_access_versions_del AFTER DELETE ON walk_in_access REFERENCING OLD TABLE AS old_rows
    FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_old('false');

-- The setup:
CREATE TRIGGER tr_salon_staff_versions_ins AFTER INSERT ON salon_staff REFERENCING NEW TABLE AS new_rows
    FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_new('true');
CREATE TRIGGER tr_salon_staff_versions_upd AFTER UPDATE ON salon_staff REFERENCING NEW TABLE AS new_rows
    FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_new('true');
CREATE TRIGGER tr_salon_staff_versions_del AFTER DELETE ON salon_staff REFERENCING OLD TABLE AS old_rows
    FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_old('true');

CREATE TRIGGER tr_staff_hours_versions_ins AFTER INSERT ON staff_hours REFERENCING NEW TABLE AS new_rows
    FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_new_staff('true');
CREATE TRIGGER tr_staff_hours_versions_upd AFTER UPDATE ON staff_hours REFERENCING NEW TABLE AS new_rows
    FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_new_staff('true');
CREATE TRIGGER tr_staff_hours_versions_del AFTER DELETE ON staff_hours REFERENCING OLD TABLE AS old_rows
    FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_old_staff('true');

CREATE TRIGGER tr_staff_services_versions_ins AFTER INSERT ON staff_services REFERENCING NEW TABLE AS new_rows
    FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_new('true');
CREATE TRIGGER tr_staff_services_versions_upd AFTER UPDATE ON staff_services REFERENCING NEW TABLE AS new_rows
    FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_new('true');
CREATE TRIGGER tr_staff_services_versions_del AFTER DELETE ON staff_services REFERENCING OLD TABLE AS old_rows
    FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_old('true');

CREATE TRIGGER tr_salon_services_versions_ins AFTER INSERT ON salon_services REFERENCING NEW TABLE AS new_rows
    FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_new('true');
CREATE TRIGGER tr_salon_services_versions_upd AFTER UPDATE ON salon_services REFERENCING NEW TABLE AS new_rows
    FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_new('true');
CREATE TRIGGER tr_salon_services_versions_del AFTER DELETE ON salon_services REFERENCING OLD TABLE AS old_rows
    FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_old('true');

CREATE TRIGGER tr_salon_settings_versions_ins AFTER INSERT ON salon_settings REFERENCING NEW TABLE AS new_rows
    FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_new('true');
CREATE TRIGGER tr_salon_settings_versions_upd AFTER UPDATE ON salon_settings REFERENCING NEW TABLE AS new_rows
    FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_new('true');
CREATE TRIGGER tr_salon_settings_versions_del AFTER DELETE ON salon_settings REFERENCING OLD TABLE AS old_rows
    FOR EACH STATEMENT EXECUTE FUNCTION salon_versions_from_old('true');

-- A customer's name and phone also show on the salon's screens. They are not followed by a trigger on users: changing
-- them locks the person's row first, and a trigger would then take salon counters after it, while a salon's own change
-- takes its counter first and the person's row after (a message to them checks their row): the two could deadlock.
-- The backend moves those salons' counters itself, before it locks the person's row (SalonCustomerDetails).
