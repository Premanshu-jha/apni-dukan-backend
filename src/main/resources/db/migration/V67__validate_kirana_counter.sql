-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- V66's keys to shops and users, added NOT VALID, now checked (as V39 did for V37). VALIDATE takes only a SHARE UPDATE
-- EXCLUSIVE lock: reads and writes go on meanwhile.
ALTER TABLE kirana_settings VALIDATE CONSTRAINT fk_kirana_settings_shop;
ALTER TABLE kirana_settings VALIDATE CONSTRAINT fk_kirana_settings_upi_by;
ALTER TABLE kirana_products VALIDATE CONSTRAINT fk_kirana_products_shop;
ALTER TABLE kirana_products VALIDATE CONSTRAINT fk_kirana_products_by;
ALTER TABLE kirana_stock_moves VALIDATE CONSTRAINT fk_kirana_stock_moves_shop;
ALTER TABLE kirana_stock_moves VALIDATE CONSTRAINT fk_kirana_stock_moves_by;
ALTER TABLE kirana_khata_customers VALIDATE CONSTRAINT fk_kirana_khata_customers_shop;
ALTER TABLE kirana_khata_customers VALIDATE CONSTRAINT fk_kirana_khata_customers_by;
ALTER TABLE kirana_bills VALIDATE CONSTRAINT fk_kirana_bills_shop;
ALTER TABLE kirana_bills VALIDATE CONSTRAINT fk_kirana_bills_by;
ALTER TABLE kirana_bills VALIDATE CONSTRAINT fk_kirana_bills_voided_by;
ALTER TABLE kirana_khata_entries VALIDATE CONSTRAINT fk_kirana_khata_entries_shop;
ALTER TABLE kirana_khata_entries VALIDATE CONSTRAINT fk_kirana_khata_entries_by;
ALTER TABLE kirana_barcodes VALIDATE CONSTRAINT fk_kirana_barcodes_shop;
ALTER TABLE kirana_barcodes VALIDATE CONSTRAINT fk_kirana_barcodes_by;
ALTER TABLE kirana_bill_returns VALIDATE CONSTRAINT fk_kirana_bill_returns_shop;
ALTER TABLE kirana_bill_returns VALIDATE CONSTRAINT fk_kirana_bill_returns_by;
ALTER TABLE kirana_bill_changes VALIDATE CONSTRAINT fk_kirana_bill_changes_shop;
ALTER TABLE kirana_bill_changes VALIDATE CONSTRAINT fk_kirana_bill_changes_by;
