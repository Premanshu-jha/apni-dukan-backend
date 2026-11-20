package in.apnidukan.kirana.service;

import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.error.DatabaseErrors;
import in.apnidukan.common.text.TextCleaner;
import in.apnidukan.kirana.api.KiranaRequests.AdvanceBack;
import in.apnidukan.kirana.api.KiranaRequests.EditKhataCustomer;
import in.apnidukan.kirana.api.KiranaRequests.KhataPayment;
import in.apnidukan.kirana.api.KiranaRequests.NewKhataCustomer;
import in.apnidukan.kirana.api.KiranaRequests.ReversePayment;
import in.apnidukan.kirana.api.KiranaResponses.KhataCustomerView;
import in.apnidukan.kirana.api.KiranaResponses.KhataDetail;
import in.apnidukan.kirana.api.KiranaResponses.KhataEntryView;
import in.apnidukan.kirana.api.KiranaResponses.KhataPage;
import in.apnidukan.kirana.api.KiranaResponses.PaymentResult;
import in.apnidukan.kirana.domain.Cursors;
import in.apnidukan.kirana.domain.KhataCustomer;
import in.apnidukan.kirana.repo.KhataStore;
import in.apnidukan.kirana.repo.KhataStore.Entry;
import in.apnidukan.kirana.service.KiranaAccess.Caller;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static in.apnidukan.kirana.service.KiranaChecks.PAGE;

/**
 * The kirana's khata book: its regular customers who buy on credit at the counter, what each owes, and the payments
 * they make (more than they owe is kept as their advance). The shop's own record (no Clozest money): the owner and
 * helpers add customers and take payments; credit limits and undoing a payment are the owner's. Sales on khata are
 * made by bills (BillService).
 */
@Service
public class KhataService {

    /** A new customer's credit limit unless the owner sets another or none: ₹2,000. */
    static final long DEFAULT_LIMIT_PAISE = 200_000;

    /** How long the helper who recorded a payment may still undo it himself (a typing mistake fixed on the spot). */
    static final java.time.Duration HELPER_WINDOW = java.time.Duration.ofMinutes(10);

    private final KiranaAccess access;
    private final KhataStore khata;
    private final UserDirectory users;
    private final Clock clock;

    public KhataService(KiranaAccess access, KhataStore khata, UserDirectory users, Clock clock) {
        this.access = access;
        this.khata = khata;
        this.users = users;
        this.clock = clock;
    }

    /**
     * The book, a page at a time in name order, with what everyone owes together. {@code q}: a name or note containing
     * it ("1204" finds "Tower B 1204"), or digits of their phone; {@code owing}: only those who owe something.
     */
    @Transactional(readOnly = true)
    public KhataPage list(UUID userId, String q, boolean owing, String cursor) {
        Caller c = access.caller(userId);
        Optional<String[]> after = Cursors.decode(cursor);
        List<KhataStore.Row> rows = khata.page(c.shopId(), KiranaChecks.prefix(q), KiranaChecks.digits(q), owing,
                after.map(a -> a[0]).orElse(null), after.map(a -> ProductService.uuid(a[1])).orElse(null), PAGE);
        boolean more = rows.size() > PAGE;
        List<KhataStore.Row> page = more ? rows.subList(0, PAGE) : rows;
        KhataStore.Row last = page.isEmpty() ? null : page.get(page.size() - 1);
        return new KhataPage(page.stream().map(row -> KhataCustomerView.of(row.customer())).toList(),
                more ? Cursors.encode(last.sortKey(), last.customer().id().toString()) : null,
                khata.owed(c.shopId()), khata.advances(c.shopId()));
    }

