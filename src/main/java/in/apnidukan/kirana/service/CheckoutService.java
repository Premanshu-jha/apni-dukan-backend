package in.apnidukan.kirana.service;

import in.apnidukan.common.config.KiranaSwitch;
import in.apnidukan.common.context.ShopDirectory;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.files.FileRefs;
import in.apnidukan.common.text.TextCleaner;
import in.apnidukan.common.text.TextCleaner.TextField;
import in.apnidukan.kirana.api.OrderRequests.PaymentDone;
import in.apnidukan.kirana.api.OrderRequests.PlaceOrder;
import in.apnidukan.kirana.api.OrderRequests.Plan;
import in.apnidukan.kirana.api.OrderRequests.Quote;
import in.apnidukan.kirana.api.OrderRequests.Rate;
import in.apnidukan.kirana.api.OrderResponses.ItemLine;
import in.apnidukan.kirana.api.OrderResponses.ListedPage;
import in.apnidukan.kirana.api.OrderResponses.ListedProduct;
import in.apnidukan.kirana.api.OrderResponses.NearbyItem;
import in.apnidukan.kirana.api.OrderResponses.NearbyPage;
import in.apnidukan.kirana.api.OrderResponses.OrderPage;
import in.apnidukan.kirana.api.OrderResponses.OrderSummary;
import in.apnidukan.kirana.api.OrderResponses.OrderView;
import in.apnidukan.kirana.api.OrderResponses.PayView;
import in.apnidukan.kirana.api.OrderResponses.QuoteView;
import in.apnidukan.kirana.api.OrderResponses.RefundView;
import in.apnidukan.kirana.api.OrderResponses.ShopCard;
import in.apnidukan.kirana.api.OrderResponses.ShopPage;
import in.apnidukan.kirana.domain.Cursors;
import in.apnidukan.kirana.domain.Quantities;
import in.apnidukan.kirana.repo.MoneyStore;
import in.apnidukan.kirana.repo.OnlineStore;
import in.apnidukan.kirana.repo.OrderStore;
import in.apnidukan.kirana.repo.OrderStore.Checkout;
import in.apnidukan.kirana.repo.OrderStore.Line;
import in.apnidukan.kirana.repo.OrderStore.Order;
import in.apnidukan.kirana.repo.OrderStore.Part;
import in.apnidukan.kirana.service.OrderPlanner.PlannedOrder;
import in.apnidukan.kirana.service.OrderPlanner.PlannedPart;
import in.apnidukan.kirana.service.OrderPlanner.Supply;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static in.apnidukan.kirana.service.KiranaChecks.PAGE;

/**
 * Kirana online orders, the customer's side (any signed-in person): the kirana shops within 2 km that take orders, a
 * shop's products and the items shops near it have, the quote, placing the order and paying it (Razorpay checkout: UPI
 * or Pay Later), their orders, cancelling one the shop hasn't accepted, and rating a delivered one. The payment is
 * confirmed by the checkout's signed answer or by Razorpay's webhook, whichever comes first; a checkout not paid within
 * 30 minutes expires (a payment after that is refunded in full).
 */
@Service
public class CheckoutService {

    private static final TextField ADDRESS = new TextField("addressLine", 3, 200);
    private static final TextField COMMENT = new TextField("comment", 2, 300);
    static final Duration PAY_WITHIN = Duration.ofMinutes(30);
    static final Duration RATE_WITHIN = Duration.ofDays(7);
    /** Address corrections per order (each one tells the shop). */
    static final int ADDRESS_CHANGES = 3;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OrderStore orders;
    private final OrderPlanner planner;
    private final OrderFlow flow;
    private final MoneyStore money;
    private final OnlineStore online;
    private final KiranaRazorpay razorpay;
    private final ShopDirectory shops;
    private final FileRefs files;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final KiranaSwitch kirana;

    public CheckoutService(OrderStore orders, OrderPlanner planner, OrderFlow flow, MoneyStore money,
                           OnlineStore online, KiranaRazorpay razorpay, ShopDirectory shops, FileRefs files,
                           TransactionTemplate transactions, Clock clock, KiranaSwitch kirana) {
        this.kirana = kirana;
        this.orders = orders;
        this.planner = planner;
        this.flow = flow;
        this.money = money;
        this.online = online;
        this.razorpay = razorpay;
        this.shops = shops;
        this.files = files;
        this.transactions = transactions;
        this.clock = clock;
    }

