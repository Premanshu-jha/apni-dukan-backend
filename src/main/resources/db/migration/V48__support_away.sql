-- On a live database: wait at most 3 s for a lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- Support people who are away (leave, sick): no new tickets go to them, and a ticket of theirs that comes back
-- (they wrote again, opened again) goes to someone else. Gone when they are back, leave support or are deleted.
CREATE TABLE support_away (
    user_id uuid PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    since   timestamptz NOT NULL,
    set_by  uuid REFERENCES users (id) ON DELETE SET NULL
);