    /** A customer with their entries, newest first; {@code cursor} for older ones. 404 KHATA_CUSTOMER_NOT_FOUND. */
    @Transactional(readOnly = true)
    public KhataDetail detail(UUID userId, UUID customerId, String cursor) {
        Caller c = access.caller(userId);
        KhataCustomer customer = khata.find(c.shopId(), customerId).orElseThrow(KhataService::notFound);
        Optional<String[]> before = Cursors.decode(cursor);
        Instant beforeAt = before.flatMap(b -> parse(b[0])).orElse(null);
        List<Entry> rows = khata.entries(customer.id(), beforeAt,
                beforeAt == null ? null : ProductService.uuid(before.get()[1]), PAGE);
        boolean more = rows.size() > PAGE;
        List<Entry> page = more ? rows.subList(0, PAGE) : rows;
        Map<UUID, String> names = KiranaChecks.names(users, page.stream().map(Entry::by).distinct().toList());
        Entry last = page.isEmpty() ? null : page.get(page.size() - 1);
        return new KhataDetail(KhataCustomerView.of(customer), page.stream().map(e -> view(e, names)).toList(),
                more ? Cursors.encode(last.at().toString(), last.id().toString()) : null);
    }

    /**
     * Adds a customer to the book (owner or helper), with a ₹2,000 credit limit unless the owner sets another or none
     * (a helper can't: 403 OWNER_ONLY). 409 PHONE_TAKEN (naming the customer) when another customer in the book has
     * this phone.
     */
    @Transactional
    public KhataCustomerView create(UUID userId, NewKhataCustomer in) {
        Caller c = access.caller(userId);
        if ((in.creditLimitPaise() != null || in.noLimit() != null) && !c.isOwner()) {
            throw KiranaAccess.ownerOnly();
        }
        Long limit = Boolean.TRUE.equals(in.noLimit()) ? null
                : in.creditLimitPaise() != null ? in.creditLimitPaise() : DEFAULT_LIMIT_PAISE;
        String name = TextCleaner.clean(KiranaChecks.CUSTOMER_NAME, in.name());
        String note = KiranaChecks.optional(KiranaChecks.CUSTOMER_NOTE, in.note());
        String phone = KiranaChecks.phone(in.phone());
        phone = phone == null || phone.isEmpty() ? null : phone;
        if (phone != null) {
            khata.lockPhone(c.shopId(), phone);
        }
        requirePhoneFree(c.shopId(), null, phone);
        Instant now = KiranaChecks.now(clock);
        KhataCustomer customer = new KhataCustomer(UUID.randomUUID(), c.shopId(), name, phone, note, limit, 0, true, 0,
                now);
        try {
            khata.insert(customer, userId, now);
        } catch (DataIntegrityViolationException e) {
            throw phoneTaken(e);
        }
        return KhataCustomerView.of(customer);
    }

    /**
     * Changes a customer (owner or helper; the credit limit only by the owner). 409 KHATA_CHANGED for a stale version;
     * 409 PHONE_TAKEN; 409 STILL_OWES when taking someone out of the book who owes (or is owed) money.
     */
    @Transactional
    public KhataCustomerView update(UUID userId, UUID customerId, EditKhataCustomer in) {
        Caller c = access.caller(userId);
        if ((in.creditLimitPaise() != null || in.noLimit() != null) && !c.isOwner()) {
            throw KiranaAccess.ownerOnly();
        }
        KhataCustomer old = khata.lock(c.shopId(), customerId).orElseThrow(KhataService::notFound);
        if (old.version() != in.version()) {
            throw new ApiException(HttpStatus.CONFLICT, "KHATA_CHANGED",
                    "Someone changed this customer just now. Reload and try again.");
        }
        String name = in.name() == null ? old.name() : TextCleaner.clean(KiranaChecks.CUSTOMER_NAME, in.name());
        String typed = KiranaChecks.phone(in.phone());
        String phone = typed == null ? old.phone() : typed.isEmpty() ? null : typed;
        String note = in.note() == null ? old.note() : KiranaChecks.optional(KiranaChecks.CUSTOMER_NOTE, in.note());
        Long limit = Boolean.TRUE.equals(in.noLimit()) ? null
                : in.creditLimitPaise() != null ? in.creditLimitPaise() : old.creditLimitPaise();
        boolean active = in.active() == null ? old.active() : in.active();
        if (!active && old.active() && old.balancePaise() != 0) {
            throw new ApiException(HttpStatus.CONFLICT, "STILL_OWES", old.balancePaise() > 0
                    ? "They still owe money. Record their payment first."
                    : "They have ₹" + rupees(-old.balancePaise()) + " advance with you. Give it back (or use it up on "
                    + "a khata bill) first.",
                    Map.of("balancePaise", old.balancePaise()));
        }
        if (active && phone != null) {
            khata.lockPhone(c.shopId(), phone);
            requirePhoneFree(c.shopId(), old.id(), phone);
        }
        Instant now = KiranaChecks.now(clock);
        KhataCustomer updated = new KhataCustomer(old.id(), old.shopId(), name, phone, note, limit, old.balancePaise(),
                active, old.version() + 1, now);
        if (Objects.equals(name, old.name()) && Objects.equals(phone, old.phone()) && Objects.equals(note, old.note())
                && Objects.equals(limit, old.creditLimitPaise()) && active == old.active()) {
            return KhataCustomerView.of(old);
        }
        try {
            khata.update(updated, now);
        } catch (DataIntegrityViolationException e) {
            throw phoneTaken(e);
        }
        return KhataCustomerView.of(updated);
    }

