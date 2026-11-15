-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- The second set of service pictures (ServiceIcon), checked with a blind recognition test: TRIM, MOUSTACHE,
-- HEAD_MASSAGE and BRAIDS are new; CLEANUP and NAIL_ART are retired because their pictures couldn't be told apart
-- from FACIAL and MANICURE, so a service that had one now has that one (its history too, so every saved name is
-- still one the app knows). The new check is validated in V64 (without holding a lock that blocks writes).
--
-- In three steps, so bookings keep reading services (an UPDATE takes only row locks; the check is swapped in a
-- moment): first the retired names, which the old check already allows the new name of; then the new check; then
-- the services the first set saved with the nearest picture it had, which get the new one that shows them (a head
-- massage, champi or maalish was MASSAGE; a trim HAIRCUT) and which only the new check allows.
UPDATE salon_services SET icon = 'FACIAL' WHERE icon = 'CLEANUP';
UPDATE salon_services SET icon = 'MANICURE' WHERE icon = 'NAIL_ART';
UPDATE salon_services_aud SET icon = 'FACIAL' WHERE icon = 'CLEANUP';
UPDATE salon_services_aud SET icon = 'MANICURE' WHERE icon = 'NAIL_ART';
-- The UPDATEs queue salon_services' deferred trigger (tr_salon_services_versions_flush, V21): run it now, as the
-- ALTER below refuses a table with trigger events still pending.
SET CONSTRAINTS ALL IMMEDIATE;
ALTER TABLE salon_services DROP CONSTRAINT ck_salon_services_icon;
ALTER TABLE salon_services ADD CONSTRAINT ck_salon_services_icon CHECK (icon IS NULL OR icon IN ('HAIRCUT', 'TRIM',
    'BEARD', 'MOUSTACHE', 'SHAVE', 'HAIR_WASH', 'HAIR_SPA', 'HEAD_MASSAGE', 'HAIR_COLOUR', 'STYLING', 'STRAIGHTENING',
    'CURLS', 'BRAIDS', 'FACIAL', 'THREADING', 'WAXING', 'EYELASH', 'MAKEUP', 'BRIDAL', 'MEHENDI', 'MANICURE',
    'PEDICURE', 'MASSAGE', 'BODY_SPA', 'PIERCING', 'KIDS', 'PACKAGE', 'OTHER')) NOT VALID;
-- Last, as the new check allows them (their trigger events may stay pending: no ALTER follows in this transaction)
UPDATE salon_services SET icon = 'HEAD_MASSAGE'
 WHERE icon = 'MASSAGE' AND name ~* '(head|champi|chumpi|maa?lish|oil|सिर|चंपी|हेड|मालिश)'
   AND name !~* '(body|foot|feet|pair|toe|back|leg|hand|बॉडी|पैर)';
UPDATE salon_services SET icon = 'TRIM'
 WHERE icon = 'HAIRCUT' AND name ~* 'trim' AND name !~* '(cut|कट)';
