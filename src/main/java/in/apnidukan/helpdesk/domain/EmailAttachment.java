package in.apnidukan.helpdesk.domain;

/**
 * A file in a support email, as it came (before we decide whether to keep it).
 * @param name        its file name, or null
 * @param contentType what the email says it is (checked from the bytes before it is kept)
 * @param bytes       the file
 * @param inline      shown inside the email's text (a pasted photo, a signature logo) rather than attached
 */
public record EmailAttachment(String name, String contentType, byte[] bytes, boolean inline) {
}
