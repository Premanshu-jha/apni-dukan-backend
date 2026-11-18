package in.apnidukan.kirana.api;

import in.apnidukan.kirana.domain.Barcode;
import in.apnidukan.kirana.domain.KhataCustomer;
import in.apnidukan.kirana.domain.Product;
import in.apnidukan.kirana.domain.Quantities;
import in.apnidukan.kirana.domain.Unit;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What the kirana counter endpoints return. Money in paise; quantities in pieces or kg (numbers, e.g. 2 or 0.25);
 * times as instants (the app shows them in India time). Lists come a page at a time: {@code next} is the token for the
 * following page, null on the last.
 */
public final class KiranaResponses {

    private KiranaResponses() {
    }

    /**
     * A product.
     * @param barcodes   its barcodes in the order linked, each with how many one scan means
     * @param runningOut at or below its alert, or below zero (the count is behind the sales)
     * @param costPaise  what was paid per piece (or kg) on the last distributor's bill: the owner only (null for a
     *                   helper, and in lists)
     * @param photoFileId the shop's own photo, else the shared catalogue's; null: show the category's icon
     * @param photoUrl    a link that opens it for at least 15 minutes (a shared photo is another shop's file)
     */
    public record ProductView(UUID id, String name, List<BarcodeView> barcodes, Unit unit, long pricePaise,
                              BigDecimal stock, BigDecimal lowStockAt, boolean runningOut, boolean active,
                              long version, Instant updatedAt, Long costPaise, UUID photoFileId, String photoUrl) {

        public ProductView {
            barcodes = List.copyOf(barcodes);
        }

        public static ProductView of(Product p, List<Barcode> barcodes) {
            return new ProductView(p.id(), p.name(), barcodes.stream().map(BarcodeView::of).toList(), p.unit(),
                    p.pricePaise(), Quantities.json(p.stock()), Quantities.json(p.lowStockAt()), p.runningOut(),
                    p.active(), p.version(), p.updatedAt(), null, null, null);
        }

        /** With the cost price (shown to the owner only: null for a helper) and the photo and its link. */
        public ProductView with(Long cost, UUID photo, String photoLink) {
            return new ProductView(id, name, barcodes, unit, pricePaise, stock, lowStockAt, runningOut, active, version,
                    updatedAt, cost, photo, photoLink);
        }
    }

    /** @param pack how many pieces (or kg) one scan of it means (12 for a carton of 12) */
    public record BarcodeView(String code, BigDecimal pack) {

        static BarcodeView of(Barcode b) {
            return new BarcodeView(b.code(), Quantities.json(b.pack()));
        }
    }

    /** A scan at the counter: the product, and how many of it the scanned pack holds (add that many to the bill). */
    public record ScanView(ProductView product, BigDecimal pack) {
    }

    public record ProductPage(List<ProductView> items, String next) {

        public ProductPage {
            items = List.copyOf(items);
        }
    }

    /**
     * A change to a product's stock.
     * @param reason SALE, VOID (a cancelled sale), RETURN (items brought back), RESTOCK, DAMAGED, COUNT, ORDER (an
     *               online order accepted) or ORDER_BACK (its items given back: missing, or the order cancelled)
     * @param billNo the bill's number for SALE, VOID and RETURN, else null
     * @param byName who made it; null if their account is gone, or for an online order's change made by no one at
     *               the shop (a part timed out, the customer cancelled)
     */
    public record MoveView(BigDecimal change, BigDecimal stockAfter, String reason, UUID billId, Long billNo,
                           String note, String byName, Instant at) {
    }

    /** A product with its latest stock changes (newest first, up to 50). */
    public record ProductDetail(ProductView product, List<MoveView> moves) {

        public ProductDetail {
            moves = List.copyOf(moves);
        }
    }

