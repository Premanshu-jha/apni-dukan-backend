SET LOCAL lock_timeout = '3s';

-- The two tables that grow fastest, sign-ins (refresh_tokens: every refresh adds a row) and messages
-- (notification_jobs: every booking adds several), are split into one part per week of created_at (README:
-- "Weekly parts"). Old rows then go by dropping a whole week at once, which is instant and leaves nothing for
-- Postgres to clean up after, instead of deleting millions of rows one by one every night.
--
-- Weeks start on Monday 00:00 UTC; a part is named after its first day: refresh_tokens_w20261123. Each table also
-- has a default part (refresh_tokens_default) for any row whose week has no part yet: the rows of before this
-- migration, and new rows if the nightly job (PartitionMaintenance) ever fell more than 4 weeks behind. Nothing
-- breaks either way; the nightly cleanup still deletes the default part's old rows one by one.
--
-- A partitioned table's primary key must contain created_at, so it is (id, created_at); ids are random UUIDs, so
-- an id alone is still unique in practice. The same holds for token_hash (a SHA-256 of 32 random bytes): it had a
-- unique constraint, which a partitioned table can't have without created_at; it now has a plain index.
--
-- The rows are copied over in this transaction (a few hundred today), with the tables locked meanwhile.

-- ---------------------------------------------------------------- refresh_tokens

ALTER TABLE refresh_tokens RENAME TO refresh_tokens_old;
ALTER TABLE refresh_tokens_old RENAME CONSTRAINT refresh_tokens_pkey TO refresh_tokens_old_pkey;
ALTER TABLE refresh_tokens_old RENAME CONSTRAINT refresh_tokens_token_hash_key TO refresh_tokens_old_hash_key;
ALTER TABLE refresh_tokens_old RENAME CONSTRAINT refresh_tokens_user_id_fkey TO refresh_tokens_old_user_fkey;
ALTER TABLE refresh_tokens_old RENAME CONSTRAINT refresh_tokens_check TO refresh_tokens_old_check;
ALTER TABLE refresh_tokens_old RENAME CONSTRAINT refresh_tokens_revoke_reason_check TO refresh_tokens_old_reason_check;
DROP INDEX ix_refresh_tokens_user;
DROP INDEX ix_refresh_tokens_expires;
DROP INDEX ix_refresh_tokens_revoked;

CREATE TABLE refresh_tokens (
    id            UUID         NOT NULL,
    user_id       UUID         NOT NULL REFERENCES users (id),
    token_hash    VARCHAR(64)  NOT NULL,
    device        VARCHAR(100),
    created_at    TIMESTAMPTZ  NOT NULL,
    expires_at    TIMESTAMPTZ  NOT NULL,
    revoked_at    TIMESTAMPTZ,
    revoke_reason VARCHAR(30),
    replaced_by   UUID,
    session_id    UUID,
    CONSTRAINT refresh_tokens_pkey PRIMARY KEY (id, created_at),
    CONSTRAINT refresh_tokens_check CHECK ((revoked_at IS NULL) = (revoke_reason IS NULL)),
    CONSTRAINT refresh_tokens_revoke_reason_check CHECK (revoke_reason IN ('ROTATED', 'EXPIRED', 'SIGNED_OUT',
        'SIGNED_OUT_EVERYWHERE', 'REUSE_DETECTED', 'GRACE_REPLACED'))
) PARTITION BY RANGE (created_at);

CREATE INDEX ix_refresh_tokens_hash ON refresh_tokens (token_hash);
CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);
CREATE INDEX ix_refresh_tokens_expires ON refresh_tokens (expires_at);
CREATE INDEX ix_refresh_tokens_revoked ON refresh_tokens (revoked_at) WHERE revoked_at IS NOT NULL;

CREATE TABLE refresh_tokens_default PARTITION OF refresh_tokens DEFAULT;

-- ---------------------------------------------------------------- notification_jobs