    // ---------- browsing ----------

    /** Kirana shops within 2 km of the customer that take orders now, nearest first, 50 a page. */
    @Transactional(readOnly = true)
    public ShopPage shops(BigDecimal lat, BigDecimal lng, String cursor) {
        requireLocation(lat, lng);
        if (kirana.off()) {
            return new ShopPage(List.of(), null);   // kirana switched off: no kirana is listed
        }
        Optional<String[]> after = Cursors.decode(cursor);
        Double afterMetres = after.flatMap(a -> number(a[0])).orElse(null);
        List<OrderStore.ShopListing> rows = orders.nearby(lat, lng, OrderPlanner.CUSTOMER_METRES,
                KiranaChecks.today(clock.instant()), afterMetres,
                afterMetres == null ? null : ProductService.uuid(after.get()[1]), PAGE);
        boolean more = rows.size() > PAGE;
        List<OrderStore.ShopListing> page = more ? rows.subList(0, PAGE) : rows;
        Map<UUID, String> photos = files.links(page.stream().map(OrderStore.ShopListing::photoFileId)
                .filter(java.util.Objects::nonNull).toList());
        OrderStore.ShopListing last = page.isEmpty() ? null : page.get(page.size() - 1);
        return new ShopPage(page.stream().map(s -> new ShopCard(s.id(), s.name(), s.area(),
                        s.photoFileId() == null ? null : photos.get(s.photoFileId()), Math.round(s.distanceMetres()),
                        s.ratingCount() == 0 ? null : Math.round(10.0 * s.ratingSum() / s.ratingCount()) / 10.0,
                        s.ratingCount())).toList(),
                more ? Cursors.encode(Double.toString(last.distanceMetres()), last.id().toString()) : null);
    }

    /** A shop's products in name order, 50 a page; q: names containing it. 409 SHOP_NOT_TAKING_ORDERS. */
    @Transactional(readOnly = true)
    public ListedPage products(UUID shopId, String q, String cursor) {
        if (kirana.off()) {
            return new ListedPage(List.of(), null);   // kirana switched off: nothing is listed
        }
        requireReady(shopId);
        Optional<String[]> after = Cursors.decode(cursor);
        List<OrderStore.Listed> rows = orders.listed(shopId, KiranaChecks.prefix(q), after.map(a -> a[0]).orElse(null),
                after.map(a -> ProductService.uuid(a[1])).orElse(null), PAGE);
        boolean more = rows.size() > PAGE;
        List<OrderStore.Listed> page = more ? rows.subList(0, PAGE) : rows;
        OrderStore.Listed last = page.isEmpty() ? null : page.get(page.size() - 1);
        return new ListedPage(page.stream().map(p -> new ListedProduct(p.id(), p.name(), p.unit(), p.pricePaise(),
                        p.stock().signum() > 0, p.catalogueItemId())).toList(),
                more ? Cursors.encode(last.sortKey(), last.id().toString()) : null);
    }

    /**
     * Items the shop doesn't list that shops within 1 km of it have, with the lowest price, in name order, 50 a page:
     * the customer adds them by their catalogueItemId and chooses at checkout how they come.
     */
    @Transactional(readOnly = true)
    public NearbyPage nearbyItems(UUID shopId, String q, String cursor) {
        if (kirana.off()) {
            return new NearbyPage(List.of(), null);   // kirana switched off: nothing is listed
        }
        BigDecimal[] at = requireReady(shopId);
        Optional<String[]> after = Cursors.decode(cursor);
        List<OrderStore.NetworkItem> rows = orders.networkItems(shopId, at[0], at[1], OrderPlanner.NETWORK_METRES,
                KiranaChecks.today(clock.instant()), KiranaChecks.prefix(q), after.map(a -> a[0]).orElse(null),
                after.map(a -> ProductService.uuid(a[1])).orElse(null), PAGE);
        boolean more = rows.size() > PAGE;
        List<OrderStore.NetworkItem> page = more ? rows.subList(0, PAGE) : rows;
        OrderStore.NetworkItem last = page.isEmpty() ? null : page.get(page.size() - 1);
        return new NearbyPage(page.stream().map(n -> new NearbyItem(n.catalogueItemId(), n.name(), n.brand(), n.size(),
                        n.unit(), n.pricePaise())).toList(),
                more ? Cursors.encode(last.sortKey(), last.catalogueItemId().toString()) : null);
    }

