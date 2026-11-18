package in.apnidukan.kirana.service;

import in.apnidukan.common.error.ApiException;
import in.apnidukan.kirana.api.KiranaRequests.NewBill;
import in.apnidukan.kirana.api.KiranaResponses.BillResult;
import in.apnidukan.kirana.api.StockRequests.ConfirmPaper;
import in.apnidukan.kirana.api.StockResponses.PaperDraft;
import in.apnidukan.kirana.api.StockResponses.PaperLineView;
import in.apnidukan.kirana.domain.Product;
import in.apnidukan.kirana.domain.Quantities;
import in.apnidukan.kirana.repo.BillStore;
import in.apnidukan.kirana.repo.ProductStore;
import in.apnidukan.kirana.repo.ReadStore;
import in.apnidukan.kirana.repo.ReadStore.Read;
import in.apnidukan.kirana.service.KiranaAccess.Caller;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static in.apnidukan.kirana.service.RazorpayHttp.JSON;

/**
 * The shop's own paper bills, from a photo (owner or helper): a backup for when the counter couldn't scan (the phone
 * was off, a rush). The photo is read by AI into a draft, each line matched to the shop's products by name; the shop
 * checks it and confirms it as a bill with how it was paid, exactly like a bill made at the counter (stock goes down,
 * khata, totals). The bill keeps the day written on the paper ({@code writtenOn}, today or up to 7 days back) and the
 * photo; it is counted on the day it is confirmed (its {@code createdAt}), like any money that moves that day.
 */
@Service
public class PaperBillService {

    /** How far back the day on a paper bill may be. */
    static final int DAYS_BACK = 7;

    private final KiranaAccess access;
    private final ReadStore reads;
    private final BillService billService;
    private final BillStore bills;
    private final ProductStore products;
    private final MatchService matcher;
    private final Clock clock;

    public PaperBillService(KiranaAccess access, ReadStore reads, BillService billService, BillStore bills,
                            ProductStore products, MatchService matcher, Clock clock) {
        this.access = access;
        this.reads = reads;
        this.billService = billService;
        this.bills = bills;
        this.products = products;
        this.matcher = matcher;
        this.clock = clock;
    }

    /**
     * The paper bill as read: while READING or FAILED, no lines; once READ, each line with the product it clearly is
     * (else up to 3 likely ones); once DONE, the bill it became. 404 READ_NOT_FOUND (not this shop's, or not a paper
     * bill).
     */
    @Transactional(readOnly = true)
    public PaperDraft draft(UUID userId, UUID readId) {
        Caller c = access.caller(userId);
        Read r = paper(reads.find(c.shopId(), readId).orElseThrow(PhotoReads::notFound));
        KiranaDocumentAi.PaperFacts f = facts(r);
        if (f == null) {
            return new PaperDraft(r.id(), r.status(), r.failure(), null, null, null, null, List.of(), r.fileIds(),
                    r.billId());
        }
        MatchService.Shop shop = matcher.shop(c.shopId());
        List<MatchService.Result> matches = new ArrayList<>();
        Set<UUID> ids = new HashSet<>();
        for (KiranaDocumentAi.PaperLine l : f.lines()) {
            MatchService.Result m = matcher.match(shop, new MatchService.Want(l.wording(), l.brand(), l.name(),
                    l.size()), Set.of(), false);
            matches.add(m);
            if (m.sure()) {
                ids.add(m.best().productId());
            }
        }
        Map<UUID, Product> named = new HashMap<>();
        for (Product p : products.findAll(c.shopId(), ids)) {
            named.put(p.id(), p);
        }
        List<PaperLineView> lines = new ArrayList<>();
        for (int i = 0; i < f.lines().size(); i++) {
            KiranaDocumentAi.PaperLine l = f.lines().get(i);
            MatchService.Result m = matches.get(i);
            Product p = m.sure() ? named.get(m.best().productId()) : null;
            lines.add(new PaperLineView(i + 1, l.wording(), l.quantity() == null ? null : Quantities.json(l.quantity()),
                    l.byWeight(), l.pricePaise(), l.amountPaise(), p == null ? null : p.id(),
                    p == null ? null : p.name(), p == null ? null : p.unit(), p != null,
                    m.suggestions().stream().map(PurchaseService::suggestion).toList()));
        }
        LocalDate today = KiranaChecks.today(KiranaChecks.now(clock));
        String dateProblem = null;
        if (f.date() != null && f.date().isAfter(today)) {
            dateProblem = "The day read is in the future: check it";
        } else if (f.date() != null && f.date().isBefore(today.minusDays(DAYS_BACK))) {
            dateProblem = "Only bills of the last " + DAYS_BACK + " days can be added: make it at the counter with "
                    + "today's day";
        }
        LocalDate written = dateProblem == null ? f.date() : null;
        return new PaperDraft(r.id(), r.status(), null, f.date(), written, dateProblem, f.totalPaise(), lines,
                r.fileIds(), r.billId());
    }

