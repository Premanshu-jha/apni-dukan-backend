package in.apnidukan.files.service;

import in.apnidukan.common.error.ApiException;
import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhotoCleanerTest {

    @Test
    void aLargePhotoBecomesASmallJpegOf1280OnItsLongestSide() throws IOException {
        byte[] cleaned = PhotoCleaner.clean(png(4000, 3000));
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(cleaned));
        assertThat(out.getWidth()).isEqualTo(1280);
        assertThat(out.getHeight()).isEqualTo(960);
        assertThat(cleaned[0] & 0xFF).isEqualTo(0xFF);   // a JPEG now
        assertThat(cleaned[1] & 0xFF).isEqualTo(0xD8);
    }

    @Test
    void aSmallPhotoKeepsItsSizeAndLosesItsHiddenText() throws IOException {
        byte[] withComment = jpegWithComment(200, 100, "GPS 17.4661,78.3650 Pixel 8");
        assertThat(new String(withComment, java.nio.charset.StandardCharsets.ISO_8859_1)).contains("GPS 17.4661");
        byte[] cleaned = PhotoCleaner.clean(withComment);
        assertThat(new String(cleaned, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("GPS");
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(cleaned));
        assertThat(out.getWidth()).isEqualTo(200);
    }

    @Test
    void somethingThatOnlyLooksLikeAJpegIsRefused() {
        byte[] fake = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, 4, 5, 6, 7, 8};
        assertThatThrownBy(() -> PhotoCleaner.clean(fake))
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("UNREADABLE_PHOTO"));
    }

    private static byte[] png(int w, int h) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    /** A JPEG carrying a text comment in its metadata, standing in for a phone's EXIF details. */
    private static byte[] jpegWithComment(int w, int h, String comment) throws IOException {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        IIOMetadata meta = writer.getDefaultImageMetadata(
                new javax.imageio.ImageTypeSpecifier(image), writer.getDefaultWriteParam());
        javax.imageio.metadata.IIOMetadataNode root =
                (javax.imageio.metadata.IIOMetadataNode) meta.getAsTree("javax_imageio_jpeg_image_1.0");
        javax.imageio.metadata.IIOMetadataNode com = new javax.imageio.metadata.IIOMetadataNode("com");
        com.setAttribute("comment", comment);
        ((javax.imageio.metadata.IIOMetadataNode) root.getElementsByTagName("markerSequence").item(0))
                .appendChild(com);
        meta.setFromTree("javax_imageio_jpeg_image_1.0", root);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, meta), null);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
