package in.apnidukan.files.service;

import in.apnidukan.common.error.ApiException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
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
    /** Refuses images so large that decoding them would take too much memory (a "decompression bomb"). */
    static final long MAX_PIXELS = 50_000_000L;
    static final float QUALITY = 0.85f;

    private PhotoCleaner() {
    }

    /** The cleaned JPEG. 400 UNREADABLE_PHOTO for a JPEG or PNG that can't be read, or one far too large. */
    static byte[] clean(byte[] original) {
        BufferedImage source = read(original);
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
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > MAX_PIXELS) {
                    throw unreadable();
                }
                return reader.read(0);
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
