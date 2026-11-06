package in.apnidukan.files.service;

import in.apnidukan.common.files.FileRefs;
import in.apnidukan.files.domain.StoredFile;
import in.apnidukan.files.repo.StoredFileRepository;
import in.apnidukan.files.storage.FileStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Files for other modules (see {@link FileRefs}). */
@Service
public class FileRefService implements FileRefs {

    private final StoredFileRepository repository;
    private final FileStorage storage;
    private final Duration linkValidity;

    public FileRefService(StoredFileRepository repository, FileStorage storage,
                          @Value("${app.files.link-minutes:5}") long linkMinutes) {
        this.repository = repository;
        this.storage = storage;
        this.linkValidity = Duration.ofMinutes(linkMinutes);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FileRef> find(UUID fileId) {
        return repository.findById(fileId).map(f -> new FileRef(f.getId(), f.getShopId(), f.getPurpose().name(),
                f.getContentType()));
    }

    @Override
    @Transactional(readOnly = true)
    public String link(UUID fileId) {
        StoredFile file = repository.findById(fileId).orElseThrow();
        return storage.signedLink(file.getStorageKey(), linkValidity, null);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Map<UUID, String> links(java.util.Collection<UUID> fileIds) {
        java.util.Map<UUID, String> out = new java.util.HashMap<>();
        if (fileIds.isEmpty()) {
            return out;
        }
        // Signing is done in memory (no call to R2), so only the database read needs batching
        for (StoredFile f : repository.findAllById(new java.util.HashSet<>(fileIds))) {
            out.put(f.getId(), storage.signedLink(f.getStorageKey(), linkValidity, null));
        }
        return out;
    }
}