    /**
     * Records a payment from a khata customer (owner or helper), in cash or to the shop's UPI. A payment whose clientRef
     * was already recorded returns that first one (repeated: true). More than they owe is kept as their advance, but
     * only when asked (advance: true); otherwise 409 MORE_THAN_OWED naming the balance and the advance it would leave
     * (a typing mistake is caught). 404 KHATA_CUSTOMER_NOT_FOUND (also for someone taken out of the book).
     */
    @Transactional
    public PaymentResult pay(UUID userId, UUID customerId, KhataPayment in) {
        Caller c = access.caller(userId);
        KhataCustomer customer = khata.lock(c.shopId(), customerId).orElseThrow(KhataService::notFound);
        // Under the customer's lock: a double tap waits for the first and then finds it here
        Optional<Entry> earlier = khata.paymentByClientRef(c.shopId(), in.clientRef());
        if (earlier.isPresent()) {
            if (!"PAYMENT".equals(earlier.get().kind())) {
                throw repeated();
            }
            UUID of = khata.customerOfEntry(earlier.get().id()).orElseThrow();
            KhataCustomer owner = of.equals(customer.id()) ? customer
                    : khata.find(c.shopId(), of).orElseThrow();
            return new PaymentResult(KhataCustomerView.of(owner), view(earlier.get(), Map.of()), true);
        }
        if (!customer.active()) {
            throw notFound();
        }
        if (in.amountPaise() > customer.balancePaise() && !Boolean.TRUE.equals(in.advance())) {
            long extra = in.amountPaise() - Math.max(0, customer.balancePaise());
            long advanceAfter = in.amountPaise() - customer.balancePaise();
            throw new ApiException(HttpStatus.CONFLICT, "MORE_THAN_OWED", (customer.balancePaise() > 0
                    ? "They owe ₹" + rupees(customer.balancePaise()) + "." : "They don't owe anything now.")
                    + " Keep ₹" + rupees(extra) + " as their advance?"
                    + (advanceAfter != extra ? " They'll have ₹" + rupees(advanceAfter) + " advance in all." : ""),
                    Map.of("balancePaise", customer.balancePaise(), "advancePaise", extra,
                            "advanceAfterPaise", advanceAfter));
        }
        Instant now = KiranaChecks.now(clock);
        UUID entryId = UUID.randomUUID();
        long after;
        try {
            after = khata.addEntry(c.shopId(), customer.id(), customer.balancePaise(), "PAYMENT", -in.amountPaise(),
                    null, in.method().name(), in.clientRef(), KiranaChecks.optional(KiranaChecks.NOTE, in.note()),
                    userId, now, entryId);
        } catch (DataIntegrityViolationException e) {
            // The same clientRef saved at this moment for another customer of the shop: the app's mistake, not ours
            if (DatabaseErrors.violatedUnique(e, "ux_kirana_khata_entries_client_ref")) {
                throw repeated();
            }
            throw e;
        }
        KhataCustomer updated = new KhataCustomer(customer.id(), customer.shopId(), customer.name(), customer.phone(),
                customer.note(), customer.creditLimitPaise(), after, true, customer.version(), now);
        Entry entry = khata.entry(entryId).orElseThrow();
        return new PaymentResult(KhataCustomerView.of(updated),
                view(entry, KiranaChecks.names(users, List.of(userId))), false);
    }

