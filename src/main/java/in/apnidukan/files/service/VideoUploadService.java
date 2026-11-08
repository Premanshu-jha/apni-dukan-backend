package in.apnidukan.files.service;

import in.apnidukan.common.context.CurrentUser;
import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.common.context.ShopDirectory;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.files.domain.FilePurpose;
import in.apnidukan.files.domain.StoredFile;
import in.apnidukan.files.repo.FileDeletionStore;
import in.apnidukan.files.repo.StoredFileRepository;
import in.apnidukan.files.repo.VideoUploadStore;
import in.apnidukan.files.repo.VideoUploadStore.Upload;
import in.apnidukan.files.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * The salon video (registration): too big for the backend, so the phone sends it straight to R2.
 * <ol>
 *   <li>{@link #start}: the owner of a salon says the video's size, type and length (length may be unknown); checked
 *       here (15-45 s, up to 100 MB), then a one-time upload link valid {@value #LINK_MINUTES} minutes to a
 *       <b>staging</b> key. The staging key is queued in file_deletions due {@value #STAGING_KEPT_MINUTES} minutes after
 *       the link was given (45 minutes after the link ran out, after the {@value #COMPLETE_MINUTES}-minute window to
 *       complete) and never taken off: whatever is PUT there, before or after "complete", is deleted.</li>
 *   <li>The phone PUTs the file to the link.</li>
 *   <li>{@link #complete} (until {@value #COMPLETE_MINUTES} minutes after the link was given): the staging object must
 *       have the size given; it is copied inside R2 to a new key of its own, and that copy (which the phone's link can't
 *       write) is checked again, size and first bytes, so what is kept is exactly what was checked. Then it is recorded
 *       in files (purpose SHOP_VIDEO). Its key is queued for deletion a day later, until it is set on the shop (PUT
 *       /shops/me/video takes it off). If that day passes, the deletion job deletes its files row with the bytes, and
 *       completing again or setting it answers 409 UPLOAD_GONE. Before that, completing again gives the same file.</li>
 * </ol>
 * R2's check of the signed Content-Length isn't relied on: the size is checked here, on the staging object and on the
 * copy.
 */
@Service
public class VideoUploadService {

    public static final long MAX_BYTES = 100L * 1024 * 1024;
    public static final int MIN_SECONDS = 15;
    public static final int MAX_SECONDS = 45;
    /** A phone may round the length it reads; a second either side is the same video. */
    static final int SLACK_SECONDS = 1;
    static final int LINK_MINUTES = 15;
    /** An upload may be completed until this long after its link was given (the link's 15 minutes, then 30 more). */
    static final int COMPLETE_MINUTES = LINK_MINUTES + 30;
    /** The staging object is deleted this long after the link was given, whatever happened. */
    static final int STAGING_KEPT_MINUTES = LINK_MINUTES + 45;
    /** A completed video not set on the shop within this time is deleted. */
    static final Duration UNUSED_KEPT = Duration.ofHours(24);
    /** Upload links one shop may get a day (India day): a few tries and replacements, not a script filling R2. */
    public static final int MAX_STARTS_PER_DAY = 5;

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final Logger log = LoggerFactory.getLogger(VideoUploadService.class);

    /**
     * What the phone needs to send the file.
     * @param expiresInSeconds how long the link lasts from now (count from when the answer arrived: the phone's clock
     *                         may be off; expiresAt is the same moment by the server's clock)
     */
    public record UploadLink(UUID uploadId, String uploadUrl, String contentType, long sizeBytes, Instant expiresAt,
                             long expiresInSeconds) {
    }

    private final VideoUploadStore uploads;
    private final StoredFileRepository files;
    private final FileDeletionStore deletions;
    private final FileStorage storage;
    private final CurrentUserProvider currentUser;
    private final ShopDirectory shops;
    private final TransactionOperations transactions;
    private final Clock clock;

    public VideoUploadService(VideoUploadStore uploads, StoredFileRepository files, FileDeletionStore deletions,
                              FileStorage storage, CurrentUserProvider currentUser, ShopDirectory shops,
                              TransactionOperations transactions, Clock clock) {
        this.uploads = uploads;
        this.files = files;
        this.deletions = deletions;
        this.storage = storage;
        this.currentUser = currentUser;
        this.shops = shops;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * A one-time upload link for the salon video. 400 VALIDATION_FAILED (length or size field), 400
     * UNSUPPORTED_FILE_TYPE, 413 FILE_TOO_LARGE, 403 OWNER_ONLY, 409 NOT_A_SALON, 429 TOO_MANY_UPLOADS.
     * @param durationSeconds null when the phone couldn't read the length (then only the size limit applies)
     */
    public UploadLink start(long sizeBytes, String contentType, Integer durationSeconds) {
        CurrentUser owner = owner();
        requireSalon(owner);
        VideoFormat format = VideoFormat.ofContentType(contentType).orElseThrow(() -> ApiException.badRequest(
                "UNSUPPORTED_FILE_TYPE", "The video must be MP4, MOV or WebM"));
        if (sizeBytes <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid",
                    Map.of("sizeBytes", "must be more than 0"));
        }
        if (sizeBytes > MAX_BYTES) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", "A video can be at most 100 MB");
        }
        if (durationSeconds != null
                && (durationSeconds < MIN_SECONDS - SLACK_SECONDS || durationSeconds > MAX_SECONDS + SLACK_SECONDS)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid",
                    Map.of("durationSeconds", "must be " + MIN_SECONDS + " to " + MAX_SECONDS + " seconds"));
        }
        Instant now = clock.instant();
        Instant dayStart = LocalDate.now(clock.withZone(INDIA)).atStartOfDay(INDIA).toInstant();
        UUID id = UUID.randomUUID();
        String staging = "shops/%s/%s/staging/%s.%s".formatted(owner.shopId(), FilePurpose.SHOP_VIDEO.folder(), id,
                format.extension());
        transactions.executeWithoutResult(status -> {
            uploads.lockShopStarts(owner.shopId());   // starts of one shop count one after the other
            if (uploads.countSince(owner.shopId(), dayStart) >= MAX_STARTS_PER_DAY) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_UPLOADS",
                        "This shop started " + MAX_STARTS_PER_DAY + " video uploads today. Try again tomorrow.");
            }
            uploads.add(new Upload(id, owner.shopId(), staging, format.contentType(), sizeBytes, durationSeconds,
                    owner.userId(), now, null));
            deletions.queueFrom(staging, now, now.plus(Duration.ofMinutes(STAGING_KEPT_MINUTES)));
        });
        Duration valid = Duration.ofMinutes(LINK_MINUTES);
        return new UploadLink(id, storage.uploadLink(staging, format.contentType(), sizeBytes, valid),
                format.contentType(), sizeBytes, now.plus(valid), valid.toSeconds());
    }

    /**
     * Records the uploaded video. 404 UPLOAD_NOT_FOUND, 409 UPLOAD_GONE (completed, then replaced or purged unused), 409
     * UPLOAD_EXPIRED (more than 45 minutes after the link: start again), 409 UPLOAD_NOT_FINISHED (nothing stored yet:
     * send it again), 400 UPLOAD_SIZE_MISMATCH or UNSUPPORTED_FILE_TYPE. Completing again answers the same file
     * (duplicate true).
     */
    public FileUploadResult complete(UUID uploadId) {
        CurrentUser owner = owner();
        Upload upload = uploads.find(uploadId, owner.shopId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "UPLOAD_NOT_FOUND", "No such upload"));
        if (upload.fileId() != null) {
            return new FileUploadResult(recorded(upload.fileId()), true);
        }
        if (!clock.instant().isBefore(upload.createdAt().plus(Duration.ofMinutes(COMPLETE_MINUTES)))) {
            throw new ApiException(HttpStatus.CONFLICT, "UPLOAD_EXPIRED",
                    "This upload is too old. Send the video again.");
        }

        // R2 is asked outside any transaction: no database connection waits on it
        OptionalLong staged = storage.size(upload.stagingKey());
        if (staged.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "UPLOAD_NOT_FINISHED",
                    "The video hasn't arrived yet. Send it again.");
        }
        if (staged.getAsLong() != upload.sizeBytes() || staged.getAsLong() > MAX_BYTES) {
            deleteQuietly(upload.stagingKey());   // still queued from start: deleted later if this fails
            throw sizeMismatch();
        }

        // Copied to a key of its own that no link can write, then that copy is what is checked and kept. Queued
        // first (due in a day, until the shop uses it), so a copy left by a failure below is deleted all the same.
        VideoFormat format = VideoFormat.ofContentType(upload.contentType()).orElseThrow();
        UUID fileId = UUID.randomUUID();
        String key = "shops/%s/%s/%s.%s".formatted(owner.shopId(), FilePurpose.SHOP_VIDEO.folder(), fileId,
                format.extension());
        Instant now = clock.instant();
        deletions.queueFrom(key, now, now.plus(UNUSED_KEPT));
        storage.copy(upload.stagingKey(), key);
        deleteQuietly(upload.stagingKey());
        OptionalLong kept = storage.size(key);
        if (kept.isEmpty() || kept.getAsLong() != upload.sizeBytes()) {
            deleteNow(key);
            throw sizeMismatch();
        }
        if (!VideoFormat.looksLike(format, storage.firstBytes(key, VideoFormat.HEAD_BYTES))) {
            deleteNow(key);
            throw ApiException.badRequest("UNSUPPORTED_FILE_TYPE", "That file isn't a video.");
        }

        FileUploadResult result = transactions.execute(status -> {
            Upload locked = uploads.lock(uploadId, owner.shopId()).orElseThrow(
                    () -> new ApiException(HttpStatus.NOT_FOUND, "UPLOAD_NOT_FOUND", "No such upload"));
            if (locked.fileId() != null) {   // completed by a second call at the same moment
                return new FileUploadResult(recorded(locked.fileId()), true);
            }
            StoredFile file = new StoredFile(fileId, owner.shopId(), FilePurpose.SHOP_VIDEO, key,
                    upload.contentType(), upload.sizeBytes(), fingerprint(uploadId), null, owner.userId(),
                    clock.instant());
            files.saveAndFlush(file);
            uploads.completed(uploadId, file.getId());
            return new FileUploadResult(file, false);
        });
        if (result.duplicate()) {
            deleteNow(key);   // the other call's copy is the one kept
        }
        return result;
    }

    /** The shop's owner, read from the database. 403 OWNER_ONLY for a helper or barber. */
    private CurrentUser owner() {
        CurrentUser user = currentUser.requireShop();
        if (!"OWNER".equals(user.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER_ONLY", "Only the shop owner can add the salon video.");
        }
        return user;
    }

    /** 409 NOT_A_SALON for a kirana: only salons send a video. */
    private void requireSalon(CurrentUser owner) {
        boolean salon = shops.find(owner.shopId()).map(c -> "SALON".equals(c.type())).orElse(false);
        if (!salon) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_A_SALON", "The salon video is only for salons.");
        }
    }

    /** The file a completed upload recorded; 409 UPLOAD_GONE once it was replaced, or purged unused after a day. */
    private StoredFile recorded(UUID fileId) {
        return files.findById(fileId).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "UPLOAD_GONE",
                "This video expired. Upload it again."));
    }

    private static ApiException sizeMismatch() {
        return ApiException.badRequest("UPLOAD_SIZE_MISMATCH",
                "The video that arrived isn't the one described. Send it again.");
    }

    /** Deletes an object whose key stays queued anyway (the job deletes it later if this fails). */
    private void deleteQuietly(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("Could not delete {} now; FileDeletionJob deletes it later", key, e);
        }
    }

    /** Deletes a refused copy now; it is queued (from before the copy), so it goes later if R2 fails now. */
    private void deleteNow(String key) {
        try {
            storage.delete(key);
            deletions.done(key);
        } catch (RuntimeException e) {
            log.warn("Could not delete the refused video {} now; FileDeletionJob deletes it later", key, e);
        }
    }

    /**
     * files.sha256 is for spotting the same photo uploaded twice; a video's bytes are never read whole here, so it
     * gets a fingerprint of its upload instead (unique, so it never matches another file).
     */
    static String fingerprint(UUID uploadId) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(("video-upload:" + uploadId).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in Java", e);
        }
    }
}
