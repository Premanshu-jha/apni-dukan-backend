package in.apnidukan.files.service;

import in.apnidukan.common.context.CurrentUser;
import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.files.domain.FilePurpose;
import in.apnidukan.files.domain.StoredFile;
import in.apnidukan.files.repo.StoredFileRepository;
import in.apnidukan.files.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    private final StoredFileRepository repository;
    private final FileStorage storage;
    private final CurrentUserProvider currentUser;
    private final Clock clock;
    private final Duration linkValidity;
    private final List<FileUsageChecker> usageCheckers;

    public FileService(StoredFileRepository repository,
                       FileStorage storage,
                       CurrentUserProvider currentUser,
                       Clock clock,
                       @Value("${app.files.link-minutes:5}") long linkMinutes,
                       List<FileUsageChecker> usageCheckers) {
        this.repository = repository;
        this.storage = storage;
        this.currentUser = currentUser;
        this.clock = clock;
        this.linkValidity = Duration.ofMinutes(linkMinutes);
        this.usageCheckers = usageCheckers;
    }

    public FileUploadResult upload(MultipartFile upload, FilePurpose purpose) {
        CurrentUser user = currentUser.get();

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

        UUID id = UUID.randomUUID();
        String key = "shops/%s/%s/%s.%s".formatted(user.shopId(), purpose.folder(), id, type.extension());

        // Pass 2: stream the bytes to R2.
        try (InputStream in = upload.getInputStream()) {
            storage.put(key, in, size, type.contentType(), inspection.sha256());
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
        CurrentUser user = currentUser.get();
        StoredFile file = repository.findByIdAndShopId(fileId, user.shopId())
                .orElseThrow(() -> ApiException.notFound("File"));
        String downloadName = download ? downloadName(file) : null;
        String url = storage.signedLink(file.getStorageKey(), linkValidity, downloadName);
        return new FileLink(url, Instant.now(clock).plus(linkValidity));
    }

    /**
     * Deletes a file from both Neon (its row) and R2 (its bytes). Only files not attached to anything can be deleted.
     * The row is deleted first, inside a transaction; if R2 then refuses, the transaction rolls back and the row stays,
     * so the database never points to a file that is gone.
     */
    @Transactional
    public void delete(UUID fileId) {
        CurrentUser user = currentUser.get();
        StoredFile file = repository.findByIdAndShopId(fileId, user.shopId())
                .orElseThrow(() -> ApiException.notFound("File"));
        if (usageCheckers.stream().anyMatch(checker -> checker.isInUse(file))) {
            throw new ApiException(HttpStatus.CONFLICT, "FILE_IN_USE",
                    "This file is attached to a record and can't be deleted");
        }
        repository.delete(file);
        repository.flush();
        storage.delete(file.getStorageKey());
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

    private void deleteQuietly(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("Could not delete orphan file {} from storage", key, e);
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

    private record Inspection(byte[] head, int headLength, String sha256) {
    }
}
