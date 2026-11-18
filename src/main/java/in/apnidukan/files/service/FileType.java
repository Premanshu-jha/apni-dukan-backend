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
    PDF("application/pdf", "pdf"),
    // Distributors' bills only (purpose BILL): what billing software sends besides photos and PDFs
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
    XLS("application/vnd.ms-excel", "xls"),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
    CSV("text/csv", "csv"),
    JSON("application/json", "json"),
    TEXT("text/plain", "txt");

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

    /**
     * A distributor's bill that isn't a photo or a PDF: an Excel or Word file (by its zip or OLE signature and its
     * name's extension; its content is checked when it is read), or text (no NUL byte, valid UTF-8 in the first bytes):
     * CSV, JSON (a GST e-invoice) or plain text, by its name or first character. An iPhone HEIC photo: {@code heic}.
     */
    public static Optional<FileType> detectBillDocument(byte[] head, int length, String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        if (length >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4) {
            if (name.endsWith(".xlsx") || name.endsWith(".xlsm")) {
                return Optional.of(XLSX);
            }
            return name.endsWith(".docx") ? Optional.of(DOCX) : Optional.empty();
        }
        if (length >= 8 && u(head[0]) == 0xD0 && u(head[1]) == 0xCF && u(head[2]) == 0x11 && u(head[3]) == 0xE0
                && u(head[4]) == 0xA1 && u(head[5]) == 0xB1 && u(head[6]) == 0x1A && u(head[7]) == 0xE1) {
            return name.endsWith(".xls") ? Optional.of(XLS) : Optional.empty();
        }
        // Text named as a picture or a PDF is neither: refused like any file that isn't what it says
        if (length == 0 || !text(head, length) || name.matches(".*\\.(jpe?g|png|webp|heic|heif|pdf|gif)$")) {
            return Optional.empty();
        }
        int start = length >= 3 && u(head[0]) == 0xEF && u(head[1]) == 0xBB && u(head[2]) == 0xBF ? 3 : 0;
        while (start < length && Character.isWhitespace(head[start])) {
            start++;
        }
        if (name.endsWith(".json") || (start < length && (head[start] == '{' || head[start] == '['))) {
            return Optional.of(JSON);
        }
        if (name.endsWith(".csv") || name.endsWith(".tsv")) {
            return Optional.of(CSV);
        }
        return Optional.of(TEXT);
    }

    /** An iPhone photo (HEIC/HEIF), which can't be read here: the app sends it as JPEG. */
    public static boolean heic(byte[] head, int length) {
        if (length < 12 || head[4] != 'f' || head[5] != 't' || head[6] != 'y' || head[7] != 'p') {
            return false;
        }
        String brand = new String(head, 8, 4, java.nio.charset.StandardCharsets.US_ASCII);
        return brand.startsWith("hei") || brand.startsWith("hev") || brand.equals("mif1") || brand.equals("msf1");
    }

    private static boolean text(byte[] head, int length) {
        for (int i = 0; i < length; i++) {
            if (head[i] == 0) {
                return false;
            }
        }
        var decoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
        // The last few bytes may cut a character in two: only what comes before them must be valid
        int upTo = length < 512 ? length : length - 4;
        try {
            decoder.decode(java.nio.ByteBuffer.wrap(head, 0, upTo));
            return true;
        } catch (java.nio.charset.CharacterCodingException e) {
            return false;
        }
    }

    private static int u(byte b) {
        return b & 0xFF;
    }
}
