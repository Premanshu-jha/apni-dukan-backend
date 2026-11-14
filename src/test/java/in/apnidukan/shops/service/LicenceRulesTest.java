package in.apnidukan.shops.service;

import in.apnidukan.shops.domain.LicenceCheckReason;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class LicenceRulesTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 14);
    static final LicenceRules.Salon SALON = new LicenceRules.Salon("Sharma Hair Studio", "Ravi Kumar Sharma",
            "Shop 4, Main Road", "Kondapur", "Hyderabad", "500084");

    static LicenceExtract genuine() {
        return new LicenceExtract(true, true, false, false, true, "Greater Hyderabad Municipal Corporation",
                "TR 0934-422-0003", "SHARMA HAIR STUDIO", "RAVI KUMAR SHARMA", "Shop 4, Main Road, Kondapur 500084",
                "500084", "Hair cutting saloon", LocalDate.of(2027, 3, 31));
    }

    static LicenceExtract with(LicenceExtract e, String tradeName, String ownerName, String address, String pincode) {
        return new LicenceExtract(e.readable(), e.tradeLicence(), e.looksEdited(), e.provisional(), e.salonTrade(),
                e.issuingBody(), e.licenceNumber(), tradeName, ownerName, address, pincode, e.tradeCategory(),
                e.validUntil());
    }

    @Test
    void aGenuineMatchingLicencePassesWithItsTidiedNumberAndLastDay() {
        LicenceRules.Outcome out = LicenceRules.decide(genuine(), SALON, TODAY);
        assertThat(out.passed()).isTrue();
        assertThat(out.reason()).isNull();
        assertThat(out.licenceNumber()).isEqualTo("TR09344220003");
        assertThat(out.validUntil()).isEqualTo(LocalDate.of(2027, 3, 31));
    }

    @Test
    void theFirstFailingCheckIsTheReason() {
        LicenceExtract g = genuine();
        assertThat(LicenceRules.decide(new LicenceExtract(false, false, true, false, false, null, null, null, null,
                null, null, null, null), SALON, TODAY).reason()).isEqualTo(LicenceCheckReason.UNREADABLE);
        assertThat(LicenceRules.decide(new LicenceExtract(true, false, false, false, true, null, "123456", null, null,
                null, null, null, null), SALON, TODAY).reason()).isEqualTo(LicenceCheckReason.NOT_TRADE_LICENCE);
        assertThat(LicenceRules.decide(new LicenceExtract(true, true, true, false, true, null, g.licenceNumber(),
                g.tradeName(), g.ownerName(), g.address(), g.pincode(), null, g.validUntil()), SALON, TODAY).reason())
                .isEqualTo(LicenceCheckReason.LOOKS_EDITED);
        assertThat(LicenceRules.decide(new LicenceExtract(true, true, false, false, true, null, "No. 1", g.tradeName(),
                g.ownerName(), g.address(), g.pincode(), null, g.validUntil()), SALON, TODAY).reason())
                .isEqualTo(LicenceCheckReason.NO_NUMBER);
        assertThat(LicenceRules.decide(new LicenceExtract(true, true, false, false, false, null, g.licenceNumber(),
                g.tradeName(), g.ownerName(), g.address(), g.pincode(), "Kirana", g.validUntil()), SALON, TODAY)
                .reason()).isEqualTo(LicenceCheckReason.NOT_A_SALON_TRADE);
        assertThat(LicenceRules.decide(new LicenceExtract(true, true, false, false, true, null, g.licenceNumber(),
                g.tradeName(), g.ownerName(), g.address(), g.pincode(), null, null), SALON, TODAY).reason())
                .isEqualTo(LicenceCheckReason.NO_VALIDITY);
    }

    @Test
    void validThroughItsLastDayExpiredTheDayAfter() {
        LicenceExtract g = genuine();
        LicenceExtract endsToday = new LicenceExtract(true, true, false, false, true, null, g.licenceNumber(),
                g.tradeName(), g.ownerName(), g.address(), g.pincode(), null, TODAY);
        assertThat(LicenceRules.decide(endsToday, SALON, TODAY).passed()).isTrue();
        assertThat(LicenceRules.decide(endsToday, SALON, TODAY.plusDays(1)).reason())
                .isEqualTo(LicenceCheckReason.EXPIRED);
    }

    @Test
    void eitherTheBusinessOrTheOwnersNameMustMatchIgnoringSalonWords() {
        LicenceExtract g = genuine();
        // Business name only ("Hair Studio" words dropped: "sharma" is shared)
        assertThat(LicenceRules.decide(with(g, "Sharma Unisex Saloon", "Somebody Else", g.address(), g.pincode()),
                SALON, TODAY).passed()).isTrue();
        // Owner's name only, in another order and with an initial
        assertThat(LicenceRules.decide(with(g, "Classic Cuts", "SHARMA R.K.", g.address(), g.pincode()), SALON,
                TODAY).passed()).isTrue();
        // Neither: only salon words in common
        assertThat(LicenceRules.decide(with(g, "Classic Hair Studio", "Mohammed Ali", g.address(), g.pincode()),
                SALON, TODAY).reason()).isEqualTo(LicenceCheckReason.NAME_MISMATCH);
        // A name missing on the licence doesn't match anything
        assertThat(LicenceRules.decide(with(g, null, null, g.address(), g.pincode()), SALON, TODAY).reason())
                .isEqualTo(LicenceCheckReason.NAME_MISMATCH);
        // Run together on one, apart on the other
        assertThat(LicenceRules.sameName("Sharmahair", "Sharma Hair")).isTrue();
    }

    @Test
    void thePincodeDecidesAndWithoutOneTheAreaMust() {
        LicenceExtract g = genuine();
        assertThat(LicenceRules.decide(with(g, g.tradeName(), g.ownerName(), "Kondapur", "500081"), SALON, TODAY)
                .reason()).isEqualTo(LicenceCheckReason.ADDRESS_MISMATCH);
        // No pincode field: the one in the address counts
        assertThat(LicenceRules.decide(with(g, g.tradeName(), g.ownerName(), "Kondapur, Hyderabad - 500 084", null),
                SALON, TODAY).passed()).isTrue();
        // No pincode at all: the area name
        assertThat(LicenceRules.decide(with(g, g.tradeName(), g.ownerName(), "H.No 2-3, Kondapur Village", null),
                SALON, TODAY).passed()).isTrue();
        // Only words every Hyderabad address has
        assertThat(LicenceRules.decide(with(g, g.tradeName(), g.ownerName(), "Main Road, Hyderabad, Telangana", null),
                SALON, TODAY).reason()).isEqualTo(LicenceCheckReason.ADDRESS_MISMATCH);
        assertThat(LicenceRules.decide(with(g, g.tradeName(), g.ownerName(), null, null), SALON, TODAY).reason())
                .isEqualTo(LicenceCheckReason.ADDRESS_MISMATCH);
    }

    @Test
    void theAddressMatchesInAnyWordOrderCaseOrPunctuation() {
        LicenceRules.Salon salon = new LicenceRules.Salon("Sharma Hair Studio", "Ravi Kumar Sharma",
                "Plot 23, Hitech City Road", "Madhapur", "Hyderabad", "500081");
        LicenceExtract g = genuine();
        // The pincode decides wherever it stands in the licence's address
        assertThat(LicenceRules.decide(with(g, g.tradeName(), g.ownerName(),
                "500081, Madhapur, Hitech City Road, Plot No. 23", null), salon, TODAY).passed()).isTrue();
        // No pincode at all: a shared place word counts in any order, case or punctuation
        assertThat(LicenceRules.decide(with(g, g.tradeName(), g.ownerName(),
                "HITECH CITY RD. / PLOT-23 / MADHAPUR", null), salon, TODAY).passed()).isTrue();
        assertThat(LicenceRules.decide(with(g, g.tradeName(), g.ownerName(), "Madhapur Hitech", null), salon, TODAY)
                .passed()).isTrue();
        // The names too: "SHARMA RAVI KUMAR" is "Ravi Kumar Sharma"
        assertThat(LicenceRules.sameName("SHARMA RAVI KUMAR", "Ravi Kumar Sharma")).isTrue();
    }

    @Test
    void oneCommonNameAloneIsNotTheSamePersonButTwoSharedWordsAre() {
        LicenceRules.Salon syedAli = new LicenceRules.Salon("Style Point", "Syed Ali", "Shop 4, Main Road", "Tolichowki",
                "Hyderabad", "500008");
        LicenceExtract g = genuine();
        // A neighbour's licence: "Syed" is shared, nothing else
        assertThat(LicenceRules.decide(with(g, "New Fashion Cuts", "Syed Rafi", "Tolichowki", "500008"), syedAli, TODAY)
                .reason()).isEqualTo(LicenceCheckReason.NAME_MISMATCH);
        // His own, the words the other way round
        assertThat(LicenceRules.decide(with(g, "New Fashion Cuts", "ALI SYED", "Tolichowki", "500008"), syedAli, TODAY)
                .passed()).isTrue();
        assertThat(LicenceRules.sameName("Kumar", "Ravi Kumar")).isFalse();
        assertThat(LicenceRules.sameName("Lakshmi Hair Salon", "Lakshmi Unisex")).isTrue();
    }

    @Test
    void aPincodeIsNeverTakenFromAPlotRoadOrPhoneNumber() {
        assertThat(LicenceRules.pincode("Plot 12, Road No 3 500033")).isEqualTo("500033");
        assertThat(LicenceRules.pincode("Hyderabad 500033 Ph 9848022338")).isEqualTo("500033");
        assertThat(LicenceRules.pincode("H.No 8-2-293/82/A, Jubilee Hills")).isNull();
        assertThat(LicenceRules.pincode("Kondapur - 500 084")).isEqualTo("500084");
        // Without a pincode: "Hills" alone doesn't make Jubilee Hills and Banjara Hills one place
        LicenceRules.Salon banjara = new LicenceRules.Salon("Sharma Hair Studio", "Ravi Kumar Sharma",
                "Road No 12", "Banjara Hills", "Hyderabad", "500034");
        assertThat(LicenceRules.samePlace(with(genuine(), null, null, "Road No 10, Jubilee Hills", null), banjara))
                .isFalse();
    }

    @Test
    void aLastValidDayFarAheadIsAMisreading() {
        LicenceExtract g = genuine();
        LicenceExtract far = new LicenceExtract(true, true, false, false, true, null, g.licenceNumber(),
                g.tradeName(), g.ownerName(), g.address(), g.pincode(), null, LocalDate.of(2099, 3, 31));
        assertThat(LicenceRules.decide(far, SALON, TODAY).reason()).isEqualTo(LicenceCheckReason.NO_VALIDITY);
        // A provisional licence (issued on payment, before the inspection) is a licence until its last day
        LicenceExtract provisional = new LicenceExtract(true, true, false, true, true, null, g.licenceNumber(),
                g.tradeName(), g.ownerName(), g.address(), g.pincode(), null, g.validUntil());
        assertThat(LicenceRules.decide(provisional, SALON, TODAY).passed()).isTrue();
    }

    @Test
    void licenceNumbersAreLettersAndDigitsOnlySoOneLicenceIsOneNumberHoweverItIsPrinted() {
        assertThat(LicenceRules.licenceNumber(" tr-0934 / 422 ")).isEqualTo("TR0934422");
        assertThat(LicenceRules.licenceNumber("TR 0934 422 0003")).isEqualTo(LicenceRules.licenceNumber("TR-0934-422-0003"));
        assertThat(LicenceRules.licenceNumber("0966/363/0003")).isEqualTo("09663630003");
        assertThat(LicenceRules.licenceNumber("ab-1")).isNull();
        assertThat(LicenceRules.licenceNumber(null)).isNull();
        assertThat(LicenceRules.licenceNumber("1".repeat(60))).hasSize(40);
    }
}
