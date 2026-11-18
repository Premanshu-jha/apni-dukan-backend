package in.apnidukan.kirana.api;

import in.apnidukan.kirana.api.KiranaRequests.Line;
import in.apnidukan.kirana.api.KiranaRequests.PaidBy;
import in.apnidukan.kirana.api.KiranaRequests.Payment;
import in.apnidukan.kirana.domain.Unit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request bodies of the kirana stock endpoints (round 3). Unknown fields are refused (400). */
public final class StockRequests {

    private StockRequests() {
    }

    /**
     * What a photo is: PURCHASE a distributor's bill (1 to 3 pages); PAPER_BILL a counter bill written on paper;
     * STOCK_LIST one page of the shop's own list of products (a register, a notebook, a price list).
     */
    public enum ReadKind { PURCHASE, PAPER_BILL, STOCK_LIST }

    /**
     * A photo to read. Upload the files first (POST /files?purpose=BILL), then send their ids.
     * @param clientRef made by the app once per photo: sending it again returns the first upload
     * @param fileIds   1 to 3 pages, in order (one for STOCK_LIST)
     */
    public record NewRead(@NotNull UUID clientRef,
                          @NotNull(message = "Choose what the photo is") ReadKind kind,
                          @NotNull(message = "Send the photo") @Size(min = 1, max = 3, message = "Send 1 to 3 pages")
                          List<@NotNull UUID> fileIds) {

        public NewRead {
            fileIds = fileIds == null ? null : List.copyOf(fileIds);
        }
    }

    /**
     * The shop's decision on a bill's line. Leave a field out to keep it. To add a product the shop doesn't have yet (a
     * catalogue suggestion, or by name), send {@code newProduct} instead of productId: it is made and picked in one go.
     * @param version   the bill's version you last read
     * @param quantity  pieces (or kg) that came, 0 to 99,999
     * @param ratePaise what was paid per piece (or kg): the owner only
     * @param skip      true: not added to stock
     */
    public record LineChange(@NotNull Long version, UUID productId, @Valid NewForLine newProduct, BigDecimal quantity,
                             @Min(value = 0, message = "The rate can't be below ₹0")
                             @Max(value = 10_000_000, message = "The rate can be at most ₹1,00,000") Long ratePaise,
                             Boolean skip) {
    }

    /** Several lines changed at once (accepting suggestions down the bill), with one version. */
    public record LinesChange(@NotNull Long version,
                              @NotNull @Size(min = 1, max = 200, message = "Send 1 to 200 lines")
                              List<@NotNull @Valid LineEdit> lines) {

        public LinesChange {
            lines = lines == null ? null : List.copyOf(lines);
        }
    }

    /** One line in {@link LinesChange}: as {@link LineChange}, with its number. */
    public record LineEdit(@NotNull @Min(1) @Max(200) Integer lineNo, UUID productId, @Valid NewForLine newProduct,
                           BigDecimal quantity,
                           @Min(value = 0, message = "The rate can't be below ₹0")
                           @Max(value = 10_000_000, message = "The rate can be at most ₹1,00,000") Long ratePaise,
                           Boolean skip) {
    }

    /** A line the reading missed: as written (optional), how many (1 if left out), its product or a new one. */
    public record NewLine(@NotNull Long version, @Size(max = 150) String wording, BigDecimal quantity, UUID productId,
                          @Valid NewForLine newProduct,
                          @Min(value = 0, message = "The rate can't be below ₹0")
                          @Max(value = 10_000_000, message = "The rate can be at most ₹1,00,000") Long ratePaise) {
    }

    /** The bill's distributor, number and date corrected; leave a field out to keep it, "" to clear it. */
    public record PurchaseHeader(@NotNull Long version, @Size(max = 100) String distributor,
                                 @Size(max = 40) String billNo, LocalDate billDate) {
    }

    /** A line of a bill already added was the wrong product: the right one (or a new one). */
    public record CorrectLine(UUID productId, @Valid NewForLine newProduct) {
    }

    /**
     * A product made from a bill's line: from a catalogue item ({@code itemId}: its name, unit and MRP unless given)
     * and/or the name, unit and price.
     */
    public record NewForLine(UUID itemId,
                             @Size(min = 2, max = 100, message = "Use 2 to 100 letters") String name,
                             Unit unit,
                             @Min(value = 1, message = "The price must be more than ₹0")
                             @Max(value = 10_000_000, message = "The price can be at most ₹1,00,000") Long pricePaise) {
    }

    /** @param addAgain true: add a bill already added once (another delivery with the same number) */
    public record AddPurchase(@NotNull Long version, Boolean addAgain) {
    }

    /**
     * Scans made while shelving, sent together (the app collects them a few seconds, or until it is back online).
     * @param clientRef  made by the app once per batch: sending it again counts nothing twice
     * @param purchaseId the distributor's bill being shelved, still a draft: its products' scans tick its lines
     *                   instead of adding stock (the bill adds it)
     * @param scans      1 to 200
     */
    public record ScanBatch(@NotNull UUID clientRef, UUID purchaseId,
                            @NotNull(message = "Scan something first")
                            @Size(min = 1, max = 200, message = "Send 1 to 200 scans at a time")
                            List<@NotNull @Valid Scan> scans) {

        public ScanBatch {
            scans = scans == null ? null : List.copyOf(scans);
        }
    }

