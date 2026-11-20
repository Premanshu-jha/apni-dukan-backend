package in.apnidukan.kirana.service;

import in.apnidukan.common.context.ShopMemberships;
import in.apnidukan.common.notify.Notifications;
import in.apnidukan.common.notify.Notifications.Notice;
import in.apnidukan.kirana.domain.Product;
import in.apnidukan.kirana.domain.Quantities;
import in.apnidukan.kirana.repo.MoneyStore;
import in.apnidukan.kirana.repo.OrderStore;
import in.apnidukan.kirana.repo.OrderStore.Checkout;
import in.apnidukan.kirana.repo.OrderStore.Line;
import in.apnidukan.kirana.repo.OrderStore.Order;
import in.apnidukan.kirana.repo.OrderStore.Part;
import in.apnidukan.kirana.repo.ProductStore;
import in.apnidukan.kirana.repo.ProductStore.Move;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The steps of an online order shared by the customer's actions, the shops' actions and the jobs: placing a paid
 * checkout's orders, dropping a part (rejected, timed out, cancelled) with its stock and refund, cancelling an order,
 * and what each shop earns on delivery. Every caller holds the locks, always in this order so no two requests wait on
 * each other in a circle: the checkout, its orders (by number), their parts, then products (by id).
 */
@Component
public class OrderFlow {

    /** How long a shop has to accept an order. */
    static final Duration ACCEPT_WITHIN = Duration.ofMinutes(3);
    /** Working days after delivery a shop's money is due (Razorpay settles the payment first). */
    static final int PAYOUT_WORKING_DAYS = 2;

    private final OrderStore orders;
    private final ProductStore products;
    private final MoneyStore money;
    private final ShopMemberships memberships;
    private final Notifications notifications;

    public OrderFlow(OrderStore orders, ProductStore products, MoneyStore money, ShopMemberships memberships,
                     Notifications notifications) {
        this.orders = orders;
        this.products = products;
        this.money = money;
        this.memberships = memberships;
        this.notifications = notifications;
    }

    /**
     * A paid checkout's orders go to their shops: each part waits 3 minutes to be accepted. A shop not in {@code ready}
     * (it stopped taking orders, lost its FSSAI licence or approval since the quote) gets nothing: its part is dropped
     * and refunded, the whole order when it was to deliver it. Holds the checkout lock.
     */
    void place(Checkout paid, java.util.Set<UUID> ready, Instant now) {
        Checkout c = paid;
        for (Order o : orders.lockOrdersOf(c.id())) {
            if (!"WAITING_PAYMENT".equals(o.status())) {
                continue;
            }
            Order placed = o.with("PLACED", now);
            orders.saveOrder(placed);
            List<Part> parts = orders.lockPartsOf(o.id());
            Part own = parts.stream().filter(p -> "OWN".equals(p.role())).findFirst().orElseThrow();
            if (!ready.contains(own.shopId())) {
                // No shop ever sees it: its parts stay off their screens (never WAITING)
                cancelOrder(c, placed, parts, "The shop stopped taking orders", "REJECTED", now);
                c = orders.checkout(c.id()).orElseThrow();
                continue;
            }
            for (Part p : parts) {
                if (ready.contains(p.shopId())) {
                    orders.savePart(new Part(p.id(), p.orderId(), p.shopId(), p.role(), "WAITING",
                            now.plus(ACCEPT_WITHIN), null, null, p.itemsPaise(), p.version() + 1, now), null);
                }
            }
            Order current = placed;
            for (Part p : parts) {
                if (ready.contains(p.shopId()) || "CANCELLED".equals(current.status())) {
                    continue;
                }
                // A supplier that stopped taking orders: dropped before it saw the order, its items refunded
                dropPart(c, current, orders.lockPartsOf(o.id()), p, "REJECTED", "it stopped taking orders", null,
                        now);
                c = orders.checkout(c.id()).orElseThrow();
                current = orders.order(o.id()).orElseThrow();
            }
            if ("CANCELLED".equals(current.status())) {
                continue;
            }
            for (Part p : orders.lockPartsOf(o.id())) {
                if ("WAITING".equals(p.status())) {
                    tellShop(p.shopId(), "KIRANA_ORDER_NEW", p.id(), now, "New order #" + o.orderNo(),
                            "OWN".equals(p.role()) ? "Accept it within 3 minutes." : "Items for another shop's order: "
                                    + "accept within 3 minutes.", "/kirana/shop-orders/" + p.id());
                }
            }
        }
    }

