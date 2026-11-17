package in.apnidukan.kirana.service;

import in.apnidukan.common.context.ShopDirectory;
import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.text.TextCleaner;
import in.apnidukan.kirana.api.OrderResponses.Customer;
import in.apnidukan.kirana.api.OrderResponses.ItemLine;
import in.apnidukan.kirana.api.OrderResponses.OtherPart;
import in.apnidukan.kirana.api.OrderResponses.ShopPartPage;
import in.apnidukan.kirana.api.OrderResponses.ShopPartView;
import in.apnidukan.kirana.domain.Cursors;
import in.apnidukan.kirana.domain.Quantities;
import in.apnidukan.kirana.repo.OrderStore;
import in.apnidukan.kirana.repo.OrderStore.Checkout;
import in.apnidukan.kirana.repo.OrderStore.Line;
import in.apnidukan.kirana.repo.OrderStore.Order;
import in.apnidukan.kirana.repo.OrderStore.Part;
import in.apnidukan.kirana.service.KiranaAccess.Caller;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static in.apnidukan.kirana.service.KiranaChecks.PAGE;

/**
 * Kirana online orders, the shop's side (the owner and helpers): the shop's order parts, accepting one within 3
 * minutes (its stock goes down then) or rejecting it, marking items it finds it doesn't have (refunded), packing,
 * handing another shop's items over (a supplier part), sending the order out and delivering it against the customer's
 * 4-digit code. A supplier never sees the customer: only the shop it hands the items to.
 */
@Service
public class ShopOrderService {

    static final int OTP_TRIES = 5;
    static final Duration OTP_LOCK = Duration.ofMinutes(15);
    /** Wrong codes in all for one order before it locks for a day (no guessing 10,000 codes 5 at a time). */
    static final int OTP_MAX_WRONG = 15;
    static final Duration OTP_LONG_LOCK = Duration.ofDays(1);