    /**
     * One line of a bill as sold (name and price copied at the time).
     * @param lineNo           1, 2, …: what a return names
     * @param productId        null for an item not in the list (1 piece at its amount)
     * @param pricePaise       per piece or per kg
     * @param amountPaise      what the line costs (for a loose item sold by amount: the amount asked for)
     * @param returnedQuantity how much of it came back so far
     */
    public record BillLineView(int lineNo, UUID productId, String name, Unit unit, BigDecimal quantity,
                               long pricePaise, long amountPaise, BigDecimal returnedQuantity) {
    }

    /**
     * A walk-in bill.
     * @param subtotalPaise    the lines together
     * @param discountPaise    taken off the whole bill (discount or round-off)
     * @param totalPaise       what the customer pays: subtotal - discount
     * @param payment          CASH, UPI or KHATA
     * @param khataCustomer    for KHATA: who it was put on; else null
     * @param paidNowPaise     for a part-paid KHATA bill: paid at the counter (paidNowBy: CASH or UPI); else null
     * @param onKhataPaise     for KHATA: what went on the khata (total - paid now); else 0
     * @param refundedPaise    given back for returns so far
     * @param voidedAt         set when it was cancelled (then voidReason too)
     * @param returns          items brought back, oldest first
     * @param changes          payment changes after saving, oldest first
     * @param writtenOn        made from a paper bill's photo: the day written on the paper; else null
     * @param paperPhotoFileId that paper bill's photo (GET /files/{id}/link opens it); else null
     */
    public record BillView(UUID id, long billNo, long subtotalPaise, long discountPaise, long totalPaise,
                           String payment, KhataRef khataCustomer, Long paidNowPaise, String paidNowBy,
                           long onKhataPaise, long refundedPaise, long version, Instant createdAt, String byName,
                           Instant voidedAt, String voidReason, List<BillLineView> lines, List<ReturnView> returns,
                           List<ChangeView> changes, java.time.LocalDate writtenOn, UUID paperPhotoFileId) {

        public BillView {
            lines = List.copyOf(lines);
            returns = List.copyOf(returns);
            changes = List.copyOf(changes);
        }
    }

    /**
     * Items of a bill brought back: the refund, how much of it came off the khata and how much to hand back.
     * @param offKhataPaise    taken off what the customer owes (a khata bill, up to what it still has on the khata)
     * @param handBackPaise    to hand back now, in handBackBy (CASH, or UPI to the customer); null when nothing
     */
    public record ReturnView(UUID id, long refundPaise, long offKhataPaise, long handBackPaise, String handBackBy,
                             String reason, String byName, Instant at, List<ReturnLineView> lines) {

        public ReturnView {
            lines = List.copyOf(lines);
        }
    }

    /** @param valuePaise that line's amount for the quantity brought back (before the bill's discount) */
    public record ReturnLineView(int lineNo, BigDecimal quantity, long valuePaise) {
    }

    /** A bill's payment changed after saving: from what to what, by whom. */
    public record ChangeView(String fromPayment, KhataRef fromKhataCustomer, Long fromPaidNowPaise,
                             String fromPaidNowBy, String toPayment, KhataRef toKhataCustomer, Long toPaidNowPaise,
                             String toPaidNowBy, String byName, Instant at) {
    }

    /** A khata customer as a bill names them. */
    public record KhataRef(UUID id, String name) {
    }

    /** A product's stock after a bill, for the "stock updated" screen. */
    public record StockLevel(UUID productId, String name, Unit unit, BigDecimal before, BigDecimal after,
                             boolean runningOut) {
    }

    /**
     * The answer to saving a bill.
     * @param repeated true when this clientRef was saved before: this is that first bill, nothing new was billed
     * @param stock    each product's stock just before and after this bill (also when repeated: as the bill left it)
     * @param khata    for a KHATA bill: the customer now (balance and what is still available); else null
     * @param upi      for a UPI bill: the QR to show for its total; for a khata bill part paid by UPI: for that part
     *                 (null otherwise, or if the shop's UPI ID isn't set)
     * @param upiIdMissing true when the bill needs the QR but the shop's UPI ID isn't set yet (the owner sets it)
     */
    public record BillResult(BillView bill, List<StockLevel> stock, KhataCustomerView khata, UpiQr upi,
                             boolean upiIdMissing, boolean repeated) {

        public BillResult {
            stock = List.copyOf(stock);
        }
    }

