-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- The picture shown next to a salon service (ServiceIcon), picked by the owner from the icons that fit its name.
-- Null for the services saved before: the apps suggest one from the name. Nullable, no default: adding it rewrites
-- nothing. The check is validated in V62 (without holding a lock that blocks writes).
ALTER TABLE salon_services ADD COLUMN icon VARCHAR(20);
ALTER TABLE salon_services_aud ADD COLUMN icon VARCHAR(20);
ALTER TABLE salon_services ADD CONSTRAINT ck_salon_services_icon CHECK (icon IS NULL OR icon IN ('HAIRCUT', 'BEARD',
    'SHAVE', 'HAIR_COLOUR', 'STYLING', 'HAIR_SPA', 'HAIR_WASH', 'FACIAL', 'CLEANUP', 'MASSAGE', 'THREADING', 'WAXING',
    'MANICURE', 'PEDICURE', 'MAKEUP', 'BRIDAL', 'KIDS', 'MEHENDI', 'STRAIGHTENING', 'CURLS', 'NAIL_ART', 'EYELASH',
    'BODY_SPA', 'PIERCING', 'PACKAGE', 'OTHER')) NOT VALID;