    private final KiranaAccess access;
    private final OrderStore orders;
    private final OrderFlow flow;
    private final ShopDirectory shops;
    private final UserDirectory users;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ShopOrderService(KiranaAccess access, OrderStore orders, OrderFlow flow, ShopDirectory shops,
                            UserDirectory users, TransactionTemplate transactions, Clock clock) {
        this.access = access;
        this.orders = orders;
        this.flow = flow;
        this.shops = shops;
        this.users = users;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** What a locked order looks like to one of its shops' actions. */
    private record Held(Checkout checkout, Order order, List<Part> parts, Part part) {
    }

    /** The shop's order parts, newest first, 50 a page: open (to accept, pack, hand over, deliver) or done. */
    @Transactional(readOnly = true)
    public ShopPartPage list(UUID userId, boolean open, String cursor) {
        Caller c = access.caller(userId);
        Optional<String[]> before = Cursors.decode(cursor);
        Instant beforeAt = before.flatMap(b -> instant(b[0])).orElse(null);
        List<Part> rows = orders.shopParts(c.shopId(), open, beforeAt,
                beforeAt == null ? null : ProductService.uuid(before.get()[1]), PAGE);
        boolean more = rows.size() > PAGE;
        List<Part> page = more ? rows.subList(0, PAGE) : rows;
        Part last = page.isEmpty() ? null : page.get(page.size() - 1);
        return new ShopPartPage(views(page), more ? Cursors.encode(last.updatedAt().toString(), last.id().toString())
                : null);
    }

    /** One of the shop's order parts. 404 when it isn't this shop's. */
    @Transactional(readOnly = true)
    public ShopPartView get(UUID userId, UUID partId) {
        Caller c = access.caller(userId);
        Part p = orders.part(partId).filter(x -> x.shopId().equals(c.shopId()))
                .orElseThrow(() -> ApiException.notFound("Order"));
        return views(List.of(p)).get(0);
    }

    /**
     * Accepts a part within its 3 minutes: its stock goes down (an online sale, like a counter sale; it may go below
     * zero). 409 TOO_LATE after the 3 minutes (the order went to no one: refunded); 409 WRONG_STATUS otherwise.
     */
    @Transactional
    public ShopPartView accept(UUID userId, UUID partId) {
        Caller c = access.caller(userId);
        Instant now = KiranaChecks.now(clock);
        Held h = hold(c, partId);
        Part p = h.part();
        if (!"WAITING".equals(p.status())) {
            throw wrong(p);
        }
        if (now.isAfter(p.acceptBy())) {
            throw new ApiException(HttpStatus.CONFLICT, "TOO_LATE", "The 3 minutes to accept this order are over.");
        }
        Part accepted = new Part(p.id(), p.orderId(), p.shopId(), p.role(), "ACCEPTED", p.acceptBy(), now, null,
                p.itemsPaise(), p.version() + 1, now);
        orders.savePart(accepted, userId);
        flow.moveStock(accepted, orders.lines(List.of(p.id())).getOrDefault(p.id(), List.of()), false, now, userId);
        if ("OWN".equals(p.role())) {
            orders.saveOrder(h.order().with("ACCEPTED", now));
            flow.tellCustomer(h.order(), "KIRANA_ORDER_ACCEPTED", now, "Order #" + h.order().orderNo() + " accepted",
                    "The shop is packing it.");
        }
        return views(List.of(orders.part(p.id()).orElseThrow())).get(0);
    }

    /**
     * Rejects a waiting part, with why (shown to the customer): its items are refunded; the delivering shop's own part
     * cancels its whole order. 409 WRONG_STATUS once accepted (mark items missing instead).
     */
    @Transactional
    public ShopPartView reject(UUID userId, UUID partId, String reason) {
        Caller c = access.caller(userId);
        String why = TextCleaner.clean(KiranaChecks.VOID_REASON, reason);
        Instant now = KiranaChecks.now(clock);
        Held h = hold(c, partId);
        if (!"WAITING".equals(h.part().status())) {
            throw wrong(h.part());
        }
        flow.dropPart(h.checkout(), h.order(), h.parts(), h.part(), "REJECTED", why, userId, now);
        return views(List.of(orders.part(partId).orElseThrow())).get(0);
    }

    /**
     * Marks lines the shop found it doesn't have while packing (an accepted, not yet packed part): their stock goes
     * back, they are refunded. When nothing is left of the whole order it is cancelled (the ₹10 refunded too). 400
     * naming lineNos for a line the part hasn't.
     */
    @Transactional
    public ShopPartView missing(UUID userId, UUID partId, List<Integer> lineNos) {
        Caller c = access.caller(userId);
        Instant now = KiranaChecks.now(clock);
        Held h = hold(c, partId);
        Part p = h.part();
        if (!"ACCEPTED".equals(p.status())) {
            throw wrong(p);
        }
        Map<Integer, Line> byNo = orders.lines(List.of(p.id())).getOrDefault(p.id(), List.of()).stream()
                .collect(Collectors.toMap(Line::lineNo, l -> l));
        Set<Integer> marked = new HashSet<>();
        for (Integer no : lineNos) {
            Line l = byNo.get(no);
            if (l == null) {
                throw KiranaChecks.invalid("lineNos", "This order has no line " + no);
            }
            if (!l.missing()) {
                marked.add(no);
            }
        }
        if (marked.isEmpty()) {
            return views(List.of(p)).get(0);
        }
        List<Line> gone = marked.stream().map(byNo::get).toList();
        long off = gone.stream().mapToLong(Line::amountPaise).sum();
        orders.markMissing(p.id(), marked);
        flow.moveStock(p, gone, true, now, userId);
        boolean empty = p.itemsPaise() - off == 0 && "SUPPLIER".equals(p.role());
        // A supplier left with nothing has nothing to hand over: its part ends, so the order can go out without it
        Part less = new Part(p.id(), p.orderId(), p.shopId(), p.role(), empty ? "CANCELLED" : p.status(), p.acceptBy(),
                p.acceptedAt(), empty ? "None of its items were available" : p.reason(), p.itemsPaise() - off,
                p.version() + 1, now);
        orders.savePart(less, null);
        flow.refund(h.checkout(), h.order(), off, "MISSING", now);
        flow.tellCustomer(h.order(), "KIRANA_ORDER_CHANGED", now, "Order #" + h.order().orderNo()
                + ": some items aren't available", "₹" + KhataService.rupeesOf(off) + " is refunded.");
        List<Part> after = orders.lockPartsOf(h.order().id());
        boolean nothingLeft = after.stream().filter(Part::live).allMatch(x -> x.itemsPaise() == 0);
        if (nothingLeft) {
            Checkout fresh = orders.checkout(h.checkout().id()).orElseThrow();
            flow.cancelOrder(fresh, orders.order(h.order().id()).orElseThrow(), after,
                    "None of the items were available", "CANCELLED", now);
        }
        return views(List.of(orders.part(partId).orElseThrow())).get(0);
    }

    /**
     * The shop can't finish an order it accepted (the customer can't be reached, the address is wrong, accepted by
     * mistake, a supplier's items got damaged), with why (shown to the customer). The delivering shop's own part, until
     * delivered: the whole order is cancelled and refunded in full, every shop's stock goes back (a supplier's handed
     * items are collected from it). A supplier's part before handing over: only its items are refunded. 409
     * WRONG_STATUS otherwise.
     */
    @Transactional
    public ShopPartView cancel(UUID userId, UUID partId, String reason) {
        Caller c = access.caller(userId);
        String why = TextCleaner.clean(KiranaChecks.VOID_REASON, reason);
        Instant now = KiranaChecks.now(clock);
        Held h = hold(c, partId);
        Part p = h.part();
        boolean own = "OWN".equals(p.role());
        boolean open = List.of("ACCEPTED", "PACKED", "OUT_FOR_DELIVERY").contains(h.order().status());
        if (!List.of("ACCEPTED", "PACKED").contains(p.status()) || (own && !open)) {
            throw wrong(p);
        }
        if (own) {
            flow.cancelOrder(h.checkout(), h.order(), h.parts(), "The shop cancelled it: " + why, "CANCELLED", now);
        } else {
            flow.dropPart(h.checkout(), h.order(), h.parts(), p, "CANCELLED", why, userId, now);
        }
        return views(List.of(orders.part(partId).orElseThrow())).get(0);
    }

    /**
     * The part is packed. The delivering shop's own part: the order is PACKED (ready to go out once other shops'
     * items are handed over). A supplier's part: use handed instead (packed and given to the delivering shop).
     */
    @Transactional
    public ShopPartView packed(UUID userId, UUID partId) {
        Caller c = access.caller(userId);
        Instant now = KiranaChecks.now(clock);
        Held h = hold(c, partId);
        Part p = h.part();
        if (!"ACCEPTED".equals(p.status()) || !"OWN".equals(p.role())) {
            throw wrong(p);
        }
        orders.savePart(new Part(p.id(), p.orderId(), p.shopId(), p.role(), "PACKED", p.acceptBy(), p.acceptedAt(),
                p.reason(), p.itemsPaise(), p.version() + 1, now), null);
        orders.saveOrder(h.order().with("PACKED", now));
        return views(List.of(orders.part(partId).orElseThrow())).get(0);
    }

    /** A supplier hands its packed items to the delivering shop. */
    @Transactional
    public ShopPartView handed(UUID userId, UUID partId) {
        Caller c = access.caller(userId);
        Instant now = KiranaChecks.now(clock);
        Held h = hold(c, partId);
        Part p = h.part();
        if (!"ACCEPTED".equals(p.status()) || !"SUPPLIER".equals(p.role())) {
            throw wrong(p);
        }
        Part own = h.parts().stream().filter(x -> "OWN".equals(x.role())).findFirst().orElseThrow();
        if (!"ACCEPTED".equals(own.status()) && !"PACKED".equals(own.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_ACCEPTED_YET", shopName(own.shopId())
                    + " hasn't accepted the order yet. Hand the items over once it has.");
        }
        orders.savePart(new Part(p.id(), p.orderId(), p.shopId(), p.role(), "HANDED", p.acceptBy(), p.acceptedAt(),
                p.reason(), p.itemsPaise(), p.version() + 1, now), null);
        flow.tellShop(h.order().shopId(), "KIRANA_ORDER_HANDED", h.order().id(), now, "Order #" + h.order().orderNo(),
                shopName(p.shopId()) + " handed over its items.", "/kirana/shop-orders/" + h.parts().stream()
                        .filter(x -> "OWN".equals(x.role())).findFirst().map(Part::id).orElse(p.id()));
        return views(List.of(orders.part(partId).orElseThrow())).get(0);
    }

    /**
     * The delivering shop sends the packed order out. 409 WAITING_FOR_SHOP while another shop hasn't handed its items
     * over (or decided); the customer is told it is on its way.
     */
    @Transactional
    public ShopPartView out(UUID userId, UUID partId) {
        Caller c = access.caller(userId);
        Instant now = KiranaChecks.now(clock);
        Held h = hold(c, partId);
        if (!"OWN".equals(h.part().role()) || !"PACKED".equals(h.order().status())) {
            throw wrong(h.part());
        }
        Optional<Part> waiting = h.parts().stream().filter(x -> "SUPPLIER".equals(x.role())
                && ("WAITING".equals(x.status()) || "ACCEPTED".equals(x.status()))).findFirst();
        if (waiting.isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "WAITING_FOR_SHOP", shopName(waiting.get().shopId())
                    + " hasn't handed over its items yet.", Map.of("partId", waiting.get().id()));
        }
        orders.saveOrder(h.order().with("OUT_FOR_DELIVERY", now));
        flow.tellCustomer(h.order(), "KIRANA_ORDER_OUT", now, "Order #" + h.order().orderNo() + " is on its way",
                "Give your code " + h.order().deliveryOtp() + " when it arrives.");
        return views(List.of(orders.part(partId).orElseThrow())).get(0);
    }

