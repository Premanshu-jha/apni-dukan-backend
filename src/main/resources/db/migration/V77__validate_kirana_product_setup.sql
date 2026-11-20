-- Checks V76's rules on existing rows without blocking writes (SHARE UPDATE EXCLUSIVE). kirana_list_lines is new and
-- empty; its keys are validated here too so a later dump/restore keeps them.
SET LOCAL lock_timeout = '3s';
ALTER TABLE kirana_reads VALIDATE CONSTRAINT kirana_reads_kind_check;
ALTER TABLE kirana_list_lines VALIDATE CONSTRAINT fk_kirana_list_lines_shop;
ALTER TABLE kirana_list_lines VALIDATE CONSTRAINT fk_kirana_list_lines_read;
ALTER TABLE kirana_products VALIDATE CONSTRAINT fk_kirana_products_photo_new;
ALTER TABLE kirana_products VALIDATE CONSTRAINT fk_kirana_products_photo_new_size;
ALTER TABLE kirana_products VALIDATE CONSTRAINT ck_kirana_products_photo_check;
