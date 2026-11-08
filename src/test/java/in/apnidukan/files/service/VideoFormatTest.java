package in.apnidukan.files.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VideoFormatTest {

    @Test
    void theThreeVideoTypesAreTakenWhateverTheirExtras() {
        assertThat(VideoFormat.ofContentType("video/mp4")).contains(VideoFormat.MP4);
        assertThat(VideoFormat.ofContentType("Video/QuickTime")).contains(VideoFormat.QUICKTIME);
        assertThat(VideoFormat.ofContentType("video/webm; codecs=\"vp8, opus\"")).contains(VideoFormat.WEBM);
        assertThat(VideoFormat.ofContentType("video/x-matroska")).isEmpty();
        assertThat(VideoFormat.ofContentType("image/jpeg")).isEmpty();
        assertThat(VideoFormat.ofContentType(null)).isEmpty();
    }

    /** An ftyp box with this major brand and these compatible brands (each 4 characters). */
    private static byte[] ftyp(String major, String... compatible) {
        int size = 16 + 4 * compatible.length;
        byte[] b = new byte[Math.max(size, 12)];
        b[3] = (byte) size;
        System.arraycopy("ftyp".getBytes(), 0, b, 4, 4);
        System.arraycopy(major.getBytes(), 0, b, 8, 4);
        for (int i = 0; i < compatible.length; i++) {
            System.arraycopy(compatible[i].getBytes(), 0, b, 16 + 4 * i, 4);
        }
        return b;
    }

    @Test
    void onlyVideoBrandsCountSoPhotosAndSoundInTheSameBoxFamilyAreRefused() {
        for (String brand : new String[] {"isom", "iso2", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1", "M4V ",
                "M4VP", "qt  ", "3gp4", "3gp5", "3gp6", "3g2a", "dash", "msnv", "f4v "}) {
            assertThat(VideoFormat.looksLike(VideoFormat.MP4, ftyp(brand))).as(brand).isTrue();
        }
        assertThat(VideoFormat.looksLike(VideoFormat.MP4, ftyp("heic", "mif1", "heic"))).isFalse();
        assertThat(VideoFormat.looksLike(VideoFormat.MP4, ftyp("heix", "mif1"))).isFalse();
        assertThat(VideoFormat.looksLike(VideoFormat.MP4, ftyp("mif1", "heic"))).isFalse();
        assertThat(VideoFormat.looksLike(VideoFormat.MP4, ftyp("avif", "avif", "mif1", "miaf"))).isFalse();
        // An M4A recording lists video-family brands as compatible: its major brand decides
        assertThat(VideoFormat.looksLike(VideoFormat.MP4, ftyp("M4A ", "M4A ", "mp42", "isom"))).isFalse();
        // An unknown major brand counts only with a video brand among the compatible ones
        assertThat(VideoFormat.looksLike(VideoFormat.MP4, ftyp("abcd", "xxxx", "mp42"))).isTrue();
        assertThat(VideoFormat.looksLike(VideoFormat.MP4, ftyp("abcd", "xxxx"))).isFalse();
    }

    @Test
    void mp4AndMovAreToldByTheirFtypBoxEitherWay() {
        byte[] mp4 = {0, 0, 0, 0x10, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0};
        byte[] mov = {0, 0, 0, 0x10, 'f', 't', 'y', 'p', 'q', 't', ' ', ' ', 0, 0, 0, 0};
        assertThat(VideoFormat.looksLike(VideoFormat.MP4, mp4)).isTrue();
        assertThat(VideoFormat.looksLike(VideoFormat.QUICKTIME, mov)).isTrue();
        assertThat(VideoFormat.looksLike(VideoFormat.MP4, mov)).isTrue();   // an iPhone's .mov labelled mp4
        assertThat(VideoFormat.looksLike(VideoFormat.WEBM, mp4)).isFalse();
    }

    @Test
    void webmIsToldByItsEbmlHeaderAndAnythingElseIsNotAVideo() {
        byte[] webm = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 1, 0, 0, 0};
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0, 0, 0};
        assertThat(VideoFormat.looksLike(VideoFormat.WEBM, webm)).isTrue();
        assertThat(VideoFormat.looksLike(VideoFormat.MP4, webm)).isFalse();
        assertThat(VideoFormat.looksLike(VideoFormat.MP4, jpeg)).isFalse();
        assertThat(VideoFormat.looksLike(VideoFormat.WEBM, jpeg)).isFalse();
        assertThat(VideoFormat.looksLike(VideoFormat.MP4, new byte[] {0, 0, 0})).isFalse();   // too short
    }
}