ALTER TABLE notification_jobs RENAME TO notification_jobs_old;
ALTER TABLE notification_jobs_old RENAME CONSTRAINT notification_jobs_pkey TO notification_jobs_old_pkey;
ALTER TABLE notification_jobs_old RENAME CONSTRAINT notification_jobs_user_id_fkey TO notification_jobs_old_user_fkey;
ALTER TABLE notification_jobs_old RENAME CONSTRAINT notification_jobs_channel_check TO notification_jobs_old_channel_check;
ALTER TABLE notification_jobs_old RENAME CONSTRAINT notification_jobs_status_check TO notification_jobs_old_status_check;
DROP INDEX ix_notification_jobs_about;
DROP INDEX ix_notification_jobs_due;
DROP INDEX ix_notification_jobs_finished;
DROP INDEX ix_notification_jobs_inbox;
DROP INDEX ix_notification_jobs_ref;
DROP INDEX ix_notification_jobs_sms_finished;
DROP INDEX ix_notification_jobs_unread;
DROP INDEX ix_notification_jobs_user;
DROP INDEX ix_notification_jobs_user_pending;

CREATE TABLE notification_jobs (
    id            UUID          NOT NULL,
    user_id       UUID          NOT NULL REFERENCES users (id),
    channel       VARCHAR(5)    NOT NULL,
    type          VARCHAR(30)   NOT NULL,
    ref_id        UUID,
    title         VARCHAR(100)  NOT NULL,
    body          VARCHAR(500)  NOT NULL,
    url           VARCHAR(300),
    sms_variables VARCHAR(1000),
    send_at       TIMESTAMPTZ   NOT NULL,
    status        VARCHAR(10)   NOT NULL,
    attempts      INTEGER       NOT NULL,
    next_try_at   TIMESTAMPTZ   NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL,
    finished_at   TIMESTAMPTZ,
    read_at       TIMESTAMPTZ,
    about_user_id UUID,
    CONSTRAINT notification_jobs_pkey PRIMARY KEY (id, created_at),
    CONSTRAINT notification_jobs_channel_check CHECK (channel IN ('PUSH', 'SMS')),
    CONSTRAINT notification_jobs_status_check CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'CANCELLED',
        'NO_DEVICE'))
) PARTITION BY RANGE (created_at);

CREATE INDEX ix_notification_jobs_about ON notification_jobs (about_user_id) WHERE about_user_id IS NOT NULL;
CREATE INDEX ix_notification_jobs_due ON notification_jobs (next_try_at) WHERE status = 'PENDING';
CREATE INDEX ix_notification_jobs_finished ON notification_jobs (finished_at) WHERE status <> 'PENDING';
CREATE INDEX ix_notification_jobs_inbox ON notification_jobs (user_id, send_at DESC, id DESC) WHERE channel = 'PUSH';
CREATE INDEX ix_notification_jobs_ref ON notification_jobs (ref_id) WHERE status = 'PENDING';
CREATE INDEX ix_notification_jobs_sms_finished ON notification_jobs (finished_at)
    WHERE status <> 'PENDING' AND channel = 'SMS';
CREATE INDEX ix_notification_jobs_unread ON notification_jobs (user_id) INCLUDE (type, status, send_at, finished_at)
    WHERE channel = 'PUSH' AND read_at IS NULL AND title <> '';
CREATE INDEX ix_notification_jobs_user ON notification_jobs (user_id, id);
CREATE INDEX ix_notification_jobs_user_pending ON notification_jobs (user_id) WHERE status = 'PENDING';

CREATE TABLE notification_jobs_default PARTITION OF notification_jobs DEFAULT;

-- ---------------------------------------------------------------- the weekly parts

-- Only the owner (who runs migrations) may create or drop tables; the backend's user, clozest_app, can't (V87). These
-- two functions run as the owner (SECURITY DEFINER) and do exactly one thing each, for these two tables only, so the
-- backend's nightly job can call them and still can't change any table itself. Neither takes a table name it hasn't
-- listed, nor runs any text it was given. Their search_path names pg_temp last, so a temporary table of the caller's
-- can never stand in for a real one.