    /** @param times how many times it was scanned in a row (1 if left out, at most 999) */
    public record Scan(@NotBlank(message = "Scan the barcode") @Size(max = 32) String code,
                       @Min(value = 1, message = "At least once") @Max(value = 999, message = "At most 999") Integer times) {
    }

    /**
     * A photo of the front of an unknown pack (uploaded first: POST /files?purpose=PRODUCT_PHOTO).
     * @param clientRef made by the app once per photo
     */
    public record LabelPhoto(@NotNull UUID clientRef, @NotNull(message = "Send the photo") UUID fileId) {
    }

    /**
     * Links an unknown barcode: to one of the shop's products ({@code productId}), or to a new product (from a
     * catalogue item, {@code itemId}, and/or the name, unit and price given).
     * @param pack   how many pieces (or kg) one scan of it means: 1 if left out
     * @param oneTap the app's one-tap suggestion was taken (counted in the admin's scanning numbers only)
     */
    public record LinkUnknown(UUID productId, UUID itemId, Boolean oneTap,
                              @Size(min = 2, max = 100, message = "Use 2 to 100 letters") String name,
                              Unit unit,
                              @Min(value = 1, message = "The price must be more than ₹0")
                              @Max(value = 10_000_000, message = "The price can be at most ₹1,00,000") Long pricePaise,
                              BigDecimal pack) {
    }

    /**
     * One of this product's barcodes belongs to another product (the owner corrects a wrong link).
     * @param moveStock true: the stock scanned in with it moves too, in the same go
     */
    public record MoveBarcode(@NotBlank(message = "Choose the barcode") @Size(max = 32) String code,
                              @NotNull(message = "Choose the product it belongs to") UUID toProductId,
                              Boolean moveStock) {
    }

    /**
     * Stock that was counted on the wrong product (owner).
     * @param clientRef made by the app once per move: sending it again moves nothing twice
     */
    public record MoveStock(@NotNull UUID clientRef,
                            @NotNull(message = "Choose the product it belongs to") UUID toProductId,
                            @NotNull(message = "Enter how many") BigDecimal quantity) {
    }

    /** The product's photo (uploaded first: POST /files?purpose=PRODUCT_PHOTO); null fileId removes the shop's own. */
    public record ProductPhoto(@NotNull UUID clientRef, UUID fileId) {
    }

    /** The owner's cost price (what is paid per piece or kg); null clears it. */
    public record ProductCost(@Min(value = 1, message = "The cost must be more than ₹0")
                              @Max(value = 10_000_000, message = "The cost can be at most ₹1,00,000") Long costPaise) {
    }

    /**
     * A paper bill confirmed as a bill: the lines as checked (the same as a new bill's), how it was paid, and the day
     * written on the paper.
     * @param writtenOn today or up to 7 days back
     */
    public record ConfirmPaper(@NotNull(message = "Enter the day on the paper") LocalDate writtenOn,
                               @NotNull(message = "Add at least one item")
                               @Size(min = 1, max = 100, message = "A bill has 1 to 100 items")
                               List<@NotNull @Valid Line> lines,
                               @NotNull(message = "Choose how they paid") Payment payment,
                               UUID khataCustomerId,
                               @Min(value = 0, message = "The discount can't be below ₹0")
                               @Max(value = 100_000_000, message = "The discount can be at most ₹10,00,000")
                               Long discountPaise,
                               @Min(value = 1, message = "Enter what they paid now, more than ₹0")
                               @Max(value = 100_000_000, message = "The amount can be at most ₹10,00,000")
                               Long paidNowPaise,
                               PaidBy paidNowBy) {

        public ConfirmPaper {
            lines = lines == null ? null : List.copyOf(lines);
        }
    }

    /** The team's decision: which item the barcode is. */
    public record ReviewDecision(@NotNull(message = "Choose an item") UUID itemId) {
    }

    // ---------- product setup ----------

    /** What to do with a line of the shop's list: add it (with its count), skip it, or bring a skipped one back. */
    public enum LineAction { ADD, SKIP, UNDO }

    /**
     * The shop's decision on a line of its list.
     * @param productId ADD: it is this product the shop already has (its stock becomes the count)
     * @param itemId    ADD: a new product from this shared catalogue item (name, unit and MRP as its own unless sent)
     * @param name      ADD: a new product's name (the line's, as corrected)
     * @param unit      ADD: a new product's unit, PIECE or KG
     * @param pricePaise ADD: a new product's selling price per piece (or kg)
     * @param quantity  ADD: how many (or how much) is on the shelf, counted now (0 or more)
     * @param addToCount ADD onto a product another line of the list was already added to (409 ALREADY_COUNTED until
     *                   said): true adds this count to it (the same product kept in two places), false replaces it
     */
    public record LineDecision(@NotNull(message = "Choose what to do") LineAction action, UUID productId, UUID itemId,
                               @Size(min = 2, max = 100, message = "Use 2 to 100 letters") String name, Unit unit,
                               @Min(value = 1, message = "The price must be more than ₹0")
                               @Max(value = 10_000_000, message = "The price can be at most ₹1,00,000")
                               Long pricePaise,
                               BigDecimal quantity, Boolean addToCount) {
    }
}
