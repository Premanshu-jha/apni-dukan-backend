package in.apnidukan.files.service;

import in.apnidukan.common.error.ApiException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Shop, owner and barber photos are drawn again before they are stored: a new JPEG, at most 1280 px on its
 * longest side. What the phone put inside the original file goes (EXIF: GPS position, phone model, time), and a
 * 10 MB camera photo becomes about 200 KB, light enough for a list of salons on mobile data.
 * Bills and receipts are kept exactly as uploaded (they are records).
 */
final class PhotoCleaner {

    static final int MAX_SIDE = 1280;
    /** Refuses images with more pixels than any phone camera makes (a "decompression bomb" is tiny but huge). */
    static final long MAX_PIXELS = 40_000_000L;
    static final float QUALITY = 0.85f;

    private PhotoCleaner() {
    }

    /** The cleaned JPEG. 400 UNREADABLE_PHOTO for a JPEG or PNG that can't be read, or one far too large. */
    static byte[] clean(byte[] original) {
        BufferedImage source = orient(read(original), ExifOrientation.of(original));
        int w = source.getWidth();
        int h = source.getHeight();
        double scale = Math.min(1.0, (double) MAX_SIDE / Math.max(w, h));
        int tw = Math.max(1, (int) Math.round(w * scale));
        int th = Math.max(1, (int) Math.round(h * scale));
        BufferedImage target = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setColor(Color.WHITE);   // transparent PNG parts become white, not black
            g.fillRect(0, 0, tw, th);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, tw, th, null);
        } finally {
            g.dispose();
        }
        return writeJpeg(target);
    }

    private static BufferedImage read(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (in == null || !readers.hasNext()) {
                throw unreadable();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);   // ignore metadata: it isn't kept anyway
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if ((long) w * h > MAX_PIXELS) {
                    throw unreadable();
                }
                // Decode only every n-th pixel of a big photo: at most about 2 x 1280 px wide in memory, never the
                // full 12 or 48 megapixels (a 7000 x 7000 image would otherwise take about 200 MB)
                ImageReadParam param = reader.getDefaultReadParam();
                int n = Math.max(1, Math.max(w, h) / (2 * MAX_SIDE));
                param.setSourceSubsampling(n, n, 0, 0);
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException api) {
                throw api;
            }
            throw unreadable();
        }
    }

    /**
     * Turns the picture the way the phone meant it: cameras store the pixels as the sensor saw them plus an EXIF
     * "orientation" (1 = as is, 6 = turn right, 3 = upside down, 8 = turn left, 2/4/5/7 = mirrored). Removing EXIF
     * without applying it would leave portrait photos lying on their side.
     */
    static BufferedImage orient(BufferedImage src, int orientation) {
        if (orientation < 2 || orientation > 8) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        boolean swap = orientation >= 5;
        AffineTransform t = new AffineTransform();
        switch (orientation) {
            case 2 -> { t.translate(w, 0); t.scale(-1, 1); }
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }
            case 4 -> { t.translate(0, h); t.scale(1, -1); }
            case 5 -> { t.rotate(Math.PI / 2); t.scale(1, -1); }
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }
            case 7 -> { t.scale(-1, 1); t.translate(-h, 0); t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            case 8 -> { t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            default -> { }
        }
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, out.getWidth(), out.getHeight());
            g.drawImage(src, t, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static byte[] writeJpeg(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the photo", e);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static ApiException unreadable() {
        return ApiException.badRequest("UNREADABLE_PHOTO",
                "This photo couldn't be read. Take it again, or send a JPEG or PNG.");
    }
}
