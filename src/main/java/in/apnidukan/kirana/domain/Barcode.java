package in.apnidukan.kirana.domain;

import java.math.BigDecimal;

/**
 * A barcode of a product (kirana_barcodes): the code as printed, and how many pieces (or kg) one scan of it means
 * (1 for a single pack, 12 for a carton of 12).
 */
public record Barcode(String code, BigDecimal pack) {
}