-- Makes sure this week and the next p_weeks (1 to 12) weeks have a part. Returns how many it made. A part that can't
-- be made right now (the table is busy for over a second) is left for the next night. A week whose rows are already
-- in the default part (the job missed a month) can't get a part any more (Postgres refuses: those rows would be in
-- the wrong part); it is skipped, its rows stay in the default part and are deleted one by one, and the weeks after
-- it still get theirs, so the parts carry on from the next week.
CREATE FUNCTION partitions_prepare(p_table TEXT, p_weeks INTEGER) RETURNS INTEGER
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, public, pg_temp
    SET lock_timeout = '1s'
AS $$
DECLARE
    first_week DATE := date_trunc('week', now() AT TIME ZONE 'UTC')::DATE;
    week       DATE;
    part       TEXT;
    made       INTEGER := 0;
BEGIN
    IF p_table NOT IN ('refresh_tokens', 'notification_jobs') THEN
        RAISE EXCEPTION 'partitions_prepare: % is not a weekly table', p_table;
    END IF;
    IF p_weeks IS NULL OR p_weeks < 1 OR p_weeks > 12 THEN
        RAISE EXCEPTION 'partitions_prepare: weeks must be 1 to 12, not %', p_weeks;
    END IF;
    FOR i IN 0 .. p_weeks LOOP
        week := first_week + i * 7;
        part := p_table || '_w' || to_char(week, 'YYYYMMDD');
        CONTINUE WHEN to_regclass('public.' || part) IS NOT NULL;
        BEGIN
            EXECUTE format('CREATE TABLE public.%I PARTITION OF public.%I FOR VALUES FROM (%L) TO (%L)', part,
                p_table, week::TEXT || ' 00:00:00+00', (week + 7)::TEXT || ' 00:00:00+00');
            made := made + 1;
        EXCEPTION
            WHEN lock_not_available THEN
                RAISE WARNING 'partitions_prepare: % is busy, % is left for next time', p_table, part;
            WHEN check_violation THEN
                RAISE WARNING 'partitions_prepare: % has rows of % in its default part already; that week stays there',
                    p_table, part;
        END;
    END LOOP;
    RETURN made;
END
$$;

-- Drops the weekly parts that hold no row the nightly cleanup would still keep (p_keep_days, at least 1), and only
-- weeks that ended more than p_keep_days ago. What "still kept" means is the cleanup's own rule:
--   refresh_tokens:    not yet expired p_keep_days ago, and not ended (revoked) p_keep_days ago;
--   notification_jobs: still waiting (PENDING), or finished less than p_keep_days ago (or with no finish time).
-- Each check goes through the part's indexes, so it is quick however big the week is. Then the table is locked
-- (waiting at most a second, else the week is left for the next night), checked once more, and the week dropped.
-- At most one week is dropped (or tried) per call, oldest first: the lock lasts until the caller's transaction ends,
-- so the caller calls again in a new transaction for the next one (WeeklyParts), and requests wait behind it only
-- for that one drop. Returns one row per old week looked at: DROPPED, IN_USE (some row is still kept) or BUSY.
CREATE FUNCTION partitions_drop_old(p_table TEXT, p_keep_days INTEGER)
    RETURNS TABLE (part TEXT, week DATE, outcome TEXT)
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, public, pg_temp
    SET lock_timeout = '1s'
AS $$
DECLARE
    cutoff TIMESTAMPTZ;
    kept   TEXT;
    used   BOOLEAN;
