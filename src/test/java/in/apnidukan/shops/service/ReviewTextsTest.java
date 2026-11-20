package in.apnidukan.shops.service;

import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.notify.Language;
import in.apnidukan.common.notify.NoticeTexts;
import in.apnidukan.shops.domain.LicenceCheckReason;
import in.apnidukan.shops.domain.RegistrationDocType;
import in.apnidukan.shops.domain.ReviewReason;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every fixed reason the owner can be given has its words in every language, and a reason without them (a gap) never
 * fails the decision.
 */
class ReviewTextsTest {

    @Test
    void everyReasonLicenceAnswerAndDocumentHasItsTextInEveryLanguage() {
        for (Language l : Language.values()) {
            for (ReviewReason r : ReviewReason.values()) {
                if (r != ReviewReason.OTHER) {   // never listed: its words are the team's note
                    assertThat(NoticeTexts.template(l, "reason." + r.name())).as(l.code() + " " + r).isPresent();
                }
            }
            for (LicenceCheckReason r : LicenceCheckReason.values()) {
                assertThat(NoticeTexts.template(l, "licence." + r.name())).as(l.code() + " " + r).isPresent();
            }
            for (RegistrationDocType d : RegistrationDocType.values()) {
                assertThat(NoticeTexts.template(l, "doc." + d.name())).as(l.code() + " " + d).isPresent();
            }
        }
        // A salon's licence answer says in English what the code always wrote
        for (LicenceCheckReason r : LicenceCheckReason.values()) {
            assertThat(NoticeTexts.render(Language.EN, "licence." + r.name(), java.util.Map.of()))
                    .contains(LicenceCheckJob.explain(r));
        }
    }

    @Test
    void reasonsInEnglishAndAReasonWithoutWordsFallsBackToItsName() {
        ShopAdminService.Reasons both = ShopAdminService.reasons(List.of("LICENCE_EXPIRED", "ADDRESS_WRONG"),
                "Call us.");
        assertThat(both.english()).isEqualTo("the trade licence has expired, the address is wrong. Call us.");
        assertThat(ShopAdminService.reasons(null, "Just words.").english()).isEqualTo("Just words.");
        // Every reason at once still renders
        List<String> every = Arrays.stream(ReviewReason.values()).map(Enum::name).toList();
        assertThat(ShopAdminService.reasons(every, "x y").english()).startsWith("the shop front photo isn't clear")
                .endsWith(". x y");
        // A code the texts don't know (never valid through reasons(), but stored rows outlive the enum's texts)
        assertThat(new ShopAdminService.Reasons(List.of("SOMETHING_NEW", "ADDRESS_WRONG"), "Call us.").english())
                .isEqualTo("SOMETHING_NEW, ADDRESS_WRONG. Call us.");
        assertThat(new ShopAdminService.Reasons(List.of("SOMETHING_NEW"), null).english()).isEqualTo("SOMETHING_NEW.");
    }

    @Test
    void aProblemWithTheWordsNamesTheFieldTheyCameIn() {
        assertThatThrownBy(() -> ShopAdminService.reasons(null, "x", "note"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getDetails()).containsOnlyKeys("note"));
        assertThatThrownBy(() -> ShopAdminService.reasons(List.of("OTHER"), " ", "note"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getDetails()).containsOnlyKeys("note"));
        assertThatThrownBy(() -> ShopAdminService.reasons(null, "x"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getDetails()).containsOnlyKeys("reason"));
        assertThatThrownBy(() -> ShopAdminService.reasons(List.of("NOPE"), "fine words", "note"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getDetails()).containsOnlyKeys("reasonCodes"));
    }
}
