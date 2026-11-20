-- A line counted automatically by a recount: the product's stock and when it was last counted, just before (to undo
-- it); null otherwise. Its own migration, so kirana_reads isn't held locked while this table's lock is waited for.
-- Nullable: no rewrite.
SET LOCAL lock_timeout = '3s';

ALTER TABLE kirana_list_lines ADD COLUMN auto_before NUMERIC(12,3),
    ADD COLUMN auto_counted_before TIMESTAMPTZ;
