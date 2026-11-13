package in.apnidukan.helpdesk.service;

import in.apnidukan.common.files.EmailedFiles;
import in.apnidukan.helpdesk.domain.EmailAttachment;
import in.apnidukan.helpdesk.service.TicketService.Received;
import in.apnidukan.common.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * A support email from the Email Worker, with its files. Over 1 MB the worker parks the whole email in storage
 * ({@code inbound/<uuid>.eml}) and posts only its headers, naming the key in a header of its own (signed with the
 * rest); it is read from there. Its photos (JPEG, PNG), PDFs and videos (MP4, MOV, WebM) are stored as ticket files
 * first, outside any transaction (storage is slow; the helpdesk lock is held only by {@link TicketService#receive}),
 * at most {@value #MAX_FILES}; any other file stays only in the support Gmail, and the message names them. A file
 * that can't be read or kept never loses the email: it stays in Gmail like the rest.
 */
@Service
public class InboundMail {

    /** The most files kept from one email. */
    public static final int MAX_FILES = 30;
    /** The header the worker adds to the headers it posts, naming where the whole email is. */
    public static final String PARKED_HEADER = "X-Clozest-Raw";
    /** Cloudflare Email Routing takes emails up to 25 MiB; a parked one bigger than this isn't read. */
    static final int MAX_PARKED = 27 * 1024 * 1024;
    private static final Logger log = LoggerFactory.getLogger(InboundMail.class);

    private final TicketService tickets;
    private final EmailedFiles files;

    public InboundMail(TicketService tickets, EmailedFiles files) {
        this.tickets = tickets;
        this.files = files;
    }

    /**
     * @param raw       what the worker posted (checked by its signature first)
     * @param truncated the worker posted only the headers
     * @param ourDomain see {@link InboundEmail#parse}
     * @throws IllegalArgumentException when it isn't a readable email
     */
    public Received take(byte[] raw, boolean truncated, String ourDomain) {
        InboundEmail email = InboundEmail.parse(raw, truncated, ourDomain);
        // Only a headers-only post (as the worker sends for a big email) may name a parked email
        if (!truncated || !headersOnly(raw)) {
            return truncated ? tickets.receive(email, List.of()) : withFiles(email, raw);
        }
        Optional<String> key = InboundEmail.header(raw, PARKED_HEADER);
        if (key.isEmpty() || email.automated() || tickets.hasEmail(email.messageId())
                || tickets.floods(email.fromAddress())) {
            return tickets.receive(email, List.of());
        }
        // A big email is read whole (up to 25 MB, several copies while it is read): a few at a time
        if (!acquire()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INBOX_BUSY",
                    "Many big emails are arriving right now. Try again in a minute.").retryAfter(60);
        }
        try {
            Optional<byte[]> parked = files.takeInbound(key.get(), MAX_PARKED);
            if (parked.isEmpty()) {
                return tickets.receive(email, List.of());
            }
            InboundEmail whole;
            try {
                whole = InboundEmail.parse(parked.get(), false, ourDomain);
            } catch (IllegalArgumentException e) {
                log.warn("A parked support email couldn't be read; kept as headers only");
                return tickets.receive(email, List.of());
            }
            return withFiles(whole, parked.get());
        } finally {
            busy.release();
        }
    }

    /** The email with its files kept (stored first, outside the helpdesk lock); the rest named in its text. */
    private Received withFiles(InboundEmail email, byte[] raw) {
        if (email.automated() || tickets.hasEmail(email.messageId()) || tickets.floods(email.fromAddress())) {
            return tickets.receive(email, List.of());
        }
        List<EmailAttachment> attached;
        try {
            attached = InboundEmail.files(raw);
        } catch (IllegalArgumentException e) {
            log.warn("A support email's files couldn't be read; it is kept without them");
            return tickets.receive(email, List.of());
        }
        List<TicketService.Emailed> kept = new ArrayList<>();
        List<String> left = new ArrayList<>();
        for (EmailAttachment a : attached) {
            if (a.inline() && a.contentType().startsWith("image/") && a.bytes().length > 0
                    && (a.bytes().length < MIN_LOGO_BYTES || !PASTED_PHOTOS.contains(a.contentType()))) {
                continue;   // a signature logo, banner (GIF, SVG) or tracking pixel, not something they sent us
            }
            Optional<UUID> id = Optional.empty();
            if (kept.size() < MAX_FILES && a.bytes().length > 0) {
                try {
                    id = files.store(a.bytes(), a.name(), a.contentType());
                } catch (RuntimeException e) {   // storage down, a photo that won't clean: it stays in Gmail
                    log.warn("A file of a support email couldn't be kept: {}", e.getClass().getSimpleName());
                }
            }
            if (id.isPresent()) {
                kept.add(new TicketService.Emailed(id.get(), sha256(a.bytes())));
            } else {
                left.add(a.name() == null ? "a file" : a.name());
            }
        }
        return tickets.receive(email.withFilesLeft(left, !kept.isEmpty()), kept);
    }

    /** Whether the post is a header block only (nothing after the blank line), as the worker sends a big email. */
    static boolean headersOnly(byte[] raw) {
        for (int i = 0; i + 3 < raw.length; i++) {
            if (raw[i] == '\r' && raw[i + 1] == '\n' && raw[i + 2] == '\r' && raw[i + 3] == '\n') {
                return i + 4 == raw.length;
            }
        }
        return false;
    }

    private boolean acquire() {
        try {
            return busy.tryAcquire(MAX_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in Java", e);
        }
    }

    /** Big emails read at once (each may hold ~100 MB while it is read); the rest wait, then 503 (the worker tries again). */
    static final int BIG_AT_ONCE = 2;
    static final int MAX_WAIT_SECONDS = 20;
    private final Semaphore busy = new Semaphore(BIG_AT_ONCE, true);

    /** Photos pasted into the text that are kept; other pasted images (GIF, SVG, BMP) are signatures and banners. */
    static final java.util.Set<String> PASTED_PHOTOS = java.util.Set.of("image/jpeg", "image/jpg", "image/png",
            "image/heic", "image/heif", "image/webp");

    /** Pasted images smaller than this are logos and pixels (the files module skips them too). */
    static final int MIN_LOGO_BYTES = 10 * 1024;
}