    /**
     * Delivers the order against the customer's 4-digit code: then each shop's money is due in 2 working days, and no
     * refund is possible any more. A wrong code: 400 WRONG_CODE (triesLeft); after 5 wrong codes 429 CODE_LOCKED for 15
     * minutes (the customer can be phoned for the right code meanwhile).
     */
    public ShopPartView deliver(UUID userId, UUID partId, String otp) {
        Caller c = access.caller(userId);
        String code = otp.strip();
        ApiException[] refusal = new ApiException[1];
        transactions.executeWithoutResult(s -> {
            Instant now = KiranaChecks.now(clock);
            Held h = hold(c, partId);
            Order o = h.order();
            if (!"OWN".equals(h.part().role()) || !"OUT_FOR_DELIVERY".equals(o.status())) {
                refusal[0] = wrong(h.part());
                return;
            }
            if (o.otpLockedUntil() != null && now.isBefore(o.otpLockedUntil())) {
                refusal[0] = locked(o.otpLockedUntil(), now);
                return;
            }
            if (!java.security.MessageDigest.isEqual(code.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    o.deliveryOtp().getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                // Counted for the whole order: every 5th wrong code locks it 15 minutes, the 15th for a day
                int wrongs = o.otpWrong() + 1;
                boolean lock = wrongs % OTP_TRIES == 0;
                Instant until = wrongs >= OTP_MAX_WRONG ? now.plus(OTP_LONG_LOCK) : now.plus(OTP_LOCK);
                orders.saveOrder(new Order(o.id(), o.orderNo(), o.checkoutId(), o.shopId(), o.customerId(),
                        o.addressLine(), o.latitude(), o.longitude(), o.status(), o.cancelReason(), o.platformPaise(),
                        o.deliveryOtp(), wrongs, lock ? until : o.otpLockedUntil(),
                        o.placedAt(), o.acceptedAt(), o.packedAt(), o.outAt(), o.deliveredAt(), o.cancelledAt(),
                        o.rating(), o.ratingComment(), o.version() + 1, o.createdAt()));
                refusal[0] = lock ? locked(until, now) : new ApiException(HttpStatus.BAD_REQUEST, "WRONG_CODE",
                        "That isn't the customer's code.", Map.of("triesLeft", OTP_TRIES - wrongs % OTP_TRIES));
                return;
            }
            orders.saveOrder(o.with("DELIVERED", now));
            flow.earn(o, h.parts(), KiranaChecks.today(now), now);
            flow.tellCustomer(o, "KIRANA_ORDER_DELIVERED", now, "Order #" + o.orderNo() + " delivered",
                    "How was it? Rate the shop.");
        });
        if (refusal[0] != null) {
            throw refusal[0];
        }
        return get(userId, partId);
    }

    // ---------- helpers ----------

    /** Locks the checkout, the order and its parts (in that order) for one of this shop's parts. */
    private Held hold(Caller c, UUID partId) {
        Part peek = orders.part(partId).filter(x -> x.shopId().equals(c.shopId()))
                .orElseThrow(() -> ApiException.notFound("Order"));
        Order orderPeek = orders.order(peek.orderId()).orElseThrow();
        Checkout checkout = orders.lockCheckout(orderPeek.checkoutId()).orElseThrow();
        Order order = orders.lockOrder(orderPeek.id()).orElseThrow();
        List<Part> parts = orders.lockPartsOf(order.id());
        Part part = parts.stream().filter(x -> x.id().equals(partId)).findFirst().orElseThrow();
        return new Held(checkout, order, parts, part);
    }

    private List<ShopPartView> views(List<Part> page) {
        List<UUID> orderIds = page.stream().map(Part::orderId).distinct().toList();
        Map<UUID, Order> byId = orders.orders(orderIds);
        Map<UUID, List<Part>> allParts = orders.partsOf(orderIds);
        List<UUID> partIds = allParts.values().stream().flatMap(List::stream).map(Part::id).toList();
        Map<UUID, List<Line>> lines = orders.lines(partIds);
        Map<UUID, ShopDirectory.ShopCard> cards = shops.findAll(allParts.values().stream().flatMap(List::stream)
                .map(Part::shopId).distinct().toList());
        Map<UUID, UserDirectory.Person> people = users.find(page.stream().filter(ShopOrderService::seesCustomer)
                .map(p -> byId.get(p.orderId())).filter(java.util.Objects::nonNull).map(Order::customerId).distinct()
                .toList());
        Map<UUID, String> phones = orders.ownerPhones(allParts.values().stream().flatMap(List::stream)
                .map(Part::shopId).distinct().toList());
        Instant now = clock.instant();
        List<ShopPartView> out = new ArrayList<>();
        for (Part p : page) {
            Order o = byId.get(p.orderId());
            boolean own = "OWN".equals(p.role());
            Customer customer = null;
            if (seesCustomer(p) && o != null && !"CANCELLED".equals(o.status())) {
                // While delivering: who and where; once delivered, the name and address only (its record)
                boolean delivered = "DELIVERED".equals(o.status());
                UserDirectory.Person person = people.get(o.customerId());
                customer = new Customer(person == null ? null : person.name(),
                        person == null || delivered ? null : person.phone(), o.addressLine(),
                        delivered ? null : o.latitude(), delivered ? null : o.longitude());
            }
            boolean live = o != null && !"DELIVERED".equals(o.status()) && !"CANCELLED".equals(o.status());
            List<OtherPart> others = own ? allParts.getOrDefault(p.orderId(), List.of()).stream()
                    .filter(x -> !x.id().equals(p.id())).map(x -> new OtherPart(x.id(), name(cards, x.shopId()),
                            live ? phones.get(x.shopId()) : null, x.status(),
                            items(lines.getOrDefault(x.id(), List.of()), true))).toList() : List.of();
            long left = "WAITING".equals(p.status()) && p.acceptBy() != null
                    ? Math.max(0, Duration.between(now, p.acceptBy()).getSeconds()) : 0;
            out.add(new ShopPartView(p.id(), p.orderId(), o == null ? 0 : o.orderNo(), p.role(), p.status(),
                    p.acceptBy(), left, customer, own || o == null ? null : name(cards, o.shopId()),
                    own || o == null || !live ? null : phones.get(o.shopId()),
                    items(lines.getOrDefault(p.id(), List.of()), false), p.itemsPaise(), others,
                    o == null ? null : o.status(), o == null ? null : o.createdAt()));
        }
        return out;
    }

    /** The delivering shop sees its customer only once it accepted the order (never before, nor when it didn't take it). */
    private static boolean seesCustomer(Part p) {
        return "OWN".equals(p.role()) && ("ACCEPTED".equals(p.status()) || "PACKED".equals(p.status()));
    }

    private static List<ItemLine> items(List<Line> lines, boolean fromNearby) {
        return lines.stream().map(l -> new ItemLine(l.lineNo(), l.productId(), l.catalogueItemId(), l.name(), l.unit(),
                Quantities.json(l.quantity()), l.pricePaise(), l.amountPaise(), fromNearby, l.missing())).toList();
    }

    private static String name(Map<UUID, ShopDirectory.ShopCard> cards, UUID shopId) {
        return cards.containsKey(shopId) ? cards.get(shopId).name() : "";
    }

    private String shopName(UUID shopId) {
        return shops.find(shopId).map(ShopDirectory.ShopCard::name).orElse("Another shop");
    }

    private static ApiException wrong(Part p) {
        return new ApiException(HttpStatus.CONFLICT, "WRONG_STATUS", "This order is " + p.status().toLowerCase(
                java.util.Locale.ROOT).replace('_', ' ') + ": reload it.", Map.of("status", p.status()));
    }

    private static ApiException locked(Instant until, Instant now) {
        long minutes = Math.max(1, Duration.between(now, until).toMinutes() + 1);
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "CODE_LOCKED", "Too many wrong codes. Try again in "
                + minutes + " minutes, or call the customer.", Map.of("retryAfterSeconds",
                Math.max(1, Duration.between(now, until).getSeconds())));
    }

    private static Optional<Instant> instant(String s) {
        try {
            return Optional.of(Instant.parse(s));
        } catch (java.time.format.DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
