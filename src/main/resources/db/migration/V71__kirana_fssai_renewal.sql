-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- A renewed FSSAI certificate (the same licence number) being checked, or refused, while the shop's earlier verified
-- licence is still valid: that licence's last day and its certificate, so the shop keeps taking orders until then and
-- the certificate it trades on stays on record. Cleared once the renewal is verified, or when the team ends it.
-- (Nullable columns: added without rewriting the table.)
ALTER TABLE kirana_online ADD COLUMN fssai_kept_until DATE;
ALTER TABLE kirana_online ADD COLUMN fssai_kept_file_id UUID;
-- The owner who added the licence (issued to him as the food business operator): forgotten when he hands the shop over
ALTER TABLE kirana_online ADD COLUMN fssai_set_by UUID;

-- How many times the customer corrected an order's address (at most 3: each one tells the shop)
ALTER TABLE kirana_orders ADD COLUMN address_changes SMALLINT NOT NULL DEFAULT 0;
