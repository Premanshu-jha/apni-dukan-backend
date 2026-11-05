-- Who may use the admin website. ADMIN: the field team (shop visits, support). SUPER_ADMIN: also adds and removes
-- admins and runs OTP test mode. There is at most one super admin; it is set by hand in the database, once:
--   UPDATE users SET admin_role = 'SUPER_ADMIN' WHERE phone = '9xxxxxxxxx';
-- The old users.admin column is no longer read (kept so the version running during a deploy still starts); it is
-- dropped in a later migration.
ALTER TABLE users ADD COLUMN admin_role VARCHAR(12) NOT NULL DEFAULT 'NONE'
    CHECK (admin_role IN ('NONE', 'ADMIN', 'SUPER_ADMIN'));
UPDATE users SET admin_role = 'ADMIN' WHERE admin;

-- At most one super admin: a second UPDATE to SUPER_ADMIN is refused by the database
CREATE UNIQUE INDEX ux_users_one_super_admin ON users (admin_role) WHERE admin_role = 'SUPER_ADMIN';

-- What the super admin did: admins added or removed, test numbers, OTP test mode on and off. Never deleted.
CREATE TABLE admin_events (
    id         UUID          PRIMARY KEY,
    at         TIMESTAMPTZ   NOT NULL,
    by_user    UUID          NOT NULL REFERENCES users (id),
    event      VARCHAR(30)   NOT NULL CHECK (event IN ('ADMIN_ADDED', 'ADMIN_REMOVED', 'TEST_PHONE_ADDED',
                                                       'TEST_PHONE_REMOVED', 'TEST_MODE_ON', 'TEST_MODE_OFF')),
    phone      VARCHAR(10),                              -- the admin or test number it was about
    detail     VARCHAR(200)
);
CREATE INDEX ix_admin_events_at ON admin_events (at DESC);
