package in.apnidukan.kirana.service;

import in.apnidukan.common.context.ShopDirectory;
import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.text.TextCleaner;
import in.apnidukan.common.text.TextCleaner.TextField;
import in.apnidukan.kirana.api.KiranaRequests.ChangePayment;
import in.apnidukan.kirana.api.KiranaRequests.Line;
import in.apnidukan.kirana.api.KiranaRequests.NewBill;
import in.apnidukan.kirana.api.KiranaRequests.NewReturn;
import in.apnidukan.kirana.api.KiranaRequests.PaidBy;
import in.apnidukan.kirana.api.KiranaRequests.Payment;
import in.apnidukan.kirana.api.KiranaRequests.ReturnLine;
import in.apnidukan.kirana.api.KiranaRequests.VoidBill;
import in.apnidukan.kirana.api.KiranaResponses.BillLineView;
import in.apnidukan.kirana.api.KiranaResponses.BillPage;
import in.apnidukan.kirana.api.KiranaResponses.BillResult;
import in.apnidukan.kirana.api.KiranaResponses.BillSummary;
import in.apnidukan.kirana.api.KiranaResponses.BillView;
import in.apnidukan.kirana.api.KiranaResponses.CancelResult;
import in.apnidukan.kirana.api.KiranaResponses.ChangeResult;
import in.apnidukan.kirana.api.KiranaResponses.ChangeView;
import in.apnidukan.kirana.api.KiranaResponses.DaySummary;
import in.apnidukan.kirana.api.KiranaResponses.KhataCustomerView;
import in.apnidukan.kirana.api.KiranaResponses.KhataRef;
import in.apnidukan.kirana.api.KiranaResponses.ReturnLineView;
import in.apnidukan.kirana.api.KiranaResponses.ReturnResult;
import in.apnidukan.kirana.api.KiranaResponses.ReturnView;
import in.apnidukan.kirana.api.KiranaResponses.StockLevel;
import in.apnidukan.kirana.api.KiranaResponses.UpiQr;
import in.apnidukan.kirana.domain.Cursors;
import in.apnidukan.kirana.domain.KhataCustomer;
import in.apnidukan.kirana.domain.Product;
import in.apnidukan.kirana.domain.Quantities;
import in.apnidukan.kirana.domain.Unit;
import in.apnidukan.kirana.repo.BillStore;
import in.apnidukan.kirana.repo.BillStore.Bill;
import in.apnidukan.kirana.repo.BillStore.Return;
import in.apnidukan.kirana.repo.BillStore.Summary;
import in.apnidukan.kirana.repo.KhataStore;
import in.apnidukan.kirana.repo.KiranaSettingsStore;
import in.apnidukan.kirana.repo.ProductStore;
import in.apnidukan.kirana.repo.ProductStore.Move;
import in.apnidukan.kirana.service.KiranaAccess.Caller;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static in.apnidukan.kirana.service.KiranaChecks.PAGE;
import static in.apnidukan.kirana.service.KiranaChecks.invalid;

/**
 * Walk-in bills at the kirana's counter. The customer pays the shop directly (cash, or the shop's own UPI QR) or buys
 * on the shop's khata, all or part; Clozest only records it. Saving a bill takes the stock down and, on khata, adds to
 * what the customer owes, all in one transaction. Afterwards a bill can be cancelled, have items returned, or have its
 * payment changed: by the owner any time, by the helper who made it within 10 minutes.
 *
 * <p>Locks, always in this order, so no two requests wait on each other in a circle: the shop's bill counter (new
 * bills) or the bill's own row (changes to a bill), then its products in id order, then khata customers in id order.
 */
@Service
public class BillService {

    /** What a helper may take off a bill (a round-off); more is the owner's: ₹10. */
    static final long HELPER_DISCOUNT_PAISE = 1_000;

    /** How long the helper who made a bill may still cancel it, take a return on it or change its payment. */
    static final Duration HELPER_WINDOW = Duration.ofMinutes(10);

    /** The furthest a product's stock may go either side of zero (its column holds 12 digits, 3 after the point). */
    private static final BigDecimal STOCK_BOUND = new BigDecimal("999999999");

    private final KiranaAccess access;
    private final KiranaSettingsStore settings;
    private final ProductStore products;
    private final BillStore bills;
    private final KhataStore khata;
    private final UserDirectory users;
    private final ShopDirectory shops;
    private final Clock clock;

    public BillService(KiranaAccess access, KiranaSettingsStore settings, ProductStore products, BillStore bills,
                       KhataStore khata, UserDirectory users, ShopDirectory shops, Clock clock) {
        this.shops = shops;
        this.access = access;
        this.settings = settings;
        this.products = products;
        this.bills = bills;
        this.khata = khata;
        this.users = users;
        this.clock = clock;
    }

