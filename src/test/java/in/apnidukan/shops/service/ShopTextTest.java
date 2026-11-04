package in.apnidukan.shops.service;

import in.apnidukan.common.error.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** GSTIN rules. The shared text cleaning (names, addresses) is tested in TextCleanerTest. */
class ShopTextTest {

    @Test
    void gstinCheckCharacterMatchesRealNumbers() {
        assertThat(ShopService.gstinCheckChar("27AAPFU0939F1ZV")).isEqualTo('V');
        assertThat(ShopService.gstinCheckChar("29AAGCB7383J1Z4")).isEqualTo('4');
    }

    @Test
    void gstinIsUpperCasedAndSpacesRemoved() {
        assertThat(ShopService.gstin(" 27aapfu0939f1zv ")).isEqualTo("27AAPFU0939F1ZV");
        assertThat(ShopService.gstin("")).isNull();
        assertThat(ShopService.gstin(null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"27AAPFU0939F1ZW", "39AAPFU0939F1ZQ", "00AAPFU0939F1ZB", "27AAPFU0939F1Z", "27AAPFU0939F1AV"})
    void wrongGstinsAreRefused(String gstin) {
        assertThatThrownBy(() -> ShopService.gstin(gstin)).isInstanceOf(ApiException.class);
    }
}