    /**
     * Drops a part (REJECTED, TIMED_OUT or CANCELLED, with why): stock it took goes back, its items are refunded; the
     * delivering shop's own part takes its whole order with it. The caller holds the checkout, the order and its parts.
     */
    void dropPart(Checkout c, Order o, List<Part> parts, Part p, String status, String reason, UUID by, Instant now) {
        if ("OWN".equals(p.role())) {
            cancelOrder(c, o, parts, status.equals("CANCELLED") ? reason : "The shop couldn't take this order: "
                    + reason, status, now);
            return;
        }
        giveBack(p, now, by);
        Part dropped = new Part(p.id(), p.orderId(), p.shopId(), p.role(), status, p.acceptBy(), p.acceptedAt(),
                reason, p.itemsPaise(), p.version() + 1, now);
        orders.savePart(dropped, null);
        refund(c, o, p.itemsPaise(), status, now);
        List<Part> after = parts.stream().map(x -> x.id().equals(p.id()) ? dropped : x).toList();
        if (after.stream().filter(Part::live).mapToLong(Part::itemsPaise).sum() == 0) {
            // Nothing left to deliver (the delivering shop had none of it, or marked its items missing)
            cancelOrder(orders.checkout(c.id()).orElseThrow(), o, after, "None of the items were available",
                    "CANCELLED", now);
            return;
        }
        tellCustomer(o, "KIRANA_ORDER_CHANGED", now, "Order #" + o.orderNo() + ": some items aren't available",
                "₹" + KhataService.rupeesOf(p.itemsPaise()) + " is refunded. The rest of your order is on its way.");
    }

    /**
     * Cancels an order: every live part is cancelled (stock back), its items refunded, and the ₹10 charge too once every
     * order of the checkout is cancelled. {@code partStatus}: what the delivering shop's own part becomes (REJECTED,
     * TIMED_OUT or CANCELLED). The caller holds the checkout, the order and its parts.
     */
    void cancelOrder(Checkout c, Order o, List<Part> parts, String reason, String partStatus, Instant now) {
        lockStockOf(parts.stream().filter(Part::live).toList());
        long refund = 0;
        for (Part p : parts) {
            if (!p.live()) {
                continue;
            }
            giveBack(p, now, null);
            refund += p.itemsPaise();
            String status = "OWN".equals(p.role()) ? partStatus : "CANCELLED";
            orders.savePart(new Part(p.id(), p.orderId(), p.shopId(), p.role(), status, p.acceptBy(), p.acceptedAt(),
                    "OWN".equals(p.role()) ? reason : "The order was cancelled", p.itemsPaise(), p.version() + 1, now),
                    null);
            if ("SUPPLIER".equals(p.role()) && "HANDED".equals(p.status())) {
                tellShop(p.shopId(), "KIRANA_ORDER_CANCELLED", p.id(), now, "Order #" + o.orderNo() + " cancelled",
                        "Collect the items you handed over from the shop. Their stock is back in your list.",
                        "/kirana/shop-orders/" + p.id());
            } else if ("SUPPLIER".equals(p.role()) && ("ACCEPTED".equals(p.status()) || "PACKED".equals(p.status()))) {
                tellShop(p.shopId(), "KIRANA_ORDER_CANCELLED", p.id(), now, "Order #" + o.orderNo() + " cancelled",
                        "Don't hand over its items. Their stock is back in your list.", "/kirana/shop-orders/" + p.id());
            }
        }
        Order cancelled = o.with("CANCELLED", now);
        orders.saveOrder(new Order(cancelled.id(), cancelled.orderNo(), cancelled.checkoutId(), cancelled.shopId(),
                cancelled.customerId(), cancelled.addressLine(), cancelled.latitude(), cancelled.longitude(),
                cancelled.status(), reason, cancelled.platformPaise(), cancelled.deliveryOtp(), cancelled.otpWrong(),
                cancelled.otpLockedUntil(), cancelled.placedAt(), cancelled.acceptedAt(), cancelled.packedAt(),
                cancelled.outAt(), cancelled.deliveredAt(), cancelled.cancelledAt(), cancelled.rating(),
                cancelled.ratingComment(), cancelled.version(), cancelled.createdAt()));
        if ("PAID".equals(c.status())) {
            boolean allCancelled = orders.ordersOf(c.id()).stream()
                    .allMatch(x -> x.id().equals(o.id()) || "CANCELLED".equals(x.status()));
            refund(c, o, refund + (allCancelled ? c.platformPaise() : 0), partStatus, now);
            tellCustomer(o, "KIRANA_ORDER_CANCELLED", now, "Order #" + o.orderNo() + " cancelled", reason);
        }
    }

