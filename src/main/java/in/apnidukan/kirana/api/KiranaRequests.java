package in.apnidukan.kirana.api;

import in.apnidukan.kirana.domain.Unit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Request bodies of the kirana counter (/api/v1/kirana). Unknown fields are refused (400). Money is whole paise;
 * quantities are pieces (whole numbers) or kg (up to 3 decimals), as the product's unit says.
 */
public final class KiranaRequests {

    private KiranaRequests() {
    }

    /**
     * A product added to the shop's catalogue.
     * @param barcode     optional, as printed (letters and digits, 4–32); one active product per barcode in a shop.
     *                    More barcodes are linked later (POST /products/{id}/barcodes)
     * @param pack        how many pieces (or kg) one scan of that barcode means: 1 if left out, 12 for a carton of 12
     * @param mrpPaise    the MRP printed on the pack (optional): kept in the shared catalogue when this barcode is new
     *                    there, so the next shop's scan fills it in
     * @param pricePaise  per piece, or per kg for KG: ₹0.01 to ₹1,00,000
     * @param stock       how many (or how much) is on the shelf now, counted (0 if left out, and then not counted:
     *                    it isn't offered online until Count)
     * @param lowStockAt  "running out" at or below this; left out: no alert
     */
    public record NewProduct(@NotBlank(message = "Enter the product's name")
                             @Size(min = 2, max = 100, message = "Use 2 to 100 letters") String name,
                             @Size(max = 32, message = "A barcode has at most 32 characters") String barcode,
                             BigDecimal pack,
                             @Min(value = 1, message = "The MRP must be more than ₹0")
                             @Max(value = 10_000_000, message = "The MRP can be at most ₹1,00,000") Long mrpPaise,
                             @NotNull(message = "Choose piece or kg") Unit unit,
                             @NotNull(message = "Enter the price")
                             @Min(value = 1, message = "The price must be more than ₹0")
                             @Max(value = 10_000_000, message = "The price can be at most ₹1,00,000") Long pricePaise,
                             BigDecimal stock,
                             BigDecimal lowStockAt) {
    }

    /**
     * Changes to a product (owner only). Leave a field out to keep it. Barcodes are linked and unlinked apart.
     * @param version         the version you last read; 409 PRODUCT_CHANGED if someone saved in between
     * @param unit            piece or kg, only until it is first sold (409 PRODUCT_SOLD after)
     * @param noLowStockAlert true: no "running out" alert any more (lowStockAt is then ignored)
     * @param active          false removes it from the catalogue (old bills keep it); true puts a removed one back
     */
    public record EditProduct(@NotNull Long version,
                              @Size(min = 2, max = 100, message = "Use 2 to 100 letters") String name,
                              Unit unit,
                              @Min(value = 1, message = "The price must be more than ₹0")
                              @Max(value = 10_000_000, message = "The price can be at most ₹1,00,000") Long pricePaise,
                              BigDecimal lowStockAt,
                              Boolean noLowStockAlert,
                              Boolean active) {
    }

    /**
     * A barcode linked to a product (owner or helper: linking a pack at the counter). The owner may send one the product
     * already has with another pack, to correct it.
     * @param code as printed (letters and digits, 4–32)
     * @param pack how many pieces (or kg) one scan means: 1 if left out, 12 for a carton of 12 (whole for pieces)
     */
    public record NewBarcode(@NotBlank(message = "Scan or type the barcode")
                             @Size(max = 32, message = "A barcode has at most 32 characters") String code,
                             BigDecimal pack) {
    }

    /** RESTOCK adds stock that came in; DAMAGED takes pieces off the shelf; COUNT sets the stock to a count by hand. */
    public enum StockReason { RESTOCK, DAMAGED, COUNT }

    /**
     * @param clientRef made by the app once per change: sending it again (a retry after a lost answer) changes the stock
     *                  once
     * @param quantity  how much came in or was damaged (more than 0, at most 9999), or the count (0 to 999999)
     */
    public record StockChange(@NotNull UUID clientRef,
                              @NotNull(message = "Choose what happened") StockReason reason,
                              @NotNull(message = "Enter how many") BigDecimal quantity,
                              @Size(max = 200, message = "A note has at most 200 characters") String note) {
    }

