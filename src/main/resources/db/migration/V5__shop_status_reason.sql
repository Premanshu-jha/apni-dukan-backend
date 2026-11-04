-- Why the field team sent a shop back to DRAFT (the visit didn't pass) or suspended it. Shown to the owner;
-- cleared when the shop is submitted again, approved or reinstated.
ALTER TABLE shops ADD COLUMN status_reason VARCHAR(200);
ALTER TABLE shops_aud ADD COLUMN status_reason VARCHAR(200);
