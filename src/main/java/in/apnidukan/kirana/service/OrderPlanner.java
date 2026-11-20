package in.apnidukan.kirana.service;

import in.apnidukan.common.context.ShopDirectory;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.kirana.api.OrderRequests.CartLine;
import in.apnidukan.kirana.api.OrderResponses.ItemLine;
import in.apnidukan.kirana.api.OrderResponses.PlanOrder;
import in.apnidukan.kirana.api.OrderResponses.PlanView;
import in.apnidukan.kirana.api.OrderResponses.QuoteView;
import in.apnidukan.kirana.api.OrderResponses.Unavailable;
import in.apnidukan.kirana.domain.Quantities;
import in.apnidukan.kirana.domain.Unit;
import in.apnidukan.kirana.repo.CatalogueStore;
import in.apnidukan.kirana.repo.OrderStore;
import in.apnidukan.kirana.repo.OrderStore.Listed;
import in.apnidukan.kirana.repo.OrderStore.Offer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Works out who gives what for a customer's cart at a kirana, and what it costs. The chosen shop gives what it has.
 * What it is short of (some of an item: combined only) or lacks entirely (the whole item: the customer chooses) comes
 * from the shops near it that take orders and have it, matched by the shared catalogue:
 * <ul>
 *   <li><b>Combined</b>: shops within 1 km of the chosen shop hand the items to it; one delivery, one OTP</li>
 *   <li><b>Separate</b>: for whole items only, shops within 2 km of the customer deliver them themselves</li>
 * </ul>
 * At most 2 other shops per plan, preferring the shop that covers the most items, then the nearest. Each item is
 * priced at the shop that gives it. The ₹10 platform charge is added once per checkout. Nothing is reserved: the
 * checkout works the plan out again, and a shop may still find at packing that it has less (a refund).
 */
@Component
public class OrderPlanner {

    /** Clozest's charge per checkout: ₹10. */
    public static final long PLATFORM_PAISE = 1_000;
    /** How far a customer may be from the shop they order from. */
    static final int CUSTOMER_METRES = 2_000;
    /** How far another shop may be from the chosen shop to hand it items (a combined order). */
    static final int NETWORK_METRES = 1_000;
    /** Other shops one plan may use. */
    static final int MAX_OTHER_SHOPS = 2;

    private final OrderStore orders;
    private final CatalogueStore catalogue;
    private final ShopDirectory shops;
    private final Clock clock;

    public OrderPlanner(OrderStore orders, CatalogueStore catalogue, ShopDirectory shops, Clock clock) {
        this.orders = orders;
        this.catalogue = catalogue;
        this.shops = shops;
        this.clock = clock;
    }

    /** An item given by a shop: its product there, how much, at its price. */
    public record Supply(UUID productId, UUID catalogueItemId, String name, Unit unit, BigDecimal quantity,
                         long pricePaise) {

        long amountPaise() {
            return Quantities.amount(pricePaise, quantity);
        }
    }

    /** A shop's part of an order: OWN (the delivering shop) or SUPPLIER (handed to it). */
    public record PlannedPart(UUID shopId, String shopName, String role, List<Supply> lines) {

        public PlannedPart {
            lines = List.copyOf(lines);
        }

        long itemsPaise() {
            return lines.stream().mapToLong(Supply::amountPaise).sum();
        }
    }

    /** One delivery: its delivering shop's own part first, then the parts other shops hand to it. */
    public record PlannedOrder(UUID shopId, String shopName, List<PlannedPart> parts) {

        public PlannedOrder {
            parts = List.copyOf(parts);
        }

        long itemsPaise() {
            return parts.stream().mapToLong(PlannedPart::itemsPaise).sum();
        }

        boolean empty() {
            return parts.stream().allMatch(p -> p.lines().isEmpty());
        }
    }

    /** A way to split the cart, and what it costs. */
    public record Plan(List<PlannedOrder> orders, long itemsPaise, long platformPaise) {

        public Plan {
            orders = List.copyOf(orders);
        }

        public long totalPaise() {
            return itemsPaise + platformPaise;
        }
    }

