SET LOCAL lock_timeout = '3s';

-- V89's check on push_subscriptions (a browser has its keys, an app none), now checked for the rows that were there
-- before. Its own migration (own transaction): VALIDATE takes only a SHARE UPDATE EXCLUSIVE lock, so sign-ins and
-- messages go on while the table is read.
ALTER TABLE push_subscriptions VALIDATE CONSTRAINT ck_push_subscriptions_kind;
