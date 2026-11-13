package in.apnidukan.files.service;

import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.files.EmailedFiles;
import in.apnidukan.files.domain.FilePurpose;
import in.apnidukan.files.domain.StoredFile;
import in.apnidukan.files.repo.FileDeletionStore;
import in.apnidukan.files.repo.StoredFileRepository;
import in.apnidukan.files.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The files of support emails: the raw email the Email Worker parked in storage (too big to post), and each photo,
 * PDF or video in an email kept as a ticket file. Ticket files from an email are kept like those from the app: queued
 * for deletion in a day, until the email's message carries them.
 */
@Service
public class EmailedFileService implements EmailedFiles {

    /** Where the worker parks a big email: inbound/ and a UUID (nothing else is ever read from here). */
    static final Pattern INBOUND_KEY = Pattern.compile(
            "inbound/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.eml");
    /** Read: deleted soon after (and by the bucket's rule for inbound/, should this fail). */
    static final Duration INBOUND_KEPT = Duration.ofMinutes(30);
    /** An email is at most 25 MB (Cloudflare Email Routing), so a video in one is too. */
    public static final long MAX_EMAILED_VIDEO_BYTES = 25L * 1024 * 1024;
    /** Smaller images are signature logos and tracking pixels, not something the person sent us. */
    public static final int MIN_IMAGE_BYTES = 10 * 1024;
    private static final Logger log = LoggerFactory.getLogger(EmailedFileService.class);

    private final StoredFileRepository files;
    private final FileDeletionStore deletions;
    private final FileStorage storage;
    private final PhotoCleaning photos;
    private final TransactionOperations transactions;
    private final Clock clock;

    public EmailedFileService(StoredFileRepository files, FileDeletionStore deletions, FileStorage storage,
                              PhotoCleaning photos, TransactionOperations transactions, Clock clock) {
        this.files = files;
        this.deletions = deletions;
        this.storage = storage;
        this.photos = photos;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public Optional<byte[]> takeInbound(String key, int max) {
        if (key == null || !INBOUND_KEY.matcher(key).matches()) {
            return Optional.empty();
        }
        OptionalLong size = storage.size(key);
        if (size.isEmpty() || size.getAsLong() > max || size.getAsLong() == 0) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        // Queued before it is read: deleted even if what follows fails (a retry within the half hour reads it again)
        transactions.executeWithoutResult(status -> deletions.queueFrom(key, now, now.plus(INBOUND_KEPT)));
        return Optional.of(storage.firstBytes(key, (int) size.getAsLong()));
    }

    @Override
    public Optional<UUID> store(byte[] bytes, String name, String contentType) {
        if (bytes == null || bytes.length == 0) {
            return Optional.empty();
        }
        Kept kept = kind(bytes, contentType);
        if (kept == null) {
            return Optional.empty();
        }
        byte[] content = bytes;
        if (kept.photo()) {
            try {
                content = photos.clean(bytes);   // no hidden details (where it was taken, the phone)
            } catch (ApiException e) {   // busy, or unreadable after all (PhotoCleaner)
                log.info("A photo in a support email was left in Gmail: {}", e.getMessage());
                return Optional.empty();
            }
            if (content.length > TicketFileService.MAX_FILE_BYTES) {
                return Optional.empty();
            }
        }
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        String key = "%s/email/%s.%s".formatted(FilePurpose.TICKET_FILE.folder(), id, kept.extension());
        StoredFile file = new StoredFile(id, null, FilePurpose.TICKET_FILE, key, kept.contentType(), content.length,
                TicketFileService.sha256(content), TicketFileService.cleanName(name), null, now);
        // Recorded and queued first (due in a day, until the message carries it), so a failure below leaves nothing
        transactions.executeWithoutResult(status -> {
            deletions.queueFrom(key, now, now.plus(TicketFileService.UNUSED_KEPT));
            files.saveAndFlush(file);
        });
        storage.put(key, new ByteArrayInputStream(content), content.length, kept.contentType());
        return Optional.of(id);
    }

    /**
     * What we keep this as (told from its bytes, not the email's claim): a photo (cleaned), a PDF or a video to show;
     * anything else (Word, Excel, a zip, an iPhone HEIC photo, a forwarded email) as a plain file only ever downloaded.
     * Null only for an image too small to be one (a logo or pixel) or a file over the size limits.
     */
    private static Kept kind(byte[] bytes, String claimed) {
        Optional<FileType> type = FileType.detect(bytes, bytes.length);
        if (type.isPresent() && (type.get() == FileType.JPEG || type.get() == FileType.PNG)) {
            return bytes.length < MIN_IMAGE_BYTES ? null
                    : new Kept(FileType.JPEG.contentType(), FileType.JPEG.extension(), true);
        }
        if (type.isPresent() && type.get() == FileType.PDF) {
            return bytes.length > TicketFileService.MAX_FILE_BYTES ? null
                    : new Kept(FileType.PDF.contentType(), FileType.PDF.extension(), false);
        }
        Optional<VideoFormat> video = VideoFormat.ofContentType(claimed == null ? "" : claimed);
        if (video.isPresent() && bytes.length <= MAX_EMAILED_VIDEO_BYTES) {
            byte[] head = java.util.Arrays.copyOf(bytes, Math.min(bytes.length, VideoFormat.HEAD_BYTES));
            if (VideoFormat.looksLike(video.get(), head)) {
                return new Kept(video.get().contentType(), video.get().extension(), false);
            }
        }
        // Anything else: kept to download (an email is at most 25 MB, so this is too)
        return bytes.length > MAX_EMAILED_VIDEO_BYTES ? null
                : new Kept(TicketFileService.DOWNLOAD_ONLY, "bin", false);
    }

    private record Kept(String contentType, String extension, boolean photo) {
    }
}
