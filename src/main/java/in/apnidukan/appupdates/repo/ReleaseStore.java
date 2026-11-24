package in.apnidukan.appupdates.repo;

import in.apnidukan.appupdates.domain.AppName;
import in.apnidukan.appupdates.domain.Release;
import in.apnidukan.appupdates.domain.UpdateSettings;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** app_releases and app_update_settings (V92). */
@Repository
public class ReleaseStore {

    private static final String COLUMNS = "r.app, r.build, r.native_level, r.version_name, r.commit_sha, r.size_bytes, "
            + "r.checksum, r.session_key, r.storage_key, r.status, r.created_at, r.published_at, r.changed_at, u.name";

    private static final RowMapper<Release> RELEASE = (rs, i) -> new Release(
            AppName.of(rs.getString(1)).orElseThrow(), rs.getInt(2), rs.getInt(3), rs.getString(4), rs.getString(5),
            rs.getLong(6), rs.getString(7), rs.getString(8), rs.getString(9),
            Release.Status.valueOf(rs.getString(10)), instant(rs.getTimestamp(11)), instant(rs.getTimestamp(12)),
            instant(rs.getTimestamp(13)), rs.getString(14));

    private static final RowMapper<UpdateSettings> SETTINGS = (rs, i) -> new UpdateSettings(
            AppName.of(rs.getString(1)).orElseThrow(), (Integer) rs.getObject(2), (Integer) rs.getObject(3),
            rs.getInt(4), instant(rs.getTimestamp(5)), rs.getString(6));

    private final JdbcTemplate jdbc;

    public ReleaseStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** What CI sends when it starts sending a bundle. */
    public record NewRelease(AppName app, int build, int nativeLevel, String versionName, String commitSha,
                             long sizeBytes, String checksum, String sessionKey) {
    }

    /**
     * Adds the release as UPLOADING, or, if CI started this same build before and never finished (a retried run),
     * takes the new details for it, with a new storage key: the earlier attempt's zip (signed with another session
     * key) can never be published with this attempt's checksum. Empty if the build is already LIVE or PULLED: a build
     * is never replaced. One statement, so two runs sending the same build at once can't both add it.
     *
     * @return the storage key to send the bundle to
     */
    public Optional<String> startUpload(NewRelease r, Instant now) {
        String key = "app-releases/" + r.app().value() + "/" + r.build() + "-" + UUID.randomUUID() + ".zip";
        List<String> keys = jdbc.queryForList("INSERT INTO app_releases (id, app, build, native_level, version_name, "
                        + "commit_sha, size_bytes, checksum, session_key, storage_key, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'UPLOADING', ?) "
                        + "ON CONFLICT (app, build) DO UPDATE SET native_level = EXCLUDED.native_level, "
                        + "version_name = EXCLUDED.version_name, commit_sha = EXCLUDED.commit_sha, "
                        + "size_bytes = EXCLUDED.size_bytes, checksum = EXCLUDED.checksum, "
                        + "session_key = EXCLUDED.session_key, storage_key = EXCLUDED.storage_key, "
                        + "created_at = EXCLUDED.created_at "
                        + "WHERE app_releases.status = 'UPLOADING' RETURNING storage_key",
                String.class, UUID.randomUUID(), r.app().value(), r.build(), r.nativeLevel(), r.versionName(),
                r.commitSha(), r.sizeBytes(), r.checksum(), r.sessionKey(), key, Timestamp.from(now));
        return keys.stream().findFirst();
    }

    public Optional<Release> find(AppName app, int build) {
        return jdbc.query("SELECT " + COLUMNS + " FROM app_releases r LEFT JOIN users u ON u.id = r.changed_by "
                + "WHERE r.app = ? AND r.build = ?", RELEASE, app.value(), build).stream().findFirst();
    }

    /**
     * UPLOADING → LIVE, only if the row still names the zip that was checked ({@code storageKey}): false if another
     * call published it first, or a retried start gave it a new key (and new checksum) meanwhile.
     */
    public boolean publish(AppName app, int build, String storageKey, Instant now) {
        return jdbc.update("UPDATE app_releases SET status = 'LIVE', published_at = ? WHERE app = ? AND build = ? "
                + "AND status = 'UPLOADING' AND storage_key = ?", Timestamp.from(now), app.value(), build,
                storageKey) == 1;
    }

    /** from → to, by this super admin. False if it wasn't in {@code from} (any more). */
    public boolean changeStatus(AppName app, int build, Release.Status from, Release.Status to, UUID by, Instant now) {
        return jdbc.update("UPDATE app_releases SET status = ?, changed_at = ?, changed_by = ? "
                        + "WHERE app = ? AND build = ? AND status = ?", to.name(), Timestamp.from(now), by,
                app.value(), build, from.name()) == 1;
    }

    /**
     * The newest LIVE releases of an app, at most {@code perLevel} for each native level, newest first (what the apps'
     * check keeps in memory): an older app always finds the newest release its native part can run, however many
     * releases for newer native parts came after it.
     */
    public List<Release> live(AppName app, int perLevel) {
        return jdbc.query("SELECT " + COLUMNS + " FROM (SELECT *, row_number() OVER (PARTITION BY native_level "
                + "ORDER BY build DESC) AS n FROM app_releases WHERE app = ? AND status = 'LIVE') r "
                + "LEFT JOIN users u ON u.id = r.changed_by WHERE r.n <= ? ORDER BY r.build DESC", RELEASE,
                app.value(), perLevel);
    }

    /** One page of every release of an app, newest first; one more than asked tells whether there is another page. */
    public List<Release> page(AppName app, Integer beforeBuild, int size) {
        int before = beforeBuild == null ? Integer.MAX_VALUE : beforeBuild;
        return jdbc.query("SELECT " + COLUMNS + " FROM app_releases r LEFT JOIN users u ON u.id = r.changed_by "
                + "WHERE r.app = ? AND r.build < ? ORDER BY r.build DESC LIMIT ?", RELEASE, app.value(), before, size);
    }

    public UpdateSettings settings(AppName app) {
        return jdbc.queryForObject("SELECT s.app, s.min_version_code, s.store_version_code, s.version, s.changed_at, "
                + "u.name FROM app_update_settings s LEFT JOIN users u ON u.id = s.changed_by WHERE s.app = ?",
                SETTINGS, app.value());
    }

    /** Saves the settings if they are still at {@code version}; false if someone changed them meanwhile. */
    public boolean saveSettings(AppName app, Integer minVersionCode, Integer storeVersionCode, int version, UUID by,
                                Instant now) {
        return jdbc.update("UPDATE app_update_settings SET min_version_code = ?, store_version_code = ?, "
                        + "version = version + 1, changed_at = ?, changed_by = ? WHERE app = ? AND version = ?",
                minVersionCode, storeVersionCode, Timestamp.from(now), by, app.value(), version) == 1;
    }

    /** A line in the team's change log (admin_events), in the caller's transaction. */
    public void logEvent(UUID by, String event, String detail, Instant now) {
        jdbc.update("INSERT INTO admin_events (id, at, by_user, event, phone, detail) VALUES (?, ?, ?, ?, NULL, ?)",
                UUID.randomUUID(), Timestamp.from(now), by, event, detail);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
