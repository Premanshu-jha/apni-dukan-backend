-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

ALTER TABLE salon_services VALIDATE CONSTRAINT ck_salon_services_icon;