BEGIN
    IF p_table NOT IN ('refresh_tokens', 'notification_jobs') THEN
        RAISE EXCEPTION 'partitions_drop_old: % is not a weekly table', p_table;
    END IF;
    IF p_keep_days IS NULL OR p_keep_days < 1 THEN
        RAISE EXCEPTION 'partitions_drop_old: keep days must be at least 1, not %', p_keep_days;
    END IF;
    cutoff := now() - make_interval(days => p_keep_days);
    IF p_table = 'refresh_tokens' THEN
        kept := 'expires_at >= $1 AND (revoked_at IS NULL OR revoked_at >= $1)';
    ELSE
        kept := 'status = ''PENDING'' OR (status <> ''PENDING'' AND (finished_at >= $1 OR finished_at IS NULL))';
    END IF;
    FOR part, week IN
        SELECT c.relname::TEXT, to_date(substring(c.relname FROM '_w([0-9]{8})$'), 'YYYYMMDD')
        FROM pg_catalog.pg_inherits i
        JOIN pg_catalog.pg_class c ON c.oid = i.inhrelid
        WHERE i.inhparent = ('public.' || p_table)::regclass
          AND c.relname ~ ('^' || p_table || '_w[0-9]{8}$')
        ORDER BY 2
    LOOP
        CONTINUE WHEN (week + 7)::TIMESTAMP AT TIME ZONE 'UTC' >= cutoff;   -- the week isn't that old yet
        EXECUTE format('SELECT EXISTS (SELECT 1 FROM public.%I WHERE %s)', part, kept) INTO used USING cutoff;
        IF used THEN
            outcome := 'IN_USE';
            RETURN NEXT;
            CONTINUE;
        END IF;
        BEGIN
            EXECUTE format('LOCK TABLE public.%I IN ACCESS EXCLUSIVE MODE', p_table);
            EXECUTE format('SELECT EXISTS (SELECT 1 FROM public.%I WHERE %s)', part, kept) INTO used USING cutoff;
            IF used THEN
                outcome := 'IN_USE';
            ELSE
                EXECUTE format('DROP TABLE public.%I', part);
                outcome := 'DROPPED';
            END IF;
        EXCEPTION WHEN lock_not_available THEN
            outcome := 'BUSY';
        END;
        RETURN NEXT;
        RETURN;   -- one drop per call (see above)
    END LOOP;
END
$$;

REVOKE ALL ON FUNCTION partitions_prepare(TEXT, INTEGER) FROM PUBLIC;
REVOKE ALL ON FUNCTION partitions_drop_old(TEXT, INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION partitions_prepare(TEXT, INTEGER) TO clozest_app;
GRANT EXECUTE ON FUNCTION partitions_drop_old(TEXT, INTEGER) TO clozest_app;

-- This week and the next 4, then the rows: this week's go into its part, older ones into the default part
SELECT partitions_prepare('refresh_tokens', 4);
SELECT partitions_prepare('notification_jobs', 4);

INSERT INTO refresh_tokens (id, user_id, token_hash, device, created_at, expires_at, revoked_at, revoke_reason,
                            replaced_by, session_id)
SELECT id, user_id, token_hash, device, created_at, expires_at, revoked_at, revoke_reason, replaced_by, session_id
FROM refresh_tokens_old;
DROP TABLE refresh_tokens_old;

INSERT INTO notification_jobs (id, user_id, channel, type, ref_id, title, body, url, sms_variables, send_at, status,
                               attempts, next_try_at, created_at, finished_at, read_at, about_user_id)
SELECT id, user_id, channel, type, ref_id, title, body, url, sms_variables, send_at, status, attempts, next_try_at,
       created_at, finished_at, read_at, about_user_id
FROM notification_jobs_old;
DROP TABLE notification_jobs_old;

-- The app's user reaches every part through the main table; only the default parts does it also clean up directly
-- (SignInCleanupStore, NotificationStore), so they get the rights by name too (the default privileges of V87 give
-- them already, when the owner runs this; said again in case it doesn't)
GRANT SELECT, INSERT, UPDATE, DELETE ON refresh_tokens, notification_jobs, refresh_tokens_default,
    notification_jobs_default TO clozest_app;
