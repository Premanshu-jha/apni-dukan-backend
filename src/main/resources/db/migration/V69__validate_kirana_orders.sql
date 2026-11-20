-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- V68's keys to shops and users, added NOT VALID, now checked (as V67 did for V66). VALIDATE takes only a SHARE UPDATE
-- EXCLUSIVE lock: reads and writes go on meanwhile.
ALTER TABLE kirana_catalogue_items VALIDATE CONSTRAINT fk_kirana_catalogue_items_shop;
ALTER TABLE kirana_online VALIDATE CONSTRAINT fk_kirana_online_shop;
ALTER TABLE kirana_online VALIDATE CONSTRAINT fk_kirana_online_upi_by;
ALTER TABLE kirana_checkouts VALIDATE CONSTRAINT fk_kirana_checkouts_customer;
ALTER TABLE kirana_orders VALIDATE CONSTRAINT fk_kirana_orders_shop;
ALTER TABLE kirana_orders VALIDATE CONSTRAINT fk_kirana_orders_customer;
ALTER TABLE kirana_order_parts VALIDATE CONSTRAINT fk_kirana_order_parts_shop;
ALTER TABLE kirana_order_parts VALIDATE CONSTRAINT fk_kirana_order_parts_by;
ALTER TABLE kirana_payouts VALIDATE CONSTRAINT fk_kirana_payouts_shop;
ALTER TABLE kirana_earnings VALIDATE CONSTRAINT fk_kirana_earnings_shop;
ALTER TABLE kirana_payout_accounts VALIDATE CONSTRAINT fk_kirana_payout_accounts_shop;
ALTER TABLE kirana_products VALIDATE CONSTRAINT fk_kirana_products_catalogue;
ALTER TABLE kirana_stock_moves VALIDATE CONSTRAINT fk_kirana_stock_moves_part;
ALTER TABLE kirana_stock_moves VALIDATE CONSTRAINT kirana_stock_moves_reason_check;
ALTER TABLE kirana_stock_moves VALIDATE CONSTRAINT ck_kirana_stock_moves_order;
ALTER TABLE kirana_stock_moves VALIDATE CONSTRAINT ck_kirana_stock_moves_by;