    /**
     * The whole answer: both plans (separate only when the shop lacks whole items; combined null when nothing at all
     * can be given) and what each plan can't give.
     */
    public record Quote(UUID shopId, String shopName, Plan combined, Plan separate, boolean choose,
                        List<Unavailable> unavailable, List<Unavailable> separateUnavailable) {

        public Quote {
            unavailable = List.copyOf(unavailable);
            separateUnavailable = List.copyOf(separateUnavailable);
        }
    }

    /** What a cart line still needs from other shops. */
    private record Need(int line, UUID catalogueItemId, String name, Unit unit, BigDecimal quantity) {
    }

    /**
     * The quote for a cart. 409 SHOP_NOT_TAKING_ORDERS (not approved, switched off, no valid FSSAI licence...); 409
     * TOO_FAR when the customer is more than 2 km away; 409 PRODUCT_GONE (details.lines) for products removed meanwhile;
     * 400 naming lines[i].quantity or lines[i] (neither or both of productId and catalogueItemId).
     */
    public Quote quote(UUID shopId, BigDecimal lat, BigDecimal lng, List<CartLine> cart) {
        LocalDate today = KiranaChecks.today(clock.instant());
        double metres = orders.readyShopMetres(shopId, lat, lng, today).orElseThrow(() -> new ApiException(
                HttpStatus.CONFLICT, "SHOP_NOT_TAKING_ORDERS", "This shop isn't taking online orders right now."));
        if (metres > CUSTOMER_METRES) {
            throw new ApiException(HttpStatus.CONFLICT, "TOO_FAR", "This shop is more than 2 km from you.",
                    Map.of("metres", Math.round(metres)));
        }
        String shopName = shops.find(shopId).map(ShopDirectory.ShopCard::name).orElse("");
        Map<UUID, Listed> mine = orders.products(shopId, cart.stream().map(CartLine::productId)
                .filter(java.util.Objects::nonNull).distinct().toList());
        List<Integer> gone = new ArrayList<>();
        List<Supply> own = new ArrayList<>();
        List<Need> short_ = new ArrayList<>();
        List<Need> missing = new ArrayList<>();
        List<Unavailable> unavailable = new ArrayList<>();
        Map<UUID, BigDecimal> taken = new LinkedHashMap<>();   // of the shop's own stock, by earlier lines
        for (int i = 0; i < cart.size(); i++) {
            CartLine l = cart.get(i);
            if ((l.productId() == null) == (l.catalogueItemId() == null)) {
                throw KiranaChecks.invalid("lines[" + i + "]", "Send the product or the catalogue item");
            }
            if (l.productId() != null) {
                Listed p = mine.get(l.productId());
                if (p == null) {
                    gone.add(i);
                    continue;
                }
                BigDecimal q = ProductService.quantity("lines[" + i + "].quantity", l.quantity(), p.unit(), false);
                BigDecimal left = p.stock().subtract(taken.getOrDefault(p.id(), BigDecimal.ZERO)).max(BigDecimal.ZERO);
                BigDecimal mineQ = q.min(left);
                if (mineQ.signum() > 0) {
                    own.add(new Supply(p.id(), p.catalogueItemId(), p.name(), p.unit(), Quantities.stored(mineQ),
                            p.pricePaise()));
                    taken.merge(p.id(), mineQ, BigDecimal::add);
                }
                BigDecimal rest = q.subtract(mineQ);
                if (rest.signum() > 0) {
                    Need n = new Need(i, p.catalogueItemId(), p.name(), p.unit(), Quantities.stored(rest));
                    if (p.catalogueItemId() == null) {
                        unavailable.add(new Unavailable(i, p.name(), Quantities.json(rest)));
                    } else if (mineQ.signum() > 0) {
                        short_.add(n);   // some of it here: the rest comes in the same delivery
                    } else {
                        missing.add(n);  // none here: the customer chooses how it comes
                    }
                }
            } else {
                String field = "lines[" + i + "].catalogueItemId";
                CatalogueStore.Item item = catalogue.find(l.catalogueItemId()).orElseThrow(() -> KiranaChecks.invalid(
                        field, "No such item"));
                BigDecimal q = ProductService.quantity("lines[" + i + "].quantity", l.quantity(), item.unit(), false);
                missing.add(new Need(i, item.id(), item.name(), item.unit(), q));
            }
        }
        if (!gone.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "PRODUCT_GONE",
                    "Some items were removed from the shop's list. Take them out of the cart.", Map.of("lines", gone));
        }
        BigDecimal[] at = orders.location(shopId).orElseThrow();
        Set<UUID> wanted = new LinkedHashSet<>();
        short_.forEach(n -> wanted.add(n.catalogueItemId()));
        missing.forEach(n -> wanted.add(n.catalogueItemId()));
        List<Offer> near = orders.offers(wanted, shopId, at[0], at[1], NETWORK_METRES, today);

