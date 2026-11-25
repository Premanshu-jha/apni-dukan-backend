SET LOCAL lock_timeout = '3s';

-- Urgent screen updates (see "App updates" in the README): a release that fixes something serious (money, bookings,
-- a crash, privacy) is marked urgent, by the super admin on App releases or by CI when the merged PR's title starts
-- with [urgent]. Phones that are open check every 10 minutes and, once they have it, show "A fix is ready" with
-- Restart. Any other release stays silent (used from the next start, or after the app was left for 5 minutes).
--   urgent_note: what it fixes, one line (the super admin's, or the PR title without [urgent]); set by CI at start
--   urgent_at:   when it became urgent (CI's: when it went live); null = not urgent
--   urgent_by:   the super admin who marked it; null when CI did
ALTER TABLE app_releases ADD COLUMN urgent_note VARCHAR(120);
ALTER TABLE app_releases ADD COLUMN urgent_at TIMESTAMPTZ;
ALTER TABLE app_releases ADD COLUMN urgent_by UUID REFERENCES users (id);
