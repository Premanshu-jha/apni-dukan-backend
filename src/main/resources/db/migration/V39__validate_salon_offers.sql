-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- V37's checks and keys, added NOT VALID, now checked for the existing rows too (as V36 did for V34). VALIDATE reads
-- every row but takes only a SHARE UPDATE EXCLUSIVE lock: reads and writes go on meanwhile.
ALTER TABLE appointment_services VALIDATE CONSTRAINT ck_appointment_services_offer;
ALTER TABLE appointment_services VALIDATE CONSTRAINT fk_appointment_services_offer;
ALTER TABLE salon_offers VALIDATE CONSTRAINT fk_salon_offers_shop;
ALTER TABLE salon_offers VALIDATE CONSTRAINT fk_salon_offers_by;
ALTER TABLE salon_offer_services VALIDATE CONSTRAINT fk_salon_offer_services_service;
ALTER TABLE salon_offer_mutes VALIDATE CONSTRAINT fk_salon_offer_mutes_user;
ALTER TABLE salon_offer_mutes VALIDATE CONSTRAINT fk_salon_offer_mutes_shop;
