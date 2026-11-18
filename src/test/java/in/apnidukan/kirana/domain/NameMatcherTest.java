package in.apnidukan.kirana.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How names from bills, labels and the catalogue are compared: flexible about units, short forms, spellings, word
 * order and brand; strict about size and variant.
 */
class NameMatcherTest {

    private static double score(String want, String have) {
        return NameMatcher.score(NameMatcher.parse(want, null, null), NameMatcher.parse(have, null, null)).value();
    }

    @Test
    void unitsShortFormsSpellingsAndWordOrderStillMatch() {
        assertThat(score("AMUL TAZA 500ML TM", "Amul Taaza Toned Milk 500 ml")).isGreaterThanOrEqualTo(NameMatcher.SURE);
        assertThat(score("Toned Milk Amul Taaza 0.5 L", "Amul Taaza Toned Milk 500 ml"))
                .isGreaterThanOrEqualTo(NameMatcher.SURE);
        assertThat(score("BRITANIA GOODDAY CASHEW 100GM", "Britannia Good Day Cashew 100 g"))
                .isGreaterThanOrEqualTo(NameMatcher.SURE);
        assertThat(score("Sugar 1kg", "Sugar 1000 g")).isGreaterThanOrEqualTo(NameMatcher.SURE);
        assertThat(score("MAGI NOODLE 70G", "Maggi Noodles 70 g")).isGreaterThanOrEqualTo(NameMatcher.SURE);
    }

    @Test
    void aDifferentSizeIsNeverTheSameProduct() {
        assertThat(NameMatcher.score(NameMatcher.parse("Amul Taaza 1 L", null, null),
                NameMatcher.parse("Amul Taaza 500 ml", null, null)).never()).isTrue();
        assertThat(NameMatcher.score(NameMatcher.parse("Parle G 6 x 70 g", null, null),
                NameMatcher.parse("Parle G 70 g", null, null)).never()).isTrue();
    }

    @Test
    void aDifferentVariantIsNeverAOneTapMatch() {
        assertThat(score("Amul Gold Milk 500 ml", "Amul Taaza Toned Milk 500 ml")).isLessThan(NameMatcher.SURE);
        assertThat(score("Colgate Sensitive 100 g", "Colgate Strong Teeth 100 g")).isLessThan(NameMatcher.SURE);
    }

    @Test
    void anotherBrandCountsAgainstAMatch() {
        double same = NameMatcher.score(NameMatcher.parse("Toned Milk 500 ml", "Amul", null),
                NameMatcher.parse("Toned Milk 500 ml", "Amul", null)).value();
        double other = NameMatcher.score(NameMatcher.parse("Toned Milk 500 ml", "Amul", null),
                NameMatcher.parse("Toned Milk 500 ml", "Heritage", null)).value();
        assertThat(other).isLessThan(same).isLessThan(NameMatcher.SURE);
    }

    @Test
    void oneClearWinnerIsSureButTwoCloseOnesAreNot() {
        NameMatcher.Parsed want = NameMatcher.parse("Amul Taaza Toned Milk 500 ml", null, null);
        List<NameMatcher.Ranked<String>> clear = NameMatcher.rank(want, List.of("Amul Taaza Toned Milk 500 ml",
                "Bread", "Sugar 1 kg"), n -> NameMatcher.parse(n, null, null), 3);
        assertThat(clear.get(0).candidate()).isEqualTo("Amul Taaza Toned Milk 500 ml");
        assertThat(NameMatcher.sure(clear)).isTrue();
        List<NameMatcher.Ranked<String>> close = NameMatcher.rank(want, List.of("Amul Taaza Toned Milk 500 ml",
                "Amul Taaza Toned Milk Pouch 500 ml"), n -> NameMatcher.parse(n, null, null), 3);
        assertThat(NameMatcher.sure(close)).isFalse();
    }

    @Test
    void wordingKeysIgnoreCaseSpacesAndPunctuation() {
        assertThat(NameMatcher.wordingKey("AMUL TAZA  500ML-TM.")).isEqualTo(NameMatcher.wordingKey("amul taza 500ml tm"));
    }
}
