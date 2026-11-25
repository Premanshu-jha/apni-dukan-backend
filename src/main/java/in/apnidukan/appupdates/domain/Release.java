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
        String changedByName,
        /** What an urgent release fixes; set with urgentAt (CI's note is kept from the start, before it is live) */
        String urgentNote,
        /** When it became urgent; null: not urgent (phones get it silently) */
        Instant urgentAt,
        /** The super admin who marked it; null when CI did, or it isn't urgent */
        String urgentByName) {

    /** Phones that have it show "A fix is ready" with Restart. */
    public boolean urgent() {
        return urgentAt != null;
    }

    public enum Status {
        /** CI was given the upload link and hasn't said it finished. */
        UPLOADING,
        /** Offered to phones. */
        LIVE,
        /** Taken back by the super admin. */
        PULLED
    }
}
