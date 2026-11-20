-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- V72's keys and checks on existing tables, added NOT VALID, now checked. VALIDATE takes only a SHARE UPDATE EXCLUSIVE
-- lock: reads and writes go on meanwhile.
ALTER TABLE kirana_catalogue_barcodes VALIDATE CONSTRAINT ck_kirana_catalogue_barcodes_source;
ALTER TABLE kirana_products VALIDATE CONSTRAINT ck_kirana_products_cost;
ALTER TABLE kirana_stock_moves VALIDATE CONSTRAINT fk_kirana_stock_moves_purchase;
ALTER TABLE kirana_stock_moves VALIDATE CONSTRAINT kirana_stock_moves_reason_check;
ALTER TABLE kirana_stock_moves VALIDATE CONSTRAINT ck_kirana_stock_moves_purchase;
ALTER TABLE kirana_reads VALIDATE CONSTRAINT fk_kirana_reads_shop;
ALTER TABLE kirana_reads VALIDATE CONSTRAINT fk_kirana_reads_by;
ALTER TABLE kirana_shop_ai_calls VALIDATE CONSTRAINT fk_kirana_shop_ai_calls_shop;
ALTER TABLE kirana_purchases VALIDATE CONSTRAINT fk_kirana_purchases_shop;
ALTER TABLE kirana_purchases VALIDATE CONSTRAINT fk_kirana_purchases_by;
ALTER TABLE kirana_purchases VALIDATE CONSTRAINT fk_kirana_purchases_added_by;
ALTER TABLE kirana_wordings VALIDATE CONSTRAINT fk_kirana_wordings_shop;
ALTER TABLE kirana_unknown_scans VALIDATE CONSTRAINT fk_kirana_unknown_scans_shop;
ALTER TABLE kirana_unknown_scans VALIDATE CONSTRAINT fk_kirana_unknown_scans_by;
ALTER TABLE kirana_scan_batches VALIDATE CONSTRAINT fk_kirana_scan_batches_shop;
ALTER TABLE kirana_scan_stats VALIDATE CONSTRAINT fk_kirana_scan_stats_shop;
ALTER TABLE kirana_barcode_votes VALIDATE CONSTRAINT fk_kirana_barcode_votes_shop;
ALTER TABLE kirana_catalogue_reviews VALIDATE CONSTRAINT fk_kirana_catalogue_reviews_shop;
ALTER TABLE kirana_catalogue_reviews VALIDATE CONSTRAINT fk_kirana_catalogue_reviews_by;