    /** How a walk-in bill was paid: cash, to the shop's own UPI QR, or on the shop's khata. Nothing goes via Clozest. */
    public enum Payment { CASH, UPI, KHATA }

    /**
     * A walk-in bill. Prices of listed items come from the catalogue, never from the app.
     * @param clientRef        made by the app once per bill: sending the same bill again (a double tap, a retry after a
     *                         lost answer) returns the first one instead of billing twice
     * @param lines            1 to 100; the same product may appear on several lines
     * @param khataCustomerId  required for KHATA, refused otherwise
     * @param discountPaise    off the whole bill (a discount or a round-off), at most its total; a helper up to ₹10
     * @param paidNowPaise     KHATA only: paid at the counter now (less than the total), the rest goes on the khata
     * @param paidNowBy        with paidNowPaise: CASH or UPI (the UPI QR is then for this amount)
     */
    public record NewBill(@NotNull UUID clientRef,
                          @NotNull(message = "Add at least one item")
                          @Size(min = 1, max = 100, message = "A bill has 1 to 100 items") List<@NotNull @Valid Line> lines,
                          @NotNull(message = "Choose how they paid") Payment payment,
                          UUID khataCustomerId,
                          @Min(value = 0, message = "The discount can't be below ₹0")
                          @Max(value = 100_000_000, message = "The discount can be at most ₹10,00,000")
                          Long discountPaise,
                          @Min(value = 1, message = "Enter what they paid now, more than ₹0")
                          @Max(value = 100_000_000, message = "The amount can be at most ₹10,00,000")
                          Long paidNowPaise,
                          PaidBy paidNowBy) {

        public NewBill {
            lines = lines == null ? null : List.copyOf(lines);
        }
    }

    /**
     * One line of a bill: a listed product by quantity, a loose (kg) product by the amount asked for ("₹20 of
     * jeera": the weight is worked out), or an item not in the list by its name, price and how many.
     * @param productId   a listed product; leave out for an item not in the list
     * @param quantity    pieces (whole) or kg (to the gram), more than 0; for a kg product, or leave out and send
     *                    amountPaise; for an item not in the list: how many pieces (1 if left out)
     * @param amountPaise for a kg product: what the customer asked for; for an item not in the list: its price each
     * @param name        an item not in the list only (2–100 letters, not the name of a product in the list); its stock
     *                    isn't kept
     */
    public record Line(UUID productId, BigDecimal quantity,
                       @Min(value = 1, message = "The amount must be more than ₹0")
                       @Max(value = 10_000_000, message = "The amount can be at most ₹1,00,000") Long amountPaise,
                       @Size(max = 100, message = "Use 2 to 100 letters") String name) {
    }

    /** Why a bill is cancelled (kept with it, 2–200 characters). */
    public record VoidBill(@NotBlank(message = "Say why you're cancelling it")
                           @Size(min = 2, max = 200, message = "Use 2 to 200 letters") String reason) {
    }

    /**
     * A bill's payment changed after saving (UPI failed and they paid cash; put on khata after all). The same fields
     * as on a new bill. The owner any time; the helper who made the bill within 10 minutes.
     * @param version the bill's version you last read: 409 BILL_CHANGED if someone changed it meanwhile
     * @param advance true: taking the bill off a khata its customer already paid may leave them an advance (asked after
     *                409 KHATA_ALREADY_PAID)
     */
    public record ChangePayment(@NotNull Long version,
                                @NotNull(message = "Choose how they paid") Payment payment,
                                UUID khataCustomerId,
                                @Min(value = 1, message = "Enter what they paid now, more than ₹0")
                                @Max(value = 100_000_000, message = "The amount can be at most ₹10,00,000")
                                Long paidNowPaise,
                                PaidBy paidNowBy,
                                Boolean advance) {
    }

    /**
     * Items of a bill brought back. The money goes back as the bill was paid (cash, UPI, or off the khata).
     * @param clientRef made by the app once per return: sending it again makes it once
     * @param lines     the bill's lines and how much of each comes back (pieces or kg, as the line was sold)
     * @param reason    optional, e.g. "Expired"
     */
    public record NewReturn(@NotNull UUID clientRef,
                            @NotNull(message = "Choose what comes back")
                            @Size(min = 1, max = 100, message = "Choose what comes back")
                            List<@NotNull @Valid ReturnLine> lines,
                            @Size(max = 200, message = "A reason has at most 200 characters") String reason) {

        public NewReturn {
            lines = lines == null ? null : List.copyOf(lines);
        }
    }