    /**
     * Gives a customer's advance back to them, all or part (owner only: 403 OWNER_ONLY; the money leaves the drawer or
     * the shop's UPI), e.g. before taking them out of the book. The same clientRef again returns the first (repeated:
     * true). 409 MORE_THAN_ADVANCE when it is more than the advance they have (naming it).
     */
    @Transactional
    public PaymentResult giveBack(UUID userId, UUID customerId, AdvanceBack in) {
        Caller c = access.owner(userId);
        KhataCustomer customer = khata.lock(c.shopId(), customerId).orElseThrow(KhataService::notFound);
        Optional<Entry> earlier = khata.paymentByClientRef(c.shopId(), in.clientRef());
        if (earlier.isPresent()) {
            if (!"REFUND".equals(earlier.get().kind())
                    || !khata.customerOfEntry(earlier.get().id()).orElseThrow().equals(customer.id())) {
                throw repeated();
            }
            return new PaymentResult(KhataCustomerView.of(customer), view(earlier.get(), Map.of()), true);
        }
        long advance = Math.max(0, -customer.balancePaise());
        if (in.amountPaise() > advance) {
            throw new ApiException(HttpStatus.CONFLICT, "MORE_THAN_ADVANCE", advance == 0
                    ? "They have no advance with you." : "Their advance is ₹" + rupees(advance) + ".",
                    Map.of("advancePaise", advance));
        }
        Instant now = KiranaChecks.now(clock);
        UUID entryId = UUID.randomUUID();
        long after;
        try {
            after = khata.addEntry(c.shopId(), customer.id(), customer.balancePaise(), "REFUND", in.amountPaise(), null,
                    in.method().name(), in.clientRef(), null, userId, now, entryId);
        } catch (DataIntegrityViolationException e) {
            if (DatabaseErrors.violatedUnique(e, "ux_kirana_khata_entries_client_ref")) {
                throw repeated();
            }
            throw e;
        }
        KhataCustomer updated = new KhataCustomer(customer.id(), customer.shopId(), customer.name(), customer.phone(),
                customer.note(), customer.creditLimitPaise(), after, customer.active(), customer.version(), now);
        return new PaymentResult(KhataCustomerView.of(updated),
                view(khata.entry(entryId).orElseThrow(), KiranaChecks.names(users, List.of(userId))), false);
    }

