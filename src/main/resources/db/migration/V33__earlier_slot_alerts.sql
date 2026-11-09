-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- ============ 1. "Tell me if an earlier slot opens" (EarlierSlotAlerts) ============
--
-- One row per online booking whose customer switched the alert on (at booking, or later on the booking's page);
-- switching it off deletes the row. Kept apart from appointments on purpose: every write of an appointment row moves
-- the salon's change counter and lands in the day board's deltas (V21), and a customer flipping a switch is no change
-- to the salon's day.
--
-- starts_at is a copy of the booking's start, kept in step when the customer moves it, so a cancel finds the bookings
-- to alert with one range of (shop_id, starts_at): that day's rows of that salon only, however many bookings had the
-- alert in the past. A row whose booking has since closed (cancelled, started, done, missed) is skipped by the join on
-- appointments (status = 'CONFIRMED' and the same start) and deleted a day after its start by the automatic-end round.
--
-- offered_start: the start offered to this booking last, so the same time is never sent twice in a row (another time
-- is: the one offered before may have gone to whoever moved first). Cleared when the customer moves the booking.
--
-- No foreign key to appointments: appointment rows are never deleted (closed bookings and deleted accounts keep them),
-- and adding one would lock appointments, the busiest table, for every row checked.
CREATE TABLE earlier_slot_alerts (
    appointment_id UUID PRIMARY KEY,
    shop_id        UUID        NOT NULL,
    starts_at      TIMESTAMPTZ NOT NULL,
    offered_start  TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL
);

-- A freed time's candidates: one salon's alerts starting after it, that day.
CREATE INDEX ix_earlier_slot_alerts_shop_start ON earlier_slot_alerts (shop_id, starts_at);
-- The automatic-end round's cleanup: rows whose start is over a day old.
CREATE INDEX ix_earlier_slot_alerts_start ON earlier_slot_alerts (starts_at);


-- ============ 2. The late allowance is now 10 minutes (was 15) ============
--
-- A booking not started 10 minutes after its start becomes a no-show (SalonDayService.LATE_ALLOWANCE). Reminders
-- already waiting were written with the old "Held until" time (start + 15); each is put right here, in the app's text
-- and in the SMS variables, with start + 10 in India time. Only the time changes. It is usually no longer than the old
-- one, except for a 12:45 start ("12:55 PM" where it was "1:00 PM": one character more), so a text already at the
-- column's 500 characters (a long salon name and address) is cut back to 500, ending in "…" as every cut message does
-- (MessageText.cut). The SMS variables (up to 1000) have room. Found through the pending messages' ref index
-- (ix_notification_jobs_ref).
WITH rewritten AS (
    SELECT j.id, u.until,
           regexp_replace(j.body, '(Start code [0-9]{4}\. Held until )[0-9]{1,2}:[0-9]{2} [AP]M', '\1' || u.until)
               AS body
    FROM notification_jobs j
    JOIN appointments a ON a.id = j.ref_id
    CROSS JOIN LATERAL (SELECT to_char((a.starts_at + interval '10 minutes') AT TIME ZONE 'Asia/Kolkata',
                                       'FMHH12:MI AM') AS until) u
    WHERE j.type = 'BOOKING_REMINDER' AND j.status = 'PENDING' AND a.status = 'CONFIRMED')
UPDATE notification_jobs j
SET body = CASE WHEN length(r.body) > 500 THEN left(r.body, 499) || '…' ELSE r.body END,
    sms_variables = CASE WHEN j.sms_variables IS NULL THEN NULL
                         ELSE regexp_replace(j.sms_variables, 'UNTIL=[^&]*',
                                             'UNTIL=' || replace(replace(r.until, ':', '%3A'), ' ', '+')) END
FROM rewritten r
WHERE j.id = r.id;