    /**
     * What the counter's UPI QR encodes: the shop's own UPI ID and the bill's total, so the customer's UPI app opens
     * with the amount filled in and the money goes straight to the shop.
     * @param uri ready to draw as a QR: upi://pay?pa=…&pn=…&am=…&cu=INR&tn=Bill%20…
     */
    public record UpiQr(String upiId, String payeeName, long amountPaise, String uri) {
    }

    /**
     * The answer to cancelling a bill.
     * @param stock each product's stock just before and after the cancellation
     * @param khata for a KHATA bill: the customer now; else null
     */
    public record CancelResult(BillView bill, List<StockLevel> stock, KhataCustomerView khata) {

        public CancelResult {
            stock = List.copyOf(stock);
        }
    }

    /**
     * The answer to changing a bill's payment.
     * @param khata the khata customers it touched, as they are now (the one it left and the one it went to)
     * @param upi   as on a new bill, for the new payment (upiIdMissing as there)
     */
    public record ChangeResult(BillView bill, List<KhataCustomerView> khata, UpiQr upi, boolean upiIdMissing) {

        public ChangeResult {
            khata = List.copyOf(khata);
        }
    }

    /**
     * The answer to a return.
     * @param stock    each listed product's stock just before and after the return
     * @param khata    for a KHATA bill: the customer now; else null
     * @param repeated true when this clientRef was returned before: that first return, nothing new was made
     */
    public record ReturnResult(BillView bill, ReturnView returned, List<StockLevel> stock, KhataCustomerView khata,
                               boolean repeated) {

        public ReturnResult {
            stock = List.copyOf(stock);
        }
    }

    /**
     * A bill in the list: without its lines, with how many items and the first few names.
     * @param paidNowPaise     the part of a khata bill paid at once; else null
     * @param paymentChanged   its payment was changed after saving
     */
    public record BillSummary(UUID id, long billNo, long totalPaise, long discountPaise, String payment,
                              String khataCustomerName, Long paidNowPaise, int lines, String preview, Instant createdAt,
                              boolean voided, long refundedPaise, boolean paymentChanged) {
    }

    public record BillPage(List<BillSummary> items, String next) {

        public BillPage {
            items = List.copyOf(items);
        }
    }

    /**
     * One India day at the counter. Everything counts on the day it happened, so a day's figures never change after
     * the drawer was counted: bills on the day they were made (as they were paid then, cancelled since or not); a
     * payment change, a cancellation and a return on the day they were made, whatever the bill's day.
     * @param bills                   bills made that day
     * @param salesPaise              their totals, after discounts
     * @param discountPaise           what was taken off them
     * @param cashPaise               paid in cash at the counter (cash bills, and the part paid now of khata bills)
     * @param upiPaise                paid to the shop's UPI QR (likewise)
     * @param khataPaise              put on khata (what was not paid now)
     * @param paymentChanges          payment changes made that day (on bills of that day or before)
     * @param changedCashPaise        what they moved into cash (below 0: out of it); likewise UPI and khata
     * @param khataReceivedPaise      khata payments received that day, less those undone and advances given back
     * @param khataReceivedCashPaise  of which in cash
     * @param khataReceivedUpiPaise   of which by UPI
     * @param returns                 returns made that day (on bills of that day or before)
     * @param returnedPaise           refunded for them: handed back in cash, by UPI, or taken off the khata
     * @param cancelledBills          bills cancelled that day (made that day or before)
     * @param cancelledPaise          their total: their cash and UPI handed back, their khata part taken off
     * @param cashInPaise             the cash the drawer should have gained that day: cash sales, cash moved in by payment
     *                                changes and khata cash received, less cash handed back for returns and cancellations
     * @param upiInPaise              likewise for the shop's UPI
     */
    public record DaySummary(LocalDate date, int bills, long salesPaise, long discountPaise, long cashPaise,
                             long upiPaise, long khataPaise, int paymentChanges, long changedCashPaise,
                             long changedUpiPaise, long changedKhataPaise, long khataReceivedPaise,
                             long khataReceivedCashPaise, long khataReceivedUpiPaise, int returns, long returnedPaise,
                             long returnedCashPaise, long returnedUpiPaise, long returnedKhataPaise,
                             int cancelledBills, long cancelledPaise, long cancelledCashPaise, long cancelledUpiPaise,
                             long cancelledKhataPaise, long cashInPaise, long upiInPaise) {
    }

