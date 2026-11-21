package in.apnidukan.salon.service;

import in.apnidukan.common.context.ShopDirectory;
import in.apnidukan.common.error.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the customer typed in the salon search box, as the database gets it (the matching itself runs against real
 * Postgres in SalonSearchIntegrationTest).
 */
class SalonSearchTest {

    @Test
    void noTextOrOnlySpacesIsNoSearchAtAll() {
        assertThat(SalonSearch.parse(null)).isNull();
        assertThat(SalonSearch.parse("")).isNull();
        assertThat(SalonSearch.parse("   \t ")).isNull();
    }

    @Test
    void theTextIsTrimmedLowerCasedAndMatchedAnywhereInAName() {
        ShopDirectory.Search s = SalonSearch.parse("  Hair   COLOUR ");
        assertThat(s.likePattern()).isEqualTo("%hair colour%");
        assertThat(s.typoWords()).isEqualTo("hair colour");
        assertThat(s.serviceIcons()).containsExactly("HAIR_COLOUR");
    }

    @Test
    void theTextIsTidiedAsSavedNamesAreBeforeItIsCountedAndLowerCased() {
        // Full-width letters from some keyboards: NFKC, as TextCleaner saves names
        assertThat(SalonSearch.parse("\uFF32\uFF41\uFF4A\uFF55").likePattern()).isEqualTo("%raju%");
        // A one-character "फ़" (U+095E) is saved as "फ" and its dot (U+092B U+093C)
        assertThat(SalonSearch.parse("\u095E\u0947\u0936\u093F\u092F\u0932").likePattern())
                .isEqualTo("%\u092B\u093C\u0947\u0936\u093F\u092F\u0932%");
        // A no-break space (and any other kind) between words is one plain space
        assertThat(SalonSearch.parse("raju\u00A0hair\u2003salon").likePattern()).isEqualTo("%raju hair salon%");
        assertThat(SalonSearch.parse("raju\u00A0hair").typoWords()).isEqualTo("raju hair");
        // Only no-break spaces: nothing to search for
        assertThat(SalonSearch.parse("\u00A0\u00A0")).isNull();
        // Counted after tidying: "Ｒ" + a no-break space is one letter
        assertThatThrownBy(() -> SalonSearch.parse("\uFF32\u00A0")).isInstanceOf(ApiException.class);
    }

    @Test
    void theNameIsMatchedWordByWordWithTheWordsAsOneParameter() {
        ShopDirectory.Search s = SalonSearch.parse("Lakme  Salon");
        assertThat(s.textArgs()).containsExactly("%lakme salon%", "lakme salon");
        String sql = s.textMatches("lower(s.name)");
        assertThat(sql).contains("lower(s.name) LIKE ? ESCAPE", "string_to_array(?, ' ')",
                "strpos(lower(s.name), qw.word) = 0", "char_length(qw.word) >= 4",
                "THEN 2 ELSE 1 END", "similarity(qw.word, nw.word) >= 0.35").doesNotContain("word_similarity");
        assertThat(sql.chars().filter(c -> c == '?').count()).isEqualTo(s.textArgs().size());
        ShopDirectory.Search exact = SalonSearch.parse("50%");
        assertThat(exact.textMatches("lower(s.name)")).doesNotContain("similarity");
        assertThat(exact.textArgs()).containsExactly("%50\\%%");
        // Only the columns written in the code can go into the statement, never anything else
        assertThatThrownBy(() -> s.textMatches("lower(s.name); DROP TABLE shops"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void percentUnderscoreAndBackslashAreJustCharactersAndNeverForgiveTypos() {
        assertThat(SalonSearch.parse("50%")).isEqualTo(new ShopDirectory.Search("%50\\%%", null, List.of()));
        assertThat(SalonSearch.parse("a_b").likePattern()).isEqualTo("%a\\_b%");
        assertThat(SalonSearch.parse("a\\b").likePattern()).isEqualTo("%a\\\\b%");
        assertThat(SalonSearch.parse("a\\b").typoWords()).isNull();
    }

    @Test
    void slipsAreForgivenFromFourLettersInAnyScriptButNotForShorterWords() {
        assertThat(SalonSearch.parse("facal").typoWords()).isEqualTo("facal");
        assertThat(SalonSearch.parse("दाढ़ी").typoWords()).isEqualTo("दाढ़ी");   // vowel signs count as letters
        assertThat(SalonSearch.parse("spa").typoWords()).isNull();
        assertThat(SalonSearch.parse("fa").typoWords()).isNull();
        assertThat(SalonSearch.parse("hair2").typoWords()).isNull();
    }

    @Test
    void aPictureIsNamedByTheStartOfOneOfItsWordsOrAnotherWordForIt() {
        assertThat(SalonSearch.parse("Facial").serviceIcons()).containsExactly("FACIAL");
        assertThat(SalonSearch.parse("clean").serviceIcons()).containsExactly("FACIAL");   // "Face clean-up"
        assertThat(SalonSearch.parse("face").serviceIcons()).containsExactly("FACIAL");    // people say "face" for a facial
        assertThat(SalonSearch.parse("color").serviceIcons()).containsExactly("HAIR_COLOUR");
        assertThat(SalonSearch.parse("mehndi").serviceIcons()).containsExactly("MEHENDI");
        assertThat(SalonSearch.parse("mustache").serviceIcons()).containsExactly("MOUSTACHE");
        assertThat(SalonSearch.parse("massage").serviceIcons()).containsExactly("HEAD_MASSAGE", "MASSAGE");
        assertThat(SalonSearch.parse("ha").serviceIcons())
                .containsExactlyInAnyOrder("HAIRCUT", "HAIR_WASH", "HAIR_SPA", "HAIR_COLOUR");   // not SHAVE, BRIDAL
        assertThat(SalonSearch.parse("olour").serviceIcons()).isEmpty();
        assertThat(SalonSearch.parse("other").serviceIcons()).isEmpty();
        assertThat(SalonSearch.parse("Raju").serviceIcons()).isEmpty();
    }

    @Test
    void oneCharacterOrMoreThanSixtyIsRefusedOnQ() {
        for (String bad : new String[] {"f", "  f  ", "\\", "x".repeat(61)}) {
            assertThatThrownBy(() -> SalonSearch.parse(bad)).as(bad)
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.getStatus().value()).isEqualTo(400);
                        assertThat(e.getCode()).isEqualTo("VALIDATION_FAILED");
                        assertThat(e.getDetails()).containsOnlyKeys("q");
                    });
        }
        assertThat(SalonSearch.parse("x".repeat(60))).isNotNull();
        assertThat(SalonSearch.parse("  " + "x".repeat(60) + "  ")).isNotNull();   // spaces at the ends don't count
        assertThat(SalonSearch.parse("ऊ".repeat(60))).isNotNull();   // characters, not bytes
    }
}
