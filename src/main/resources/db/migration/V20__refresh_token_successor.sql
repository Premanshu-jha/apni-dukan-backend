-- See V17: wait at most 3 s for the table's lock, then fail the deploy (deploy again) instead of queueing sign-ins.
SET LOCAL lock_timeout = '3s';

-- Which token a refresh token was swapped for. A swapped token coming back within the short grace (the app reloaded
-- while its refresh was on the way, so it never saved the new pair) gets a new pair only while that successor has
-- never been used; otherwise it still means the token was copied. No foreign key: the cleanup deletes ended tokens in
-- any order. Adding a column without a default changes no row, so this is instant.
ALTER TABLE refresh_tokens ADD COLUMN replaced_by UUID;

-- A token replaced under the refresh grace gets its own reason (GRACE_REPLACED). The check from V2 is replaced; the
-- new one is added NOT VALID (no table scan while the lock is held for the change) and then validated.
ALTER TABLE refresh_tokens DROP CONSTRAINT refresh_tokens_revoke_reason_check;
ALTER TABLE refresh_tokens ADD CONSTRAINT refresh_tokens_revoke_reason_check CHECK (revoke_reason IN ('ROTATED',
    'EXPIRED', 'SIGNED_OUT', 'SIGNED_OUT_EVERYWHERE', 'REUSE_DETECTED', 'GRACE_REPLACED')) NOT VALID;
ALTER TABLE refresh_tokens VALIDATE CONSTRAINT refresh_tokens_revoke_reason_check;