    /**
     * A customer in the khata book.
     * @param balancePaise    what they owe now (below 0: their advance, used up by their next khata sale)
     * @param owesPaise       what they owe now, 0 when they owe nothing
     * @param advancePaise    the advance the shop holds of theirs, 0 when none
     * @param availablePaise  what more they may buy on khata under their limit (their advance counts); null without a
     *                        limit
     */
    public record KhataCustomerView(UUID id, String name, String phone, String note, Long creditLimitPaise,
                                    long balancePaise, long owesPaise, long advancePaise, Long availablePaise,
                                    boolean active, long version, Instant updatedAt) {

        public static KhataCustomerView of(KhataCustomer c) {
            return new KhataCustomerView(c.id(), c.name(), c.phone(), c.note(), c.creditLimitPaise(),
                    c.balancePaise(), Math.max(0, c.balancePaise()), Math.max(0, -c.balancePaise()),
                    c.creditLimitPaise() == null ? null : Math.max(0, c.creditLimitPaise() - c.balancePaise()),
                    c.active(), c.version(), c.updatedAt());
        }
    }

    /**
     * @param owedPaise     what all the shop's khata customers owe together
     * @param advancesPaise what the shop holds as its customers' advances together
     */
    public record KhataPage(List<KhataCustomerView> items, String next, long owedPaise, long advancesPaise) {

        public KhataPage {
            items = List.copyOf(items);
        }
    }

    /**
     * An entry in a customer's khata.
     * @param kind         SALE (+), PAYMENT (-), VOID (a cancelled sale, -), RETURN (items brought back, -), CHANGE (a
     *                     bill moved on or off the khata, + or -), REVERSAL (a payment undone, +) or REFUND (an
     *                     advance given back, +)
     * @param method       CASH or UPI for a payment, its reversal and an advance given back, else null
     * @param reversesId   for a REVERSAL: the payment it undoes
     * @param reversed     for a PAYMENT: whether the owner undid it since
     */
    public record KhataEntryView(UUID id, String kind, long amountPaise, long balanceAfter, UUID billId, Long billNo,
                                 String method, String note, UUID reversesId, boolean reversed, String byName,
                                 Instant at) {
    }

    /** A khata customer with their entries, newest first (50 at a time; nextEntries for older ones). */
    public record KhataDetail(KhataCustomerView customer, List<KhataEntryView> entries, String nextEntries) {

        public KhataDetail {
            entries = List.copyOf(entries);
        }
    }

    /**
     * The answer to recording a khata payment.
     * @param repeated true when this clientRef was recorded before: nothing new was recorded
     */
    public record PaymentResult(KhataCustomerView customer, KhataEntryView entry, boolean repeated) {
    }

    /**
     * The shop's counter settings.
     * @param shopName  what the UPI QR shows as the payee
     * @param upiId     the shop's UPI ID, null until the owner sets it (needed before Submit for visit)
     */
    public record Settings(String shopName, String upiId, Instant upiUpdatedAt) {
    }
}
