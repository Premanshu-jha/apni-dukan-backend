package in.apnidukan.kirana.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The counter's rules that need no database: quantities, money, UPI IDs and page tokens. */
class KiranaRulesTest {

    @Test
    void piecesMustBeWholeAndKgCanGoToTheGram() {
        assertThat(Quantities.problem(new BigDecimal("2"), Unit.PIECE, false)).isEmpty();
        assertThat(Quantities.problem(new BigDecimal("2.000"), Unit.PIECE, false)).isEmpty();   // 2 written longer
        assertThat(Quantities.problem(new BigDecimal("1.5"), Unit.PIECE, false).orElse("")).contains("whole number");
        assertThat(Quantities.problem(new BigDecimal("0.25"), Unit.KG, false)).isEmpty();
        assertThat(Quantities.problem(new BigDecimal("0.001"), Unit.KG, false)).isEmpty();
        assertThat(Quantities.problem(new BigDecimal("0.0005"), Unit.KG, false).orElse("")).contains("3 decimals");
        assertThat(Quantities.problem(new BigDecimal("1E+1"), Unit.PIECE, false)).isEmpty();   // 10, in JSON's other form
    }

    @Test
    void zeroIsOnlyForACountAndNothingIsNegativeOrHuge() {
        assertThat(Quantities.problem(BigDecimal.ZERO, Unit.PIECE, false).orElse("")).contains("more than 0");
        assertThat(Quantities.problem(BigDecimal.ZERO, Unit.PIECE, true)).isEmpty();
        assertThat(Quantities.problem(new BigDecimal("-1"), Unit.KG, true).orElse("")).contains("below 0");
        assertThat(Quantities.problem(new BigDecimal("9999"), Unit.PIECE, false)).isEmpty();
        assertThat(Quantities.problem(new BigDecimal("10000"), Unit.PIECE, false).orElse("")).contains("at most 9999");
        assertThat(Quantities.problem(null, Unit.PIECE, false).orElse("")).contains("required");
        // A count by hand (or a first stock) may be a godown's worth
        assertThat(Quantities.problem(new BigDecimal("14000"), Unit.PIECE, true)).isEmpty();
        assertThat(Quantities.problem(new BigDecimal("1000000"), Unit.PIECE, true).orElse("")).contains("999999");
    }

    @Test
    void amountsRoundToTheNearestPaisa() {
        assertThat(Quantities.amount(4800, new BigDecimal("0.25"))).isEqualTo(1200);   // 250 g of ₹48/kg
        assertThat(Quantities.amount(4500, new BigDecimal("0.333"))).isEqualTo(1499);  // 1498.5 → 1499
        assertThat(Quantities.amount(2700, new BigDecimal("3"))).isEqualTo(8100);
        assertThat(Quantities.amount(1, new BigDecimal("0.001"))).isZero();            // a tenth of a paisa
    }

    @Test
    void quantitiesAreStoredWithThreeDecimalsAndShownPlainly() {
        assertThat(Quantities.stored(new BigDecimal("2")).toPlainString()).isEqualTo("2.000");
        assertThat(Quantities.plain(new BigDecimal("2.000"))).isEqualTo("2");
        assertThat(Quantities.plain(new BigDecimal("20.000"))).isEqualTo("20");   // never "2E+1"
        assertThat(Quantities.plain(new BigDecimal("0.250"))).isEqualTo("0.25");
        assertThat(Quantities.json(new BigDecimal("100.000")).scale()).isZero();   // 100 in JSON, never 1E+2
        assertThat(Quantities.json(null)).isNull();
    }

    @Test
    void upiIdsAreTidiedAndMustLookLikeNameAtBank() {
        assertThat(UpiIds.clean(" SharmaStore @OKHDFCBank ")).isEqualTo("sharmastore@okhdfcbank");
        assertThat(UpiIds.valid("sharmastore@okhdfcbank")).isTrue();
        assertThat(UpiIds.valid("9848012345@ybl")).isTrue();
        assertThat(UpiIds.valid("ramesh.sharma-1@paytm")).isTrue();
        assertThat(UpiIds.valid("sharmastore")).isFalse();             // no bank
        assertThat(UpiIds.valid("@okhdfcbank")).isFalse();             // no name
        assertThat(UpiIds.valid("a@okhdfcbank")).isFalse();            // too short
        assertThat(UpiIds.valid("sharma@store@ybl")).isFalse();
        assertThat(UpiIds.valid("sharma@1bank")).isFalse();            // bank names start with a letter
        assertThat(UpiIds.valid("sharma store@ybl")).isFalse();        // as typed, before clean
        assertThat(UpiIds.clean(null)).isEmpty();
    }

    @Test
    void pageTokensRoundTripAndABrokenOneMeansTheFirstPage() {
        String t = Cursors.encode("amul butter 100 g", "6f1c0e8e-0000-4000-8000-000000000001");
        String[] back = Cursors.decode(t).orElseThrow();
        assertThat(back[0]).isEqualTo("amul butter 100 g");
        assertThat(back[1]).isEqualTo("6f1c0e8e-0000-4000-8000-000000000001");
        assertThat(Cursors.decode(null)).isEmpty();
        assertThat(Cursors.decode("")).isEmpty();
        assertThat(Cursors.decode("not base64 at all!")).isEmpty();
        assertThat(Cursors.decode("bm8tc2VwYXJhdG9y")).isEmpty();   // "no-separator"
        assertThat(Cursors.decode("x".repeat(500))).isEmpty();
    }

    @Test
    void aProductIsRunningOutAtItsAlertOrBelowZero() {
        assertThat(product("5", "5").runningOut()).isTrue();
        assertThat(product("6", "5").runningOut()).isFalse();
        assertThat(product("-1", null).runningOut()).isTrue();
        assertThat(product("0", null).runningOut()).isFalse();   // no alert set: an empty shelf isn't nagged about
    }

    private static Product product(String stock, String low) {
        return new Product(UUID.randomUUID(), UUID.randomUUID(), "Amul Butter 100 g", Unit.PIECE, 5800,
                new BigDecimal(stock), low == null ? null : new BigDecimal(low), true, 0, Instant.EPOCH, null);
    }
}
