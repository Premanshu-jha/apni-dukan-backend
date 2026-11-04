package in.apnidukan.common.text;

import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.text.TextCleaner.TextField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static in.apnidukan.common.text.TextCleaner.clean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The text cleaning shared by shop names and addresses, salon services and barbers (moved here from ShopTextTest). */
class TextCleanerTest {

    private static final TextField NAME = new TextField("name", 2, 100);
    private static final TextField OWNER_NAME = new TextField("ownerName", 2, 100);
    private static final TextField ADDRESS_LINE = new TextField("addressLine", 5, 200);

    @Test
    void textCollapsesEverySpaceKindAndKeepsOtherScripts() {
        assertThat(clean(NAME, "  Sharma\u2003\u00A0 Kirana\t")).isEqualTo("Sharma Kirana");
        assertThat(clean(NAME, "ＳＨＡＲＭＡ")).isEqualTo("SHARMA");        // full-width letters (NFKC)
        assertThat(clean(NAME, "शर्मा किराना")).isEqualTo("शर्मा किराना");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u00A0\u00A0", "a\u200Bb", "ab\u2028cd", "a\u0007b", "!!", "a."})
    void hiddenOrTooShortTextIsRefused(String value) {
        assertThatThrownBy(() -> clean(NAME, value)).isInstanceOf(ApiException.class);
    }

    @Test
    void rightToLeftOverrideIsRefused() {
        // Built at runtime: the character itself in source code can hide code in editors (Error Prone refuses it)
        String trick = "Ramesh " + (char) 0x202E + "kcaj";
        assertThatThrownBy(() -> clean(OWNER_NAME, trick)).isInstanceOf(ApiException.class);
    }

    @Test
    void textThatGrowsPastTheColumnWhenNormalisedIsRefused() {
        // 100 characters as sent (passes @Size), but each ellipsis becomes "..." when normalised: 296 characters
        String growsTo296 = "ab" + "\u2026".repeat(98);
        assertThatThrownBy(() -> clean(NAME, growsTo296))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getDetails())
                        .containsEntry("name", "must be 2 to 100 characters"));
        // A single character that becomes 18 letters; 6 of them already pass 100
        assertThatThrownBy(() -> clean(NAME, "\uFDFA".repeat(6)))
                .isInstanceOf(ApiException.class);
        assertThat(clean(NAME, "ab" + "\u2026".repeat(32))).hasSize(98);
    }

    @Test
    void lengthIsCheckedAfterSpacesAreCollapsed() {
        // 6 characters as sent, but "a b" after tidying: below the address minimum of 5
        assertThatThrownBy(() -> clean(ADDRESS_LINE, "a    b"))
                .isInstanceOf(ApiException.class);
        assertThat(clean(ADDRESS_LINE, "12   MG  Road")).isEqualTo("12 MG Road");
    }
}
