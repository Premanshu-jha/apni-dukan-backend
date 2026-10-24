package in.apnidukan.files.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class FileTypeTest {

    @Test
    void recognisesJpegFromItsFirstBytes() {
        byte[] head = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0};
        assertThat(FileType.detect(head, head.length)).contains(FileType.JPEG);
    }

    @Test
    void recognisesPng() {
        byte[] head = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        assertThat(FileType.detect(head, head.length)).contains(FileType.PNG);
    }

    @Test
    void recognisesWebp() {
        byte[] head = "RIFF\0\0\0\0WEBP".getBytes(StandardCharsets.ISO_8859_1);
        assertThat(FileType.detect(head, head.length)).contains(FileType.WEBP);
    }

    @Test
    void recognisesPdf() {
        byte[] head = "%PDF-1.7\n%abc".getBytes(StandardCharsets.ISO_8859_1);
        assertThat(FileType.detect(head, head.length)).contains(FileType.PDF);
    }

    @Test
    void refusesTextRenamedToJpg() {
        byte[] head = "hello world!".getBytes(StandardCharsets.ISO_8859_1);
        assertThat(FileType.detect(head, head.length)).isEmpty();
    }

    @Test
    void refusesTooShortFile() {
        byte[] head = {(byte) 0xFF, (byte) 0xD8};
        assertThat(FileType.detect(head, head.length)).isEmpty();
    }
}
