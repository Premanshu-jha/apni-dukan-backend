SET LOCAL lock_timeout = '3s';

-- Screen updates for the Play Store apps (live updates, see "App updates" in the README): each merge to main in the
-- partner or customer repo builds the app's screens (the website built for the app), signs them, and sends them here
-- (AppReleases). The apps ask on every start which one to use (AppUpdates).
CREATE TABLE app_releases (
    id            UUID          PRIMARY KEY,
    app           VARCHAR(10)   NOT NULL CHECK (app IN ('partner', 'customer')),
    -- The CI run's number: the same number the APK of that run carries as its versionCode, so a phone can tell
    -- whether a release is newer than the screens it was installed with. Grows with every run.
    build         INTEGER       NOT NULL CHECK (build > 0),
    -- The native part it needs (android/native-level in the app's repo): offered only to phones whose app has at
    -- least this level, so new screens never call native code an older app doesn't have
    native_level  INTEGER       NOT NULL CHECK (native_level BETWEEN 1 AND 10000),
    -- The website's version (package.json) and the commit, for the admin's list
    version_name  VARCHAR(30)   NOT NULL,
    commit_sha    VARCHAR(40)   NOT NULL,
    size_bytes    BIGINT        NOT NULL CHECK (size_bytes BETWEEN 1 AND 52428800),
    -- Made by CI with the app's private signing key (Capgo's encryption v2): the app checks both with the public key
    -- it was built with, so only a bundle signed with our key is ever installed
    checksum      VARCHAR(1024) NOT NULL,
    session_key   VARCHAR(1024) NOT NULL,
    storage_key   VARCHAR(200)  NOT NULL UNIQUE,
    -- UPLOADING: CI was given the upload link and hasn't finished; LIVE: offered to phones; PULLED: taken back by the
    -- super admin (phones on it go back to the newest LIVE one, or to the screens their app came with)
    status        VARCHAR(10)   NOT NULL CHECK (status IN ('UPLOADING', 'LIVE', 'PULLED')),
    created_at    TIMESTAMPTZ   NOT NULL,
    published_at  TIMESTAMPTZ,
    changed_at    TIMESTAMPTZ,
    changed_by    UUID          REFERENCES users (id),
    UNIQUE (app, build)
);
-- The apps' check reads the LIVE ones newest first (into memory, AppUpdates); the admin's list pages by build
CREATE INDEX ix_app_releases_live ON app_releases (app, build DESC) WHERE status = 'LIVE';

-- Per app, set by the super admin on the admin website's App releases page:
-- min_version_code: phones with an older app (versionCode) see "Please update to continue" (Google Play's update)
-- store_version_code: the newest app version live on the Play Store. Phones with a newer one (a version Google is
-- still reviewing) keep the screens they were installed with, so a reviewer never sees the app change under them.
-- Null: no version is live yet, so no Play Store phone gets screen updates (test APKs always do).
CREATE TABLE app_update_settings (
    app                VARCHAR(10)  PRIMARY KEY CHECK (app IN ('partner', 'customer')),
    min_version_code   INTEGER      CHECK (min_version_code > 0),
    store_version_code INTEGER      CHECK (store_version_code > 0),
    version            INTEGER      NOT NULL DEFAULT 0,
    changed_at         TIMESTAMPTZ,
    changed_by         UUID         REFERENCES users (id)
);
INSERT INTO app_update_settings (app) VALUES ('partner'), ('customer');

-- The super admin's changes on that page are logged with the team's other changes
ALTER TABLE admin_events DROP CONSTRAINT admin_events_event_check;
ALTER TABLE admin_events ADD CONSTRAINT admin_events_event_check CHECK (event IN ('ADMIN_ADDED', 'ADMIN_REMOVED',
    'ADMIN_EDITED', 'TEST_PHONE_ADDED', 'TEST_PHONE_REMOVED', 'TEST_EMAIL_ADDED', 'TEST_EMAIL_REMOVED',
    'TEST_MODE_ON', 'TEST_MODE_OFF', 'APP_RELEASE_PULLED', 'APP_RELEASE_RESTORED', 'APP_SETTINGS_CHANGED'));