    /** What the cart would cost and who would give what; nothing is made. See OrderPlanner. */
    @Transactional(readOnly = true)
    public QuoteView quote(Quote in) {
        requireLocation(in.latitude(), in.longitude());
        return OrderPlanner.view(planner.quote(in.shopId(), in.latitude(), in.longitude(), in.lines()));
    }

    // ---------- checkout ----------

    /**
     * Places the order and opens its payment: works the plan out again, and the total must be the one the customer saw
     * (409 PRICE_CHANGED with the new quote in details.quote). 409 NOTHING_AVAILABLE when no item can be given; 400
     * naming plan when SEPARATE has nothing to split. Sending the same clientRef again returns the first checkout.
     * Razorpay's order is asked for outside the transaction (503 PAYMENTS_UNAVAILABLE: sending it again asks again).
     */
    public PayView place(UUID userId, PlaceOrder in) {
        requireLocation(in.latitude(), in.longitude());
        Optional<Checkout> earlier = orders.checkoutByClientRef(userId, in.clientRef());
        if (earlier.isPresent()) {
            return pay(withRazorpayOrder(earlier.get()));
        }
        String address = TextCleaner.clean(ADDRESS, in.addressLine());
        OrderPlanner.Quote q = planner.quote(in.shopId(), in.latitude(), in.longitude(), in.lines());
        OrderPlanner.Plan plan = in.plan() == Plan.SEPARATE ? q.separate() : q.combined();
        if (plan == null && in.plan() == Plan.SEPARATE && q.combined() != null) {
            throw KiranaChecks.invalid("plan", "Every item is at this shop: there is nothing to send separately");
        }
        if (plan == null) {
            throw new ApiException(HttpStatus.CONFLICT, "NOTHING_AVAILABLE",
                    "None of these items can be ordered now.", Map.of("quote", OrderPlanner.view(q)));
        }
        if (plan.totalPaise() != in.expectedTotalPaise()) {
            throw new ApiException(HttpStatus.CONFLICT, "PRICE_CHANGED", "Prices or stock changed: check the new total.",
                    Map.of("quote", OrderPlanner.view(q)));
        }
        Instant now = KiranaChecks.now(clock);
        Checkout c = new Checkout(UUID.randomUUID(), userId, in.clientRef(), plan.itemsPaise(), plan.platformPaise(),
                plan.totalPaise(), in.method().name(), "PENDING", null, null, null, 0, now, null);
        Boolean made = transactions.execute(s -> {
            if (!orders.insertCheckout(c)) {
                return false;
            }
            boolean first = true;
            for (PlannedOrder po : plan.orders()) {
                Order o = new Order(UUID.randomUUID(), 0, c.id(), po.shopId(), userId, address, in.latitude(),
                        in.longitude(), "WAITING_PAYMENT", null, first ? plan.platformPaise() : 0,
                        String.format("%04d", RANDOM.nextInt(10_000)), 0, null, null, null, null, null, null, null,
                        null, null, 0, now);
                first = false;
                orders.insertOrder(o);
                for (PlannedPart pp : po.parts()) {
                    UUID partId = UUID.randomUUID();
                    List<Line> lines = new ArrayList<>();
                    int no = 0;
                    for (Supply sp : pp.lines()) {
                        lines.add(new Line(partId, ++no, sp.productId(), sp.catalogueItemId(), sp.name(), sp.unit(),
                                sp.quantity(), sp.pricePaise(), sp.amountPaise(), false));
                    }
                    orders.insertPart(new Part(partId, o.id(), pp.shopId(), pp.role(), "WAITING_PAYMENT", null, null,
                            null, pp.itemsPaise(), 0, now), lines);
                }
            }
            return true;
        });
        Checkout saved = Boolean.TRUE.equals(made) ? c : orders.checkoutByClientRef(userId, in.clientRef()).orElseThrow();
        return pay(withRazorpayOrder(saved));
    }