    /**
     * Undoes a payment recorded by mistake, e.g. on the wrong customer or ₹500 typed for ₹50: the owner any time, the
     * helper who recorded it within 10 minutes (else 403 OWNER_ONLY). What it took off goes back on what they owe, and
     * the payment stays in the history marked as undone. The day's khata money counts it off on the day it was undone.
     * 404 KHATA_ENTRY_NOT_FOUND unless it is one of this customer's payments; 409 PAYMENT_REVERSED if it was undone
     * already. A customer taken out of the book is put back in it (they owe again).
     */
    @Transactional
    public PaymentResult reverse(UUID userId, UUID customerId, UUID entryId, ReversePayment in) {
        Caller c = access.caller(userId);
        String reason = TextCleaner.clean(KiranaChecks.VOID_REASON, in.reason());
        KhataCustomer customer = khata.lock(c.shopId(), customerId).orElseThrow(KhataService::notFound);
        Entry payment = khata.entryOf(customer.id(), entryId).filter(e -> "PAYMENT".equals(e.kind()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "KHATA_ENTRY_NOT_FOUND",
                        "No such payment in this customer's khata."));
        // Under the customer's lock: the same reversal sent twice at once waits and then sees the first
        Instant now = KiranaChecks.now(clock);
        if (!c.isOwner() && !(payment.by().equals(userId) && !now.isAfter(payment.at().plus(HELPER_WINDOW)))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER_ONLY", "Only the owner can undo this payment now. A "
                    + "helper can undo a payment he recorded, within " + HELPER_WINDOW.toMinutes() + " minutes.",
                    Map.of("helperMinutes", HELPER_WINDOW.toMinutes()));
        }
        if (payment.reversed()) {
            throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_REVERSED", "This payment was already undone.");
        }
        UUID id = UUID.randomUUID();
        long after = khata.addReversal(c.shopId(), customer.id(), customer.balancePaise(), -payment.amountPaise(),
                payment.method(), payment.id(), reason, userId, now, id);
        KhataCustomer inBook = customer.active() ? customer : khata.reactivate(customer, now);
        KhataCustomer updated = new KhataCustomer(inBook.id(), inBook.shopId(), inBook.name(), inBook.phone(),
                inBook.note(), inBook.creditLimitPaise(), after, true, inBook.version(), now);
        return new PaymentResult(KhataCustomerView.of(updated),
                view(khata.entry(id).orElseThrow(), KiranaChecks.names(users, List.of(userId))), false);
    }

    // ---------- shared with BillService ----------

    /** 409 KHATA_LIMIT if adding {@code amount} would take the customer over their credit limit. */
    static void requireWithinLimit(KhataCustomer c, long amount) {
        if (c.creditLimitPaise() != null && Math.addExact(c.balancePaise(), amount) > c.creditLimitPaise()) {
            long available = Math.max(0, c.creditLimitPaise() - c.balancePaise());
            throw new ApiException(HttpStatus.CONFLICT, "KHATA_LIMIT", c.name() + " can buy ₹" + rupees(available)
                    + " more on khata (limit ₹" + rupees(c.creditLimitPaise()) + "). Take payment for their khata first, "
                    + "bill fewer items, or ask the owner to raise the limit.", Map.of("availablePaise", available, "limitPaise", c.creditLimitPaise(),
                    "balancePaise", c.balancePaise()));
        }
    }

    /** The customer's limit message parts, for bills: what they may still buy. */
    static String rupeesOf(long paise) {
        return rupees(paise);
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "KHATA_CUSTOMER_NOT_FOUND", "No such customer in your khata.");
    }

    private static ApiException repeated() {
        return new ApiException(HttpStatus.CONFLICT, "PAYMENT_REPEATED",
                "This was already recorded. Reload the khata.");
    }

    // ---------- helpers ----------

    private void requirePhoneFree(UUID shopId, UUID self, String phone) {
        if (phone == null) {
            return;
        }
        Optional<KhataCustomer> other = khata.findByPhone(shopId, phone).filter(o -> !o.id().equals(self));
        if (other.isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "PHONE_TAKEN", other.get().name()
                    + " in your khata already has this number.", Map.of("customerId", other.get().id()));
        }
    }

    private static RuntimeException phoneTaken(DataIntegrityViolationException e) {
        if (DatabaseErrors.violatedUnique(e, "ux_kirana_khata_customers_phone")) {
            return new ApiException(HttpStatus.CONFLICT, "PHONE_TAKEN",
                    "Another customer in your khata already has this number.");
        }
        return e;
    }

    private static KhataEntryView view(Entry e, Map<UUID, String> names) {
        return new KhataEntryView(e.id(), e.kind(), e.amountPaise(), e.balanceAfter(), e.billId(), e.billNo(),
                e.method(), e.note(), e.reversesId(), e.reversed(), names.get(e.by()), e.at(), e.by());
    }

    /** Rupees as people in India write them: 2,000 · 1,00,000 · 175.50. */
    private static String rupees(long paise) {
        long whole = Math.abs(paise) / 100;
        String digits = Long.toString(whole);
        StringBuilder out = new StringBuilder();
        int n = digits.length();
        for (int i = 0; i < n; i++) {
            int left = n - i;
            out.append(digits.charAt(i));
            if (left > 1 && (left == 4 || (left > 4 && left % 2 == 0))) {
                out.append(',');
            }
        }
        String sign = paise < 0 ? "-" : "";
        long part = Math.abs(paise) % 100;
        return sign + out + (part == 0 ? "" : String.format(Locale.ROOT, ".%02d", part));
    }

    private static Optional<Instant> parse(String s) {
        try {
            return Optional.of(Instant.parse(s));
        } catch (java.time.format.DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
