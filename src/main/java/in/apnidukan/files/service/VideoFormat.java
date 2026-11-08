package in.apnidukan.files.service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The video types a salon video may be, told from the file's first bytes (not the phone's claim). MP4 and QuickTime
 * (MOV, what iPhones record) start with an "ftyp" box whose brands say what the file is: only video brands count, so
 * a HEIC/AVIF photo or an M4A recording (the same box family) is refused. WebM starts with the EBML header.
 */
public enum VideoFormat {
    MP4("video/mp4", "mp4"),
    QUICKTIME("video/quicktime", "mov"),
    WEBM("video/webm", "webm");

    /** Enough bytes for the ftyp box's major brand and several compatible ones. */
    public static final int HEAD_BYTES = 64;

    /** ftyp brands of video files (trailing spaces trimmed: "qt  " is "qt", "M4V " is "M4V"). */
    static final Set<String> VIDEO_BRANDS = Set.of("isom", "iso2", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1",
            "M4V", "M4VP", "qt", "3gp4", "3gp5", "3gp6", "3g2a", "dash", "msnv", "f4v");
    /** Major brands of the same box family that are photos or sound, never a video. */
    static final Set<String> NOT_VIDEO_BRANDS = Set.of("heic", "heix", "heim", "heis", "hevc", "hevx", "mif1",
            "msf1", "avif", "avis", "M4A", "M4B", "M4P", "F4A", "F4B", "jp2", "crx");

    private final String contentType;
    private final String extension;

    VideoFormat(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    public String contentType() { return contentType; }
    public String extension() { return extension; }

    /** The format for a content type the phone gave ("video/mp4; codecs=..." too), if it is one we take. */
    public static Optional<VideoFormat> ofContentType(String contentType) {
        if (contentType == null) {
            return Optional.empty();
        }
        String base = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        for (VideoFormat f : values()) {
            if (f.contentType.equals(base)) {
                return Optional.of(f);
            }
        }
        return Optional.empty();
    }

    /**
     * Whether these first bytes are a video of the given format. MP4 and MOV are the same container family (an
     * iPhone's .mov is often labelled video/mp4 and the other way round), so a video ftyp counts for both.
     */
    public static boolean looksLike(VideoFormat format, byte[] head) {
        return switch (format) {
            case MP4, QUICKTIME -> isVideoFtyp(head);
            case WEBM -> head.length >= 4 && (head[0] & 0xFF) == 0x1A && (head[1] & 0xFF) == 0x45
                    && (head[2] & 0xFF) == 0xDF && (head[3] & 0xFF) == 0xA3;
        };
    }

    /** An ftyp box (size, "ftyp", major brand, minor version, compatible brands) naming a video brand. */
    static boolean isVideoFtyp(byte[] head) {
        if (head.length < 12 || head[4] != 'f' || head[5] != 't' || head[6] != 'y' || head[7] != 'p') {
            return false;
        }
        String major = brand(head, 8);
        if (NOT_VIDEO_BRANDS.contains(major)) {
            return false;
        }
        if (VIDEO_BRANDS.contains(major)) {
            return true;
        }
        long box = ((head[0] & 0xFFL) << 24) | ((head[1] & 0xFFL) << 16) | ((head[2] & 0xFFL) << 8) | (head[3] & 0xFFL);
        int end = (int) Math.min(box, head.length);
        for (int i = 16; i + 4 <= end; i += 4) {
            if (VIDEO_BRANDS.contains(brand(head, i))) {
                return true;
            }
        }
        return false;
    }

    private static String brand(byte[] head, int at) {
        return new String(head, at, 4, StandardCharsets.ISO_8859_1).stripTrailing();
    }
}
