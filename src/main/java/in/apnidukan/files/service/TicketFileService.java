package in.apnidukan.files.service;

import in.apnidukan.common.context.CurrentUser;
import in.apnidukan.common.context.CurrentUserProvider;
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
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
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
 * Photos, videos and PDFs sent with support tickets, by anyone signed in (a customer, a salon's people, support). They
 * belong to the person who sent them (no shop) and are kept only once a ticket message carries them: each is queued
 * for deletion a day after it was stored, and the helpdesk takes it off the queue when the message is sent
 * (FileRefs.keep). A file never sent goes with FileDeletionJob, row and bytes.
 * <ul>
 *   <li>A photo or PDF (at most 10 MB) comes through here: photos are drawn again as a JPEG without the phone's
 *       hidden details (where it was taken), PDFs are kept as they came.</li>
 *   <li>A video (MP4, MOV or WebM, at most 100 MB) goes from the phone straight to R2 with a one-time upload link,
 *       then {@link #completeVideo} checks what arrived (size and first bytes), as the salon video does.</li>
 * </ul>
 * At most {@value #MAX_PER_DAY} uploads per person a day (India day), files and video links together.
 */
@Service
public class TicketFileService {

    public static final long MAX_FILE_BYTES = FileService.MAX_BYTES;
    public static final long MAX_VIDEO_BYTES = 100L * 1024 * 1024;
    public static final int MAX_PER_DAY = 30;
    static final Duration UNUSED_KEPT = Duration.ofHours(24);
    static final int LINK_MINUTES = 15;
    static final int COMPLETE_MINUTES = LINK_MINUTES + 30;
    static final int STAGING_KEPT_MINUTES = LINK_MINUTES + 45;
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final Logger log = LoggerFactory.getLogger(TicketFileService.class);

    private final StoredFileRepository files;
    private final VideoUploadStore uploads;
    private final FileDeletionStore deletions;
    private final FileStorage storage;
    private final PhotoCleaning photos;
    private final CurrentUserProvider currentUser;
    private final TransactionOperations transactions;
    private final Clock clock;

    public TicketFileService(StoredFileRepository files, VideoUploadStore uploads, FileDeletionStore deletions,
                             FileStorage storage, PhotoCleaning photos, CurrentUserProvider currentUser,
                             TransactionOperations transactions, Clock clock) {
        this.files = files;
        this.uploads = uploads;
        this.deletions = deletions;
        this.storage = storage;
        this.photos = photos;
        this.currentUser = currentUser;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * A photo (JPEG or PNG; stored as a cleaned JPEG) or a PDF. 400 EMPTY_FILE, 413 FILE_TOO_LARGE, 400
     * UNSUPPORTED_FILE_TYPE, 429 TOO_MANY_UPLOADS.
     */
    public StoredFile upload(MultipartFile upload) {
        CurrentUser user = currentUser.get();
        if (upload == null || upload.isEmpty()) {
            throw ApiException.badRequest("EMPTY_FILE", "The file is empty");
        }
        if (upload.getSize() > MAX_FILE_BYTES) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE",
                    "A photo or PDF can be at most 10 MB. Send a smaller one, or a screenshot of it.");
        }
        byte[] bytes;
        try {
            bytes = upload.getBytes();   // at most 10 MB
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded file", e);
        }
        FileType type = FileType.detect(bytes, bytes.length).orElseThrow(() -> ApiException.badRequest(
                "UNSUPPORTED_FILE_TYPE", "Send a photo (JPEG or PNG), a video or a PDF"));
        if (type == FileType.WEBP) {
            throw ApiException.badRequest("UNSUPPORTED_FILE_TYPE", "Photos must be JPEG or PNG");
        }
        if (type != FileType.PDF) {
            bytes = photos.clean(bytes);   // no hidden details (where it was taken, the phone)
            type = FileType.JPEG;
        }
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        String key = "%s/%s/%s.%s".formatted(FilePurpose.TICKET_FILE.folder(), user.userId(), id, type.extension());
        StoredFile file = new StoredFile(id, null, FilePurpose.TICKET_FILE, key, type.contentType(), bytes.length,
                sha256(bytes), cleanName(upload.getOriginalFilename()), user.userId(), now);
        // Counted, recorded and queued (due in a day, until a message carries it) under the person's lock, so uploads
        // at the same moment can't pass the daily limit together; if the bytes then fail to go up, the queue deletes
        // the row (and anything that did arrive) all the same, and its id was never given out.
        transactions.executeWithoutResult(status -> {
            uploads.lockPersonStarts(user.userId());
            requireUnderDailyLimit(user.userId());
            deletions.queueFrom(key, now, now.plus(UNUSED_KEPT));
            files.saveAndFlush(file);
        });
        storage.put(key, new ByteArrayInputStream(bytes), bytes.length, type.contentType());
        return file;
    }

    /**
     * A one-time link to PUT a video to (with exactly this Content-Type), valid {@value #LINK_MINUTES} minutes. 400
     * UNSUPPORTED_FILE_TYPE, 400 VALIDATION_FAILED, 413 FILE_TOO_LARGE, 429 TOO_MANY_UPLOADS.
     */
    public VideoUploadService.UploadLink startVideo(long sizeBytes, String contentType) {
        CurrentUser user = currentUser.get();
        VideoFormat format = VideoFormat.ofContentType(contentType).orElseThrow(() -> ApiException.badRequest(
                "UNSUPPORTED_FILE_TYPE", "The video must be MP4, MOV or WebM"));
        if (sizeBytes <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid",
                    Map.of("sizeBytes", "must be more than 0"));
        }
        if (sizeBytes > MAX_VIDEO_BYTES) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE",
                    "A video can be at most 100 MB (under a minute on most phones). Send a shorter clip.");
        }
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        String staging = "%s/%s/staging/%s.%s".formatted(FilePurpose.TICKET_FILE.folder(), user.userId(), id,
                format.extension());
        transactions.executeWithoutResult(status -> {
            uploads.lockPersonStarts(user.userId());
            requireUnderDailyLimit(user.userId());
            uploads.add(new Upload(id, null, staging, format.contentType(), sizeBytes, null, user.userId(), now,
                    null));
            deletions.queueFrom(staging, now, now.plus(Duration.ofMinutes(STAGING_KEPT_MINUTES)));
        });
        Duration valid = Duration.ofMinutes(LINK_MINUTES);
        return new VideoUploadService.UploadLink(id, storage.uploadLink(staging, format.contentType(), sizeBytes,
                valid), format.contentType(), sizeBytes, now.plus(valid), valid.toSeconds());
    }

    /**
     * After the PUT: checks the video that arrived and records it (to send with a message within a day). 404
     * UPLOAD_NOT_FOUND, 409 UPLOAD_EXPIRED, 409 UPLOAD_NOT_FINISHED, 400 UPLOAD_SIZE_MISMATCH or UNSUPPORTED_FILE_TYPE.
     * Completing again answers the same file.
     */
    public StoredFile completeVideo(UUID uploadId) {
        CurrentUser user = currentUser.get();
        Upload upload = uploads.findPersonal(uploadId, user.userId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "UPLOAD_NOT_FOUND", "No such upload"));
        if (upload.fileId() != null) {
            return recorded(upload.fileId());
        }
        if (!clock.instant().isBefore(upload.createdAt().plus(Duration.ofMinutes(COMPLETE_MINUTES)))) {
            throw new ApiException(HttpStatus.CONFLICT, "UPLOAD_EXPIRED", "This upload is too old. Add the video again.");
        }
        // R2 is asked outside any transaction: no database connection waits on it
        OptionalLong staged = storage.size(upload.stagingKey());
        if (staged.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "UPLOAD_NOT_FINISHED",
                    "The video hasn't arrived yet. Add it again.");
        }
        if (staged.getAsLong() != upload.sizeBytes()) {
            deleteQuietly(upload.stagingKey());
            throw sizeMismatch();
        }
        // Copied to a key no link can write; that copy is checked and kept. Queued first (due in a day, until a
        // message carries it), so a copy left by a failure below is deleted all the same.
        VideoFormat format = VideoFormat.ofContentType(upload.contentType()).orElseThrow();
        UUID fileId = UUID.randomUUID();
        String key = "%s/%s/%s.%s".formatted(FilePurpose.TICKET_FILE.folder(), user.userId(), fileId,
                format.extension());
        Instant now = clock.instant();
        transactions.executeWithoutResult(status -> deletions.queueFrom(key, now, now.plus(UNUSED_KEPT)));
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
        StoredFile result = transactions.execute(status -> {
            Upload locked = uploads.lockPersonal(uploadId, user.userId()).orElseThrow(
                    () -> new ApiException(HttpStatus.NOT_FOUND, "UPLOAD_NOT_FOUND", "No such upload"));
            if (locked.fileId() != null) {   // completed by a second call at the same moment
                return recorded(locked.fileId());
            }
            StoredFile file = new StoredFile(fileId, null, FilePurpose.TICKET_FILE, key, upload.contentType(),
                    upload.sizeBytes(), VideoUploadService.fingerprint(uploadId), null, user.userId(), now);
            files.saveAndFlush(file);
            uploads.completed(uploadId, fileId);
            return file;
        });
        if (result != null && !result.getId().equals(fileId)) {
            deleteNow(key);   // the other call's copy is the one kept
        }
        return result;
    }

    private void requireUnderDailyLimit(UUID userId) {
        Instant dayStart = LocalDate.now(clock.withZone(INDIA)).atStartOfDay(INDIA).toInstant();
        long today = files.countByUploadedByAndPurposeAndCreatedAtGreaterThanEqual(userId, FilePurpose.TICKET_FILE,
                dayStart) + uploads.countPersonalSince(userId, dayStart);
        if (today >= MAX_PER_DAY) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_UPLOADS",
                    "You've added " + MAX_PER_DAY + " files today. Try again tomorrow, or describe it in words.");
        }
    }

    private StoredFile recorded(UUID fileId) {
        return files.findById(fileId).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "UPLOAD_GONE",
                "This video expired. Add it again."));
    }

    private static ApiException sizeMismatch() {
        return ApiException.badRequest("UPLOAD_SIZE_MISMATCH",
                "The video that arrived isn't the one described. Add it again.");
    }

    private void deleteQuietly(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("Could not delete {} now; FileDeletionJob deletes it later", key, e);
        }
    }

    private void deleteNow(String key) {
        try {
            storage.delete(key);
            deletions.done(key);
        } catch (RuntimeException e) {
            log.warn("Could not delete the refused video {} now; FileDeletionJob deletes it later", key, e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in Java", e);
        }
    }

    /** The name as the phone gave it, without any folder, at most 255 characters; null when there is none. */
    static String cleanName(String name) {
        if (name == null) {
            return null;
        }
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).strip();
        base = base.replaceAll("\\p{Cntrl}", "");
        if (base.isEmpty()) {
            return null;
        }
        return base.length() > 255 ? base.substring(0, 255) : base;
    }
}
