package in.apnidukan.appupdates.domain;

import java.time.Instant;

/** One row of app_releases, as the services read it. */
public record Release(
        AppName app,
        int build,
        int nativeLevel,
        String versionName,
        String commitSha,
        long sizeBytes,
        String checksum,
        String sessionKey,
        String storageKey,
        Status status,
        Instant createdAt,
        Instant publishedAt,
        Instant changedAt,
        String changedByName) {

    public enum Status {
        /** CI was given the upload link and hasn't said it finished. */
        UPLOADING,
        /** Offered to phones. */
        LIVE,
        /** Taken back by the super admin. */
        PULLED
    }
}
