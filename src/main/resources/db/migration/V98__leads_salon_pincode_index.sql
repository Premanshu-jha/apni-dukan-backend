-- Built CONCURRENTLY, without a transaction and with no lock timeout, for the reasons given in V24: writes to shops go
-- on while it builds. If a build fails, see V24 and "Database changes" in the README (the DROP lets it run again).
SET lock_timeout = 0;

-- The Leads page's Clozest salons in an area (and the lead matching job's candidates): one range of this index per
-- pincode.
DROP INDEX CONCURRENTLY IF EXISTS ix_shops_salon_pincode;
CREATE INDEX CONCURRENTLY ix_shops_salon_pincode ON shops (pincode, id) WHERE type = 'SALON';
