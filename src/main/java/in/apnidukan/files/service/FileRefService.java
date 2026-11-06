package in.apnidukan.files.service;

import in.apnidukan.common.files.FileRefs;
import in.apnidukan.files.domain.StoredFile;
import in.apnidukan.files.repo.StoredFileRepository;
import in.apnidukan.files.storage.FileStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Files for other modules (see {@link FileRefs}).
 *
 * Photo links in lists (salons near you, the field team's lists) are signed for an hour and the same link is given
 * again while it has at least 15 minutes left. A list refreshed every minute then shows the same photo links, so the
 * phone's browser shows the photos it already downloaded instead of fetching every photo again on mobile data, and
 * the answer's ETag stays the same when nothing changed (an unchanged list costs no data at all). Each server keeps
 * up to 20,000 links in memory; the oldest go first.
 */
@Service
public class FileRefService implements FileRefs {

    /** A cached link is given out only while it is valid at least this much longer. */
    static final Duration MIN_LEFT = Duration.ofMinutes(15);
    static final int MAX_CACHED = 20_000;

    private record Cached(String url, Instant until) {
    }

    private final StoredFileRepository repository;
    private final FileStorage storage;
    private final Clock clock;
    private final Duration linkValidity;
    private final Map<UUID, Cached> cache = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Cached> eldest) {
            return size() > MAX_CACHED;
        }
    });

    public FileRefService(StoredFileRepository repository, FileStorage storage, Clock clock,
                          @Value("${app.files.photo-link-minutes:60}") long linkMinutes) {
        if (linkMinutes <= MIN_LEFT.toMinutes()) {
            throw new IllegalStateException("app.files.photo-link-minutes must be more than " + MIN_LEFT.toMinutes());
        }
        this.repository = repository;
        this.storage = storage;
        this.clock = clock;
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
        String cached = cached(fileId, clock.instant());
        if (cached != null) {
            return cached;
        }
        StoredFile file = repository.findById(fileId).orElseThrow();
        return sign(file, clock.instant());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> links(Collection<UUID> fileIds) {
        Map<UUID, String> out = new HashMap<>();
        Instant now = clock.instant();
        Set<UUID> missing = new HashSet<>();
        for (UUID id : fileIds) {
            String cached = cached(id, now);
            if (cached != null) {
                out.put(id, cached);
            } else {
                missing.add(id);
            }
        }
        if (!missing.isEmpty()) {
            // Signing is done in memory (no call to R2), so only the database read needs batching
            for (StoredFile f : repository.findAllById(missing)) {
                out.put(f.getId(), sign(f, now));
            }
        }
        return out;
    }

    private String cached(UUID fileId, Instant now) {
        Cached c = cache.get(fileId);
        return c != null && c.until().minus(MIN_LEFT).isAfter(now) ? c.url() : null;
    }

    private String sign(StoredFile file, Instant now) {
        String url = storage.signedLink(file.getStorageKey(), linkValidity, null);
        cache.put(file.getId(), new Cached(url, now.plus(linkValidity)));
        return url;
    }
}
