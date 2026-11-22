SET LOCAL lock_timeout = '3s';

-- The admin website's second step (AdminSessionStore): after the phone code, an admin types a code sent to their
-- verified email, and only then does that device's sign-in (its session, the "sid" kept through every refresh) get
-- admin access, for app.auth.admin-code-hours (12) from that moment. A stolen SIM alone can no longer open the admin
-- pages. One row per device sign-in that passed the step; a later pass on the same device moves verified_at.
-- Old rows of an admin are deleted when they pass the step again (admins are a handful of people, so the table stays
-- tiny). A new, empty table: nothing else is locked.
CREATE TABLE admin_sessions (
    session_id  uuid        PRIMARY KEY,
    user_id     uuid        NOT NULL REFERENCES users (id),
    verified_at timestamptz NOT NULL
);

-- The foreign key's lookups (a user's rows) and the clean-up of an admin's old rows
CREATE INDEX ix_admin_sessions_user ON admin_sessions (user_id, verified_at);
