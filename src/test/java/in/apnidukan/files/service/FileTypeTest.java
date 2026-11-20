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

    @Test
    void billsMayAlsoBeSpreadsheetsDocumentsCsvJsonOrText() {
        byte[] zip = {'P', 'K', 3, 4, 20, 0, 6, 0};
        assertThat(FileType.detectBillDocument(zip, zip.length, "Invoice.XLSX")).contains(FileType.XLSX);
        assertThat(FileType.detectBillDocument(zip, zip.length, "bill.docx")).contains(FileType.DOCX);
        assertThat(FileType.detectBillDocument(zip, zip.length, "photos.zip")).isEmpty();
        byte[] ole = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        assertThat(FileType.detectBillDocument(ole, ole.length, "bill.xls")).contains(FileType.XLS);
        byte[] csv = "Item,Qty\nSugar,10\n".getBytes(StandardCharsets.UTF_8);
        assertThat(FileType.detectBillDocument(csv, csv.length, "bill.csv")).contains(FileType.CSV);
        byte[] json = " {\"ItemList\":[]}".getBytes(StandardCharsets.UTF_8);
        assertThat(FileType.detectBillDocument(json, json.length, "einvoice")).contains(FileType.JSON);
        byte[] text = "Sugar 10 kg".getBytes(StandardCharsets.UTF_8);
        assertThat(FileType.detectBillDocument(text, text.length, "bill.txt")).contains(FileType.TEXT);
        // Text pretending to be a photo, and binary that isn't text, are refused
        assertThat(FileType.detectBillDocument(text, text.length, "bill.jpg")).isEmpty();
        byte[] binary = {1, 0, 2, 3};
        assertThat(FileType.detectBillDocument(binary, binary.length, "bill.txt")).isEmpty();
    }

    @Test
    void recognisesAnIphoneHeicPhoto() {
        byte[] heic = {0, 0, 0, 24, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'};
        assertThat(FileType.heic(heic, heic.length)).isTrue();
        assertThat(FileType.detect(heic, heic.length)).isEmpty();
    }
}
