-- OTP test mode: while it is on, the numbers listed here sign in with any 6-digit code (no SMS is sent) and their
-- email code takes any 6 digits too. Every other number works as usual. For testing on the real deployment
-- without spending SMS credit. An admin's number can never be a test number.
CREATE TABLE test_phones (
    phone      VARCHAR(10)   PRIMARY KEY CHECK (phone ~ '^[6-9][0-9]{9}$'),
    label      VARCHAR(60)   NOT NULL,                   -- e.g. "Prem's test barber"
    added_by   UUID          NOT NULL REFERENCES users (id),
    added_at   TIMESTAMPTZ   NOT NULL
);

-- One row: on until `until` (null = off). It switches itself off when `until` passes.
CREATE TABLE otp_test_mode (
    id              SMALLINT     PRIMARY KEY CHECK (id = 1),
    until           TIMESTAMPTZ,
    turned_on_by    UUID         REFERENCES users (id),
    turned_on_at    TIMESTAMPTZ
);
INSERT INTO otp_test_mode (id) VALUES (1);
