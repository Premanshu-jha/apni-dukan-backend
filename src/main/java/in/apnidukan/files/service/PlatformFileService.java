package in.apnidukan.files.service;

import in.apnidukan.common.files.AttachedFiles;
import in.apnidukan.common.files.PlatformFiles;
import in.apnidukan.files.domain.FilePurpose;
import in.apnidukan.files.domain.StoredFile;
import in.apnidukan.files.repo.StoredFileRepository;
import in.apnidukan.files.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The platform's own pictures (PlatformFiles): cleaned like a shop's photo, stored under "platform/catalogue", no
 * shop's (purpose CATALOGUE_PHOTO). The same picture twice is one file (its fingerprint; the fetching job runs on one
 * server at a time). Nothing a shop sees or can delete: they are reached only through the shared catalogue.
 */
@Service
public class PlatformFileService implements PlatformFiles {

    private static final Logger log = LoggerFactory.getLogger(PlatformFileService.class);

    private final StoredFileRepository repository;
    private final FileStorage storage;
    private final PhotoCleaning photos;
    private final List<AttachedFiles> attached;
    private final Clock clock;

    public PlatformFileService(StoredFileRepository repository, FileStorage storage, PhotoCleaning photos,
                               List<AttachedFiles> attached, Clock clock) {
        this.repository = repository;
        this.storage = storage;
        this.photos = photos;
        this.attached = attached;
        this.clock = clock;
    }

    @Override
    public Optional<Stored> storePicture(byte[] bytes, String name) {
        if (bytes == null || bytes.length == 0 || bytes.length > FileService.MAX_BYTES) {
            return Optional.empty();
        }
        Optional<FileType> type = FileType.detect(bytes, Math.min(bytes.length, 64));
        if (type.isEmpty() || type.get() != FileType.JPEG && type.get() != FileType.PNG) {
            return Optional.empty();
        }
        byte[] jpeg;
        try {
            jpeg = photos.clean(bytes);
        } catch (RuntimeException e) {
            log.info("A platform picture couldn't be read ({}): {}", name, e.getMessage());
            return Optional.empty();
        }
        String sha = sha256(jpeg);
        Optional<StoredFile> had = repository.findFirstByShopIdIsNullAndPurposeAndSha256(FilePurpose.CATALOGUE_PHOTO,
                sha);
        if (had.isPresent()) {
            return Optional.of(new Stored(had.get().getId(), jpeg));
        }
        UUID id = UUID.randomUUID();
        String key = "platform/catalogue/%s.jpg".formatted(id);
        storage.put(key, new ByteArrayInputStream(jpeg), jpeg.length, FileType.JPEG.contentType());
        StoredFile file = new StoredFile(id, null, FilePurpose.CATALOGUE_PHOTO, key, FileType.JPEG.contentType(),
                jpeg.length, sha, name == null ? null : name.length() > 255 ? name.substring(0, 255) : name,
                null, Instant.now(clock));
        try {
            repository.saveAndFlush(file);
            return Optional.of(new Stored(id, jpeg));
        } catch (RuntimeException e) {
            storage.delete(key);
            throw e;
        }
    }

    @Override
    public void discard(UUID fileId) {
        repository.findById(fileId).filter(f -> f.getShopId() == null
                        && f.getPurpose() == FilePurpose.CATALOGUE_PHOTO)
                .filter(f -> attached.stream().noneMatch(a -> a.isAttached(f.getId())))
                .ifPresent(f -> {
                    repository.delete(f);
                    storage.delete(f.getStorageKey());
                });
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
