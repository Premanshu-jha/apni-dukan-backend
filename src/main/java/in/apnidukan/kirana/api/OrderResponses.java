package in.apnidukan.kirana.api;

import in.apnidukan.kirana.domain.Unit;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What the kirana online-order endpoints return (round 2). Money in paise; quantities as plain numbers; times as
 * instants. Lists come a page at a time: {@code next} is the following page's token, null on the last.
 */
public final class OrderResponses {

    private OrderResponses() {
    }

    // ---------- catalogue ----------

    /**
     * A shared catalogue item.
     * @param pack  for a scan: how many of it the scanned barcode means; else null
     * @param codes its barcodes
     */
    public record CatalogueView(UUID id, String name, String brand, String size, Unit unit, Long mrpPaise,
                                String category, BigDecimal pack, List<String> codes, long version, Instant updatedAt) {

        public CatalogueView {
            codes = List.copyOf(codes);
        }
    }

    public record CataloguePage(List<CatalogueView> items, String next) {

        public CataloguePage {
            items = List.copyOf(items);
        }
    }

    // ---------- the shop's online settings ----------

    /**
     * @param status     NONE, CHECKING (being read), VERIFIED or REJECTED (reason says why, in the owner's words)
     * @param validUntil the last day it is valid
     * @param fileId     the certificate uploaded (GET /files/{id}/link shows it; sent again with a corrected number)
     * @param currentValidUntil while a renewed certificate is checked (or was refused): the last day of the licence
     *                   verified before, which keeps the shop taking orders until then; null otherwise
     */
    public record FssaiView(String number, UUID fileId, String status, String reason, LocalDate validUntil,
                            LocalDate currentValidUntil, Instant checkedAt) {
    }

    /**
     * A shop's online orders.
     * @param ready      customers can order now
     * @param missing    what stops it, if anything: APPROVAL (the field team's), UPI_ID, FSSAI, SWITCHED_OFF
     * @param upiRecheck its UPI ID changed after approval: payouts wait for the field team's check
     * @param duePaise   earned and due to be paid now; laterPaise: earned, due in the next working days
     */
    public record OnlineView(boolean takingOrders, boolean ready, List<String> missing, FssaiView fssai,
                             boolean upiRecheck, Double rating, int ratingCount, long duePaise, long laterPaise,
                             List<PayoutView> payouts) {

        public OnlineView {
            missing = List.copyOf(missing);
            payouts = List.copyOf(payouts);
        }
    }

    /**
     * What one delivered order earned the shop.
     * @param dueOn        when it is paid out (2 working days after delivery)
     * @param payoutStatus null until paid out; then SENDING, SENT, PROCESSED, FAILED or REVERSED (with the UPI ID it
     *                     went to, and why it failed)
     */
    public record EarningView(long orderNo, long amountPaise, Instant deliveredAt, LocalDate dueOn, UUID payoutId,
                              String payoutStatus, String payoutUpiId, String payoutFailure) {
    }

    public record EarningPage(List<EarningView> items, String next) {

        public EarningPage {
            items = List.copyOf(items);
        }
    }

    /** @param status SENDING, SENT, PROCESSED (in the shop's account), FAILED or REVERSED */
    public record PayoutView(UUID id, long amountPaise, String upiId, String status, String failure,
                             Instant createdAt) {
    }

    // ---------- the customer: shops, products, quote ----------

    /** @param rating the average of customers' ratings, null before any */
    public record ShopCard(UUID id, String name, String area, String photoUrl, long distanceMetres, Double rating,
                           int ratingCount) {
    }

    public record ShopPage(List<ShopCard> items, String next) {

        public ShopPage {
            items = List.copyOf(items);
        }
    }

    /** One of the shop's products. @param available it has some in stock */
    public record ListedProduct(UUID id, String name, Unit unit, long pricePaise, boolean available,
                                UUID catalogueItemId) {
    }

    public record ListedPage(List<ListedProduct> items, String next) {

        public ListedPage {
            items = List.copyOf(items);
        }
    }

    /** An item the shop doesn't have that shops near it do, from the lowest price (added by its catalogueItemId). */
    public record NearbyItem(UUID catalogueItemId, String name, String brand, String size, Unit unit,
                             long fromPricePaise) {
    }

    public record NearbyPage(List<NearbyItem> items, String next) {

        public NearbyPage {
            items = List.copyOf(items);
        }
    }

    /**
     * One line of a quote or an order.
     * @param fromNearbyShop the item comes from another shop (in a combined order it is handed to the customer's shop)
     */
    public record ItemLine(int lineNo, UUID productId, UUID catalogueItemId, String name, Unit unit,
                           BigDecimal quantity, long pricePaise, long amountPaise, boolean fromNearbyShop,
                           boolean missing) {
    }

    /**
     * One delivery of a plan.
     * @param shopName who delivers it (in a separate order, the other shop)
     */
    public record PlanOrder(UUID shopId, String shopName, List<ItemLine> lines, long itemsPaise) {

        public PlanOrder {
            lines = List.copyOf(lines);
        }
    }

    /** A way to split the cart: its deliveries and its total (items + the ₹10 charge once). */
    public record PlanView(List<PlanOrder> orders, long itemsPaise, long platformPaise, long totalPaise) {

        public PlanView {
            orders = List.copyOf(orders);
        }
    }

    /** What no shop near can give (the line's position in the cart, and how much of it). */
    public record Unavailable(int line, String name, BigDecimal quantity) {
    }

