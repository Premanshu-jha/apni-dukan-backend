-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- ============ The salon video (registration) ============
-- A short video of the salon (15-45 s, up to 100 MB) that the field team watches before the visit; customers never
-- see it. It is too big to pass through the backend, so the phone sends it straight to R2 with a one-time upload link
-- (POST /files/video-uploads); this table remembers each link until the phone says it's done (.../complete), when
-- the backend checks the object in R2 and records it in files like any other upload.
--
-- The phone PUTs to a staging key (storage_key here), queued in file_deletions when the link is given, due an hour
-- later and never taken off: anything PUT there is deleted, whatever happened. "complete" copies it inside R2 to a key
-- of its own (the files row), queued for deletion a day later until the shop uses it (PUT /shops/me/video). Rows are
-- forgotten after 7 days (FileDeletionJob). No foreign keys: files and uploads belong to the files module (shop_id as
-- in files), and a file may be deleted later.
CREATE TABLE video_uploads (
    id                UUID          PRIMARY KEY,
    shop_id           UUID          NOT NULL,
    storage_key       VARCHAR(500)  NOT NULL UNIQUE,           -- the staging key the link writes
    content_type      VARCHAR(100)  NOT NULL,
    size_bytes        BIGINT        NOT NULL CHECK (size_bytes > 0),
    duration_seconds  INTEGER,                                  -- null: the phone couldn't read it
    created_by        UUID          NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL,
    file_id           UUID                                      -- set once completed
);
-- Today's upload links of a shop (the daily limit).
CREATE INDEX ix_video_uploads_shop_created ON video_uploads (shop_id, created_at);

-- The shop's video, like its two photos (V4). A new nullable column with no default: no table rewrite. Its foreign key
-- is added NOT VALID so no lock is held while every existing row is read (they are all NULL, so none can be wrong);
-- every row written from now on is checked.
ALTER TABLE shops ADD COLUMN shop_video_file_id UUID;
ALTER TABLE shops ADD CONSTRAINT fk_shops_shop_video FOREIGN KEY (shop_video_file_id) REFERENCES files (id) NOT VALID;
ALTER TABLE shops_aud ADD COLUMN shop_video_file_id UUID;