    /**
     * The checkout's answer after paying: checked by its signature (400 BAD_SIGNATURE), then the orders go to their
     * shops. The webhook may have done it already; either way the answer is the checkout as it is.
     */
    public PayView paid(UUID userId, UUID checkoutId, PaymentDone in) {
        Checkout c = orders.checkout(checkoutId).filter(x -> x.customerId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Checkout"));
        if (c.razorpayOrderId() == null || !c.razorpayOrderId().equals(in.razorpayOrderId())
                || !razorpay.checkoutSigned(in.razorpayOrderId(), in.razorpayPaymentId(), in.razorpaySignature())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_SIGNATURE", "This payment couldn't be confirmed.");
        }
        transactions.executeWithoutResult(s -> markPaid(in.razorpayOrderId(), in.razorpayPaymentId(), null, 0,
                KiranaChecks.now(clock)));
        return pay(orders.checkout(checkoutId).orElseThrow());
    }

    /** The checkout as it is (the app polls it after paying until the webhook or its answer marks it paid). */
    @Transactional(readOnly = true)
    public PayView checkout(UUID userId, UUID checkoutId) {
        return pay(orders.checkout(checkoutId).filter(x -> x.customerId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Checkout")));
    }

    /**
     * Razorpay says a payment for this order was made (the checkout's answer, payment.captured, order.paid). Once per
     * checkout: a paid checkout's orders go to their shops; a payment after the checkout expired is refunded in full.
     * A second payment of the same checkout (paid again from another UPI app after the first seemed stuck) is refunded
     * in full, once. A shop that stopped taking orders between the checkout and the payment gets no order: its part is
     * dropped and refunded (the whole order when it was the delivering shop). The caller holds a transaction. Returns
     * what happened (PAID, OLD, LATE, PAID_TWICE, UNKNOWN).
     * @param amountPaise the payment's amount when Razorpay said it (0: the checkout's total)
     */
    String markPaid(String razorpayOrderId, String paymentId, String method, long amountPaise, Instant now) {
        Optional<Checkout> found = orders.lockCheckoutByRazorpay(razorpayOrderId);
        if (found.isEmpty()) {
            return "UNKNOWN";
        }
        Checkout c = found.get();
        boolean known = paymentId.equals(c.razorpayPaymentId());
        if ("PAID".equals(c.status()) || ("EXPIRED".equals(c.status()) && c.razorpayPaymentId() != null)) {
            if (!known) {
                if (!money.refundedPayment(c.id(), paymentId)) {
                    money.addRefund(UUID.randomUUID(), c.id(), null, paymentId,
                            amountPaise > 0 ? amountPaise : c.totalPaise(), "PAID_TWICE", now);
                }
                return "PAID_TWICE";
            }
            if (method != null && "PAID".equals(c.status())) {
                orders.setPaidMethod(c.id(), method);
            }
            return "PAID".equals(c.status()) ? "OLD" : "LATE";
        }
        if ("EXPIRED".equals(c.status())) {
            orders.markLatePayment(c.id(), paymentId, method);
            if (orders.addRefunded(c.id(), c.totalPaise())) {
                money.addRefund(UUID.randomUUID(), c.id(), null, c.totalPaise(), "NOT_PAID_IN_TIME", now);
            }
            return "LATE";
        }
        orders.markPaid(c.id(), paymentId, method, now);
        Checkout paid = orders.checkout(c.id()).orElseThrow();
        List<UUID> shopIds = orders.partsOf(orders.ordersOf(c.id()).stream().map(Order::id).toList()).values().stream()
                .flatMap(List::stream).map(Part::shopId).distinct().toList();
        flow.place(paid, orders.readyAmong(shopIds, KiranaChecks.today(now)), now);
        return "PAID";
    }

    /**
     * Checkouts not paid within 30 minutes expire: their orders are cancelled (nothing was taken from any shop's stock).
     * Run by OrderJobs.
     */
    void expire(UUID checkoutId, Instant now) {
        Checkout c = orders.lockCheckout(checkoutId).orElse(null);
        if (c == null || !"PENDING".equals(c.status())) {
            return;
        }
        orders.expire(c.id());
        Checkout expired = orders.checkout(c.id()).orElseThrow();
        for (Order o : orders.lockOrdersOf(c.id())) {
            flow.cancelOrder(expired, o, orders.lockPartsOf(o.id()), "Not paid in time", "CANCELLED", now);
        }
    }

    // ---------- the customer's orders ----------

    /** The customer's orders, newest first, 50 a page. */
    @Transactional(readOnly = true)
    public OrderPage myOrders(UUID userId, String cursor) {
        Optional<String[]> before = Cursors.decode(cursor);
        Instant beforeAt = before.flatMap(b -> instant(b[0])).orElse(null);
        List<Order> rows = orders.customerOrders(userId, beforeAt,
                beforeAt == null ? null : ProductService.uuid(before.get()[1]), PAGE);
        boolean more = rows.size() > PAGE;
        List<Order> page = more ? rows.subList(0, PAGE) : rows;
        Map<UUID, List<Part>> parts = orders.partsOf(page.stream().map(Order::id).toList());
        Map<UUID, ShopDirectory.ShopCard> cards = shops.findAll(page.stream().map(Order::shopId).distinct().toList());
        Map<UUID, List<Line>> lines = orders.lines(parts.values().stream().flatMap(List::stream).map(Part::id).toList());
        Order last = page.isEmpty() ? null : page.get(page.size() - 1);
        return new OrderPage(page.stream().map(o -> {
            List<Part> ps = parts.getOrDefault(o.id(), List.of());
            long items = ps.stream().filter(Part::live).mapToLong(Part::itemsPaise).sum();
            int count = (int) ps.stream().filter(Part::live).flatMap(p -> lines.getOrDefault(p.id(), List.of()).stream())
                    .filter(l -> !l.missing()).count();
            return new OrderSummary(o.id(), o.orderNo(), cards.containsKey(o.shopId()) ? cards.get(o.shopId()).name()
                    : "", o.status(), items + o.platformPaise(), count, o.createdAt());
        }).toList(), more ? Cursors.encode(last.createdAt().toString(), last.id().toString()) : null);
    }

    /** One of the customer's orders. 404 when it isn't theirs. */
    @Transactional(readOnly = true)
    public OrderView order(UUID userId, UUID orderId) {
        Order o = orders.order(orderId).filter(x -> x.customerId().equals(userId) && !"WAITING_PAYMENT".equals(x.status()))
                .orElseThrow(() -> ApiException.notFound("Order"));
        return view(o);
    }

    /**
     * The customer cancels an order the shop hasn't accepted yet: what it would have taken is refunded (the ₹10 too
     * when it was the checkout's only live order). 409 CANT_CANCEL once the shop accepted it.
     */
    @Transactional
    public OrderView cancel(UUID userId, UUID orderId, String reason) {
        Order peek = orders.order(orderId).filter(x -> x.customerId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Order"));
        Checkout c = orders.lockCheckout(peek.checkoutId()).orElseThrow();
        Order o = orders.lockOrder(orderId).orElseThrow();
        List<Part> parts = orders.lockPartsOf(o.id());
        Part own = parts.stream().filter(p -> "OWN".equals(p.role())).findFirst().orElseThrow();
        if ("WAITING_PAYMENT".equals(o.status())) {
            throw ApiException.notFound("Order");
        }
        if (!"PLACED".equals(o.status()) || !"WAITING".equals(own.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "CANT_CANCEL", switch (o.status()) {
                case "CANCELLED" -> "This order is cancelled already.";
                case "DELIVERED" -> "This order was delivered.";
                case "OUT_FOR_DELIVERY" -> "Your order is on its way, so it can't be cancelled now.";
                default -> "The shop has accepted your order, so it can't be cancelled now. Call the shop if you "
                        + "need to.";
            });
        }
        String why = reason == null || reason.isBlank() ? "You cancelled it" : "You cancelled it: " + reason.strip();
        flow.cancelOrder(c, o, parts, why, "CANCELLED", KiranaChecks.now(clock));
        return view(orders.order(orderId).orElseThrow());
    }

    /**
     * The customer corrects the address text (flat, tower, landmark) until the order goes out, at most 3 times; the
     * pin stays, so the shop is still within 2 km. The delivering shop is told once it accepted. 409 CANT_CHANGE_ADDRESS
     * once it is out, delivered or cancelled, or after 3 corrections.
     */
    @Transactional
    public OrderView changeAddress(UUID userId, UUID orderId, String addressLine) {
        Order peek = orders.order(orderId).filter(x -> x.customerId().equals(userId)
                && !"WAITING_PAYMENT".equals(x.status())).orElseThrow(() -> ApiException.notFound("Order"));
        String address = TextCleaner.clean(ADDRESS, addressLine);
        if (orders.lockCheckout(peek.checkoutId()).isEmpty()) {   // the checkout's lock first, as every order step
            throw ApiException.notFound("Order");
        }
        Order o = orders.lockOrder(orderId).orElseThrow();
        if (!List.of("PLACED", "ACCEPTED", "PACKED").contains(o.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "CANT_CHANGE_ADDRESS", "DELIVERED".equals(o.status())
                    || "CANCELLED".equals(o.status()) ? "This order is closed." : "Your order is on its way: call the "
                    + "shop to tell it the right address.");
        }
        if (!address.equals(o.addressLine())) {
            if (!orders.setAddress(o.id(), address, ADDRESS_CHANGES)) {
                throw new ApiException(HttpStatus.CONFLICT, "CANT_CHANGE_ADDRESS", "You've changed the address "
                        + ADDRESS_CHANGES + " times already: call the shop to tell it the right one.");
            }
            // Told only to a shop that accepted it (before that it doesn't see the customer's address at all)
            orders.partsOf(o.id()).stream().filter(p -> "OWN".equals(p.role())
                    && ("ACCEPTED".equals(p.status()) || "PACKED".equals(p.status()))).findFirst().ifPresent(p ->
                    flow.tellShop(p.shopId(), "KIRANA_ORDER_CHANGED", p.id(), KiranaChecks.now(clock),
                            "Order #" + o.orderNo() + ": new address", address, "/kirana/shop-orders/" + p.id()));
        }
        return view(orders.order(orderId).orElseThrow());
    }

    /** Rates a delivered order (1–5), once, within 7 days. 409 CANT_RATE / ALREADY_RATED. */
    @Transactional
    public OrderView rate(UUID userId, UUID orderId, Rate in) {
        Order o = orders.order(orderId).filter(x -> x.customerId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Order"));
        Instant now = KiranaChecks.now(clock);
        if (!"DELIVERED".equals(o.status()) || o.deliveredAt().plus(RATE_WITHIN).isBefore(now)) {
            throw new ApiException(HttpStatus.CONFLICT, "CANT_RATE", "Only an order delivered in the last 7 days can be rated.");
        }
        String comment = KiranaChecks.optional(COMMENT, in.comment());
        if (!orders.rate(o.id(), in.stars(), comment, now)) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_RATED", "You rated this order already.");
        }
        online.addRating(o.shopId(), in.stars(), now);
        return view(orders.order(orderId).orElseThrow());
    }

    // ---------- views ----------

    OrderView view(Order o) {
        List<Part> parts = orders.partsOf(o.id());
        Map<UUID, List<Line>> lines = orders.lines(parts.stream().map(Part::id).toList());
        List<ItemLine> items = new ArrayList<>();
        int no = 0;
        for (Part p : parts) {
            for (Line l : lines.getOrDefault(p.id(), List.of())) {
                items.add(new ItemLine(++no, l.productId(), l.catalogueItemId(), l.name(), l.unit(),
                        Quantities.json(l.quantity()), l.pricePaise(), l.amountPaise(), "SUPPLIER".equals(p.role()),
                        l.missing() || !p.live()));
            }
        }
        long itemsPaise = parts.stream().filter(Part::live).mapToLong(Part::itemsPaise).sum();
        Part own = parts.stream().filter(p -> "OWN".equals(p.role())).findFirst().orElse(null);
        Instant now = clock.instant();
        boolean open = !"DELIVERED".equals(o.status()) && !"CANCELLED".equals(o.status());
        List<RefundView> refunds = money.refundsOf(o.checkoutId()).stream()
                .filter(r -> o.id().equals(r.orderId())).map(CheckoutService::refundView).toList();
        boolean waiting = "PLACED".equals(o.status()) && own != null && "WAITING".equals(own.status());
        String shopPhone = open ? orders.ownerPhones(List.of(o.shopId())).get(o.shopId()) : null;
        return new OrderView(o.id(), o.orderNo(), o.checkoutId(), o.status(), o.shopId(),
                shops.find(o.shopId()).map(ShopDirectory.ShopCard::name).orElse(""), shopPhone, o.addressLine(),
                open ? o.deliveryOtp() : null, items, itemsPaise, o.platformPaise(), itemsPaise + o.platformPaise(),
                refunds, waiting ? own.acceptBy() : null, o.placedAt(), o.acceptedAt(), o.packedAt(), o.outAt(),
                o.deliveredAt(), o.cancelledAt(), o.cancelReason(), waiting,
                "DELIVERED".equals(o.status()) && o.rating() == null
                        && !o.deliveredAt().plus(RATE_WITHIN).isBefore(now), o.rating(), o.ratingComment(),
                List.of("PLACED", "ACCEPTED", "PACKED").contains(o.status())
                        && orders.addressChanges(o.id()) < ADDRESS_CHANGES);
    }

    private static RefundView refundView(MoneyStore.Refund r) {
        return new RefundView(r.amountPaise(), r.reason(), "FAILED".equals(r.status()) ? "FAILED"
                : "PROCESSED".equals(r.status()) ? "PROCESSED" : "PENDING", r.createdAt());
    }

    private PayView pay(Checkout c) {
        List<Order> list = orders.ordersOf(c.id());
        Map<UUID, ShopDirectory.ShopCard> cards = shops.findAll(list.stream().map(Order::shopId).distinct().toList());
        Map<UUID, List<Part>> parts = orders.partsOf(list.stream().map(Order::id).toList());
        Map<UUID, List<Line>> lines = orders.lines(parts.values().stream().flatMap(List::stream).map(Part::id).toList());
        return new PayView(c.id(), c.status(), c.razorpayOrderId(), razorpay.keyId(), c.totalPaise(), "INR",
                c.methodWanted(), list.stream().map(o -> {
                    List<Part> ps = parts.getOrDefault(o.id(), List.of());
                    int count = (int) ps.stream().filter(Part::live)
                            .flatMap(p -> lines.getOrDefault(p.id(), List.of()).stream()).filter(l -> !l.missing())
                            .count();
                    return new OrderSummary(o.id(), o.orderNo(),
                            cards.containsKey(o.shopId()) ? cards.get(o.shopId()).name() : "", o.status(),
                            ps.stream().filter(Part::live).mapToLong(Part::itemsPaise).sum() + o.platformPaise(), count,
                            o.createdAt());
                }).toList(), money.refundsOf(c.id()).stream().map(CheckoutService::refundView).toList());
    }

    /** The checkout with its Razorpay order, asked for (outside any transaction) if it has none yet. */
    private Checkout withRazorpayOrder(Checkout c) {
        if (c.razorpayOrderId() != null || !"PENDING".equals(c.status())) {
            return c;
        }
        String id = razorpay.createOrder(c.id(), c.totalPaise());
        transactions.executeWithoutResult(s -> orders.setRazorpayOrder(c.id(), id));
        return orders.checkout(c.id()).orElseThrow();
    }

    /** The shop takes orders now: its location; 409 SHOP_NOT_TAKING_ORDERS otherwise. */
    private BigDecimal[] requireReady(UUID shopId) {
        BigDecimal[] at = orders.location(shopId).orElseThrow(() -> notTaking());
        if (orders.readyShopMetres(shopId, at[0], at[1], KiranaChecks.today(clock.instant())).isEmpty()) {
            throw notTaking();
        }
        return at;
    }

    private static ApiException notTaking() {
        return new ApiException(HttpStatus.CONFLICT, "SHOP_NOT_TAKING_ORDERS",
                "This shop isn't taking online orders right now.");
    }

    private static void requireLocation(BigDecimal lat, BigDecimal lng) {
        if (lat == null || lng == null || lat.abs().compareTo(BigDecimal.valueOf(90)) > 0
                || lng.abs().compareTo(BigDecimal.valueOf(180)) > 0) {
            throw KiranaChecks.invalid("latitude", "Turn on your phone's location");
        }
    }

    private static Optional<Double> number(String s) {
        try {
            return Optional.of(Double.parseDouble(s));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<Instant> instant(String s) {
        try {
            return Optional.of(Instant.parse(s));
        } catch (java.time.format.DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
