-- Built CONCURRENTLY, without a transaction and with no lock timeout (as V38): counter bills go on writing stock
-- moves while it builds. If the build fails, the DROP lets it run again (see "Database changes" in the README).
SET lock_timeout = 0;

-- An order part's stock moves (what to give back when it is cancelled, the product's history)
DROP INDEX CONCURRENTLY IF EXISTS ix_kirana_stock_moves_order;
CREATE INDEX CONCURRENTLY ix_kirana_stock_moves_order ON kirana_stock_moves (order_part_id)
    WHERE order_part_id IS NOT NULL;