    /**
     * Confirms the checked paper bill as a bill (see the class): the lines and payment exactly as on a new bill (every
     * check and error of POST /bills applies). 400 naming writtenOn outside today and 7 days back; 409 READ_NOT_READY
     * while it is still being read; 409 READ_USED when it failed or was thrown away. Confirming it again (a retry) gives
     * the same bill ({@code repeated}).
     */
    @Transactional
    public BillResult confirm(UUID userId, UUID readId, ConfirmPaper in) {
        Caller c = access.caller(userId);
        Read r = paper(reads.lock(c.shopId(), readId).orElseThrow(PhotoReads::notFound));
        switch (r.status()) {
            case "READING" -> throw new ApiException(HttpStatus.CONFLICT, "READ_NOT_READY",
                    "This bill is still being read. Try again in a minute.", Map.of());
            case "FAILED", "DISCARDED" -> throw new ApiException(HttpStatus.CONFLICT, "READ_USED",
                    "This photo can't be used any more. Take it again, or make the bill at the counter.",
                    Map.of("status", r.status()));
            default -> {
                // READ, or DONE: the retry below returns the bill already made
            }
        }
        LocalDate today = KiranaChecks.today(KiranaChecks.now(clock));
        // A retry of a bill already made is answered with it, whatever day it is now
        boolean already = "DONE".equals(r.status());
        if (!already && (in.writtenOn().isAfter(today) || in.writtenOn().isBefore(today.minusDays(DAYS_BACK)))) {
            throw KiranaChecks.invalid("writtenOn", "Enter a day from the last " + DAYS_BACK + " days");
        }
        UUID clientRef = UUID.nameUUIDFromBytes(("kirana-paper-bill:" + r.id()).getBytes(StandardCharsets.UTF_8));
        BillResult made = billService.create(userId, new NewBill(clientRef, in.lines(), in.payment(),
                in.khataCustomerId(), in.discountPaise(), in.paidNowPaise(), in.paidNowBy()));
        UUID billId = made.bill().id();
        if (!made.repeated()) {
            bills.setPaper(billId, in.writtenOn(), r.fileIds().get(0));
            reads.setStatus(r.id(), "DONE", billId, KiranaChecks.now(clock));
        }
        return new BillResult(billService.viewOf(c.shopId(), billId), made.stock(), made.khata(), made.upi(),
                made.upiIdMissing(), made.repeated());
    }

    private static Read paper(Read r) {
        if (!"PAPER_BILL".equals(r.kind())) {
            throw PhotoReads.notFound();
        }
        return r;
    }

    private static KiranaDocumentAi.PaperFacts facts(Read r) {
        if (r.result() == null || !("READ".equals(r.status()) || "DONE".equals(r.status()))) {
            return null;
        }
        return JSON.readValue(r.result(), KiranaDocumentAi.PaperFacts.class);
    }
}
