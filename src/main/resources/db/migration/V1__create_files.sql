-- Files uploaded by shops (shop photos, distributor bills, payment receipts).
-- The file itself lives in Cloudflare R2; this table only describes it.
-- Foreign keys to shops/users are added in the migration that creates those tables.
CREATE TABLE files (
    id             UUID         PRIMARY KEY,
    shop_id        UUID         NOT NULL,
    purpose        VARCHAR(30)  NOT NULL,
    storage_key    VARCHAR(500) NOT NULL UNIQUE,
    content_type   VARCHAR(100) NOT NULL,
    size_bytes     BIGINT       NOT NULL,
    sha256         VARCHAR(64)  NOT NULL,
    original_name  VARCHAR(255),
    uploaded_by    UUID         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL
);

-- The same file uploaded twice by one shop for the same purpose is caught by this.
CREATE UNIQUE INDEX ux_files_shop_purpose_sha256 ON files (shop_id, purpose, sha256);
CREATE INDEX ix_files_shop ON files (shop_id);
