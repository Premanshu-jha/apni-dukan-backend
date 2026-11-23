SET LOCAL lock_timeout = '3s';

-- The Android apps (Play Store) get notifications through Firebase Cloud Messaging, not Web Push: their row in
-- push_subscriptions holds the app's FCM registration token in endpoint (unique, like a browser's address) and no
-- keys. kind tells the two apart: WEB (a browser, as before; every existing row) or APP (an installed app).
-- The default fills the existing rows without rewriting the table (a constant default is only noted in the catalog).
ALTER TABLE push_subscriptions ADD COLUMN kind VARCHAR(8) NOT NULL DEFAULT 'WEB';
ALTER TABLE push_subscriptions ALTER COLUMN p256dh DROP NOT NULL;
ALTER TABLE push_subscriptions ALTER COLUMN auth DROP NOT NULL;

-- A browser always has its two keys; an app never has any. Added NOT VALID: no scan while this migration holds the
-- table's lock (V91 checks the existing rows in its own transaction, where reads and writes go on meanwhile).
ALTER TABLE push_subscriptions ADD CONSTRAINT ck_push_subscriptions_kind CHECK (
    (kind = 'WEB' AND p256dh IS NOT NULL AND auth IS NOT NULL)
    OR (kind = 'APP' AND p256dh IS NULL AND auth IS NULL)) NOT VALID;

-- A message to someone with several devices (a browser and the app): the devices it already reached, so a retry for
-- the one that failed (Firebase down, say) doesn't send it twice to the others. Null until one is reached.
ALTER TABLE notification_jobs ADD COLUMN sent_devices UUID[];
