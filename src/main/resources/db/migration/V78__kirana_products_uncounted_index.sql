-- Built CONCURRENTLY, without a transaction and with no lock timeout (as V70): counter bills go on writing stock
-- while it builds. If the build fails, the DROP lets it run again (see "Database changes" in the README).
SET lock_timeout = 0;

-- The shop's products not counted yet, for its product setup ("I've added all my products") and the names it shows
DROP INDEX CONCURRENTLY IF EXISTS ix_kirana_products_uncounted;
CREATE INDEX CONCURRENTLY ix_kirana_products_uncounted ON kirana_products (shop_id) WHERE active AND counted_at IS NULL;
