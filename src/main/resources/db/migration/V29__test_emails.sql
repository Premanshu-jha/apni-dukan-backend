-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- OTP test mode's email list, like test_phones: while test mode is on, a test account (users.test_account, its number
-- in test_phones) verifying one of these addresses takes any 6-digit code and no email is sent. An address already
-- verified by a real account or an admin can't be added (TestModeService.addEmail). Stored as Emails.normalize gives
-- it (trimmed, lower case). No index on added_by, for the same reason as test_phones.added_by (V18).
CREATE TABLE test_emails (
    email      VARCHAR(254)  PRIMARY KEY CHECK (email = lower(email) AND email = btrim(email)),
    label      VARCHAR(60)   NOT NULL,                   -- e.g. "Prem's test salon"
    added_by   UUID          NOT NULL REFERENCES users (id),
    added_at   TIMESTAMPTZ   NOT NULL
);

-- The super admin's test-email changes are recorded like the test numbers' (the email goes in detail: phone is
-- VARCHAR(10)). admin_events is small (a few rows a day), so checking it again under the short lock is quick.
ALTER TABLE admin_events DROP CONSTRAINT admin_events_event_check;
ALTER TABLE admin_events ADD CONSTRAINT admin_events_event_check CHECK (event IN ('ADMIN_ADDED', 'ADMIN_REMOVED',
    'TEST_PHONE_ADDED', 'TEST_PHONE_REMOVED', 'TEST_EMAIL_ADDED', 'TEST_EMAIL_REMOVED', 'TEST_MODE_ON',
    'TEST_MODE_OFF'));
