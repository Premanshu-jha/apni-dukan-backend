package in.apnidukan.common.files;

import java.util.Optional;
import java.util.UUID;

/**
 * Files that come with a support email, as the helpdesk sees them. Implemented by the files module
 * (EmailedFileService).
 */
public interface EmailedFiles {

    /**
     * The raw email the Email Worker saved in storage because it was too big to post (a key {@code inbound/<uuid>.eml}
     * the worker named in the signed headers), at most {@code max} bytes. Empty when it isn't there (already read and
     * deleted) or is bigger. Read once: it is queued for deletion as it is read.
     */
    Optional<byte[]> takeInbound(String key, int max);

    /**
     * Stores one file of an email as a ticket file (a cleaned JPEG for a JPEG or PNG photo; a PDF; an MP4, MOV or WebM
     * video), to go with the email's message. Deleted after a day unless a message carries it (as an upload from the
     * app). Empty when it isn't a kind or size we keep, or photos are too busy to clean right now: it stays only in
     * the support Gmail.
     */
    Optional<UUID> store(byte[] bytes, String name, String contentType);
}