        // Combined: everything through the chosen shop
        List<Need> all = new ArrayList<>(short_);
        all.addAll(missing);
        List<Unavailable> combinedLeft = new ArrayList<>(unavailable);
        List<PlannedPart> suppliers = assign(all, near, "SUPPLIER", combinedLeft, MAX_OTHER_SHOPS, new HashMap<>());
        List<PlannedPart> combinedParts = new ArrayList<>();
        combinedParts.add(new PlannedPart(shopId, shopName, "OWN", own));
        combinedParts.addAll(suppliers);
        Plan combined = plan(List.of(new PlannedOrder(shopId, shopName, combinedParts)));

        Plan separate = null;
        List<Unavailable> separateLeft = new ArrayList<>(unavailable);
        if (!missing.isEmpty()) {
            Map<UUID, BigDecimal> promised = new HashMap<>();   // one shop's stock promised once across both
            List<PlannedPart> shortSuppliers = assign(short_, near, "SUPPLIER", separateLeft, MAX_OTHER_SHOPS, promised);
            List<PlannedPart> firstParts = new ArrayList<>();
            firstParts.add(new PlannedPart(shopId, shopName, "OWN", own));
            firstParts.addAll(shortSuppliers);
            List<PlannedOrder> list = new ArrayList<>();
            PlannedOrder first = new PlannedOrder(shopId, shopName, firstParts);
            if (!first.empty()) {
                list.add(first);
            }
            List<Offer> aroundCustomer = orders.offers(missing.stream().map(Need::catalogueItemId).distinct().toList(),
                    shopId, lat, lng, CUSTOMER_METRES, today);
            // At most 2 other shops in the whole plan: those handing items over count too
            for (PlannedPart p : assign(missing, aroundCustomer, "OWN", separateLeft,
                    MAX_OTHER_SHOPS - shortSuppliers.size(), promised)) {
                list.add(new PlannedOrder(p.shopId(), p.shopName(), List.of(p)));
            }
            separate = list.isEmpty() ? null : plan(list);
        }
        return new Quote(shopId, shopName, combined.itemsPaise() > 0 ? combined : null, separate, separate != null,
                sorted(combinedLeft), sorted(separateLeft));
    }

    private static List<Unavailable> sorted(List<Unavailable> list) {
        return list.stream().sorted(Comparator.comparingInt(Unavailable::line)).toList();
    }

    /**
     * Gives the needs to the offering shops: the shop covering the most items first (then the nearest), at most
     * {@code maxShops}, each item at that shop's lowest price; what is left goes to {@code left}. {@code taken}: what of
     * each product is promised already (two cart lines for the same item never promise more than a shop has).
     */
    private static List<PlannedPart> assign(List<Need> needs, List<Offer> offers, String role, List<Unavailable> left,
                                            int maxShops, Map<UUID, BigDecimal> taken) {
        Map<Need, BigDecimal> open = new LinkedHashMap<>();
        needs.forEach(n -> open.put(n, n.quantity()));
        Map<UUID, List<Offer>> byShop = new LinkedHashMap<>();
        Map<UUID, Double> distance = new LinkedHashMap<>();
        for (Offer o : offers) {
            byShop.computeIfAbsent(o.shopId(), k -> new ArrayList<>()).add(o);
            distance.putIfAbsent(o.shopId(), o.metres());
        }
        List<PlannedPart> parts = new ArrayList<>();
        Set<UUID> used = new LinkedHashSet<>();
        while (!open.isEmpty() && parts.size() < maxShops) {
            UUID best = null;
            int bestCount = 0;
            for (Map.Entry<UUID, List<Offer>> e : byShop.entrySet()) {
                if (used.contains(e.getKey())) {
                    continue;
                }
                int count = (int) open.keySet().stream().filter(n -> offerFor(e.getValue(), n, taken).isPresent())
                        .count();
                if (count > bestCount || (count == bestCount && count > 0 && best != null
                        && distance.get(e.getKey()) < distance.get(best))) {
                    best = e.getKey();
                    bestCount = count;
                }
            }
            if (best == null) {
                break;
            }
            used.add(best);
            List<Supply> lines = new ArrayList<>();
            String name = "";
            for (Need n : new ArrayList<>(open.keySet())) {
                Optional<Offer> o = offerFor(byShop.get(best), n, taken);
                if (o.isEmpty()) {
                    continue;
                }
                BigDecimal give = open.get(n).min(remaining(o.get(), taken));
                if (n.unit() == Unit.PIECE) {
                    give = give.setScale(0, java.math.RoundingMode.DOWN);
                }
                if (give.signum() <= 0) {
                    continue;
                }
                name = o.get().shopName();
                taken.merge(o.get().productId(), give, BigDecimal::add);
                lines.add(new Supply(o.get().productId(), n.catalogueItemId(), o.get().name(), n.unit(),
                        Quantities.stored(give), o.get().pricePaise()));
                BigDecimal rest = open.get(n).subtract(give);
                if (rest.signum() > 0) {
                    open.put(n, rest);
                } else {
                    open.remove(n);
                }
            }
            if (!lines.isEmpty()) {
                parts.add(new PlannedPart(best, name, role, lines));
            }
        }
        open.forEach((n, q) -> left.add(new Unavailable(n.line(), n.name(), Quantities.json(q))));
        return parts;
    }

    /** The shop's cheapest offer for a need: the same catalogue item, the same unit, some left in stock. */
    private static Optional<Offer> offerFor(List<Offer> offers, Need n, Map<UUID, BigDecimal> taken) {
        return offers.stream().filter(o -> o.catalogueItemId().equals(n.catalogueItemId()) && o.unit() == n.unit()
                && remaining(o, taken).signum() > 0).min(Comparator.comparingLong(Offer::pricePaise));
    }

    private static BigDecimal remaining(Offer o, Map<UUID, BigDecimal> taken) {
        return o.stock().subtract(taken.getOrDefault(o.productId(), BigDecimal.ZERO));
    }

    private static Plan plan(List<PlannedOrder> list) {
        long items = list.stream().mapToLong(PlannedOrder::itemsPaise).sum();
        return new Plan(List.copyOf(list), items, PLATFORM_PAISE);
    }

    // ---------- views ----------

    public static QuoteView view(Quote q) {
        return new QuoteView(q.shopId(), q.shopName(), q.combined() == null ? null : view(q.combined()),
                q.separate() == null ? null : view(q.separate()), q.choose(), q.unavailable(),
                q.separate() == null ? List.of() : q.separateUnavailable());
    }

    static PlanView view(Plan p) {
        return new PlanView(p.orders().stream().map(o -> {
            List<ItemLine> lines = new ArrayList<>();
            int no = 0;
            for (PlannedPart part : o.parts()) {
                for (Supply s : part.lines()) {
                    lines.add(new ItemLine(++no, s.productId(), s.catalogueItemId(), s.name(), s.unit(),
                            Quantities.json(s.quantity()), s.pricePaise(), s.amountPaise(), "SUPPLIER".equals(part.role()),
                            false));
                }
            }
            return new PlanOrder(o.shopId(), o.shopName(), lines, o.itemsPaise());
        }).toList(), p.itemsPaise(), p.platformPaise(), p.totalPaise());
    }
}
