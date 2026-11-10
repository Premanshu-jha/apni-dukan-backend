package in.apnidukan.files.service;

import in.apnidukan.common.context.CurrentUser;
import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.files.AttachedFiles;
import in.apnidukan.files.domain.FilePurpose;
import in.apnidukan.files.domain.StoredFile;
import in.apnidukan.files.repo.FileDeletionStore;
import in.apnidukan.files.repo.StoredFileRepository;
import in.apnidukan.files.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Upload flow: check size and real type, fingerprint (sha256), skip exact duplicates,
 * save the bytes to R2, then record a row in the files table.
 */
@Service
public class FileService {

    public static final long MAX_BYTES = 10L * 1024 * 1024;
    /** Uploads one shop may make per day (India day). Bills are a handful a day; this stops a script filling R2. */
    public static final int MAX_UPLOADS_PER_DAY = 100;

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    private final StoredFileRepository repository;
    private final FileStorage storage;
    private final CurrentUserProvider currentUser;
    private final Clock clock;
    private final Duration linkValidity;
    private final List<FileUsageChecker> usageCheckers;
    private final List<AttachedFiles> attachedElsewhere;
    private final PhotoCleaning photos;
    private final TransactionOperations transactions;
    private final FileDeletionStore deletions;

    public FileService(StoredFileRepository repository,
                       FileStorage storage,
                       CurrentUserProvider currentUser,
                       Clock clock,
                       @Value("${app.files.link-minutes:5}") long linkMinutes,
                       List<FileUsageChecker> usageCheckers,
                       List<AttachedFiles> attachedElsewhere,
                       PhotoCleaning photos,
                       TransactionOperations transactions,
                       FileDeletionStore deletions) {
        this.photos = photos;
        this.transactions = transactions;
        this.deletions = deletions;
        this.repository = repository;
        this.storage = storage;
        this.currentUser = currentUser;
        this.clock = clock;
        this.linkValidity = Duration.ofMinutes(linkMinutes);
        this.usageCheckers = usageCheckers;
        this.attachedElsewhere = attachedElsewhere;
    }

    public FileUploadResult upload(MultipartFile upload, FilePurpose purpose) {
        CurrentUser user = shopStaff();
        if (purpose == FilePurpose.SHOP_VIDEO) {
            throw ApiException.badRequest("UNSUPPORTED_FILE_TYPE",
                    "The salon video is sent with an upload link (POST /files/video-uploads), not here");
        }
        requireOwnerFor(user, purpose);

        if (upload == null || upload.isEmpty()) {
            throw ApiException.badRequest("EMPTY_FILE", "The file is empty");
        }
        long size = upload.getSize();
        if (size > MAX_BYTES) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", "Files can be at most 10 MB");
        }

        // Pass 1: read the file once to learn its real type and its fingerprint.
        Inspection inspection = inspect(upload);
        FileType type = FileType.detect(inspection.head(), inspection.headLength())
                .orElseThrow(() -> ApiException.badRequest("UNSUPPORTED_FILE_TYPE",
                        "Only JPEG, PNG, WEBP photos and PDF files can be uploaded"));

        // Same shop, same purpose, same bytes: return the earlier upload instead of storing it twice.
        var existing = repository.findByShopIdAndPurposeAndSha256(user.shopId(), purpose, inspection.sha256());
        if (existing.isPresent()) {
            return new FileUploadResult(existing.get(), true);
        }