    /** @param quantity how much of that line comes back, at most what is left of it */
    public record ReturnLine(@NotNull @Min(1) @Max(100) Integer lineNo,
                             @NotNull(message = "Enter how many") BigDecimal quantity) {
    }

    /**
     * A regular customer in the khata book.
     * @param phone             optional mobile, for the WhatsApp reminder: typed or pasted as people do ("+91 98480-12345"
     *                          is fine); stored as 10 digits
     * @param note              optional, e.g. "Tower B 1204"
     * @param creditLimitPaise  the most they may owe; left out: ₹2,000. Owner only
     * @param noLimit           true: no limit at all. Owner only
     */
    public record NewKhataCustomer(@NotBlank(message = "Enter the customer's name")
                                   @Size(min = 2, max = 80, message = "Use 2 to 80 letters") String name,
                                   @Size(max = 20, message = "Enter a 10-digit mobile number") String phone,
                                   @Size(max = 100, message = "A note has at most 100 characters") String note,
                                   @Min(value = 0, message = "The limit can't be below ₹0")
                                   @Max(value = 100_000_000, message = "The limit can be at most ₹10,00,000")
                                   Long creditLimitPaise,
                                   Boolean noLimit) {
    }

    /**
     * Changes to a khata customer; leave a field out to keep it.
     * @param phone    "" removes it
     * @param note     "" removes it
     * @param noLimit  true removes the credit limit (owner only, like creditLimitPaise)
     * @param active   false takes them out of the book (only when they owe nothing)
     */
    public record EditKhataCustomer(@NotNull Long version,
                                    @Size(min = 2, max = 80, message = "Use 2 to 80 letters") String name,
                                    @Size(max = 20, message = "Enter a 10-digit mobile number") String phone,
                                    @Size(max = 100, message = "A note has at most 100 characters") String note,
                                    @Min(value = 0, message = "The limit can't be below ₹0")
                                    @Max(value = 100_000_000, message = "The limit can be at most ₹10,00,000")
                                    Long creditLimitPaise,
                                    Boolean noLimit,
                                    Boolean active) {
    }

    /** How a khata customer paid the shop. */
    public enum PaidBy { CASH, UPI }

    /**
     * A payment received from a khata customer.
     * @param clientRef   made by the app once per payment: the same payment sent again is recorded once
     * @param amountPaise what they paid; more than they owe only with advance
     * @param advance     true: keep what is more than they owe as their advance (asked after 409 MORE_THAN_OWED)
     */
    public record KhataPayment(@NotNull UUID clientRef,
                               @NotNull(message = "Enter the amount")
                               @Min(value = 1, message = "The amount must be more than ₹0")
                               @Max(value = 100_000_000, message = "The amount can be at most ₹10,00,000")
                               Long amountPaise,
                               @NotNull(message = "Choose cash or UPI") PaidBy method,
                               @Size(max = 200) String note,
                               Boolean advance) {
    }

    /**
     * A customer's advance given back to them (owner only).
     * @param clientRef made by the app once: sending it again gives it back once
     * @param amountPaise at most their advance
     */
    public record AdvanceBack(@NotNull UUID clientRef,
                              @NotNull(message = "Enter the amount")
                              @Min(value = 1, message = "The amount must be more than ₹0")
                              @Max(value = 100_000_000, message = "The amount can be at most ₹10,00,000")
                              Long amountPaise,
                              @NotNull(message = "Choose cash or UPI") PaidBy method) {
    }

    /** Why a khata payment recorded by mistake is undone (kept with it, 2–200 characters). */
    public record ReversePayment(@NotBlank(message = "Say why you're undoing it")
                                 @Size(min = 2, max = 200, message = "Use 2 to 200 letters") String reason) {
    }

    /** The shop's UPI ID, e.g. sharmastore@okhdfcbank (spaces removed, stored lower case). Owner only. */
    public record SellsFood(@NotNull(message = "Say whether you sell any food items") Boolean sellsFood) {
    }

    public record UpiId(@NotBlank(message = "Enter the shop's UPI ID")
                        @Size(max = 120, message = "A UPI ID has at most 120 characters") String upiId) {
    }
}
