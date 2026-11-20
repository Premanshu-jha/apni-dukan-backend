-- V79's rules and keys checked against the rows there (a scan without blocking writes).
SET LOCAL lock_timeout = '3s';

ALTER TABLE files VALIDATE CONSTRAINT files_shop_unless_ticket;
ALTER TABLE files VALIDATE CONSTRAINT files_uploader_unless_ticket;
ALTER TABLE kirana_catalogue_barcodes VALIDATE CONSTRAINT ck_kirana_catalogue_barcodes_source;
ALTER TABLE kirana_gs1_lookups VALIDATE CONSTRAINT fk_kirana_gs1_item;
ALTER TABLE kirana_gs1_lookups VALIDATE CONSTRAINT fk_kirana_gs1_shop;
ALTER TABLE kirana_gs1_lookups VALIDATE CONSTRAINT fk_kirana_gs1_product;