        java.time.LocalDate today = java.time.LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Kolkata")));
        Instant dayStart = today.atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
        if (repository.countByShopIdAndCreatedAtGreaterThanEqual(user.shopId(), dayStart) >= MAX_UPLOADS_PER_DAY) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_UPLOADS",
                    "This shop uploaded " + MAX_UPLOADS_PER_DAY + " files today. Try again tomorrow.");
        }

        // Photos of people and shops are drawn again as a smaller JPEG without the phone's hidden details (EXIF);
        // bills and receipts are stored exactly as they came
        byte[] cleaned = null;
        // A registration document may be a PDF (stored as it came) or a photo (cleaned like the others: it may carry
        // where it was taken)
        boolean photoDocument = purpose == FilePurpose.REG_DOC && type != FileType.PDF;
        if (isPhotoPurpose(purpose) || photoDocument) {
            if (type != FileType.JPEG && type != FileType.PNG) {
                throw ApiException.badRequest("UNSUPPORTED_FILE_TYPE", "Photos must be JPEG or PNG");
            }
            try {
                cleaned = photos.clean(upload.getBytes());   // a few at a time per server
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read the uploaded file", e);
            }
            type = FileType.JPEG;
            size = cleaned.length;
        }

        UUID id = UUID.randomUUID();
        String key = "shops/%s/%s/%s.%s".formatted(user.shopId(), purpose.folder(), id, type.extension());

        // Pass 2: stream the bytes to R2.
        try (InputStream in = cleaned != null ? new java.io.ByteArrayInputStream(cleaned) : upload.getInputStream()) {
            storage.put(key, in, size, type.contentType());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded file", e);
        }

        StoredFile file = new StoredFile(id, user.shopId(), purpose, key, type.contentType(), size,
                inspection.sha256(), cleanName(upload.getOriginalFilename()), user.userId(), Instant.now(clock));
        try {
            return new FileUploadResult(repository.saveAndFlush(file), false);
        } catch (DataIntegrityViolationException race) {
            // The same file was uploaded twice at the same moment; the other request won. Keep its row.
            deleteQuietly(key);
            return repository.findByShopIdAndPurposeAndSha256(user.shopId(), purpose, inspection.sha256())
                    .map(winner -> new FileUploadResult(winner, true))
                    .orElseThrow(() -> race);
        } catch (RuntimeException e) {
            deleteQuietly(key);
            throw e;
        }
    }

    /** A link that opens the file straight from R2 for a few minutes. Only the file's own shop can get one. */
    public FileLink link(UUID fileId, boolean download) {
        CurrentUser user = shopStaff();
        StoredFile file = repository.findByIdAndShopId(fileId, user.shopId())
                .orElseThrow(() -> ApiException.notFound("File"));
        requireOwnerFor(user, file.getPurpose());
        String downloadName = download ? downloadName(file) : null;
        String url = storage.signedLink(file.getStorageKey(), linkValidity, downloadName);
        return new FileLink(url, Instant.now(clock).plus(linkValidity));
    }

    /**
     * Deletes a file from both Neon (its row) and R2 (its bytes). Only files not attached to anything can be deleted.
     * In one transaction the row goes and its storage key is queued in file_deletions; the bytes are deleted only after
     * that committed and the database connection went back to the pool, so no connection waits on R2 (and a rollback
     * never leaves a row pointing at bytes already gone). If R2 fails then, the delete still succeeded for the person
     * (the row is gone, nothing can link to the bytes); the key stays queued and FileDeletionJob deletes the object
     * later, so no object is ever left behind for good.
     */
    public void delete(UUID fileId) {
        CurrentUser user = shopStaff();
        String key = transactions.execute(status -> {
            String gone = deleteRow(user, fileId);
            deletions.queue(gone, Instant.now(clock));
            return gone;
        });
        deleteQueued(key);
    }

    /** Removes the file's row if nothing uses it; returns its storage key. */
    private String deleteRow(CurrentUser user, UUID fileId) {
        StoredFile file = repository.findByIdAndShopId(fileId, user.shopId())
                .orElseThrow(() -> ApiException.notFound("File"));
        requireOwnerFor(user, file.getPurpose());
        if (usageCheckers.stream().anyMatch(checker -> checker.isInUse(file))
                || attachedElsewhere.stream().anyMatch(module -> module.isAttached(file.getId()))) {
            throw new ApiException(HttpStatus.CONFLICT, "FILE_IN_USE",
                    "This file is attached to a record and can't be deleted");
        }
        // Conditional delete: if two delete requests race, only one removes the row; the other gets 404.
        int deleted;
        try {
            deleted = repository.deleteByIdAndShopId(file.getId(), user.shopId());
        } catch (DataIntegrityViolationException attachedMeanwhile) {
            // Set as a photo at the same moment: the database's foreign key keeps the file
            throw new ApiException(HttpStatus.CONFLICT, "FILE_IN_USE",
                    "This file is attached to a record and can't be deleted");
        }
        if (deleted == 0) {
            throw ApiException.notFound("File");
        }
        return file.getStorageKey();
    }

    /**
     * The owner or a helper of a shop. A salon's barber (BARBER) has no use for the shop's files (bills, receipts,
     * the owner's photo), so he gets 403 NOT_ALLOWED. The shop and role are read from the database
     * (CurrentUserProvider.requireShop), so a token from before a closing, a hand-over or a removal can't reach the
     * shop's files.
     */
    private CurrentUser shopStaff() {
        CurrentUser user = currentUser.requireShop();
        if ("BARBER".equals(user.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_ALLOWED", "Barbers can't use the shop's files.");
        }
        return user;
    }

    /**
     * The salon video and the registration document are the owner's (and the field team's, on their own pages): 403
     * OWNER_ONLY for a helper.
     */
    private static void requireOwnerFor(CurrentUser user, FilePurpose purpose) {
        if (purpose == FilePurpose.SHOP_VIDEO && !"OWNER".equals(user.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER_ONLY", "Only the shop owner can open the salon video.");
        }
        if (purpose == FilePurpose.REG_DOC && !"OWNER".equals(user.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER_ONLY",
                    "Only the shop owner can add or open the registration document.");
        }
    }

    private static Inspection inspect(MultipartFile upload) {
        try (InputStream in = upload.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] head = new byte[12];
            int headLength = 0;
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (headLength < head.length) {
                    int take = Math.min(head.length - headLength, read);
                    System.arraycopy(buffer, 0, head, headLength, take);
                    headLength += take;
                }
                digest.update(buffer, 0, read);
            }
            return new Inspection(head, headLength, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded file", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in Java", e);
        }
    }

    /**
     * Bytes stored for an upload whose row couldn't be saved: queued first (so the job deletes them if this server
     * can't), then deleted now. If even the queue can't be written (the database is the problem), only the log keeps
     * the key.
     */
    private void deleteQuietly(String key) {
        try {
            deletions.queue(key, Instant.now(clock));
        } catch (RuntimeException e) {
            log.error("Could not queue orphan file {} for deletion; deleting it now", key, e);
        }
        deleteQueued(key);
    }

    /** Deletes a queued key's object now and takes it off the queue; on failure it stays for FileDeletionJob. */
    private void deleteQueued(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("Could not delete {} from storage now; FileDeletionJob tries again", key, e);
            return;
        }
        try {
            deletions.done(key);
        } catch (RuntimeException e) {
            // Harmless: the job deletes the (already gone) key again and then takes it off the queue
            log.warn("Could not take {} off the deletion queue", key, e);
        }
    }

    private static String cleanName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        return base.length() > 255 ? base.substring(base.length() - 255) : base;
    }

    private static String downloadName(StoredFile file) {
        if (file.getOriginalName() != null) {
            return file.getOriginalName();
        }
        String key = file.getStorageKey();
        return key.substring(key.lastIndexOf('/') + 1);
    }

    private static boolean isPhotoPurpose(FilePurpose purpose) {
        return purpose == FilePurpose.SHOP_PHOTO || purpose == FilePurpose.OWNER_PHOTO
                || purpose == FilePurpose.STAFF_PHOTO;
    }

    private record Inspection(byte[] head, int headLength, String sha256) {
    }
}