    /**
     * Saves a walk-in bill (owner or helper) and returns it with each listed product's stock before and after. Prices
     * of listed products come from the catalogue; a loose (kg) product may be billed by the amount asked for, and an
     * item not in the list by its name and amount (its stock isn't kept). A bill whose clientRef was already saved
     * returns that first bill (repeated: true) and changes nothing, whatever this one says.
     *
     * <p>Checks, in order: the payment's fields fit together (400); a helper takes at most ₹10 off (403 OWNER_ONLY);
     * every line is well formed and its quantity suits its product's unit (400 naming lines[i]...); every product is
     * this shop's and not removed (409 PRODUCT_GONE naming them); the discount is at most the lines' total and what is
     * paid now less than the bill's total (400); the khata customer is this shop's and in the book (404
     * KHATA_CUSTOMER_NOT_FOUND) and what goes on their khata keeps them within their limit (409 KHATA_LIMIT with what
     * is still available). Stock may go below zero: a sale at the counter is never refused for the app's count.
     */
    @Transactional
    public BillResult create(UUID userId, NewBill in) {
        Caller c = access.caller(userId);
        requirePaymentFields(in.payment(), in.khataCustomerId(), in.paidNowPaise(), in.paidNowBy());
        long discount = in.discountPaise() == null ? 0 : in.discountPaise();
        if (discount > HELPER_DISCOUNT_PAISE && !c.isOwner()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER_ONLY",
                    "Only the owner can take more than ₹" + KhataService.rupeesOf(HELPER_DISCOUNT_PAISE) + " off a bill.",
                    Map.of("maxDiscountPaise", HELPER_DISCOUNT_PAISE));
        }
        requireLineFields(in.lines());
        // Every bill of the shop waits here for the one before: each gets the next number, and a repeat finds the first
        long billNo = settings.lockNextBillNo(c.shopId());
        Optional<Bill> earlier = bills.findByClientRef(c.shopId(), in.clientRef());
        if (earlier.isPresent()) {
            Bill first = earlier.get();
            UpiQr qr = upiQr(first);
            return new BillResult(view(first), levels(products.billMoves(first.id(), "SALE")), khataNow(first), qr,
                    needsQr(first) && qr == null, true);
        }
        List<UUID> ids = in.lines().stream().map(Line::productId).filter(Objects::nonNull).distinct().toList();
        Map<UUID, Product> locked = products.lockAll(c.shopId(), ids).stream()
                .collect(Collectors.toMap(Product::id, Function.identity()));
        List<UUID> gone = ids.stream().filter(id -> !locked.containsKey(id) || !locked.get(id).active()).toList();
        // Quantities first (a typing mistake is fixed on the spot), then products that went away meanwhile
        List<BillStore.Line> lines = new ArrayList<>();
        Map<UUID, BigDecimal> sold = new TreeMap<>();   // product → total quantity on this bill, in lock order
        long subtotal = 0;
        for (int i = 0; i < in.lines().size(); i++) {
            Line l = in.lines().get(i);
            BillStore.Line line;
            if (l.productId() == null) {
                String name = TextCleaner.clean(new TextField("lines[" + i + "].name", 2, 100), l.name());
                Optional<Product> listed = products.findByName(c.shopId(), name);
                if (listed.isPresent()) {
                    throw new ApiException(HttpStatus.CONFLICT, "ITEM_IN_LIST", "\"" + listed.get().name()
                            + "\" is in your list: scan or pick it, so its price and stock are right.",
                            Map.of("line", i, "productId", listed.get().id()));
                }
                BigDecimal q = ProductService.quantity("lines[" + i + "].quantity",
                        l.quantity() == null ? BigDecimal.ONE : l.quantity(), Unit.PIECE, false);
                line = new BillStore.Line(i + 1, null, name, Unit.PIECE, q, l.amountPaise(),
                        Quantities.amount(l.amountPaise(), q), BigDecimal.ZERO);
            } else {
                Product p = locked.get(l.productId());
                if (p == null) {
                    continue;
                }
                line = productLine(i, l, p);
                sold.merge(p.id(), line.quantity(), BigDecimal::add);
            }
            lines.add(line);
            subtotal = Math.addExact(subtotal, line.amountPaise());
        }
        if (!gone.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "PRODUCT_GONE",
                    "Some items were removed from your list meanwhile. Take them off the bill and save again.",
                    Map.of("productIds", gone));
        }
        if (discount > subtotal) {
            throw invalid("discountPaise", "Can be at most the bill's total (₹" + KhataService.rupeesOf(subtotal) + ")");
        }
        long total = subtotal - discount;
        requirePaidNowBelow(in.paidNowPaise(), total);
        Long paidNow = in.paidNowPaise();
        long owed = in.payment() == Payment.KHATA ? total - (paidNow == null ? 0 : paidNow) : 0;
        KhataCustomer customer = null;
        if (in.payment() == Payment.KHATA) {
            customer = khata.lock(c.shopId(), in.khataCustomerId()).filter(KhataCustomer::active)
                    .orElseThrow(KhataService::notFound);
            KhataService.requireWithinLimit(customer, owed);
        }
        Instant now = KiranaChecks.now(clock);
        Bill bill = new Bill(UUID.randomUUID(), c.shopId(), billNo, in.clientRef(), subtotal, discount, total,
                in.payment().name(), customer == null ? null : customer.id(), customer == null ? null : customer.name(),
                paidNow, in.paidNowBy() == null ? null : in.paidNowBy().name(), 0, 0, userId, now, null, null);
        settings.setNextBillNo(c.shopId(), billNo + 1);
        bills.insert(bill, lines);
        List<StockLevel> levels = new ArrayList<>();
        List<Move> moves = new ArrayList<>();
        for (Map.Entry<UUID, BigDecimal> s : sold.entrySet()) {
            Product p = locked.get(s.getKey());
            BigDecimal after = Quantities.stored(p.stock().subtract(s.getValue()));
            requireStockBound(p, after);
            products.setStock(p.id(), after, now);
            moves.add(new Move(p.id(), Quantities.stored(s.getValue().negate()), after, "SALE", bill.id(), null, null));
            levels.add(level(p.id(), p.name(), p.unit(), p.stock(), after, p.lowStockAt()));
        }
        products.addMoves(c.shopId(), moves, userId, now);
        KhataCustomerView after = null;
        if (customer != null) {
            long balance = khata.addEntry(c.shopId(), customer.id(), customer.balancePaise(), "SALE", owed, bill.id(),
                    null, null, null, userId, now, UUID.randomUUID());
            after = KhataCustomerView.of(withBalance(customer, balance, now));
        }
        UpiQr qr = upiQr(bill);
        return new BillResult(view(bill, lines, List.of(), List.of()), levels, after, qr, needsQr(bill) && qr == null,
                false);
    }

    /** One of the shop's bills with its lines, returns and payment changes. 404 BILL_NOT_FOUND. */
    @Transactional(readOnly = true)
    public BillView get(UUID userId, UUID billId) {
        Caller c = access.caller(userId);
        return bills.find(c.shopId(), billId).map(this::view).orElseThrow(BillService::notFound);
    }

    /** The shop's bills, newest first, a page at a time. */
    @Transactional(readOnly = true)
    public BillPage list(UUID userId, String cursor) {
        Caller c = access.caller(userId);
        Optional<String[]> before = Cursors.decode(cursor);
        Instant beforeAt = before.flatMap(b -> instant(b[0])).orElse(null);
        List<Summary> rows = bills.page(c.shopId(), beforeAt,
                beforeAt == null ? null : ProductService.uuid(before.get()[1]), PAGE);
        boolean more = rows.size() > PAGE;
        List<Summary> page = more ? rows.subList(0, PAGE) : rows;
        Summary last = page.isEmpty() ? null : page.get(page.size() - 1);
        return new BillPage(page.stream().map(s -> new BillSummary(s.id(), s.billNo(), s.totalPaise(),
                        s.discountPaise(), s.payment(), s.khataName(), s.paidNowPaise(), s.lines(), s.preview(),
                        s.createdAt(), s.voided(), s.refundedPaise(), s.changed())).toList(),
                more ? Cursors.encode(last.createdAt().toString(), last.id().toString()) : null);
    }

    /**
     * Cancels a bill (the owner any time; the helper who made it within 10 minutes, else 403 OWNER_ONLY): its stock
     * goes back and what it put on a khata comes off (their balance may go below zero if they had already paid: an
     * advance, used up by their next khata sale). A customer taken out of the book meanwhile is put back in it, so
     * their advance stays in sight (without their phone if someone else in the book has it now). 404 BILL_NOT_FOUND;
     * 409 BILL_VOIDED if it was already cancelled; 409 BILL_HAS_RETURNS once items of it came back (return the rest
     * instead). The day's totals show it on the day it was cancelled: its cash and UPI (a khata bill's part paid now
     * too) as handed back, its khata part as taken off.
     */
    @Transactional
    public CancelResult cancel(UUID userId, UUID billId, VoidBill in) {
        Caller c = access.caller(userId);
        String reason = TextCleaner.clean(KiranaChecks.VOID_REASON, in.reason());
        Bill bill = bills.lock(c.shopId(), billId).orElseThrow(BillService::notFound);
        Instant now = KiranaChecks.now(clock);
        requireMayChange(c, bill, now, "cancel this bill");
        requireOpen(bill);
        if (bill.refundedPaise() > 0 || bills.hasReturns(bill.id())) {
            throw new ApiException(HttpStatus.CONFLICT, "BILL_HAS_RETURNS",
                    "Items of this bill were returned already. Return the rest instead of cancelling it.");
        }
        List<BillStore.Line> lines = bills.lines(bill.id());
        Map<UUID, BigDecimal> back = new TreeMap<>();
        lines.stream().filter(l -> l.productId() != null)
                .forEach(l -> back.merge(l.productId(), l.quantity(), BigDecimal::add));
        List<StockLevel> levels = stockBack(c.shopId(), back, "VOID", bill.id(), reason, userId, now);
        KhataCustomerView customerNow = null;
        if (bill.khataCustomerId() != null) {
            KhataCustomer customer = khata.lock(c.shopId(), bill.khataCustomerId()).orElseThrow();
            long balance = khata.addEntry(c.shopId(), customer.id(), customer.balancePaise(), "VOID",
                    -bill.onKhataPaise(), bill.id(), null, null, reason, userId, now, UUID.randomUUID());
            customerNow = KhataCustomerView.of(withBalance(inBook(customer, now), balance, now));
        }
        bills.markVoided(bill.id(), userId, reason, now);
        Bill voided = new Bill(bill.id(), bill.shopId(), bill.billNo(), bill.clientRef(), bill.subtotalPaise(),
                bill.discountPaise(), bill.totalPaise(), bill.payment(), bill.khataCustomerId(), bill.khataName(),
                bill.paidNowPaise(), bill.paidNowBy(), bill.refundedPaise(), bill.version() + 1, bill.by(),
                bill.createdAt(), now, reason);
        return new CancelResult(view(voided, lines, List.of(), bills.changes(bill.id())), levels, customerNow);
    }

    /**
     * Changes how a bill was paid (UPI failed and they paid cash; put on khata after all; part paid now): the owner any
     * time, the helper who made it within 10 minutes (else 403 OWNER_ONLY). What it put on a khata comes off that
     * customer, and what it puts on one now goes on (within their limit: 409 KHATA_LIMIT). The bill keeps its number
     * and its day; the change is kept with it and counts in the day's totals on the day it was made. The same payment
     * as now changes nothing (also when sent again with the old version). 400 as for a new bill; 404 BILL_NOT_FOUND /
     * KHATA_CUSTOMER_NOT_FOUND (a customer taken out of the book, unless it stays on the same one); 409 BILL_CHANGED
     * (stale version); 409 BILL_VOIDED; 409 BILL_HAS_RETURNS once items of it came back; 409 KHATA_ALREADY_PAID when
     * the customer's payments already cover what comes off their khata, so they'd be left an advance (send advance:
     * true to keep it so). The limit is checked only when it puts more on a khata than before.
     */
    @Transactional
    public ChangeResult changePayment(UUID userId, UUID billId, ChangePayment in) {
        Caller c = access.caller(userId);
        requirePaymentFields(in.payment(), in.khataCustomerId(), in.paidNowPaise(), in.paidNowBy());
        Bill bill = bills.lock(c.shopId(), billId).orElseThrow(BillService::notFound);
        Instant now = KiranaChecks.now(clock);
        requireMayChange(c, bill, now, "change how this bill was paid");
        requireOpen(bill);
        if (bill.refundedPaise() > 0 || bills.hasReturns(bill.id())) {
            throw new ApiException(HttpStatus.CONFLICT, "BILL_HAS_RETURNS",
                    "Items of this bill were returned already, so its payment can't be changed.");
        }
        requirePaidNowBelow(in.paidNowPaise(), bill.totalPaise());
        String paidNowBy = in.paidNowBy() == null ? null : in.paidNowBy().name();
        if (in.payment().name().equals(bill.payment()) && Objects.equals(in.khataCustomerId(), bill.khataCustomerId())
                && Objects.equals(in.paidNowPaise(), bill.paidNowPaise()) && Objects.equals(paidNowBy,
                bill.paidNowBy())) {
            UpiQr qr = upiQr(bill);
            return new ChangeResult(view(bill), List.of(), qr, needsQr(bill) && qr == null);
        }
        if (bill.version() != in.version()) {
            throw new ApiException(HttpStatus.CONFLICT, "BILL_CHANGED",
                    "Someone changed this bill just now. Reload it and try again.");
        }
        UUID from = bill.khataCustomerId();
        UUID to = in.khataCustomerId();
        Map<UUID, KhataCustomer> customers = new HashMap<>();
        for (UUID id : new TreeSet<>(Stream.of(from, to).filter(Objects::nonNull).toList())) {
            khata.lock(c.shopId(), id).ifPresent(k -> customers.put(id, k));
        }
        if (to != null && (customers.get(to) == null || (!customers.get(to).active() && !to.equals(from)))) {
            throw KhataService.notFound();
        }
        long oldOwed = bill.onKhataPaise();
        long newOwed = in.payment() == Payment.KHATA
                ? bill.totalPaise() - (in.paidNowPaise() == null ? 0 : in.paidNowPaise()) : 0;
        Map<UUID, Long> balances = new LinkedHashMap<>();
        customers.forEach((id, k) -> balances.put(id, k.balancePaise()));
        if (from != null) {
            balances.merge(from, -oldOwed, Long::sum);
        }
        if (to != null && !(to.equals(from) && newOwed <= oldOwed)) {
            KhataCustomer k = customers.get(to);
            KhataService.requireWithinLimit(withBalance(k, balances.get(to), now), newOwed);
        }
        if (from != null) {
            long before = customers.get(from).balancePaise();
            long finalFrom = balances.get(from) + (from.equals(to) ? newOwed : 0);
            if (finalFrom < 0 && finalFrom < before && !Boolean.TRUE.equals(in.advance())) {
                throw new ApiException(HttpStatus.CONFLICT, "KHATA_ALREADY_PAID", customers.get(from).name()
                        + "'s khata payments already cover this bill. Changing it leaves them ₹"
                        + KhataService.rupeesOf(-finalFrom) + " advance. Undo their payment first, or keep it as "
                        + "their advance.", Map.of("advanceAfterPaise", -finalFrom));
            }
        }
        // Checked: now write. Off the old khata first, then onto the new one
        Map<UUID, Long> after = new LinkedHashMap<>();
        if (from != null && bill.onKhataPaise() != 0) {
            KhataCustomer k = customers.get(from);
            after.put(from, khata.addEntry(c.shopId(), from, k.balancePaise(), "CHANGE", -bill.onKhataPaise(),
                    bill.id(), null, null, null, userId, now, UUID.randomUUID()));
        }
        if (to != null) {
            long before = after.getOrDefault(to, customers.get(to).balancePaise());
            after.put(to, khata.addEntry(c.shopId(), to, before, "CHANGE", newOwed, bill.id(), null, null, null,
                    userId, now, UUID.randomUUID()));
        }
        bills.changePayment(bill, in.payment().name(), to, in.paidNowPaise(), paidNowBy, userId, now);
        Bill changed = bills.find(c.shopId(), bill.id()).orElseThrow();
        List<KhataCustomerView> touched = new ArrayList<>();
        for (Map.Entry<UUID, Long> e : after.entrySet()) {
            touched.add(KhataCustomerView.of(withBalance(inBook(customers.get(e.getKey()), now), e.getValue(), now)));
        }
        UpiQr qr = upiQr(changed);
        return new ChangeResult(view(changed), touched, qr, needsQr(changed) && qr == null);
    }

    /**
     * Takes items of a bill back: the owner any time, the helper who made it within 10 minutes (else 403 OWNER_ONLY).
     * Listed products go back into stock. The money goes back as the bill was paid: cash or UPI handed back; for a khata
     * bill off the khata first, up to what the bill still has on it, and the rest (its part paid now) handed back as
     * it was paid. It is each line's share of the bill after its discount; the last return of a bill gives back
     * exactly what is left of its total. A return
     * whose clientRef was made before returns that first one (repeated: true). 400 for a line the bill doesn't have,
     * named twice, or more than is left of it (lines[i]...); 404 BILL_NOT_FOUND; 409 BILL_VOIDED.
     */
    @Transactional
    public ReturnResult returnItems(UUID userId, UUID billId, NewReturn in) {
        Caller c = access.caller(userId);
        String reason = KiranaChecks.optional(KiranaChecks.NOTE, in.reason());
        Bill bill = bills.lock(c.shopId(), billId).orElseThrow(BillService::notFound);
        // Under the bill's lock: the same return sent twice at once waits for the first and then finds it here
        Optional<Return> earlier = bills.returnByClientRef(c.shopId(), in.clientRef());
        if (earlier.isPresent()) {
            Return first = earlier.get();
            Bill of = first.billId().equals(bill.id()) ? bill : bills.find(c.shopId(), first.billId()).orElseThrow();
            return new ReturnResult(view(of), returnView(first, KiranaChecks.names(users, List.of(first.by()))),
                    levels(products.billMoves(of.id(), "RETURN", first.at())), khataNow(of), true);
        }
        Instant now = KiranaChecks.now(clock);
        requireMayChange(c, bill, now, "take a return on this bill");
        requireOpen(bill);
        List<BillStore.Line> lines = bills.lines(bill.id());
        Map<Integer, BillStore.Line> byNo = lines.stream()
                .collect(Collectors.toMap(BillStore.Line::lineNo, Function.identity()));
        Map<Integer, Long> valueBack = bills.returnedValues(bill.id());
        Set<Integer> named = new HashSet<>();
        List<BillStore.ReturnLine> out = new ArrayList<>();
        Map<UUID, BigDecimal> back = new TreeMap<>();
        Map<Integer, BigDecimal> returnedAfter = new HashMap<>();
        long value = 0;
        for (int i = 0; i < in.lines().size(); i++) {
            ReturnLine r = in.lines().get(i);
            BillStore.Line l = byNo.get(r.lineNo());
            if (l == null) {
                throw invalid("lines[" + i + "].lineNo", "This bill has no line " + r.lineNo());
            }
            if (!named.add(r.lineNo())) {
                throw invalid("lines[" + i + "].lineNo", "Line " + r.lineNo() + " is named twice");
            }
            BigDecimal q = ProductService.quantity("lines[" + i + "].quantity", r.quantity(), l.unit(), false);
            BigDecimal left = l.quantity().subtract(l.returnedQuantity());
            if (q.compareTo(left) > 0) {
                throw invalid("lines[" + i + "].quantity", left.signum() == 0 ? "All of it came back already"
                        : "At most " + Quantities.plain(left) + (l.unit() == Unit.KG ? " kg" : "") + " is left to return");
            }
            long valueLeft = l.amountPaise() - valueBack.getOrDefault(l.lineNo(), 0L);
            long v = q.compareTo(left) == 0 ? valueLeft : Math.min(valueLeft, BigDecimal.valueOf(l.amountPaise())
                    .multiply(q).divide(l.quantity(), 0, RoundingMode.HALF_UP).longValueExact());
            out.add(new BillStore.ReturnLine(l.lineNo(), q, v));
            value = Math.addExact(value, v);
            returnedAfter.put(l.lineNo(), l.returnedQuantity().add(q));
            if (l.productId() != null) {
                back.merge(l.productId(), q, BigDecimal::add);
            }
        }
        boolean allBack = lines.stream().allMatch(l -> returnedAfter.getOrDefault(l.lineNo(), l.returnedQuantity())
                .compareTo(l.quantity()) == 0);
        long refundLeft = bill.totalPaise() - bill.refundedPaise();
        long refund = allBack ? refundLeft : bill.subtotalPaise() == 0 ? 0 : Math.min(refundLeft,
                BigDecimal.valueOf(value).multiply(BigDecimal.valueOf(bill.totalPaise()))
                        .divide(BigDecimal.valueOf(bill.subtotalPaise()), 0, RoundingMode.HALF_UP).longValueExact());
        long offKhata = 0;
        if ("KHATA".equals(bill.payment())) {
            long khataLeft = Math.max(0, bill.onKhataPaise() - bills.khataRefunded(bill.id()));
            offKhata = bill.paidNowBy() == null ? refund : Math.min(refund, khataLeft);
        }
        long handed = refund - offKhata;
        String handedBy = handed == 0 ? null : "KHATA".equals(bill.payment()) ? bill.paidNowBy() : bill.payment();
        Return r = new Return(UUID.randomUUID(), bill.id(), in.clientRef(), refund, offKhata, handed, handedBy, reason,
                userId, now, out);
        try {
            bills.addReturn(c.shopId(), r);
        } catch (DataIntegrityViolationException e) {
            // The same clientRef used at this moment for another bill of the shop: the app's mistake, not ours
            throw new ApiException(HttpStatus.CONFLICT, "RETURN_REPEATED", "This return was already made. Reload the bill.");
        }
        List<StockLevel> levels = stockBack(c.shopId(), back, "RETURN", bill.id(), reason, userId, now);
        KhataCustomerView customerNow = null;
        if (bill.khataCustomerId() != null) {
            KhataCustomer customer = khata.lock(c.shopId(), bill.khataCustomerId()).orElseThrow();
            if (offKhata == 0) {
                customerNow = KhataCustomerView.of(customer);
            } else {
                long balance = khata.addEntry(c.shopId(), customer.id(), customer.balancePaise(), "RETURN", -offKhata,
                        bill.id(), null, null, reason, userId, now, UUID.randomUUID());
                customerNow = KhataCustomerView.of(withBalance(inBook(customer, now), balance, now));
            }
        }
        return new ReturnResult(view(bills.find(c.shopId(), bill.id()).orElseThrow()),
                returnView(r, KiranaChecks.names(users, List.of(userId))), levels, customerNow, false);
    }

    /**
     * One India day at the counter (today if {@code date} is null): bills by how they were paid, khata payments
     * received (cash and UPI apart), returns, the cash taken, and bills cancelled that day. 400 naming {@code date}
     * more than 2 years away.
     */
    @Transactional(readOnly = true)
    public DaySummary day(UUID userId, LocalDate date) {
        Caller c = access.caller(userId);
        LocalDate today = KiranaChecks.today(clock.instant());
        LocalDate d = date == null ? today : date;
        if (d.isBefore(today.minusYears(2)) || d.isAfter(today.plusYears(2))) {
            throw invalid("date", "Pick a day within two years of today");
        }
        Instant from = KiranaChecks.dayStart(d);
        Instant to = KiranaChecks.dayStart(d.plusDays(1));
        BillStore.Money sales = bills.sales(c.shopId(), from, to);
        BillStore.Money changes = bills.changes(c.shopId(), from, to);
        long[] received = khata.received(c.shopId(), from, to);
        BillStore.Money returned = bills.returned(c.shopId(), from, to);
        BillStore.Money cancelled = bills.cancelled(c.shopId(), from, to);
        long cashIn = sales.cashPaise() + changes.cashPaise() + received[0] - returned.cashPaise()
                - cancelled.cashPaise();
        long upiIn = sales.upiPaise() + changes.upiPaise() + received[1] - returned.upiPaise() - cancelled.upiPaise();
        return new DaySummary(d, sales.count(), sales.totalPaise(), bills.discounts(c.shopId(), from, to),
                sales.cashPaise(), sales.upiPaise(), sales.khataPaise(), changes.count(), changes.cashPaise(),
                changes.upiPaise(), changes.khataPaise(), received[0] + received[1], received[0], received[1],
                returned.count(), returned.totalPaise(), returned.cashPaise(), returned.upiPaise(),
                returned.khataPaise(), cancelled.count(), cancelled.totalPaise(), cancelled.cashPaise(),
                cancelled.upiPaise(), cancelled.khataPaise(), cashIn, upiIn);
    }

    // ---------- checks ----------

    /** The payment's fields fit together: a khata customer for KHATA only; paid now (with how) for KHATA only. */
    private static void requirePaymentFields(Payment payment, UUID khataCustomerId, Long paidNow, PaidBy by) {
        if ((payment == Payment.KHATA) != (khataCustomerId != null)) {
            throw invalid("khataCustomerId", payment == Payment.KHATA ? "Pick the khata customer"
                    : "Only for a khata bill");
        }
        if ((paidNow != null || by != null) && payment != Payment.KHATA) {
            throw invalid(paidNow != null ? "paidNowPaise" : "paidNowBy",
                    "Only for a khata bill paid in part now");
        }
        if ((paidNow == null) != (by == null)) {
            throw invalid(paidNow == null ? "paidNowPaise" : "paidNowBy",
                    paidNow == null ? "Enter what they paid now" : "Choose cash or UPI for what they paid now");
        }
    }

    /** What is paid now of a khata bill is less than its total (all of it is a cash or UPI bill). */
    private static void requirePaidNowBelow(Long paidNow, long total) {
        if (paidNow != null && paidNow >= total) {
            throw invalid("paidNowPaise", "Must be less than the bill's total (₹" + KhataService.rupeesOf(total)
                    + "). If they paid it all, choose cash or UPI");
        }
    }

    /** Each line names a product (by quantity, or a kg product by amount) or an item not in the list (name, amount). */
    private static void requireLineFields(List<Line> lines) {
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            String at = "lines[" + i + "]";
            if (l.productId() == null) {
                if (l.name() == null || l.name().isBlank()) {
                    throw invalid(at + ".name", "Enter the item's name, or pick it from your list");
                }
                if (l.amountPaise() == null) {
                    throw invalid(at + ".amountPaise", "Enter its price");
                }
            } else {
                if (l.name() != null) {
                    throw invalid(at + ".name", "Only for an item not in your list");
                }
                if ((l.quantity() == null) == (l.amountPaise() == null)) {
                    throw invalid(at + ".quantity", l.quantity() == null ? "Enter how many, or the amount for loose items"
                            : "Send how many or the amount, not both");
                }
            }
        }
    }

    /** A listed product's line: by quantity, or (kg only) by the amount asked for, the weight worked out to the gram. */
    private static BillStore.Line productLine(int i, Line l, Product p) {
        if (l.amountPaise() == null) {
            BigDecimal q = ProductService.quantity("lines[" + i + "].quantity", l.quantity(), p.unit(), false);
            return new BillStore.Line(i + 1, p.id(), p.name(), p.unit(), q, p.pricePaise(),
                    Quantities.amount(p.pricePaise(), q), BigDecimal.ZERO);
        }
        if (p.unit() != Unit.KG) {
            throw invalid("lines[" + i + "].amountPaise", "Only loose items sold by kg can be billed by amount");
        }
        BigDecimal q = BigDecimal.valueOf(l.amountPaise()).divide(BigDecimal.valueOf(p.pricePaise()), 3,
                RoundingMode.HALF_UP);
        Optional<String> problem = Quantities.problem(q, Unit.KG, false);
        if (problem.isPresent()) {
            throw invalid("lines[" + i + "].amountPaise", q.signum() == 0
                    ? "That's less than a gram at ₹" + KhataService.rupeesOf(p.pricePaise()) + "/kg"
                    : "That buys more than " + Quantities.plain(Quantities.MAX) + " kg");
        }
        return new BillStore.Line(i + 1, p.id(), p.name(), Unit.KG, Quantities.stored(q), p.pricePaise(),
                l.amountPaise(), BigDecimal.ZERO);
    }

    /**
     * The owner may change any bill; a helper only one he made, within 10 minutes of making it (a mistake fixed on
     * the spot): 403 OWNER_ONLY otherwise.
     */
    private static void requireMayChange(Caller c, Bill b, Instant now, String what) {
        if (c.isOwner()) {
            return;
        }
        if (b.by().equals(c.userId()) && !now.isAfter(b.createdAt().plus(HELPER_WINDOW))) {
            return;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "OWNER_ONLY", "Only the owner can " + what
                + " now. A helper can, on his own bill, within " + HELPER_WINDOW.toMinutes() + " minutes of making it.",
                Map.of("helperMinutes", HELPER_WINDOW.toMinutes()));
    }

    /** 409 STOCK_OUT_OF_RANGE before a stock the column can't hold (a count far off; the owner counts it again). */
    private static void requireStockBound(Product p, BigDecimal after) {
        if (after.abs().compareTo(STOCK_BOUND) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "STOCK_OUT_OF_RANGE", "The stock of \"" + p.name()
                    + "\" is far off. Count it and set it again first.", Map.of("productId", p.id()));
        }
    }

    private static void requireOpen(Bill b) {
        if (b.voidedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "BILL_VOIDED", "This bill was already cancelled.");
        }
    }

    // ---------- writes shared by cancel and return ----------

    /** Puts these quantities back into stock (products locked in id order) and records why; their levels. */
    private List<StockLevel> stockBack(UUID shopId, Map<UUID, BigDecimal> back, String reason, UUID billId,
                                       String note, UUID by, Instant now) {
        Map<UUID, Product> locked = products.lockAll(shopId, back.keySet()).stream()
                .collect(Collectors.toMap(Product::id, Function.identity()));
        List<Move> moves = new ArrayList<>();
        List<StockLevel> levels = new ArrayList<>();
        for (Map.Entry<UUID, BigDecimal> b : back.entrySet()) {
            Product p = locked.get(b.getKey());
            BigDecimal after = Quantities.stored(p.stock().add(b.getValue()));
            requireStockBound(p, after);
            products.setStock(p.id(), after, now);
            moves.add(new Move(p.id(), Quantities.stored(b.getValue()), after, reason, billId, note, null));
            levels.add(level(p.id(), p.name(), p.unit(), p.stock(), after, p.lowStockAt()));
        }
        products.addMoves(shopId, moves, by, now);
        return levels;
    }

    /** The customer back in the book if they were taken out (the caller holds their lock and changed their balance). */
    private KhataCustomer inBook(KhataCustomer k, Instant now) {
        return k.active() ? k : khata.reactivate(k, now);
    }

    private static KhataCustomer withBalance(KhataCustomer k, long balance, Instant now) {
        return new KhataCustomer(k.id(), k.shopId(), k.name(), k.phone(), k.note(), k.creditLimitPaise(), balance,
                k.active(), k.version(), now);
    }

    // ---------- views ----------

    private BillView view(Bill b) {
        return view(b, bills.lines(b.id()), bills.returns(b.id()), bills.changes(b.id()));
    }

    private BillView view(Bill b, List<BillStore.Line> lines, List<Return> returns, List<BillStore.Change> changes) {
        Optional<BillStore.Paper> paper = bills.paper(b.id());
        Map<UUID, String> names = KiranaChecks.names(users, Stream.concat(Stream.of(b.by()),
                Stream.concat(returns.stream().map(Return::by), changes.stream().map(BillStore.Change::by)))
                .distinct().toList());
        return new BillView(b.id(), b.billNo(), b.subtotalPaise(), b.discountPaise(), b.totalPaise(), b.payment(),
                b.khataCustomerId() == null ? null : new KhataRef(b.khataCustomerId(), b.khataName()),
                b.paidNowPaise(), b.paidNowBy(), b.onKhataPaise(), b.refundedPaise(), b.version(), b.createdAt(),
                names.get(b.by()), b.voidedAt(), b.voidReason(), lines.stream()
                .map(l -> new BillLineView(l.lineNo(), l.productId(), l.name(), l.unit(), Quantities.json(l.quantity()),
                        l.pricePaise(), l.amountPaise(), Quantities.json(l.returnedQuantity())))
                .toList(),
                returns.stream().map(r -> returnView(r, names)).toList(),
                changes.stream().map(ch -> new ChangeView(ch.fromPayment(),
                        ch.fromCustomerId() == null ? null : new KhataRef(ch.fromCustomerId(), ch.fromCustomerName()),
                        ch.fromPaidNow(), ch.fromPaidNowBy(), ch.toPayment(),
                        ch.toCustomerId() == null ? null : new KhataRef(ch.toCustomerId(), ch.toCustomerName()),
                        ch.toPaidNow(), ch.toPaidNowBy(), names.get(ch.by()), ch.at())).toList(),
                paper.map(BillStore.Paper::writtenOn).orElse(null), paper.map(BillStore.Paper::photoFileId).orElse(null));
    }

    /** A bill as the app shows it (the paper bill flow returns the bill it made). */
    BillView viewOf(UUID shopId, UUID billId) {
        return view(bills.find(shopId, billId).orElseThrow());
    }

    private static ReturnView returnView(Return r, Map<UUID, String> names) {
        return new ReturnView(r.id(), r.refundPaise(), r.khataPaise(), r.handedPaise(), r.handedBy(), r.reason(),
                names.get(r.by()), r.at(),
                r.lines().stream().map(l -> new ReturnLineView(l.lineNo(), Quantities.json(l.quantity()),
                        l.valuePaise())).toList());
    }

    /** A product's stock before and after a bill, a cancellation or a return, and whether it is now running out. */
    private static StockLevel level(UUID id, String name, Unit unit, BigDecimal before, BigDecimal after,
                                    BigDecimal lowStockAt) {
        return new StockLevel(id, name, unit, Quantities.json(before), Quantities.json(after),
                after.signum() < 0 || (lowStockAt != null && after.compareTo(lowStockAt) <= 0));
    }

    /** Stock levels read back from a bill's recorded moves (a repeated bill or return shows what the first one did). */
    private static List<StockLevel> levels(List<ProductStore.BillMove> moves) {
        return moves.stream().map(m -> level(m.productId(), m.name(), Unit.valueOf(m.unit()),
                m.stockAfter().subtract(m.change()), m.stockAfter(), m.lowStockAt())).toList();
    }

    /** For a KHATA bill: the customer as they are now. */
    private KhataCustomerView khataNow(Bill b) {
        return b.khataCustomerId() == null ? null
                : khata.find(b.shopId(), b.khataCustomerId()).map(KhataCustomerView::of).orElse(null);
    }

    /**
     * The QR the counter shows (the shop's UPI ID, its name as payee, the amount): for a UPI bill its total, for a
     * khata bill part paid by UPI that part; null for other bills or before the UPI ID is set.
     */
    private UpiQr upiQr(Bill b) {
        long amountPaise;
        if ("UPI".equals(b.payment())) {
            amountPaise = b.totalPaise();
        } else if ("UPI".equals(b.paidNowBy())) {
            amountPaise = b.paidNowPaise();
        } else {
            return null;
        }
        String upi = settings.find(b.shopId()).upiId();
        if (upi == null) {
            return null;
        }
        String name = shops.find(b.shopId()).map(ShopDirectory.ShopCard::name).orElse("");
        String amount = BigDecimal.valueOf(amountPaise).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY)
                .toPlainString();
        String uri = "upi://pay?pa=" + enc(upi) + "&pn=" + enc(name) + "&am=" + amount + "&cu=INR&tn="
                + enc("Bill " + b.billNo());
        return new UpiQr(upi, name, amountPaise, uri);
    }

    /** Whether the counter shows a QR for this bill (all of it, or its part paid now, by UPI). */
    private static boolean needsQr(Bill b) {
        return "UPI".equals(b.payment()) || "UPI".equals(b.paidNowBy());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static Optional<Instant> instant(String s) {
        try {
            return Optional.of(Instant.parse(s));
        } catch (java.time.format.DateTimeParseException e) {
            return Optional.empty();
        }
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "BILL_NOT_FOUND", "No such bill.");
    }
}
