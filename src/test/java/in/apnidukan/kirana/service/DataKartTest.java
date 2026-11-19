package in.apnidukan.kirana.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** GS1's GTINs read as packs print them. */
class DataKartTest {

    @Test
    void aPlaceholderValueLeavesGs1Off() {
        assertThat(new DataKart("NULL", "NULL", "NULL").configured()).isFalse();
        assertThat(new DataKart(" null ", "none", "").configured()).isFalse();
        assertThat(new DataKart("https://datakart.example.com/api", "OFF", "").configured()).isFalse();
        assertThat(new DataKart("", "real-key", "").configured()).isFalse();
        assertThat(new DataKart("https://datakart.example.com/api/", "real-key", "NULL").configured()).isTrue();
    }

    @Test
    void anAddressWithoutHttpIsRefusedNotAFailureOfEachLookup() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new DataKart("datakart.example.com/api", "k", "")
                .byGtin("8901262150019")).isInstanceOf(DataKart.Refused.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new DataKart("ftp://datakart.example.com", "k", "")
                .byGtin("8901262150019")).isInstanceOf(DataKart.Refused.class);
    }

    @Test
    void aGtin14WithLeadingZerosReadsAsTheEan13APackPrints() {
        assertThat(DataKart.gtin13("08901262150019")).isEqualTo("8901262150019");
        assertThat(DataKart.gtin13("00000012345678")).isEqualTo("12345678");   // an EAN-8, as the pack prints it
        assertThat(DataKart.gtin13("8901262150019")).isEqualTo("8901262150019");
        assertThat(DataKart.gtin13("18901262150016")).isEqualTo("18901262150016");   // a case's own GTIN-14 stays
        assertThat(DataKart.gtin13("8901-2621-5001-9")).isEqualTo("8901262150019");
    }
}