    /**
     * What the cart would cost.
     * @param combined the plan with every item from (or through) the chosen shop: one delivery
     * @param separate when the shop lacks whole items: the plan where another shop delivers them itself; else null
     * @param choose   the customer chooses between combined and separate (the shop lacks whole items)
     * @param unavailable         what the combined plan can't give (no shop near has it, or not enough)
     * @param separateUnavailable likewise for the separate plan
     */
    public record QuoteView(UUID shopId, String shopName, PlanView combined, PlanView separate, boolean choose,
                            List<Unavailable> unavailable, List<Unavailable> separateUnavailable) {

        public QuoteView {
            unavailable = List.copyOf(unavailable);
            separateUnavailable = List.copyOf(separateUnavailable);
        }
    }

    // ---------- the customer: checkout and orders ----------

    /**
     * What the customer app opens Razorpay's checkout with.
     * @param status  PENDING (to be paid), PAID or EXPIRED
     * @param refunds every refund of this checkout (also a payment made after it expired, or paid twice)
     */
    public record PayView(UUID checkoutId, String status, String razorpayOrderId, String keyId, long amountPaise,
                          String currency, String method, List<OrderSummary> orders, List<RefundView> refunds) {

        public PayView {
            orders = List.copyOf(orders);
            refunds = List.copyOf(refunds);
        }
    }

    public record OrderSummary(UUID id, long orderNo, String shopName, String status, long totalPaise, int items,
                               Instant createdAt) {
    }

    public record OrderPage(List<OrderSummary> items, String next) {

        public OrderPage {
            items = List.copyOf(items);
        }
    }

    /**
     * @param reason REJECTED, TIMED_OUT, MISSING, CANCELLED, NOT_PAID_IN_TIME or PAID_TWICE
     * @param status PENDING (on its way), PROCESSED (back in the account; banks take 5–7 working days) or FAILED
     */
    public record RefundView(long amountPaise, String reason, String status, Instant createdAt) {
    }

    /**
     * An order as the customer sees it.
     * @param status    PLACED (waiting for the shop), ACCEPTED, PACKED, OUT_FOR_DELIVERY, DELIVERED or CANCELLED
     * @param otp       the 4-digit code to give at the door (until delivered or cancelled)
     * @param canCancel the shop hasn't accepted it yet
     * @param canRate   delivered, not rated yet, within 7 days
     * @param shopPhone to call the shop about the order (while it is open)
     * @param acceptBy  while the shop hasn't accepted: by when it must
     * @param canChangeAddress not sent out yet and fewer than 3 corrections made
     */
    public record OrderView(UUID id, long orderNo, UUID checkoutId, String status, UUID shopId, String shopName,
                            String shopPhone, String addressLine, String otp, List<ItemLine> lines, long itemsPaise,
                            long platformPaise, long totalPaise, List<RefundView> refunds, Instant acceptBy,
                            Instant placedAt, Instant acceptedAt, Instant packedAt, Instant outAt,
                            Instant deliveredAt, Instant cancelledAt, String cancelReason, boolean canCancel,
                            boolean canRate, Integer rating, String ratingComment, boolean canChangeAddress) {

        public OrderView {
            lines = List.copyOf(lines);
            refunds = List.copyOf(refunds);
        }
    }

    // ---------- the shop's order screens ----------

    /** Who to deliver to: the customer (the delivering shop only). */
    public record Customer(String name, String phone, String addressLine, BigDecimal latitude, BigDecimal longitude) {
    }

    /** Another shop's part of a combined order, as the delivering shop sees it (its phone, to chase it). */
    public record OtherPart(UUID partId, String shopName, String shopPhone, String status, List<ItemLine> lines) {

        public OtherPart {
            lines = List.copyOf(lines);
        }
    }

    /**
     * An order part as the shop sees it.
     * @param role         OWN (this shop delivers) or SUPPLIER (hand these items to deliverToShop)
     * @param status       WAITING, ACCEPTED, PACKED, HANDED, REJECTED, TIMED_OUT or CANCELLED
     * @param secondsLeft  to accept, while waiting
     * @param customer     for OWN, once accepted: who and where (after delivery the name and address only); null
     *                     before accepting, when it didn't take the order, and for a supplier (it never sees the customer)
     * @param deliverToShopPhone for a supplier: the delivering shop's phone
     * @param otherParts   for OWN: the items other shops send it
     * @param orderStatus  the whole order's status
     */
    public record ShopPartView(UUID partId, UUID orderId, long orderNo, String role, String status, Instant acceptBy,
                               long secondsLeft, Customer customer, String deliverToShop, String deliverToShopPhone,
                               List<ItemLine> lines,
                               long itemsPaise, List<OtherPart> otherParts, String orderStatus, Instant createdAt) {

        public ShopPartView {
            lines = List.copyOf(lines);
            otherParts = List.copyOf(otherParts);
        }
    }

    public record ShopPartPage(List<ShopPartView> items, String next) {

        public ShopPartPage {
            items = List.copyOf(items);
        }
    }

    // ---------- the admin website ----------

    public record UpiRecheckView(UUID shopId, String shopName, String upiId, Instant changedAt) {
    }

    /**
     * A shop's FSSAI certificate waiting for a person (the AI couldn't decide), or refused.
     * @param keptUntil during a renewal: the day the shop's earlier licence (and its orders) ends; null once past
     */
    public record FssaiCheckView(UUID shopId, String shopName, String number, UUID fileId, String status,
                                 String reason, int tries, Instant checkedAt, LocalDate keptUntil) {
    }

    /** A paid order still open long after it was placed (a shop didn't finish it): the team calls, or cancels it. */
    public record OpenOrderView(UUID id, long orderNo, UUID shopId, String shopName, String shopPhone, String status,
                                Instant placedAt) {
    }

    public record FailedRefundView(UUID id, UUID checkoutId, long amountPaise, String reason, String failure,
                                   Instant updatedAt) {
    }

    public record AdminPayoutView(UUID id, UUID shopId, long amountPaise, String upiId, String status, String failure,
                                  Instant updatedAt) {
    }
}
