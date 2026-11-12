-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- ============ A device's sign-in has a name ============
-- Each sign-in on a device starts a session; every refresh token swapped from it keeps the session's id, and the
-- access tokens carry it ("sid"). What is allowed per device keys on it: the code that unlocks editing a verified
-- shop for 15 minutes (EditUnlockStore) works only on the device it was entered on.
--
-- Nullable, no default: no table rewrite. Tokens from before this have none: their next swap starts the session with
-- the token's own id (RefreshTokenStore.Stored.session), so nobody is signed out.
ALTER TABLE refresh_tokens ADD COLUMN session_id UUID;
