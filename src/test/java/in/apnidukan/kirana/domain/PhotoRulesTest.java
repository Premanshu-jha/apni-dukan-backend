package in.apnidukan.kirana.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PhotoRulesTest {

    private static PhotoRules.Seen pack(String brand, String name, String size) {
        return new PhotoRules.Seen(true, false, true, true, true, true, brand, name, size);
    }

    @Test
    void theRightPackWithItsSizeIsAcceptedEvenWhenTheShopLeftTheVariantOut() {
        assertThat(PhotoRules.decide("Amul Taaza 250 ml", false, pack("Amul", "Taaza Toned Milk", "250 ml"), false)
                .ok()).isTrue();
        assertThat(PhotoRules.decide("Parle G", false, pack("Parle", "G Biscuit", null), false).ok()).isTrue();
    }

    @Test
    void anotherSizeOrAContradictingVariantIsRefusedWithWhatThePackShows() {
        PhotoRules.Verdict size = PhotoRules.decide("Amul Taaza 250 ml", false, pack("Amul", "Taaza", "500 ml"),
                false);
        assertThat(size.status()).isEqualTo("REFUSED");
        assertThat(size.reason()).contains("This pack says 500 ml").contains("Amul Taaza 250 ml");
        PhotoRules.Verdict variant = PhotoRules.decide("Amul Toned Milk 500 ml", false,
                pack("Amul", "Full Cream Milk", "500 ml"), false);
        assertThat(variant.status()).isEqualTo("REFUSED");
    }

    @Test
    void anUnreadableSizeAsksForTheSideWithItThenStillRefusesToGuess() {
        PhotoRules.Verdict first = PhotoRules.decide("Tata Salt 1 kg", false, pack("Tata", "Salt", null), false);
        assertThat(first.status()).isEqualTo("NEED_SIZE");
        assertThat(first.reason()).contains("side where 1 kg is printed");
        assertThat(PhotoRules.decide("Tata Salt 1 kg", false, pack("Tata", "Salt", null), true).reason())
                .startsWith("We still can't read the size");
    }

    @Test
    void looseGoodsNeedNoFrontOrSizeButMustBeThoseGoods() {
        PhotoRules.Seen sack = new PhotoRules.Seen(true, false, true, true, false, true, null, "Rice", null);
        assertThat(PhotoRules.decide("Sona Masoori Rice", true, sack, false).ok()).isTrue();
        PhotoRules.Seen dal = new PhotoRules.Seen(true, false, true, true, false, false, null, "Dal", null);
        assertThat(PhotoRules.decide("Sona Masoori Rice", true, dal, false).status()).isEqualTo("REFUSED");
    }

    @Test
    void theSizeToShowComesFromTheProductsName() {
        assertThat(PhotoRules.sizeToShow("Amul Taaza 250 ml")).isEqualTo("250 ml");
        assertThat(PhotoRules.sizeToShow("Parle G")).isNull();
    }
}