    /** Records a refund to the customer's payment (sent by the job), never past what was paid. */
    void refund(Checkout c, Order o, long amountPaise, String reason, Instant now) {
        if (amountPaise <= 0 || !"PAID".equals(c.status()) || c.razorpayPaymentId() == null) {
            return;
        }
        if (orders.addRefunded(c.id(), amountPaise)) {
            money.addRefund(UUID.randomUUID(), c.id(), o == null ? null : o.id(), amountPaise, reason, now);
        }
    }

    /**
     * Stock a part took when accepted goes back (its lines not marked missing). A supplier's handed items too: an order
     * cancelled before delivery leaves them with the delivering shop, to be collected.
     */
    void giveBack(Part p, Instant now, UUID by) {
        if (!"ACCEPTED".equals(p.status()) && !"PACKED".equals(p.status()) && !"HANDED".equals(p.status())) {
            return;   // stock is taken on accepting
        }
        List<Line> lines = orders.lines(List.of(p.id())).getOrDefault(p.id(), List.of()).stream()
                .filter(l -> !l.missing()).toList();
        moveStock(p, lines, true, now, by);
    }

    /**
     * Locks the products of these parts' lines, all shops together in id order, before their stock moves part by part:
     * two orders whose shops supply each other never wait on each other in a circle.
     */
    private void lockStockOf(List<Part> parts) {
        if (parts.size() < 2) {
            return;
        }
        Map<UUID, List<Line>> lines = orders.lines(parts.stream().map(Part::id).toList());
        Map<UUID, UUID> shopOf = new TreeMap<>();
        for (Part p : parts) {
            lines.getOrDefault(p.id(), List.of()).forEach(l -> shopOf.put(l.productId(), p.shopId()));
        }
        shopOf.forEach((product, shop) -> products.lockAll(shop, List.of(product)));
    }

    /** Takes (or gives back) the lines' stock, products locked in id order. */
    void moveStock(Part p, Collection<Line> lines, boolean back, Instant now, UUID by) {
        Map<UUID, BigDecimal> qty = new TreeMap<>();
        lines.forEach(l -> qty.merge(l.productId(), l.quantity(), BigDecimal::add));
        if (qty.isEmpty()) {
            return;
        }
        Map<UUID, Product> locked = products.lockAll(p.shopId(), qty.keySet()).stream()
                .collect(Collectors.toMap(Product::id, Function.identity()));
        List<Move> moves = new ArrayList<>();
        for (Map.Entry<UUID, BigDecimal> e : qty.entrySet()) {
            Product pr = locked.get(e.getKey());
            if (pr == null) {
                continue;
            }
            BigDecimal change = back ? e.getValue() : e.getValue().negate();
            BigDecimal after = Quantities.stored(pr.stock().add(change));
            products.setStock(pr.id(), after, now);
            moves.add(new Move(pr.id(), Quantities.stored(change), after, back ? "ORDER_BACK" : "ORDER", null, null,
                    null));
        }
        products.addOrderMoves(p.shopId(), p.id(), moves, by, now);
    }

    /** What each shop earned from a delivered order: its live parts' items, due 2 working days later. */
    void earn(Order o, List<Part> parts, LocalDate today, Instant now) {
        LocalDate due = workingDaysAfter(today, PAYOUT_WORKING_DAYS);
        for (Part p : parts) {
            if (p.live() && p.itemsPaise() > 0) {
                money.addEarning(p.id(), p.shopId(), p.itemsPaise(), due, now);
            }
        }
    }

    static LocalDate workingDaysAfter(LocalDate day, int n) {
        LocalDate d = day;
        int left = n;
        while (left > 0) {
            d = d.plusDays(1);
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                left--;
            }
        }
        return d;
    }

    // ---------- notices ----------

    void tellShop(UUID shopId, String type, UUID ref, Instant now, String title, String body, String url) {
        List<Notice> notices = memberships.members(shopId).stream()
                .filter(m -> "OWNER".equals(m.role()) || "HELPER".equals(m.role()))
                .map(m -> new Notice(m.userId(), type, ref, now, title, body, url, null)).toList();
        if (!notices.isEmpty()) {
            notifications.queueAll(notices);
        }
    }

    void tellCustomer(Order o, String type, Instant now, String title, String body) {
        notifications.queue(new Notice(o.customerId(), type, o.id(), now, title, body, "/kirana/orders/" + o.id(),
                null));
    }
}
