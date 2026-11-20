package in.apnidukan.kirana.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * The rules for quantities and money at the counter, in one place so the bill, the stock and the tests agree. Money is
 * whole paise (a long); quantities are BigDecimal with at most 3 decimals (grams of a kg).
 */
public final class Quantities {

    /** The most of one product on one bill line, or in one stock change that came in or was damaged (pieces or kg). */
    public static final BigDecimal MAX = new BigDecimal("9999");

    /** The most a stock can be set to by a count (or a product's first stock): a godown's worth. */
    public static final BigDecimal MAX_STOCK = new BigDecimal("999999");

    private Quantities() {
    }

    /**
     * Why this quantity isn't allowed for the unit, or empty if it is: more than 0 (or 0 itself where zero is allowed,
     * i.e. a count by hand or a stock level, which may go up to {@link #MAX_STOCK}), else at most {@link #MAX}; whole
     * for PIECE, to the gram (3 decimals) for KG.
     */
    public static Optional<String> problem(BigDecimal q, Unit unit, boolean zeroAllowed) {
        if (q == null) {
            return Optional.of("is required");
        }
        if (zeroAllowed ? q.signum() < 0 : q.signum() <= 0) {
            return Optional.of(zeroAllowed ? "can't be below 0" : "must be more than 0");
        }
        BigDecimal max = zeroAllowed ? MAX_STOCK : MAX;
        if (q.compareTo(max) > 0) {
            return Optional.of("can be at most " + max.toPlainString());
        }
        int decimals = Math.max(0, q.stripTrailingZeros().scale());
        if (unit == Unit.PIECE && decimals > 0) {
            return Optional.of("must be a whole number for items sold by the piece");
        }
        if (decimals > 3) {
            return Optional.of("can have at most 3 decimals (grams)");
        }
        return Optional.empty();
    }

    /** price × quantity in paise, rounded to the nearest paisa (half up): 250 g of ₹48/kg is 1200 paise. */
    public static long amount(long pricePaise, BigDecimal quantity) {
        return BigDecimal.valueOf(pricePaise).multiply(quantity).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** The value as stored (NUMERIC(12,3)): 3 decimals, so 2 and 2.000 compare and print alike. */
    public static BigDecimal stored(BigDecimal q) {
        return q.setScale(3, RoundingMode.UNNECESSARY);
    }

    /** For people: "2" for pieces, "0.25" for kg (no trailing zeros, never "2E+1"). */
    public static String plain(BigDecimal q) {
        return json(q).toPlainString();
    }

    /**
     * As sent in JSON: no trailing zeros and never in exponent form (10, not 1E+1, which typed clients misread);
     * null stays null.
     */
    public static BigDecimal json(BigDecimal q) {
        if (q == null) {
            return null;
        }
        BigDecimal s = q.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0, RoundingMode.UNNECESSARY) : s;
    }
}
