package in.apnidukan.kirana.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Request bodies of kirana online orders (round 2): the shop's online settings, the customer's cart, checkout and
 * order, the shop's handling of an order, and the admin website's catalogue and checks. Money is whole paise.
 */
public final class OrderRequests {

    private OrderRequests() {
    }

    // ---------- the shop's online settings ----------

    /** The owner's switch: take online orders now or not (a busy day, a holiday). */
    public record TakingOrders(@NotNull Boolean on) {
    }

    /**
     * The shop's FSSAI licence or registration (needed before online orders of packaged food).
     * @param number the 14-digit number (spaces are fine)
     * @param fileId the certificate, uploaded as a registration document (POST /files, purpose REG_DOC)
     */
    public record Fssai(@NotBlank(message = "Enter the FSSAI number")
                        @Size(max = 30, message = "An FSSAI number has 14 digits") String number,
                        @NotNull(message = "Add a photo or the PDF of the certificate") UUID fileId) {
    }

    // ---------- the customer ----------

    /**
     * One line of the cart: one of the shop's products, or (for an item the shop doesn't have) a catalogue item from
     * the shops near it.
     * @param quantity pieces (whole) or kg (to the gram)
     */
    public record CartLine(UUID productId, UUID catalogueItemId, @NotNull(message = "Enter how many") BigDecimal quantity) {
    }

    /**
     * What the cart would cost, and who would give what (no order is made).
     * @param latitude  where the order goes (the customer's phone location)
     */
    public record Quote(@NotNull UUID shopId, @NotNull BigDecimal latitude, @NotNull BigDecimal longitude,
                        @NotNull(message = "Add something to the cart")
                        @Size(min = 1, max = 100, message = "A cart has 1 to 100 items") List<@NotNull @Valid CartLine> lines) {

        public Quote {
            lines = lines == null ? null : List.copyOf(lines);
        }
    }

    /** COMBINED: items the shop lacks come to it from a nearby shop, one delivery. SEPARATE: the other shop delivers them. */
    public enum Plan { COMBINED, SEPARATE }

    /** UPI, or the customer's khata: Razorpay's Pay Later lenders. */
    public enum Method { UPI, PAY_LATER }

    /**
     * Places the order: the cart as quoted, how to split it, how to pay. The total must be the one the customer saw
     * (409 PRICE_CHANGED with the new quote otherwise).
     * @param clientRef   made by the app once per checkout: sending it again returns the first one
     * @param addressLine flat, tower, landmark
     */
    public record PlaceOrder(@NotNull UUID clientRef, @NotNull UUID shopId, @NotNull BigDecimal latitude,
                             @NotNull BigDecimal longitude,
                             @NotBlank(message = "Enter your flat and building")
                             @Size(min = 3, max = 200, message = "Use 3 to 200 letters") String addressLine,
                             @NotNull(message = "Add something to the cart")
                             @Size(min = 1, max = 100, message = "A cart has 1 to 100 items")
                             List<@NotNull @Valid CartLine> lines,
                             @NotNull Plan plan,
                             @NotNull(message = "Choose UPI or khata") Method method,
                             @NotNull Long expectedTotalPaise) {

        public PlaceOrder {
            lines = lines == null ? null : List.copyOf(lines);
        }
    }

    /** What Razorpay's checkout gave the app after the payment. */
    public record PaymentDone(@NotBlank String razorpayOrderId, @NotBlank String razorpayPaymentId,
                              @NotBlank String razorpaySignature) {
    }

    /** The customer's corrected address text (flat, tower, landmark); the pin on the map stays. */
    public record ChangeAddress(@NotBlank(message = "Enter your flat and building")
                                @Size(min = 3, max = 200, message = "Use 3 to 200 letters") String addressLine) {
    }

    /** Why the customer cancels (optional). */
    public record CancelOrder(@Size(max = 200, message = "At most 200 letters") String reason) {
    }

    /** The customer's rating of a delivered order. */
    public record Rate(@NotNull(message = "Choose 1 to 5 stars") @Min(1) @Max(5) Integer stars,
                       @Size(max = 300, message = "At most 300 letters") String comment) {
    }

    // ---------- the shop's handling of an order ----------

    /** Why the shop can't take this order (2–200 letters, shown to the customer). */
    public record RejectPart(@NotBlank(message = "Say why")
                             @Size(min = 2, max = 200, message = "Use 2 to 200 letters") String reason) {
    }

    /** The order's lines the shop found it doesn't have (by line number): they are refunded. */
    public record MissingLines(@NotNull @Size(min = 1, max = 100) List<@NotNull @Min(1) @Max(100) Integer> lineNos) {

        public MissingLines {
            lineNos = lineNos == null ? null : List.copyOf(lineNos);
        }
    }

    /** The customer's 4-digit code, given to the shop at the door. */
    public record DeliveryCode(@NotBlank(message = "Enter the customer's 4-digit code")
                               @Pattern(regexp = "\\s*[0-9]{4}\\s*", message = "The code has 4 digits") String otp) {
    }

    // ---------- the admin website ----------

    /** A super admin's correction of a catalogue item; leave a field out to keep it, "" to clear brand, size, category. */
    public record CatalogueEdit(@NotNull Long version,
                                @Size(min = 2, max = 100, message = "Use 2 to 100 letters") String name,
                                @Size(max = 60) String brand,
                                @Size(max = 30) String size,
                                @Min(value = 1, message = "The MRP must be more than ₹0")
                                @Max(value = 10_000_000, message = "The MRP can be at most ₹1,00,000") Long mrpPaise,
                                @Size(max = 40) String category) {
    }

    /**
     * A super admin's decision on a shop's FSSAI certificate (when the AI can't read it, or the owner asks for a second
     * look).
     * @param reason     shown to the owner when refused
     * @param validUntil as printed, when verified
     * @param endCurrent with a refusal during a renewal: also ends the licence verified before (cancelled, not
     *                   genuine), so the shop stops taking orders now instead of on its last day
     */
    public record FssaiDecision(@NotNull Boolean verified, @Size(max = 300) String reason, LocalDate validUntil,
                                Boolean endCurrent) {
    }
}
