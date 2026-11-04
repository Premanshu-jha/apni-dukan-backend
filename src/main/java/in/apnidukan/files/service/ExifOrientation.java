package in.apnidukan.files.service;

/**
 * Reads only the EXIF "orientation" number (tag 0x0112) from a JPEG's APP1 segment, so PhotoCleaner can turn the
 * picture upright before it drops EXIF. Anything unexpected (no EXIF, a PNG, a broken segment) answers 1: as is.
 * Reads within the array's bounds only; never throws.
 */
final class ExifOrientation {

    private ExifOrientation() {
    }

    static int of(byte[] b) {
        try {
            return find(b);
        } catch (RuntimeException e) {
            return 1;
        }
    }

    private static int find(byte[] b) {
        if (b.length < 4 || u8(b, 0) != 0xFF || u8(b, 1) != 0xD8) {
            return 1;
        }
        int p = 2;
        while (p + 4 <= b.length && u8(b, p) == 0xFF) {
            int marker = u8(b, p + 1);
            int length = (u8(b, p + 2) << 8) | u8(b, p + 3);
            if (marker == 0xDA || length < 2) {
                return 1;   // image data starts: no EXIF before it
            }
            if (marker == 0xE1 && p + 10 <= b.length && b[p + 4] == 'E' && b[p + 5] == 'x' && b[p + 6] == 'i'
                    && b[p + 7] == 'f' && b[p + 8] == 0 && b[p + 9] == 0) {
                return fromTiff(b, p + 10, Math.min(b.length, p + 2 + length));
            }
            p += 2 + length;
        }
        return 1;
    }

    /** The TIFF block inside APP1: byte order, then the first directory (IFD0) and its entries. */
    private static int fromTiff(byte[] b, int start, int end) {
        if (start + 8 > end) {
            return 1;
        }
        boolean little = b[start] == 'I' && b[start + 1] == 'I';
        if (!little && !(b[start] == 'M' && b[start + 1] == 'M')) {
            return 1;
        }
        int ifd = start + (int) u32(b, start + 4, little);
        if (ifd + 2 > end || ifd < start) {
            return 1;
        }
        int entries = u16(b, ifd, little);
        for (int i = 0; i < entries; i++) {
            int e = ifd + 2 + i * 12;
            if (e + 12 > end) {
                return 1;
            }
            if (u16(b, e, little) == 0x0112) {
                int value = u16(b, e + 8, little);
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    private static int u8(byte[] b, int i) {
        return b[i] & 0xFF;
    }

    private static int u16(byte[] b, int i, boolean little) {
        return little ? u8(b, i) | (u8(b, i + 1) << 8) : (u8(b, i) << 8) | u8(b, i + 1);
    }

    private static long u32(byte[] b, int i, boolean little) {
        long a = u8(b, i);
        long c = u8(b, i + 1);
        long d = u8(b, i + 2);
        long e = u8(b, i + 3);
        return little ? a | (c << 8) | (d << 16) | (e << 24) : (a << 24) | (c << 16) | (d << 8) | e;
    }
}
