-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- V83's language check, validated without blocking writes (SHARE UPDATE EXCLUSIVE).
ALTER TABLE users VALIDATE CONSTRAINT ck_users_language;

-- What the field team asks an owner to fix, from a fixed list (ReviewReason names: SHOP_FRONT_PHOTO, LICENCE_EXPIRED,
-- ...), so the partner app shows each in the owner's language; the team's own words stay in the reason column (now
-- optional when reasons are picked). Null: none picked (every row before this version: its words are all there is).
-- Nullable arrays, no default: adding them rewrites nothing.
--   shops: a send back (DRAFT) or a fix asked for on a live shop's re-check, cleared with status_reason. A shop sent
--     back with reasons only keeps status_reason = '' (not null), so "sent back" is still "a draft with a reason"
--     for the lists and ix_shops_review_sent_back (V16); the APIs show '' as null.
--   address_change_requests: a declined request.
--   shop_events: the timeline entry of the decision.
ALTER TABLE shops ADD COLUMN status_reason_codes TEXT[];
ALTER TABLE shops_aud ADD COLUMN status_reason_codes TEXT[];
ALTER TABLE address_change_requests ADD COLUMN reason_codes TEXT[];
ALTER TABLE shop_events ADD COLUMN reason_codes TEXT[];
