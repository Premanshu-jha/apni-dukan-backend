-- Built CONCURRENTLY, without a transaction and with no lock timeout (as V78): salons go on saving services and
-- customers go on booking while it builds. If the build fails, the DROP lets it run again (see "Database changes" in
-- the README).
SET lock_timeout = 0;

-- The customers' salon search (GET /salons?q=, ShopDirectoryService): the map index finds the salons within 2 km,
-- then each one's active services are checked for the words, the picture and a barber who does them. With the name,
-- picture and id in the index itself, that check reads only this index (an index-only scan: about a fifth of the
-- pages that reading the services' rows through ix_salon_services_shop takes), never the table. A trigram index on
-- the names would be the wrong way round: common words ("haircut") are in nearly every salon in India, so starting
-- from them reads far more than the few hundred salons nearby. The salons' own names are checked on the rows the
-- map index already read.
DROP INDEX CONCURRENTLY IF EXISTS ix_salon_services_search;
CREATE INDEX CONCURRENTLY ix_salon_services_search ON salon_services (shop_id) INCLUDE (name, icon, id) WHERE active;
