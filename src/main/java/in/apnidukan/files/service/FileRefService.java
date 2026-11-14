package in.apnidukan.files.service;

import in.apnidukan.common.files.FileRefs;
import in.apnidukan.files.domain.StoredFile;
import in.apnidukan.files.repo.StoredFileRepository;
import in.apnidukan.files.storage.FileStorage;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Files for other modules (see {@link FileRefs}).
 *
 * Photo links in lists (salons near you, the field team's lists) are signed with the time rounded down to the hour,
 * valid from then for an hour plus {@code app.files.photo-link-minutes} (60). So every copy of the backend gives the
 * very same link for a photo during the whole hour, and a link handed out at :59 still works for at least 60 more
 * minutes. A list refreshed every minute, served by any copy, shows the same photo links: the phone's browser shows
 * the photos it already downloaded instead of fetching every photo again on mobile data, and the answer's ETag stays
 * the same when nothing changed (an unchanged list costs no data at all). Signing is done in memory (no call to R2);
 * each server keeps up to 20,000 links (Caffeine: bounded, no lock for the whole map), for the hour they were signed.
 */
@Service
public class FileRefService implements FileRefs {

    /** The extra minutes may not be fewer than this: a link handed out at the end of the hour must still be usable. */
    static final Duration MIN_LEFT = Duration.ofMinutes(15);
    static final int MAX_CACHED = 20_000;

    /** A link and the hour it was signed for. */
    private record Cached(String url, Instant hour) {
    }

    private final StoredFileRepository repository;
    private final in.apnidukan.files.repo.FileDeletionStore deletions;
    private final in.apnidukan.files.repo.VideoUploadStore videoUploads;
    private final FileStorage storage;
    private final Clock clock;
    private final Duration linkValidity;
    private final Cache<UUID, Cached> cache = Caffeine.newBuilder().maximumSize(MAX_CACHED).build();
    /** The same, for the links that download the file under its name. */
    private final Cache<UUID, Cached> downloads = Caffeine.newBuilder().maximumSize(MAX_CACHED).build();

    public FileRefService(StoredFileRepository repository, in.apnidukan.files.repo.FileDeletionStore deletions,
                          FileStorage storage, Clock clock,
                          @Value("${app.files.photo-link-minutes:60}") long linkMinutes,
                          in.apnidukan.files.repo.VideoUploadStore videoUploads) {
        this.videoUploads = videoUploads;
        this.deletions = deletions;
        if (linkMinutes <= MIN_LEFT.toMinutes()) {
            throw new IllegalStateException("app.files.photo-link-minutes must be more than " + MIN_LEFT.toMinutes());
        }
        this.repository = repository;
        this.storage = storage;
        this.clock = clock;
        // From the hour's start: the rest of the hour, then the extra minutes
        this.linkValidity = Duration.ofHours(1).plusMinutes(linkMinutes);
    }

    @Override
    public Optional<byte[]> read(UUID fileId, long maxBytes) {
        // One short read of the row (no transaction kept open while the bytes download)
        return repository.findById(fileId).map(f -> storage.read(f.getStorageKey(), maxBytes));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FileRef> find(UUID fileId) {
        return repository.findById(fileId).map(f -> new FileRef(f.getId(), f.getShopId(), f.getPurpose().name(),
                f.getContentType(), f.getUploadedBy(), f.getSizeBytes(), f.getOriginalName()));
    }

    @Override
    @Transactional(readOnly = true)
    public String link(UUID fileId) {
        Instant hour = hour(clock.instant());
        String cached = cached(fileId, hour);
        if (cached != null) {
            return cached;
        }
        StoredFile file = repository.findById(fileId).orElseThrow();
        return sign(file, hour);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> links(Collection<UUID> fileIds) {
        Map<UUID, String> out = new HashMap<>();
        Instant hour = hour(clock.instant());
        Set<UUID> missing = new HashSet<>();
        for (UUID id : fileIds) {
            String cached = cached(id, hour);
            if (cached != null) {
                out.put(id, cached);
            } else {
                missing.add(id);
            }
        }
        if (!missing.isEmpty()) {
            // Signing is done in memory (no call to R2), so only the database read needs batching
            for (StoredFile f : repository.findAllById(missing)) {
                out.put(f.getId(), sign(f, hour));
            }
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> downloadLinks(Collection<UUID> fileIds) {
        Map<UUID, String> out = new HashMap<>();
        Instant hour = hour(clock.instant());
        Set<UUID> missing = new HashSet<>();
        for (UUID id : fileIds) {
            Cached c = downloads.getIfPresent(id);
            if (c != null && c.hour().equals(hour)) {
                out.put(id, c.url());
            } else {
                missing.add(id);
            }
        }
        if (!missing.isEmpty()) {
            for (StoredFile f : repository.findAllById(missing)) {
                String name = safeDownloadName(f.getOriginalName());
                String url = storage.signedLinkAt(f.getStorageKey(), hour, linkValidity, name);
                downloads.put(f.getId(), new Cached(url, hour));
                out.put(f.getId(), url);
            }
        }
        return out;
    }

    @Override
    @Transactional
    public void keep(UUID fileId) {
        StoredFile f = repository.findById(fileId).orElseThrow(FileRefService::gone);
        // Locked first: a claim of the deletion job under way is waited for; one not yet started skips this row
        deletions.lock(f.getStorageKey());
        if (!deletions.fileExists(fileId)) {
            throw gone();   // the job purged it (a video completed but not set within a day)
        }
        deletions.done(f.getStorageKey());
    }

    @Override
    @Transactional
    public boolean tryKeep(UUID fileId) {
        Optional<StoredFile> f = repository.findById(fileId);
        if (f.isEmpty()) {
            return false;
        }
        deletions.lock(f.get().getStorageKey());   // as keep: waits for a deletion claim under way
        if (!deletions.fileExists(fileId)) {
            return false;
        }
        deletions.done(f.get().getStorageKey());
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean expiredVideo(UUID fileId) {
        return !repository.existsById(fileId) && videoUploads.completedWith(fileId);
    }

    /** 409 UPLOAD_GONE: the salon video is no longer stored. */
    public static in.apnidukan.common.error.ApiException gone() {
        return new in.apnidukan.common.error.ApiException(org.springframework.http.HttpStatus.CONFLICT, "UPLOAD_GONE",
                "This video expired. Upload it again.");
    }

    @Override
    @Transactional
    public void release(UUID fileId) {
        repository.findById(fileId).ifPresent(f -> {
            if (f.getShopId() == null) {
                repository.deleteById(f.getId());   // a ticket file: a person's, no shop
            } else {
                repository.deleteByIdAndShopId(f.getId(), f.getShopId());
            }
            deletions.queueNow(f.getStorageKey(), clock.instant());
            cache.invalidate(f.getId());
            downloads.invalidate(f.getId());
        });
    }

    /**
     * Kinds that run when opened (programs, scripts, web pages, macro documents): anyone can email support a file, so
     * one of these is saved as "name.exe.download", never run by a double click. Everything else keeps its name.
     */
    static final java.util.Set<String> RUNS = java.util.Set.of("exe", "msi", "bat", "cmd", "com", "scr", "pif", "ps1",
            "psm1", "vbs", "vbe", "js", "jse", "mjs", "wsf", "wsh", "hta", "html", "htm", "xhtml", "shtml", "svg",
            "mht", "mhtml", "lnk", "url", "apk", "aab", "jar", "dll", "sys", "cpl", "msc", "reg", "sh", "bash", "command",
            "app", "dmg", "pkg", "iso", "img", "vhd", "vhdx", "docm", "xlsm", "pptm", "dotm", "xltm", "xlam", "ppam",
            "chm", "inf", "gadget", "appx", "msix", "py", "pyw", "pl", "rb", "php", "asp", "aspx", "jsp", "xml", "xsl");

    static String safeDownloadName(String name) {
        String n = name == null || name.isBlank() ? "file" : name.strip();
        int dot = n.lastIndexOf('.');
        String ext = dot < 0 ? "" : n.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return RUNS.contains(ext) ? n + ".download" : n;
    }

    /** The start of the hour this moment falls in (the same on every server, whatever its own clock's seconds). */
    static Instant hour(Instant now) {
        return now.truncatedTo(ChronoUnit.HOURS);
    }

    private String cached(UUID fileId, Instant hour) {
        Cached c = cache.getIfPresent(fileId);
        return c != null && c.hour().equals(hour) ? c.url() : null;
    }

    private String sign(StoredFile file, Instant hour) {
        String url = storage.signedLinkAt(file.getStorageKey(), hour, linkValidity);
        cache.put(file.getId(), new Cached(url, hour));
        return url;
    }
}
