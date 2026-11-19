-- A page of the shop's list sent as a recount: the counts it clearly gives for products the shop has are taken
-- without asking (each can be undone). A constant default: no table rewrite.
SET LOCAL lock_timeout = '3s';

ALTER TABLE kirana_reads ADD COLUMN recount BOOLEAN NOT NULL DEFAULT false;
