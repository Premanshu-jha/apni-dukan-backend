-- Built CONCURRENTLY, without a transaction and with no lock timeout, for the reasons given in V24: writes to shops go
-- on while it builds. If the build fails, see V24 and "Database changes" in the README (the DROP lets it run again).
SET lock_timeout = 0;

-- "Is this file some shop's video?" (a file in use can't be deleted), like ix_shops_shop_photo (V13).
DROP INDEX CONCURRENTLY IF EXISTS ix_shops_shop_video;
CREATE INDEX CONCURRENTLY ix_shops_shop_video ON shops (shop_video_file_id) WHERE shop_video_file_id IS NOT NULL;
