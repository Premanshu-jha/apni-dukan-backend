-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- The language a person reads the apps in (PATCH /auth/me {"language": "hi"}): their push and in-app messages are
-- written in it when queued (NotificationService), and the field team / support see it next to the owner or the
-- person who asked. Null: never chosen, English. Nullable, no default: adding it rewrites nothing. The check is added
-- NOT VALID (no scan of users while holding its lock) and validated in V84.
-- V83 (not V66): the kirana branch already uses V66 to V82.
ALTER TABLE users ADD COLUMN language VARCHAR(2);
ALTER TABLE users ADD CONSTRAINT ck_users_language
    CHECK (language IS NULL OR language IN ('en', 'hi', 'te', 'mr', 'ta', 'kn')) NOT VALID;
