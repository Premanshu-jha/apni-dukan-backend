package in.apnidukan.files.service;

import java.util.Optional;

/**
 * The file types we accept, recognised from the file's first bytes ("magic numbers"),
 * not from its name or the browser's claim, which anyone can fake.
 */
public enum FileType {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp"),
    PDF("application/pdf", "pdf");

    private final String contentType;
    private final String extension;

    FileType(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    public String contentType() { return contentType; }
    public String extension() { return extension; }

    /** @param head the first bytes of the file (at least 12 are needed to recognise every type) */
    public static Optional<FileType> detect(byte[] head, int length) {
        if (length >= 3 && u(head[0]) == 0xFF && u(head[1]) == 0xD8 && u(head[2]) == 0xFF) {
            return Optional.of(JPEG);
        }
        if (length >= 8 && u(head[0]) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G'
                && head[4] == 0x0D && head[5] == 0x0A && head[6] == 0x1A && head[7] == 0x0A) {
            return Optional.of(PNG);
        }
        if (length >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            return Optional.of(WEBP);
        }
        if (length >= 5 && head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F' && head[4] == '-') {
            return Optional.of(PDF);
        }
        return Optional.empty();
    }

    private static int u(byte b) {
        return b & 0xFF;
    }
}
