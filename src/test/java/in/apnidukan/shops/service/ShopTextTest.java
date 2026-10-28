package in.apnidukan.shops.service;

import in.apnidukan.common.error.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void textCollapsesEverySpaceKindAndKeepsOtherScripts() {
        assertThat(ShopService.text(ShopService.NAME, "  Sharma\u2003\u00A0 Kirana\t")).isEqualTo("Sharma Kirana");
        assertThat(ShopService.text(ShopService.NAME, "ＳＨＡＲＭＡ")).isEqualTo("SHARMA");        // full-width letters (NFKC)
        assertThat(ShopService.text(ShopService.NAME, "शर्मा किराना")).isEqualTo("शर्मा किराना");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u00A0\u00A0", "a\u200Bb", "ab\u2028cd", "a\u0007b", "!!", "a."})
    void hiddenOrTooShortTextIsRefused(String value) {
        assertThatThrownBy(() -> ShopService.text(ShopService.NAME, value)).isInstanceOf(ApiException.class);
    }

    @Test
    void rightToLeftOverrideIsRefused() {
        // Built at runtime: the character itself in source code can hide code in editors (Error Prone refuses it)
        String trick = "Ramesh " + (char) 0x202E + "kcaj";
        assertThatThrownBy(() -> ShopService.text(ShopService.OWNER_NAME, trick)).isInstanceOf(ApiException.class);
    }

    @Test
    void textThatGrowsPastTheColumnWhenNormalisedIsRefused() {
        // 100 characters as sent (passes @Size), but each ellipsis becomes "..." when normalised: 296 characters
        String growsTo296 = "ab" + "\u2026".repeat(98);
        assertThatThrownBy(() -> ShopService.text(ShopService.NAME, growsTo296))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getDetails())
                        .containsEntry("name", "must be 2 to 100 characters"));
        // A single character that becomes 18 letters; 6 of them already pass 100
        assertThatThrownBy(() -> ShopService.text(ShopService.NAME, "\uFDFA".repeat(6)))
                .isInstanceOf(ApiException.class);
        assertThat(ShopService.text(ShopService.NAME, "ab" + "\u2026".repeat(32))).hasSize(98);
    }

    @Test
    void lengthIsCheckedAfterSpacesAreCollapsed() {
        // 6 characters as sent, but "a b" after tidying: below the address minimum of 5
        assertThatThrownBy(() -> ShopService.text(ShopService.ADDRESS_LINE, "a    b"))
                .isInstanceOf(ApiException.class);
        assertThat(ShopService.text(ShopService.ADDRESS_LINE, "12   MG  Road")).isEqualTo("12 MG Road");
    }
}
